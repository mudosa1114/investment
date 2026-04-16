package com.coin.coin.service;

import com.coin.coin.common.MarketPhase;
import com.coin.coin.config.UpbitJwtGenerator;
import com.coin.coin.dto.*;
import com.coin.coin.dto.response.*;
import com.coin.coin.entity.CoinCode;
import com.coin.coin.entity.LastTrade;
import com.coin.coin.entity.TradeHistory;
import com.coin.coin.entity.TradeResult;
import com.coin.coin.repository.CoinCodeRepository;
import com.coin.coin.repository.LastTradeRepository;
import com.coin.coin.repository.TradeHistoryRepository;
import com.coin.coin.repository.TradeResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.coin.coin.dto.CoinPrice.latestCoinPrice;
import static com.coin.coin.dto.LastTradeDto.damageTrade;
import static com.coin.coin.dto.LastTradeDto.profitTrade;
import static com.coin.coin.dto.TradeHistoryDto.buyHistory;
import static com.coin.coin.dto.TradeHistoryDto.sellHistory;

@Service
@Slf4j
@RequiredArgsConstructor
public class UpbitApi {

    private final RestTemplate restTemplate;
    private final UriBuilderDto coinUriBuilder;
    private final UpbitJwtGenerator jwtGenerator;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final LastTradeRepository lastTradeRepository;
    private final CoinCodeRepository codeRepository;
    private final TradeResultRepository tradeResultRepository;
    private final KakaoService messageService;

    // ─── 매매 임계값 상수 ──────────────────────────────────────────────
    private static final BigDecimal PROFIT_THRESHOLD = new BigDecimal("1.007");  // +0.7% 익절 (손익비 개선)
    private static final BigDecimal STOP_LOSS_LIMIT  = new BigDecimal("0.990");  // -1.0% 손절
    private static final BigDecimal MAX_INVEST_BULL  = new BigDecimal("25000");  // BULL 최대 투자금
    private static final BigDecimal MAX_INVEST_SIDE  = new BigDecimal("20000");  // SIDEWAYS/BEAR 최대 투자금
    private static final String ADD_ORDER_AMOUNT = "5000";
    private static final String MIN_ORDER_AMOUNT = "10000";

    // ─── 지표 임계값 상수 ──────────────────────────────────────────────
    private static final BigDecimal RSI_OVERBOUGHT = BigDecimal.valueOf(70);  // 매수 차단 상한 / 익절 신호
    private static final BigDecimal RSI_LOW        = BigDecimal.valueOf(30);  // 손절 가중치 기준

    // ─── 점수 임계값 (매도/손절 판단용) ───────────────────────────────
    private static final int SELL_SCORE_THRESHOLD = 4;

    // ─── Circuit Breaker (일일 손실 한도) ────────────────────────────
    /**
     * 일일 실현손익 한도 (KRW) — 이 금액 이하 손실 시 봇 완전 정지
     * 총 운용 자본의 약 5% 수준으로 설정 권장 (예: 자본 20만원 → -10,000원)
     */
    private static final BigDecimal DAILY_LOSS_HALT_KRW = new BigDecimal("-10000");

    // ─── 손절 후 재진입 설정 ──────────────────────────────────────────
    /** 손절 직후 절대 재진입 차단 시간 (이후엔 회복 점수로 판단) */
    private static final int RE_ENTRY_COOLDOWN_MINUTES = 3;
    /** 재진입 허용 최소 점수 — 초기 진입보다 높게 설정 (손절 직후 선별적 진입) */
    private static final int RE_ENTRY_SCORE_THRESHOLD  = 4;
    /** 재진입 RSI 허용 구간 하한: 과매도 탈출 확인 */
    private static final BigDecimal RSI_RECOVERY_MIN = BigDecimal.valueOf(40);
    /** 재진입 RSI 허용 구간 상한: 과열 없음 확인 */
    private static final BigDecimal RSI_RECOVERY_MAX = BigDecimal.valueOf(60);

    // ─── 동적 코인 선정 설정 ──────────────────────────────────────────
    private static final int MAX_COIN_SLOTS = 8;        // 최대 보유 코인 종류
    private static final int VOLUME_TOP_N   = 20;       // 거래량 상위 N개 후보
    /** 24h 최소 거래대금 (KRW) — 이 미만 코인은 유동성 부족으로 제외 */
    private static final BigDecimal MIN_VOLUME_24H = new BigDecimal("10000000000"); // 100억원
    /** DCA 추가매수 기준: 평균매수가 대비 이 비율 이하로 하락 시 추가매수 */
    private static final BigDecimal ADD_BUY_DROP_RATE = new BigDecimal("0.985");   // -1.5%
    /** 하드 익절 기준: 지표 무관하게 이 비율 이상 수익이면 즉시 매도 */
    private static final BigDecimal HARD_PROFIT_RATE  = new BigDecimal("1.02");    // +2.0%
    /** 하드 손절 기준: 지표 지연으로 score 미달 상태에서도 이 비율 이하 손실이면 즉시 매도 */
    private static final BigDecimal HARD_STOP_RATE    = new BigDecimal("0.975");   // -2.5%
    /** 선정 대상에서 제외할 마켓 (스테이블코인 등) */
    private static final Set<String> COIN_EXCLUSIONS = Set.of(
            "KRW-USDT", "KRW-USDC", "KRW-DAI", "KRW-BTC"
    );

    // ─── 지표 캐시 (슬로우 루프가 3분마다 갱신, 패스트 루프가 참조) ─────
    /** volatile: 참조 교체가 원자적으로 보장됨 (슬로우 루프 갱신 → 패스트 루프 즉시 가시) */
    private volatile Map<String, CoinSignalDto> cachedSignalMap = Collections.emptyMap();

    // ══════════════════════════════════════════════════════════════════
    //  패스트 루프 (30초) — 현재가 기반: 하드 익절/손절, DCA
    //  캔들 지표를 조회하지 않으므로 API 호출 최소화
    // ══════════════════════════════════════════════════════════════════
    @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void fastPriceCheck() {
        // 슬로우 루프가 한 번도 실행되지 않은 초기 상태라면 스킵
        if (cachedSignalMap.isEmpty()) {
            log.info("지표 캐시 미준비 - 슬로우 루프 대기 중");
            return;
        }

        boolean halted = isDailyLossHaltTriggered();

        List<CoinAccount> accountList = checkCoinAccount();
        for (CoinAccount account : accountList) {
            String coinNm = account.getCoinType() + "-" + account.getCoinName();
            if ("KRW-KRW".equals(coinNm)) continue;

            CoinSignalDto signal = cachedSignalMap.get(coinNm);
            if (signal == null) continue;

            if (halted) {
                // 정지 상태: 하드 익절·손절만 실행 (DCA 차단)
                executeHardExitsOnly(account, coinNm, signal);
            } else {
                executePriceBasedActions(account, coinNm, signal);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  슬로우 루프 (3분) — 캔들 지표 기반: 점수 익절/손절, 최초 매수
    //  3분봉이 최단 캔들이므로 이보다 짧은 주기는 동일한 지표를 반복 계산할 뿐
    // ══════════════════════════════════════════════════════════════════
    @Scheduled(fixedDelay = 3, timeUnit = TimeUnit.MINUTES)
    public void slowIndicatorCheck() {
        List<CoinAccount> accountList = checkCoinAccount();

        Set<String> holdCoinSet = accountList.stream()
                .map(a -> a.getCoinType() + "-" + a.getCoinName())
                .collect(Collectors.toSet());

        // 지표 빌드 후 캐시 갱신 (패스트 루프가 즉시 새 캐시 참조)
        Map<String, CoinSignalDto> signalMap = buildSignalMap(holdCoinSet);
        this.cachedSignalMap = signalMap;

        // ── Circuit Breaker: 일일 손실 한도 도달 시 매매 전면 중단 ────
        if (isDailyLossHaltTriggered()) {
            log.warn("=== 봇 정지 상태 — 점수 매매·신규 매수 스킵 (하드 익절/손절은 패스트 루프에서 유지) ===");
            return;
        }

        // ── 보유 코인 점수 기반 익절/손절 ────────────────────────────
        for (CoinAccount account : accountList) {
            String coinNm = account.getCoinType() + "-" + account.getCoinName();
            if ("KRW-KRW".equals(coinNm)) continue;

            CoinSignalDto signal = signalMap.get(coinNm);
            if (signal == null) {
                log.warn("{} 지표 데이터 없음, 스킵", coinNm);
                continue;
            }
            evaluateScoreBasedExit(account, coinNm, signal);
        }

        // ── 미보유 코인 최초 매수 ────────────────────────────────────
        firstPurchaseCoin(holdCoinSet, signalMap);
    }

    // ══════════════════════════════════════════════════════════════════
    //  지표 Map 빌드 (캔들 조회 최소화)
    // ══════════════════════════════════════════════════════════════════
    private Map<String, CoinSignalDto> buildSignalMap(Set<String> holdCoinSet) {
        Map<String, CoinSignalDto> map = new HashMap<>();

        // coin_code 목록 + 현재 보유 코인의 합집합을 대상으로 지표 빌드
        // → coin_code에서 제거된 코인을 보유 중이어도 익절/손절 판단이 정상 작동
        Set<String> targetCoins = new HashSet<>(codeRepository.findAllCoinCode());
        holdCoinSet.stream()
                .filter(c -> !c.equals("KRW-KRW"))
                .forEach(targetCoins::add);

        for (String coin : targetCoins) {
            try {
                List<CandleResponse> shortCandles = candleResponses(coin, 3, 22);
                List<CandleResponse> phaseCandles = candleResponses(coin, 60, 50);
                List<CandleResponse> emaCandles = candleResponses(coin, 15, 30);
                if (isInvalid(shortCandles, 15)
                        || isInvalid(phaseCandles, 40)
                        || isInvalid(emaCandles, 20)) {
                    log.warn("{} 캔들 부족 - 지표 계산 스킵", coin);
                    continue;
                }

                BigDecimal rsi = calculateRsi(shortCandles);
                MarketPhase phase = detectMarketPhase(phaseCandles);
                Map<String, BigDecimal> ema = calculateEmaCross(emaCandles);
                Map<String, BigDecimal> bb = calculateBollingerBands(shortCandles);
                CoinPrice price = checkCoinPrice(coin);

                map.put(coin, CoinSignalDto.builder()
                        .rsi(rsi)
                        .phase(phase)
                        .ema(ema)
                        .bb(bb)
                        .price(price)
                        .build());

            } catch (Exception e) {
                log.warn("{} 지표 빌드 실패: {}", coin, e.getMessage());
            }
        }
        return map;
    }

    private boolean isInvalid(List<CandleResponse> candles, int minSize) {
        return candles == null || candles.size() < minSize;
    }

    // ══════════════════════════════════════════════════════════════════
    //  [패스트 루프용] 현재가 기반 액션: 하드 익절/손절, DCA
    //  캐시된 signal 의 phase 만 참조 (가격은 orderbook 에서 실시간 조회)
    // ══════════════════════════════════════════════════════════════════
    private void executePriceBasedActions(CoinAccount account, String coinNm, CoinSignalDto signal) {

        BigDecimal currentPrice = checkCoinPrice(coinNm).getBidPrice(); // 실시간 현재가
        BigDecimal totalCost    = account.getAvgBuyPrice()
                .multiply(account.getBalance()).setScale(0, RoundingMode.CEILING);
        BigDecimal sellablePrice = currentPrice.multiply(account.getBalance());

        // ── 하드 익절: +2% ────────────────────────────────────────────
        if (sellablePrice.compareTo(totalCost.multiply(HARD_PROFIT_RATE)) >= 0) {
            log.info("{} 하드 익절 실행 (+2% 도달) - 평가금액:{}", coinNm, sellablePrice);
            executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice());
            return;
        }

        // ── 하드 손절: -3% ────────────────────────────────────────────
        if (sellablePrice.compareTo(totalCost.multiply(HARD_STOP_RATE)) <= 0) {
            log.warn("{} 하드 손절 실행 (-3% 도달) - 평가금액:{}", coinNm, sellablePrice);
            executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice());
            return;
        }

        // ── DCA 추가매수: 평균매수가 대비 -1.5% ──────────────────────
        MarketPhase phase  = signal.getPhase();
        BigDecimal addBuyLine = account.getAvgBuyPrice().multiply(ADD_BUY_DROP_RATE);
        BigDecimal maxInvest  = (phase == MarketPhase.BULL) ? MAX_INVEST_BULL : MAX_INVEST_SIDE;

        if (currentPrice.compareTo(addBuyLine) <= 0 && totalCost.compareTo(maxInvest) < 0) {
            log.info("{} DCA 추가매수 실행 - 평단:{} 현재가:{} 추가매수선:{}",
                    coinNm, account.getAvgBuyPrice(), currentPrice, addBuyLine);
            OrdersResponse response = orderCoin(coinNm, "bid", ADD_ORDER_AMOUNT);
            tradeHistoryRepository.save(buyHistory(coinNm, ADD_ORDER_AMOUNT, signal));
            askSuccessMessage(response);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  [봇 정지 상태 전용] 하드 익절·손절만 실행 — DCA·신규매수 차단
    // ══════════════════════════════════════════════════════════════════
    private void executeHardExitsOnly(CoinAccount account, String coinNm, CoinSignalDto signal) {
        BigDecimal currentPrice  = checkCoinPrice(coinNm).getBidPrice();
        BigDecimal totalCost     = account.getAvgBuyPrice()
                .multiply(account.getBalance()).setScale(0, RoundingMode.CEILING);
        BigDecimal sellablePrice = currentPrice.multiply(account.getBalance());

        if (sellablePrice.compareTo(totalCost.multiply(HARD_PROFIT_RATE)) >= 0) {
            log.info("{} [정지 중] 하드 익절 실행 (+2%)", coinNm);
            executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice());
            return;
        }
        if (sellablePrice.compareTo(totalCost.multiply(HARD_STOP_RATE)) <= 0) {
            log.warn("{} [정지 중] 하드 손절 실행 (-2.5%)", coinNm);
            executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Circuit Breaker — 당일 실현손익 조회
    // ══════════════════════════════════════════════════════════════════
    /**
     * 오늘 00:00 이후 실현손익 합산이 DAILY_LOSS_HALT_KRW 이하이면 true 반환.
     * true 반환 시 신규 매수·DCA·점수 매도를 모두 차단하고 하드 익절/손절만 유지.
     */
    private boolean isDailyLossHaltTriggered() {
        BigDecimal todayPnl = tradeHistoryRepository
                .sumTodayRealizedPnl(LocalDate.now().atStartOfDay());
        if (todayPnl.compareTo(DAILY_LOSS_HALT_KRW) <= 0) {
            log.error("!!! 일일 손실 한도 도달 ({}원 / 한도 {}원) — 봇 정지, 수동 검토 필요 !!!",
                    todayPnl.setScale(0, RoundingMode.HALF_UP), DAILY_LOSS_HALT_KRW);
            return true;
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════════
    //  [슬로우 루프용] 지표 점수 기반 익절/손절
    //  하드 트리거는 패스트 루프가 담당하므로 여기서는 제외
    // ══════════════════════════════════════════════════════════════════
    private void evaluateScoreBasedExit(CoinAccount account, String coinNm, CoinSignalDto signal) {

        MarketPhase phase      = signal.getPhase();
        BigDecimal currentPrice = signal.getPrice().getBidPrice(); // 지표 빌드 시점 가격 (일관성 유지)
        boolean isGoldenCross  = isGoldenCross(signal.getEma());

        BigDecimal totalCost    = account.getAvgBuyPrice()
                .multiply(account.getBalance()).setScale(0, RoundingMode.CEILING);
        BigDecimal sellablePrice = currentPrice.multiply(account.getBalance());

        boolean isProfitRange = sellablePrice.compareTo(totalCost.multiply(PROFIT_THRESHOLD)) >= 0;
        int profitSellScore   = profitSellScore(signal, currentPrice, !isGoldenCross, isProfitRange);

        boolean isDamageRange = sellablePrice.compareTo(totalCost.multiply(STOP_LOSS_LIMIT)) <= 0;
        int stopScore         = stopLossScore(currentPrice, signal, isDamageRange, !isGoldenCross);

        log.info("{} 익절 점수: {}, 손절 점수: {}", coinNm, profitSellScore, stopScore);

        // ── 점수 기반 익절 ─────────────────────────────────────────────
        if (phase == MarketPhase.BULL && profitSellScore >= SELL_SCORE_THRESHOLD + 1) {
            log.info("{} 익절 실행 [BULL] - 점수: {}", coinNm, profitSellScore);
            executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice());
            return;
        }
        if (phase == MarketPhase.SIDEWAYS && profitSellScore >= SELL_SCORE_THRESHOLD) {
            log.info("{} 익절 실행 [SIDE] - 점수: {}", coinNm, profitSellScore);
            executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice());
            return;
        }
        if (phase == MarketPhase.BEAR && profitSellScore >= SELL_SCORE_THRESHOLD - 1) {
            log.info("{} 익절 실행 [BEAR] - 점수: {}", coinNm, profitSellScore);
            executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice());
            return;
        }

        // ── 점수 기반 손절 ─────────────────────────────────────────────
        // BEAR: 빠른 손절 (≥3), SIDEWAYS: 중간 (≥4), BULL: 여유 (≥5)
        if (phase == MarketPhase.BEAR && stopScore >= SELL_SCORE_THRESHOLD - 1) {
            log.warn("{} 손절 실행 [BEAR] - 점수: {}", coinNm, stopScore);
            executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice());
            return;
        }
        if (phase == MarketPhase.SIDEWAYS && stopScore >= SELL_SCORE_THRESHOLD) {
            log.warn("{} 손절 실행 [SIDE] - 점수: {}", coinNm, stopScore);
            executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice());
            return;
        }
        if (phase == MarketPhase.BULL && stopScore >= SELL_SCORE_THRESHOLD + 1) {
            log.warn("{} 손절 실행 [BULL] - 점수: {}", coinNm, stopScore);
            executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  최초 매수
    // ══════════════════════════════════════════════════════════════════
    private void firstPurchaseCoin(Set<String> holdCoinSet,
                                   Map<String, CoinSignalDto> signalMap) {

        for (String coin : codeRepository.findAllCoinCode()) {
            if (holdCoinSet.contains(coin)) {
                continue;
            }

            CoinSignalDto signal = signalMap.get(coin);
            if (signal == null) {
                continue;
            }

            // ── RSI 과매수 진입 차단 (70 이상은 고점 매수 위험) ────────────
            if (signal.getRsi().compareTo(RSI_OVERBOUGHT) >= 0) {
                log.info("{} RSI 과매수({}) - 최초 매수 보류", coin,
                        signal.getRsi().setScale(1, RoundingMode.HALF_UP));
                continue;
            }

            // ── 손절 후 재진입 판단 (dropCount 기반 동적 쿨다운) ───────────
            Optional<LastTrade> lastTradeOpt = lastTradeRepository.findByMarket(coin);
            if (lastTradeOpt.isPresent() && lastTradeOpt.get().getLastDamagedAt() != null) {
                LocalDateTime lastDamagedAt = lastTradeOpt.get().getLastDamagedAt();
                int dropCount       = Optional.ofNullable(lastTradeOpt.get().getDropCount()).orElse(0);
                int cooldownMinutes = calcCooldownMinutes(dropCount);

                // 동적 쿨다운: dropCount 0~1→3분, 2~3→30분, 4+→4시간
                if (lastDamagedAt.isAfter(LocalDateTime.now().minusMinutes(cooldownMinutes))) {
                    log.info("{} 손절 후 쿨다운 중 (dropCount:{}, {}분 대기) - 재진입 차단",
                            coin, dropCount, cooldownMinutes);
                    continue;
                }

                // 쿨다운 경과 후: 3분봉 RSI·BB 회복 점수로 재진입 여부 결정
                int reEntryScore = calcReEntryScore(signal);
                if (reEntryScore < RE_ENTRY_SCORE_THRESHOLD) {
                    log.info("{} 손절 후 회복 점수 미달 ({}/{}, dropCount:{}) - 재진입 보류",
                            coin, reEntryScore, RE_ENTRY_SCORE_THRESHOLD, dropCount);
                    continue;
                }
                log.info("{} 손절 후 회복 점수 충족 ({}/{}, dropCount:{}) - 재진입 허용",
                        coin, reEntryScore, RE_ENTRY_SCORE_THRESHOLD, dropCount);
            }

            log.info("{} 최초 매수 진행 - RSI:{}", coin,
                    signal.getRsi().setScale(1, RoundingMode.HALF_UP));
            OrdersResponse response = orderCoin(coin, "bid", MIN_ORDER_AMOUNT);
            tradeHistoryRepository.save(buyHistory(coin, MIN_ORDER_AMOUNT, signal));
            lastTradeOpt.ifPresent(lastTradeRepository::save);
            askSuccessMessage(response);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  점수 계산
    // ══════════════════════════════════════════════════════════════════

    /**
     * dropCount 기반 동적 쿨다운 계산
     * <pre>
     *   dropCount 0~1 → 3분   (일반)
     *   dropCount 2~3 → 30분  (반복 손절 경고)
     *   dropCount 4+  → 4시간 (연속 손절 — 해당 코인 사실상 당일 거래 중단)
     * </pre>
     */
    private int calcCooldownMinutes(int dropCount) {
        if (dropCount >= 4) return 240;              // 4시간
        if (dropCount >= 2) return 30;               // 30분
        return RE_ENTRY_COOLDOWN_MINUTES;             // 3분
    }

    /**
     * 손절 후 재진입 회복 점수 (최대 5점, Phase·EMA 제외 — 3분봉 RSI·BB만 사용)
     * 지표 지연이 큰 60분봉 Phase 는 재진입 타이밍 판단에 적합하지 않아 제외
     * - RSI 40~60 (과매도 탈출 + 과열 없음)  +2
     * - 현재가 > BB 하단 (하락 이탈 구간 탈출) +2
     * - 현재가 > BB 중간 (중심선 회복)         +1
     */
    private int calcReEntryScore(CoinSignalDto signal) {
        int score = 0;
        BigDecimal rsi   = signal.getRsi();
        BigDecimal price = signal.getPrice().getBidPrice();

        if (rsi.compareTo(RSI_RECOVERY_MIN) >= 0 && rsi.compareTo(RSI_RECOVERY_MAX) < 0) {
            score += 2;
        }
        if (price.compareTo(signal.getBb().get("lower")) > 0) {
            score += 2;
        }
        if (price.compareTo(signal.getBb().get("middle")) > 0) {
            score += 1;
        }
        return score;
    }

    /**
     * 매도 점수 (최대 8점, SELL_SCORE_THRESHOLD 이상이면 익절)
     * - 수익 구간            +2
     * - 볼린저 상단 터치     +2
     * - 데드크로스           +2
     * - 볼린저 중단 초과     +1
     * - RSI 70 초과          +1
     */
    private int profitSellScore(CoinSignalDto signal,
                                BigDecimal price,
                                boolean isDeadCross,
                                boolean isProfitRange) {

        if (!isProfitRange) {
            return 0;
        }
        int score = 3;
        if (price.compareTo(signal.getBb().get("upper")) >= 0) {
            score += 2;
        }
        if (isDeadCross) {
            score += 2;
        }
        if (price.compareTo(signal.getBb().get("middle")) >= 0) {
            score += 1;
        }
        if (signal.getRsi().compareTo(RSI_OVERBOUGHT) > 0) {
            score += 1;
        }
        return score;
    }

    private boolean isGoldenCross(Map<String, BigDecimal> ema) {
        return ema.get("ema5").compareTo(ema.get("ema20")) > 0;
    }

    private int stopLossScore(BigDecimal currentPrice,
                              CoinSignalDto signal,
                              boolean isDamageLine,
                              boolean isDeadCross) {
        if (!isDamageLine) {
            return 0;
        }

        int score = 2;
        if (currentPrice.compareTo(signal.getBb().get("lower")) < 0) {
            score += 2;
        }
        if (isDeadCross) {
            score += 2;
        }
        if (currentPrice.compareTo(signal.getBb().get("middle")) < 0) {
            score += 1;
        }
        if (signal.getRsi().compareTo(RSI_LOW) < 0) {
            score += 1;
        }
        return score;
    }

    // ══════════════════════════════════════════════════════════════════
    //  지표 계산
    // ══════════════════════════════════════════════════════════════════
    private BigDecimal calculateRsi(List<CandleResponse> candles) {
        int period = 14;
        if (candles.size() < period + 1) {
            log.warn("RSI 계산 불가 - 캔들 부족");
            return BigDecimal.ZERO;
        }

        List<BigDecimal> close = candles.stream()
                .map(CandleResponse::getTradePrice)
                .toList();

        BigDecimal gain = BigDecimal.ZERO;
        BigDecimal loss = BigDecimal.ZERO;

        // index: size-1(oldest) ~ size-period (period개 diff 계산)
        for (int i = close.size() - 1; i >= close.size() - period; i--) {
            BigDecimal diff = close.get(i - 1).subtract(close.get(i)); // 최신-과거
            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                gain = gain.add(diff);
            } else loss = loss.add(diff.abs());
        }

        BigDecimal avgGain = gain.divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);
        BigDecimal avgLoss = loss.divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);

        // Wilder's smoothing: 나머지 봉 적용
        for (int i = close.size() - period - 1; i >= 1; i--) {
            BigDecimal diff = close.get(i - 1).subtract(close.get(i));
            BigDecimal g = diff.compareTo(BigDecimal.ZERO) > 0 ? diff : BigDecimal.ZERO;
            BigDecimal l = diff.compareTo(BigDecimal.ZERO) < 0 ? diff.abs() : BigDecimal.ZERO;

            avgGain = avgGain.multiply(BigDecimal.valueOf(period - 1))
                    .add(g)
                    .divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);
            avgLoss = avgLoss.multiply(BigDecimal.valueOf(period - 1))
                    .add(l)
                    .divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);
        }

        if (avgLoss.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.valueOf(100);

        BigDecimal rs = avgGain.divide(avgLoss, 10, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(100)
                .subtract(BigDecimal.valueOf(100)
                        .divide(BigDecimal.ONE.add(rs), 10, RoundingMode.HALF_UP));
    }

    private MarketPhase detectMarketPhase(List<CandleResponse> candles) {
        try {
            if (candles.size() < 30) {
                return MarketPhase.SIDEWAYS;
            }

            List<BigDecimal> prices = candles.stream()
                    .map(CandleResponse::getTradePrice)
                    .toList();

            BigDecimal mult = new BigDecimal("2")
                    .divide(BigDecimal.valueOf(21), 10, RoundingMode.HALF_UP);

            // 가장 오래된 가격부터 시작
            BigDecimal ema = prices.get(prices.size() - 1);
            BigDecimal prevEma = null;

            for (int i = prices.size() - 2; i >= 0; i--) {
                if (i == 0) {
                    prevEma = ema;  // 최신 봉 바로 이전 EMA 저장
                }
                ema = prices.get(i).multiply(mult)
                        .add(ema.multiply(BigDecimal.ONE.subtract(mult)));
            }

            // ema = 현재(최신) EMA20
            BigDecimal slope = ema.subtract(prevEma)
                    .divide(prevEma, 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            if (slope.compareTo(new BigDecimal("0.15")) > 0) return MarketPhase.BULL;
            if (slope.compareTo(new BigDecimal("-0.15")) < 0) return MarketPhase.BEAR;
            return MarketPhase.SIDEWAYS;

        } catch (Exception e) {
            log.warn("시장 국면 감지 실패: {}", e.getMessage());
            return MarketPhase.SIDEWAYS;
        }
    }

    private Map<String, BigDecimal> calculateEmaCross(List<CandleResponse> candles) {
        if (candles == null || candles.isEmpty()) {
            return Map.of("ema5", BigDecimal.ZERO, "ema20", BigDecimal.ZERO);
        }

        List<BigDecimal> prices = candles.stream()
                .map(CandleResponse::getTradePrice)
                .toList();

        BigDecimal mult5 = new BigDecimal("2").divide(BigDecimal.valueOf(6), 10, RoundingMode.HALF_UP);  // EMA5
        BigDecimal mult20 = new BigDecimal("2").divide(BigDecimal.valueOf(21), 10, RoundingMode.HALF_UP); // EMA20

        BigDecimal ema5 = prices.get(prices.size() - 1);
        BigDecimal ema20 = prices.get(prices.size() - 1);

        for (int i = prices.size() - 2; i >= 0; i--) {
            BigDecimal p = prices.get(i);
            ema5 = p.multiply(mult5).add(ema5.multiply(BigDecimal.ONE.subtract(mult5)));
            ema20 = p.multiply(mult20).add(ema20.multiply(BigDecimal.ONE.subtract(mult20)));
        }

        return Map.of("ema5", ema5, "ema20", ema20);
    }

    private Map<String, BigDecimal> calculateBollingerBands(List<CandleResponse> candles) {
        int period = 20;
        if (candles == null || candles.size() < period) {
            return Map.of("upper", BigDecimal.ZERO, "middle", BigDecimal.ZERO, "lower", BigDecimal.ZERO);
        }

        // 최신 20개만 사용 (index 0~19)
        List<BigDecimal> prices = candles.subList(0, period).stream()
                .map(CandleResponse::getTradePrice)
                .toList();

        BigDecimal sma = prices.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);

        BigDecimal variance = prices.stream()
                .map(p -> p.subtract(sma).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);

        BigDecimal stdDev = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));

        return Map.of(
                "upper", sma.add(stdDev.multiply(new BigDecimal("2"))),
                "middle", sma,
                "lower", sma.subtract(stdDev.multiply(new BigDecimal("2")))
        );
    }

    // ══════════════════════════════════════════════════════════════════
    //  API 호출
    // ══════════════════════════════════════════════════════════════════
    private List<CandleResponse> candleResponses(String market, int unit, int period) {
        CandleResponse[] candles = restTemplate.getForObject(
                coinUriBuilder.upbitCandles(market, unit, period),
                CandleResponse[].class
        );
        return ObjectUtils.isEmpty(candles) ? null : Arrays.asList(candles);
    }

    private CoinPrice checkCoinPrice(String market) {
        OrderBookResponse[] res = restTemplate.getForObject(
                coinUriBuilder.upbitOrderBook(market), OrderBookResponse[].class);
        return latestCoinPrice(Optional.ofNullable(res)
                .map(Arrays::asList).orElse(Collections.emptyList()));
    }

    private Map<String, BigDecimal> orderPrice(String market) {
        OrderBookResponse[] res = restTemplate.getForObject(
                coinUriBuilder.upbitOrderBook(market), OrderBookResponse[].class);
        List<OrderBookResponse> list = Optional.ofNullable(res)
                .map(Arrays::asList).orElse(Collections.emptyList());
        return Map.of(
                "askPrice", list.get(0).getOrderBookUnits().get(0).getAskPrice(),
                "bidPrice", list.get(0).getOrderBookUnits().get(0).getBidPrice()
        );
    }

    private List<CoinAccount> checkCoinAccount() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwtGenerator.upbitJwtToken());
        headers.set("accept", "application/json");

        ResponseEntity<AccountResponse[]> res = restTemplate.exchange(
                coinUriBuilder.upbitAccount(), HttpMethod.GET,
                new HttpEntity<>(headers), AccountResponse[].class);

        return Optional.ofNullable(res.getBody())
                .map(Arrays::asList).orElse(Collections.emptyList())
                .stream().map(CoinAccount::coinAccount).toList();
    }

    private OrderResponse checkCoin(String uuid) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " +
                jwtGenerator.upbitJwtTokenWithQuery("uuid=" + uuid));
        headers.set("accept", "application/json");

        return restTemplate.exchange(
                coinUriBuilder.upbitOrder(uuid), HttpMethod.GET,
                new HttpEntity<>(headers), OrderResponse.class).getBody();
    }

    private OrdersResponse orderCoin(String market, String side, String value) {
        try {
            TradeRequest req = (side.equals("bid"))
                    ? TradeRequest.builder().market(market).side(side)
                    .price(value).ordType("price").build()
                    : TradeRequest.builder().market(market).side(side)
                    .volume(value).ordType("market").build();

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + jwtGenerator.upbitOrderToken(req));
            headers.set("accept", "application/json");

            return restTemplate.exchange(
                    coinUriBuilder.upbitOrder(), HttpMethod.POST,
                    new HttpEntity<>(req, headers), OrdersResponse.class).getBody();

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    private void executeSell(String coinNm, String volume, String type,
                             CoinSignalDto signal, BigDecimal avgBuyPrice) {
        OrdersResponse response = orderCoin(coinNm, "ask", volume);
        try {
            Thread.sleep(2000);
            OrderResponse result = checkCoin(response.getUuid());
            BigDecimal sellUnitPrice = orderPrice(coinNm).get("bidPrice");
            BigDecimal executedVol   = new BigDecimal(result.getExecutedVolume());
            BigDecimal amount        = executedVol.multiply(sellUnitPrice);

            int lastDropCount   = lastTradeRepository.findByMarket(coinNm)
                    .map(LastTrade::getDropCount).orElse(0);
            int lastProfitCount = lastTradeRepository.findByMarket(coinNm)
                    .map(LastTrade::getProfitCount).orElse(0);

            log.info("{} 판매 완료 - 체결금액:{} type:{}", coinNm, amount, type);
            TradeHistory history = sellHistory(coinNm, amount, avgBuyPrice, executedVol, signal);

            if (type.equals("damage")) {
                LastTrade lt = damageTrade(coinNm, amount, sellUnitPrice, signal)
                        .toBuilder()
                        .dropCount(lastDropCount + 1)
                        .profitCount(lastProfitCount)
                        .build();
                lastTradeRepository.save(lt);
                tradeHistoryRepository.save(history.toBuilder().tradeType("손절").build());
                return;
            }

            if (type.equals("profit")) {
                LastTrade lt = profitTrade(coinNm, amount, sellUnitPrice, signal)
                        .toBuilder()
                        .dropCount(lastDropCount)
                        .profitCount(lastProfitCount + 1)
                        .build();
                lastTradeRepository.save(lt);
                tradeHistoryRepository.save(history.toBuilder().tradeType("익절").build());
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Sell interrupted", e);
        }
    }

    private void askSuccessMessage(OrdersResponse response) {
        log.info("{} 코인 최초 구매 성공", response.getMarket());
    }

    // ══════════════════════════════════════════════════════════════════
    //  일별 손익 집계 (매일 00:10 KST)
    // ══════════════════════════════════════════════════════════════════

    /**
     * 전날(KST 00:00 ~ 23:59:59) 거래 내역을 코인별로 집계해 trade_result 에 저장한다.
     *
     * <p>실현 손익(realizedPnl) = trade_history.realized_pnl 합산
     *    (매도 시점에 "수령액 - 평균매수가×체결수량"으로 즉시 계산됨)
     * <p>미실현 손익(unrealizedPnl) = 집계 시점 현재가 × 보유수량 - 평균매수가 × 보유수량
     */
    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Seoul")
    @Transactional
    public void calculateDailyPnl() {
        LocalDate targetDate = LocalDate.now().minusDays(1);
        LocalDateTime start  = targetDate.atStartOfDay();
        LocalDateTime end    = targetDate.plusDays(1).atStartOfDay();

        log.info("=== 일별 손익 집계 시작: {} ===", targetDate);

        // 1. 대상 날짜에 거래한 코인 목록
        List<String> markets = tradeHistoryRepository.findDistinctMarkets(start, end);
        if (markets.isEmpty()) {
            log.info("집계 대상 거래 없음 - 종료");
            return;
        }

        // 2. 매수 통계 (금액·건수)
        List<Object[]> buyAgg = tradeHistoryRepository.aggregateByMarketAndType(start, end);
        Map<String, BigDecimal> buyAmountMap  = new HashMap<>();
        Map<String, BigDecimal> sellAmountMap = new HashMap<>();
        Map<String, Integer>    buyCountMap   = new HashMap<>();

        for (Object[] row : buyAgg) {
            String market    = (String) row[0];
            String tradeType = (String) row[1];
            BigDecimal sum   = (BigDecimal) row[2];
            int count        = ((Number) row[3]).intValue();
            if ("매수".equals(tradeType)) {
                buyAmountMap.put(market, sum);
                buyCountMap.put(market, count);
            } else {
                sellAmountMap.merge(market, sum, BigDecimal::add);
            }
        }

        // 3. 실현손익 집계 — trade_history.realized_pnl 직접 합산
        //    [market, SUM(realized_pnl), COUNT(익절), COUNT(손절)]
        List<Object[]> pnlAgg = tradeHistoryRepository.sumRealizedPnlByMarket(start, end);
        Map<String, BigDecimal> realizedMap  = new HashMap<>();
        Map<String, Integer>    profitCntMap = new HashMap<>();
        Map<String, Integer>    stopCntMap   = new HashMap<>();

        for (Object[] row : pnlAgg) {
            String market = (String) row[0];
            realizedMap.put(market, (BigDecimal) row[1]);
            profitCntMap.put(market, ((Number) row[2]).intValue());
            stopCntMap.put(market, ((Number) row[3]).intValue());
        }

        // 4. 현재 보유 잔고 조회 (미실현 손익 계산용)
        List<CoinAccount> accounts = checkCoinAccount();
        Map<String, CoinAccount> accountMap = accounts.stream()
                .filter(a -> !a.getCoinName().equals("KRW"))
                .collect(Collectors.toMap(
                        a -> a.getCoinType() + "-" + a.getCoinName(),
                        a -> a,
                        (a, b) -> a
                ));

        // 5. 코인별 TradeResult 저장
        LocalDateTime now = LocalDateTime.now();
        for (String market : markets) {
            if (tradeResultRepository.existsByMarketAndTradeDate(market, targetDate)) {
                log.info("{} {} 이미 집계됨 - 스킵", market, targetDate);
                continue;
            }

            BigDecimal realized  = realizedMap.getOrDefault(market, BigDecimal.ZERO);
            BigDecimal buyAmt    = buyAmountMap.getOrDefault(market, BigDecimal.ZERO);
            BigDecimal sellAmt   = sellAmountMap.getOrDefault(market, BigDecimal.ZERO);
            int buyCnt           = buyCountMap.getOrDefault(market, 0);
            int profitCnt        = profitCntMap.getOrDefault(market, 0);
            int stopCnt          = stopCntMap.getOrDefault(market, 0);

            // 미실현 손익: 집계 시점 현재 보유분
            BigDecimal unrealized = BigDecimal.ZERO;
            CoinAccount account = accountMap.get(market);
            if (account != null && account.getBalance().compareTo(BigDecimal.ZERO) > 0) {
                try {
                    BigDecimal currentPrice = checkCoinPrice(market).getBidPrice();
                    BigDecimal costBasis    = account.getAvgBuyPrice().multiply(account.getBalance());
                    BigDecimal currentValue = currentPrice.multiply(account.getBalance());
                    unrealized = currentValue.subtract(costBasis);
                } catch (Exception e) {
                    log.warn("{} 현재가 조회 실패 - 미실현 손익 0 처리: {}", market, e.getMessage());
                }
            }

            TradeResult result = TradeResult.builder()
                    .market(market)
                    .tradeDate(targetDate)
                    .buyAmount(buyAmt)
                    .sellAmount(sellAmt)
                    .buyCount(buyCnt)
                    .profitCount(profitCnt)
                    .stopCount(stopCnt)
                    .realizedPnl(realized)
                    .unrealizedPnl(unrealized)
                    .totalPnl(realized.add(unrealized))
                    .createdAt(now)
                    .build();

            tradeResultRepository.save(result);
            log.info("{} {} 손익 저장 완료 - 실현:{}, 미실현:{}, 합계:{}",
                    market, targetDate, realized, unrealized, realized.add(unrealized));
        }

        // 6. 당일 전체 합계 로그
        BigDecimal cumulative = tradeResultRepository.sumTotalPnl();
        log.info("=== 일별 손익 집계 완료 / 전체 누적 총손익: {}원 ===", cumulative);
    }

    // ══════════════════════════════════════════════════════════════════
    //  동적 코인 목록 갱신 (매일 새벽 5시)
    // ══════════════════════════════════════════════════════════════════

    /**
     * 업비트 KRW 마켓 전체를 24h 거래대금 기준으로 정렬 후
     * 상위 {@value VOLUME_TOP_N}개 후보에서 수익 이력 점수를 반영해
     * 최적 {@value MAX_COIN_SLOTS}개를 coin_code 테이블에 저장한다.
     *
     * <p>점수 산정 기준:
     * <ul>
     *   <li>거래량 순위 점수: 20 - rank (1위=19점, 20위=0점)</li>
     *   <li>수익 이력 보정: (익절 수 - 손절 수) × 2</li>
     *   <li>제외 조건: 손절 5회 이상 && 익절 0회인 코인</li>
     * </ul>
     */
    @Scheduled(cron = "0 0 5 * * *", zone = "Asia/Seoul")
    @Transactional
    public void refreshCoinList() {
        log.info("=== 동적 코인 목록 갱신 시작 ===");
        try {
            // 1. 전체 KRW 마켓 조회
            MarketResponse[] markets = restTemplate.getForObject(
                    coinUriBuilder.upbitMarkets(), MarketResponse[].class);
            if (ObjectUtils.isEmpty(markets)) {
                log.warn("마켓 목록 조회 실패 - 갱신 중단");
                return;
            }

            List<String> krwMarkets = Arrays.stream(markets)
                    .map(MarketResponse::getMarket)
                    .filter(m -> m.startsWith("KRW-"))
                    .filter(m -> !COIN_EXCLUSIONS.contains(m))
                    .toList();

            // 2. 티커(24h 거래대금) 일괄 조회 - 50개씩 배치
            List<CoinTickerResponse> allTickers = new ArrayList<>();
            for (int i = 0; i < krwMarkets.size(); i += 50) {
                List<String> batch = krwMarkets.subList(i, Math.min(i + 50, krwMarkets.size()));
                String param = String.join(",", batch);
                CoinTickerResponse[] batchResult = restTemplate.getForObject(
                        coinUriBuilder.upbitTicker(param), CoinTickerResponse[].class);
                if (!ObjectUtils.isEmpty(batchResult)) {
                    allTickers.addAll(Arrays.asList(batchResult));
                }
            }

            if (allTickers.isEmpty()) {
                log.warn("티커 조회 결과 없음 - 갱신 중단");
                return;
            }

            // 3. 최소 거래대금 필터 후 상위 VOLUME_TOP_N개 추출
            //    MIN_VOLUME_24H 미만 코인은 유동성 부족으로 제외 (펌핑 알트 방지)
            List<CoinTickerResponse> top = allTickers.stream()
                    .filter(t -> t.getAccTradePrice24h() != null)
                    .filter(t -> t.getAccTradePrice24h().compareTo(MIN_VOLUME_24H) >= 0)
                    .sorted(Comparator.comparing(CoinTickerResponse::getAccTradePrice24h).reversed())
                    .limit(VOLUME_TOP_N)
                    .toList();

            // 4. 수익 이력 반영 점수 산정
            Map<String, Integer> scoreMap = new LinkedHashMap<>();
            for (int rank = 0; rank < top.size(); rank++) {
                String market = top.get(rank).getMarket();
                int score = VOLUME_TOP_N - rank;  // 거래량 순위 기본 점수

                Optional<LastTrade> lt = lastTradeRepository.findByMarket(market);
                if (lt.isPresent()) {
                    int profitCnt = Optional.ofNullable(lt.get().getProfitCount()).orElse(0);
                    int dropCnt   = Optional.ofNullable(lt.get().getDropCount()).orElse(0);
                    // 손절 과다 코인 제외
                    if (dropCnt >= 5 && profitCnt == 0) {
                        log.info("{} 제외 - 손절 과다 (손절:{}, 익절:{})", market, dropCnt, profitCnt);
                        continue;
                    }
                    score += (profitCnt - dropCnt) * 2;
                }
                scoreMap.put(market, score);
            }

            // 5. 최종 점수 순 상위 MAX_COIN_SLOTS개 선정
            List<String> selected = scoreMap.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(MAX_COIN_SLOTS)
                    .map(Map.Entry::getKey)
                    .toList();

            if (selected.isEmpty()) {
                log.warn("선정된 코인 없음 - 갱신 중단");
                return;
            }

            // 6. coin_code 테이블 교체
            codeRepository.deleteAllInBatch();
            List<CoinCode> newCodes = selected.stream()
                    .map(m -> CoinCode.builder().coinCode(m).build())
                    .toList();
            codeRepository.saveAll(newCodes);

            // 7. 새 주기 시작 — drop/profit 카운트 초기화
            //    (다음 갱신 시 이번 주기 성과만 반영되도록)
            lastTradeRepository.resetAllCounts();
            log.info("last_trade drop/profit count 초기화 완료");

            log.info("=== 코인 목록 갱신 완료: {} ===", selected);

        } catch (Exception e) {
            log.error("코인 목록 갱신 중 오류 발생: {}", e.getMessage(), e);
        }
    }
}
