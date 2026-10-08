package com.coin.coin.service;

import com.coin.coin.dto.CoinAccount;
import com.coin.coin.dto.CoinSignalDto;
import com.coin.coin.repository.TradeHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 청산 판단 — 10/7 모의매매 전면 개편으로 단순화, 10/8 ATR 기반 폭 차등 적용.
 *
 * <p>패스트 루프(30초)에서 현재 매수호가 / 평균매수가 만으로 판단한다. 익절·손절 폭은 같다(대칭).
 * <ul>
 *   <li>기본: ±1% — +1% 이상 익절, -1% 이하 손절</li>
 *   <li>노이즈 코인: ±1.5% — 매수 시점 3분봉 ATR이 가격의 {@link #NOISE_ATR_PCT}% 이상이면
 *       1% 움직임이 3분봉 두 개 흔들림 안에 들어가므로 1%를 노이즈로 보고 폭을 1.5%로 넓힌다.</li>
 * </ul>
 * 폭은 매수 시점에 정해 포지션이 끝날 때까지 유지한다(보유 중 ATR이 바뀌어도 변경 없음).
 *
 * <p>10/8 보유시간 제한 추가 — 위 기준에 닿지 않으면 무한정 보유하는 것을 막는다.
 * <ul>
 *   <li>30분 이상 보유 중 -0.5% 이하 → 시간손절 (사유: 시간손절30m)</li>
 *   <li>60분 이상 보유 → 손익 무관 시간매도 (사유: 시간매도60m). 수수료 0.1% 이상 남는 경우만 '익절', 나머지는 '손절'로 기록</li>
 * </ul>
 * 기준은 가격 변화율(수수료 제외)이며, 수수료 왕복 0.1%를 빼면 실제 손익은 ±1% → +0.9/-1.1%, ±1.5% → +1.4/-1.6%.
 *
 * <p>ATR 기준값 근거 (9/30~10/7 스냅샷 시뮬레이션, ±1% 도달까지 걸린 시간 중앙값):
 * 3분 ATR 0.25~0.33% → 72분, 0.33~0.5% → 41분, 0.5~0.8% → 22분, 0.8% 이상 → 12분.
 * 0.5% 이상부터 1% 손익이 20분 안팎에 무작위로 결정돼 노이즈 구간으로 판단.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PositionExitService {

    private final UpbitExchangeClient exchangeClient;
    private final TradingStateStore stateStore;
    private final TradeExecutionService tradeExecutionService;
    private final CoinSignalService coinSignalService;
    private final TradeHistoryRepository tradeHistoryRepository;

    /** 기본 익절·손절 폭(%) */
    public static final BigDecimal BASE_EXIT_PCT = new BigDecimal("1.0");
    /** 노이즈 코인 익절·손절 폭(%) */
    public static final BigDecimal NOISE_EXIT_PCT = new BigDecimal("1.5");
    /** 3분봉 ATR / 가격 × 100 이 이 값 이상이면 1%를 노이즈로 판단 */
    public static final BigDecimal NOISE_ATR_PCT = new BigDecimal("0.5");
    /** ExitReviewService(구 매도검증)가 참조하는 익절 임계값 — 호환용 */
    static final BigDecimal PROFIT_THRESHOLD = BigDecimal.ONE.add(BASE_EXIT_PCT.movePointLeft(2));

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    // ── 보유시간 제한 (10/8) ──────────────────────────────────────────
    /** 이 시간(분) 이상 보유 중 TIME_STOP_PCT 이하이면 시간손절 */
    public static final long TIME_STOP_MINUTES = 30;
    /** 시간손절 기준 변화율(%) */
    public static final BigDecimal TIME_STOP_PCT = new BigDecimal("-0.5");
    /** 이 시간(분) 이상 보유하면 손익 무관 시간매도 */
    public static final long TIME_EXIT_MINUTES = 60;
    /** 시간매도를 '익절'로 기록할 최소 변화율(%) — 왕복 수수료 0.1% 이상 남아야 실제 이익 */
    private static final BigDecimal FEE_ROUND_TRIP_PCT = new BigDecimal("0.1");

    /** 매수 시점 지표로 이 포지션의 익절·손절 폭(%)을 정한다 — CoinSignalService 매수 직후 호출 */
    public static BigDecimal exitPctFor(CoinSignalDto signal) {
        BigDecimal atrPct = signal == null ? null : signal.getAtrPct();
        return (atrPct != null && atrPct.compareTo(NOISE_ATR_PCT) >= 0) ? NOISE_EXIT_PCT : BASE_EXIT_PCT;
    }

    /** 패스트 루프(30초)에서 보유 코인마다 호출 */
    public void checkExit(CoinAccount account, String coinNm) {
        BigDecimal avg = account.getAvgBuyPrice();
        BigDecimal balance = account.getBalance();
        if (avg == null || avg.signum() <= 0 || balance == null || balance.signum() <= 0) return;

        // 포지션 폭 — 매수 때 정해둔 값. 재시작 등으로 없으면 현재 캐시 지표로 정해 고정
        BigDecimal exitPct = stateStore.exitPctMap.computeIfAbsent(coinNm,
                c -> exitPctFor(stateStore.getCachedSignalMap().get(c)));

        BigDecimal bid = exchangeClient.checkCoinPrice(coinNm).getBidPrice();
        BigDecimal rate = bid.divide(avg, 10, RoundingMode.HALF_UP);
        BigDecimal ratePct = rate.subtract(BigDecimal.ONE).multiply(HUNDRED).setScale(3, RoundingMode.HALF_UP);

        // 보유 중 최고/최저 수익률 (MFE/MAE) — 30초 간격 표본
        stateStore.holdMaxRateMap.merge(coinNm, ratePct, BigDecimal::max);
        stateStore.holdMinRateMap.merge(coinNm, ratePct, BigDecimal::min);

        long heldMinutes = heldMinutes(coinNm);

        String pctLabel = exitPct.stripTrailingZeros().toPlainString();
        String type;
        String reason;
        if (ratePct.compareTo(exitPct) >= 0) {
            type = "profit";
            reason = "익절+" + pctLabel + "%";
        } else if (ratePct.compareTo(exitPct.negate()) <= 0) {
            type = "damage";
            reason = "손절-" + pctLabel + "%";
        } else if (heldMinutes >= TIME_EXIT_MINUTES) {
            type = ratePct.compareTo(FEE_ROUND_TRIP_PCT) > 0 ? "profit" : "damage";
            reason = "시간매도" + TIME_EXIT_MINUTES + "m";
        } else if (heldMinutes >= TIME_STOP_MINUTES && ratePct.compareTo(TIME_STOP_PCT) <= 0) {
            type = "damage";
            reason = "시간손절" + TIME_STOP_MINUTES + "m";
        } else {
            return;
        }

        log.info("{} [{}] 평균매수가:{} 매수호가:{} 변화율:{}% 보유:{}분 (보유중 최고:{}% 최저:{}%)",
                coinNm, reason, avg.stripTrailingZeros().toPlainString(), bid.stripTrailingZeros().toPlainString(),
                ratePct, heldMinutes, stateStore.holdMaxRateMap.get(coinNm), stateStore.holdMinRateMap.get(coinNm));

        // 매도 당시 지표를 기록하기 위해 지금 시점 지표를 새로 계산 (실패 시 슬로우 루프 캐시)
        CoinSignalDto signal = coinSignalService.freshSignalOrCached(coinNm);
        if (signal == null) {
            log.warn("{} 지표 없음 — 다음 틱에 매도 재시도", coinNm);
            return;
        }
        tradeExecutionService.executeSell(coinNm, balance.toPlainString(), type, signal, avg, reason);
    }

    /**
     * 현재 포지션 보유 시간(분). 매수 시각은 메모리 우선, 없으면(재시작 등) 가장 최근 매수 행 시각으로 복원.
     * 둘 다 없으면 지금을 매수 시각으로 간주(그 시점부터 시간 제한 적용).
     */
    private long heldMinutes(String coinNm) {
        LocalDateTime entryAt = stateStore.positionEntryTimeMap.computeIfAbsent(coinNm, c ->
                tradeHistoryRepository.findTopByMarketAndTradeTypeOrderByIdDesc(c, "매수")
                        .map(com.coin.coin.entity.TradeHistory::getTradedAt)
                        .orElse(LocalDateTime.now()));
        return Duration.between(entryAt, LocalDateTime.now()).toMinutes();
    }
}
