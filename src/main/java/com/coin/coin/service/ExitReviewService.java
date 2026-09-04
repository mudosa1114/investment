package com.coin.coin.service;

import com.coin.coin.common.MarketPhase;
import com.coin.coin.dto.CoinSignalDto;
import com.coin.coin.entity.ExitReview;
import com.coin.coin.repository.ExitReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 매도 판단 사후 검증(ExitReview) — 손절/익절 시점의 지표 스냅샷을 남기고
 * 매도 이후 24시간 가격을 추적해 "그 판단이 맞았는가"를 사후 검증한다
 * (9/4 도입, UpbitApi 역할분리로 별도 서비스로 분리).
 *
 * <p>가격 추적은 slowIndicatorCheck(3분 루프)가 어차피 후보 코인 전체의
 * 현재가를 조회하는 데 편승 — 별도 API 호출 추가 없음(updateExitReviews 참고).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ExitReviewService {

    private final ExitReviewRepository exitReviewRepository;
    private final UpbitExchangeClient exchangeClient;

    /** 매도 이후 추적 기간(시간) — 이 시간이 지나면 추적 종료하고 판정 확정 */
    private static final int EXIT_REVIEW_WINDOW_HOURS = 24;
    /** 수수료 손익분기(왕복 0.1%) — 이보다 작은 변동은 "회복/추가하락"으로 보지 않음 */
    private static final BigDecimal EXIT_REVIEW_NOISE_FLOOR_PCT = new BigDecimal("0.1");

    /**
     * 매도 체결 직후 호출 — 사후 검증용 ExitReview 레코드를 생성한다(24시간 가격 추적 시작).
     * 원래 UpbitApi.executeSell 안에 있던 exitReviewRepository.save(...) 블록을 그대로 옮긴 것.
     */
    public void recordExitReview(String coinNm, String type, String reason,
                                  BigDecimal sellUnitPrice, BigDecimal avgBuyPrice, CoinSignalDto signal) {
        MarketPhase effectPhase = (signal.getShortPhase() != MarketPhase.SIDEWAYS)
                ? signal.getShortPhase() : signal.getPhase();
        LocalDateTime sellNow = LocalDateTime.now();
        exitReviewRepository.save(ExitReview.builder()
                .market(coinNm)
                .sellType(type)
                .sellReason(reason)
                .sellPrice(sellUnitPrice)
                .avgBuyPrice(avgBuyPrice)
                .entryRsi(signal.getRsi())
                .entryShortPhase(signal.getShortPhase().name())
                .entryPhase(signal.getPhase().name())
                .profitTargetPct(currentProfitTargetPct(effectPhase))
                .sellTime(sellNow)
                .trackUntil(sellNow.plusHours(EXIT_REVIEW_WINDOW_HOURS))
                .peakPrice(sellUnitPrice)
                .peakAt(sellNow)
                .troughPrice(sellUnitPrice)
                .troughAt(sellNow)
                .reviewComplete(false)
                .build());
    }

    // ══════════════════════════════════════════════════════════════════
    //  매도 판단 사후 검증 — 3분 루프에 편승해 진행 중인 추적을 갱신
    // ══════════════════════════════════════════════════════════════════
    /**
     * 진행 중(reviewComplete=false)인 모든 ExitReview에 대해 현재가를 반영한다.
     * signalMap에 이미 있는 코인은 그 가격을 그대로 쓰고(추가 API 호출 없음),
     * signalMap에서 빠진 코인(퇴출·블랙리스트 등)만 직접 현재가를 조회한다 —
     * 대상이 소수라 부담이 크지 않음.
     */
    public void updateExitReviews(Map<String, CoinSignalDto> signalMap) {
        List<ExitReview> pending = exitReviewRepository.findPending();
        if (pending.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();
        for (ExitReview review : pending) {
            BigDecimal currentPrice;
            CoinSignalDto signal = signalMap.get(review.getMarket());
            if (signal != null) {
                currentPrice = signal.getPrice().getBidPrice();
            } else {
                try {
                    currentPrice = exchangeClient.checkCoinPrice(review.getMarket()).getBidPrice();
                } catch (Exception e) {
                    log.warn("{} 매도검증 가격조회 실패, 이번 주기 스킵: {}", review.getMarket(), e.getMessage());
                    continue;
                }
            }

            ExitReview.ExitReviewBuilder b = review.toBuilder();

            // 연속 최고/최저가 갱신
            if (currentPrice.compareTo(review.getPeakPrice()) > 0) {
                b.peakPrice(currentPrice).peakAt(now);
            }
            if (currentPrice.compareTo(review.getTroughPrice()) < 0) {
                b.troughPrice(currentPrice).troughAt(now);
            }

            // 고정 체크포인트 채우기 (경과 후 처음 관측된 값, 최대 3분 오차)
            long minutesSinceSell = java.time.Duration.between(review.getSellTime(), now).toMinutes();
            if (review.getPriceAt15m() == null && minutesSinceSell >= 15) b.priceAt15m(currentPrice);
            if (review.getPriceAt1h()  == null && minutesSinceSell >= 60) b.priceAt1h(currentPrice);
            if (review.getPriceAt3h()  == null && minutesSinceSell >= 180) b.priceAt3h(currentPrice);
            if (review.getPriceAt6h()  == null && minutesSinceSell >= 360) b.priceAt6h(currentPrice);
            if (review.getPriceAt24h() == null && minutesSinceSell >= 1440) b.priceAt24h(currentPrice);

            ExitReview updated = b.build();

            if (!now.isBefore(review.getTrackUntil())) {
                updated = updated.toBuilder()
                        .reviewComplete(true)
                        .verdict(computeExitVerdict(updated))
                        .build();
                log.info("{} 매도검증 완료 [{}/{} 매도가:{} 최고:{}(+{}%) 최저:{}({}%)] 판정:{}",
                        updated.getMarket(), updated.getSellType(), updated.getSellReason(),
                        updated.getSellPrice().setScale(0, RoundingMode.HALF_UP),
                        updated.getPeakPrice().setScale(0, RoundingMode.HALF_UP),
                        pctChange(updated.getSellPrice(), updated.getPeakPrice()),
                        updated.getTroughPrice().setScale(0, RoundingMode.HALF_UP),
                        pctChange(updated.getSellPrice(), updated.getTroughPrice()),
                        updated.getVerdict());
            }
            exitReviewRepository.save(updated);
        }
    }

    /** (target - base) / base × 100, 소수점 2자리 */
    private BigDecimal pctChange(BigDecimal base, BigDecimal target) {
        return target.subtract(base).divide(base, 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 매도 판단 사후 판정.
     * - 손절(damage): 이후 회복 없었으면 "정당", 익절임계까지 회복했으면 "조기손절의심(익절임계도달)",
     *   손익분기(0.1%)만 넘겼으면 "조기손절의심(회복만)"
     * - 익절(profit): 이후 추가 하락했으면 "정당", 손익분기 이상 더 올랐으면 "조기익절의심"
     */
    private String computeExitVerdict(ExitReview r) {
        BigDecimal recoveryPct = pctChange(r.getSellPrice(), r.getPeakPrice());   // ≥ 0
        BigDecimal declinePct  = pctChange(r.getSellPrice(), r.getTroughPrice()); // ≤ 0

        if ("damage".equals(r.getSellType())) {
            if (recoveryPct.compareTo(r.getProfitTargetPct()) >= 0) {
                return String.format("조기손절의심(익절임계도달, 최대회복+%s%%)", recoveryPct);
            }
            if (recoveryPct.compareTo(EXIT_REVIEW_NOISE_FLOOR_PCT) >= 0) {
                return String.format("조기손절의심(회복만, 최대회복+%s%%, 임계미도달)", recoveryPct);
            }
            return String.format("손절정당(회복없음, 추가하락%s%%)", declinePct);
        } else {
            if (declinePct.abs().compareTo(EXIT_REVIEW_NOISE_FLOOR_PCT) >= 0
                    && declinePct.abs().compareTo(recoveryPct) >= 0) {
                return String.format("매도정당(이후추가하락%s%%)", declinePct);
            }
            if (recoveryPct.compareTo(EXIT_REVIEW_NOISE_FLOOR_PCT) >= 0) {
                return String.format("조기익절의심(추가상승 놓침+%s%%)", recoveryPct);
            }
            return "매도적절(이후 큰 변동 없음)";
        }
    }

    /** 매도 시점 기준 현재 effectPhase의 점수 익절 임계값(%) — ExitReview.profitTargetPct 스냅샷용 */
    private BigDecimal currentProfitTargetPct(MarketPhase effectPhase) {
        BigDecimal threshold;
        if      (effectPhase == MarketPhase.BULL)     threshold = PositionExitService.PROFIT_THRESHOLD_BULL;
        else if (effectPhase == MarketPhase.SIDEWAYS)  threshold = PositionExitService.PROFIT_THRESHOLD_SIDEWAYS;
        else                                           threshold = PositionExitService.PROFIT_THRESHOLD_BEAR;
        return threshold.subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 일별 매도검증 집계 리포트 — 손절/익절 로직(sellReason)별로 "판단이 맞았는지" 비율을 보여줌.
     * ExitReview는 매도 후 24시간이 지나야 완료되므로, "24~48시간 전에 매도된" 건들이
     * 정확히 "지난 24시간 사이에 완료된" 건들이다(달력상의 어제와는 무관 — sellTime 기준 슬라이딩 윈도우).
     */
    @Scheduled(cron = "0 20 0 * * *", zone = "Asia/Seoul")
    public void exitReviewDailyReport() {
        LocalDateTime now   = LocalDateTime.now();
        LocalDateTime start = now.minusHours(48);
        LocalDateTime end   = now.minusHours(24);
        List<ExitReview> completed = exitReviewRepository.findCompletedBetween(start, end);
        if (completed.isEmpty()) {
            log.info("=== 매도검증 일별 리포트: 최근 24시간 내 완료된 검증 없음 ===");
            return;
        }

        Map<String, int[]> bySellReason = new HashMap<>(); // [총건수, 의심건수(조기손절/조기익절)]
        for (ExitReview r : completed) {
            int[] stat = bySellReason.computeIfAbsent(r.getSellReason(), k -> new int[2]);
            stat[0]++;
            if (r.getVerdict() != null && r.getVerdict().contains("의심")) stat[1]++;
        }

        log.info("=== 매도검증 일별 리포트 (최근 24시간, 총 {}건 완료) ===", completed.size());
        bySellReason.entrySet().stream()
                .sorted((a, b) -> b.getValue()[0] - a.getValue()[0])
                .forEach(e -> {
                    String reason = e.getKey();
                    int total = e.getValue()[0];
                    int suspect = e.getValue()[1];
                    log.info("  {}: {}건 중 {}건({}%) 판단 재검토 필요", reason, total, suspect,
                            String.format("%.1f", total == 0 ? 0.0 : suspect * 100.0 / total));
                });
    }
}
