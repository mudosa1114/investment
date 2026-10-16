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
 * <p>슬로우 루프(3분, 10/16~ — 이전엔 패스트 루프 30초)에서 현재 매수호가 / 평균매수가 만으로 판단한다. 익절·손절 폭은 같다(대칭).
 * 10/16 본전탈출 추가: 보유 중 손실이었다가 +0.1%(수수료 본전)가 되면 30초 확인에서 즉시 매도({@link #checkBreakEvenEscape}).
 * 10/16부터 연속 하락·고점 경신·보유 중 최고/최저(MFE/MAE)는 매도 판단용으로 3분 간격 표본을 쓴다(매도 행의 max_rate/min_rate는 30초 확인 값과 합친 것).
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
 *   <li>10/13: 수익권(+0.1%, 수수료 본전)에 들어온 뒤 확인 주기(10/16~ 3분, 이전 30초)에서 2번 연속 하락했는데 아직 수익권 → 수익권하락익절</li>
 *   <li>10/13: 고정 코인만, 수익권에서 마지막 고점 이후 15분간 새 고점 없음 → 수익권정체익절</li>
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
    /** 본전탈출 매도 사유 (10/16) */
    public static final String BREAK_EVEN_ESCAPE = "본전탈출";
    /** 3분 루프(checkExit)와 30초 루프(본전탈출)가 같은 코인을 동시에 팔지 않도록 */
    private final Object sellLock = new Object();

    // ── 보유시간 제한 (10/8) ──────────────────────────────────────────
    /** 이 시간(분) 이상 보유 중 TIME_STOP_PCT 이하이면 시간손절 */
    public static final long TIME_STOP_MINUTES = 30;
    /** 시간손절 기준 변화율(%) */
    public static final BigDecimal TIME_STOP_PCT = new BigDecimal("-0.5");
    /** 이 시간(분) 이상 보유하면 손익 무관 시간매도 */
    public static final long TIME_EXIT_MINUTES = 60;
    /** 시간매도를 '익절'로 기록할 최소 변화율(%) — 왕복 수수료 0.1% 이상 남아야 실제 이익 */
    private static final BigDecimal FEE_ROUND_TRIP_PCT = new BigDecimal("0.1");

    // ── 수익권 하락 익절 (10/13) ──────────────────────────────────────
    // 변화율은 "지금 매수호가 / 매수 체결가(매도호가)"라 스프레드는 이미 빠져 있다. 여기서 수수료(왕복 0.1%)까지
    // 넘으면 순손익이 0 이상 = 수익권. 수익권에 한 번이라도 들어온 뒤, 확인 주기(10/16~ 3분)마다 연속 N번 하락했는데
    // 아직 수익권이면 1%(1.5%) 익절을 기다리지 않고 바로 판다.
    // 근거: 10/12~13 실매도 234건 중 73건(31%)이 수익권(최고 +0.48% 평균)에 갔다가 손실로 끝났고,
    // 이 손실이 전체 손실의 약 절반(-3,507원 / -7,233원)이었다.
    /** 수익권 기준 변화율(%) — 왕복 수수료와 같음(본전) */
    public static final BigDecimal PROFIT_ZONE_PCT = FEE_ROUND_TRIP_PCT;
    /** 수익권에서 이 횟수만큼 연속 하락(10/16~ 3분 간격, 이전 30초)하면 익절 */
    public static final int PROFIT_ZONE_DROP_COUNT = 2;

    // ── 수익권 정체 익절 (10/13) — 고정 코인만 ─────────────────────────
    // 수익권(+0.1% 이상)에서 마지막 고점 이후 이 시간(분) 동안 새 고점이 없으면 판다.
    // 근거(9/30~10/13 3분 스냅샷): 정체 시점에 팔았을 때 vs 기존대로 들고 갔을 때 가격변화 차이
    //   고정 코인: 10분 전후 +0.05%p, 15분 +0.06%p, 20분 +0.08%p (앞·뒤 기간 모두 플러스 → 파는 게 유리)
    //   유동 코인: 10~20분 모두 -0.03~-0.07%p (앞·뒤 기간 모두 마이너스 → 들고 가는 게 유리) → 적용 안 함
    /** 정체 판정 시간(분) */
    public static final long STALL_MINUTES = 15;

    /** 매수 시점 지표로 이 포지션의 익절·손절 폭(%)을 정한다 — CoinSignalService 매수 직후 호출 */
    public static BigDecimal exitPctFor(CoinSignalDto signal) {
        BigDecimal atrPct = signal == null ? null : signal.getAtrPct();
        return (atrPct != null && atrPct.compareTo(NOISE_ATR_PCT) >= 0) ? NOISE_EXIT_PCT : BASE_EXIT_PCT;
    }

    /**
     * 연속 하락 횟수 갱신 — 직전 확인보다 낮으면 +1, 높으면 0으로 초기화, 같으면(호가 그대로) 유지.
     */
    public static int nextDropStreak(BigDecimal prevRate, BigDecimal ratePct, int streak) {
        if (prevRate == null) return 0;
        int cmp = ratePct.compareTo(prevRate);
        if (cmp < 0) return streak + 1;
        if (cmp > 0) return 0;
        return streak;
    }

    /**
     * 매도 규칙 (10/12 분리 — 당시 가상매매와 공유, 가상매매는 10/16 폐지).
     * @return {type("profit"|"damage"), reason} 또는 매도하지 않으면 null
     */
    public static String[] decideExit(BigDecimal ratePct, BigDecimal exitPct, long heldMinutes,
                                      BigDecimal maxRate, int dropStreak,
                                      boolean stallRuleApplies, long minutesSinceHigh) {
        String pctLabel = exitPct.stripTrailingZeros().toPlainString();
        if (ratePct.compareTo(exitPct) >= 0) {
            return new String[]{"profit", "익절+" + pctLabel + "%"};
        }
        if (ratePct.compareTo(exitPct.negate()) <= 0) {
            return new String[]{"damage", "손절-" + pctLabel + "%"};
        }
        // 수익권 하락 익절: 수익권에 들어온 적 있고, 연속 N번 하락했는데, 지금도 수익권
        if (maxRate != null && maxRate.compareTo(PROFIT_ZONE_PCT) >= 0
                && dropStreak >= PROFIT_ZONE_DROP_COUNT
                && ratePct.compareTo(PROFIT_ZONE_PCT) >= 0) {
            return new String[]{"profit", "수익권하락익절"};
        }
        // 수익권 정체 익절 (고정 코인만): 수익권에서 마지막 고점 이후 STALL_MINUTES 동안 새 고점 없음
        if (stallRuleApplies && maxRate != null && maxRate.compareTo(PROFIT_ZONE_PCT) >= 0
                && ratePct.compareTo(PROFIT_ZONE_PCT) >= 0
                && minutesSinceHigh >= STALL_MINUTES) {
            return new String[]{"profit", "수익권정체익절"};
        }
        if (heldMinutes >= TIME_EXIT_MINUTES) {
            return new String[]{ratePct.compareTo(FEE_ROUND_TRIP_PCT) > 0 ? "profit" : "damage",
                    "시간매도" + TIME_EXIT_MINUTES + "m"};
        }
        if (heldMinutes >= TIME_STOP_MINUTES && ratePct.compareTo(TIME_STOP_PCT) <= 0) {
            return new String[]{"damage", "시간손절" + TIME_STOP_MINUTES + "m"};
        }
        return null;
    }

    /** 슬로우 루프(3분)에서 보유 코인마다 호출 (10/16~, 이전 패스트 루프 30초) */
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

        // 보유 중 최고/최저 수익률 (MFE/MAE) — 3분 간격 표본(10/16~). 최고점 경신 시각도 기록(정체 판정용)
        BigDecimal prevMax = stateStore.holdMaxRateMap.get(coinNm);
        if (prevMax == null || ratePct.compareTo(prevMax) > 0 || !stateStore.highAtMap.containsKey(coinNm)) {
            stateStore.highAtMap.put(coinNm, LocalDateTime.now());
        }
        stateStore.holdMaxRateMap.merge(coinNm, ratePct, BigDecimal::max);
        stateStore.holdMinRateMap.merge(coinNm, ratePct, BigDecimal::min);

        // 수익권 하락 익절용 연속 하락 횟수
        int dropStreak = nextDropStreak(stateStore.prevRateMap.get(coinNm), ratePct,
                stateStore.dropStreakMap.getOrDefault(coinNm, 0));
        stateStore.prevRateMap.put(coinNm, ratePct);
        stateStore.dropStreakMap.put(coinNm, dropStreak);

        long heldMinutes = heldMinutes(coinNm);

        long minutesSinceHigh = Duration.between(stateStore.highAtMap.get(coinNm), LocalDateTime.now()).toMinutes();
        String[] decision = decideExit(ratePct, exitPct, heldMinutes,
                stateStore.holdMaxRateMap.get(coinNm), dropStreak,
                CoinSignalService.MAJOR_COINS.contains(coinNm), minutesSinceHigh);
        if (decision == null) return;
        String type = decision[0];
        String reason = decision[1];

        log.debug("{} [{}] 평균매수가:{} 매수호가:{} 변화율:{}% 보유:{}분 (보유중 최고:{}% 최저:{}%)",
                coinNm, reason, avg.stripTrailingZeros().toPlainString(), bid.stripTrailingZeros().toPlainString(),
                ratePct, heldMinutes, stateStore.holdMaxRateMap.get(coinNm), stateStore.holdMinRateMap.get(coinNm));

        sellNow(coinNm, type, reason);
    }

    /**
     * 본전탈출 (10/16) — 패스트 루프(30초)에서 보유 코인마다 호출.
     * 보유 중 한 번이라도 손실(매수가 대비 0% 미만)이었다가 매수가 대비 +{@link #PROFIT_ZONE_PCT}%(수수료 본전) 이상이 되면
     * 3분 판단을 기다리지 않고 즉시 매도한다. 시장가로 사므로 사실상 모든 포지션이 손실에서 시작한다 —
     * 즉 "본전이 되면 바로 판다"와 같다.
     *
     * @param ratePct    지금 매수가 대비 매수호가 변화율(%)
     * @param minRatePct 보유 중 최저 변화율(%) — HoldTrackingService 30초 확인 기준
     */
    public void checkBreakEvenEscape(String coinNm, BigDecimal ratePct, BigDecimal minRatePct) {
        if (ratePct == null || minRatePct == null) return;
        if (minRatePct.signum() >= 0 || ratePct.compareTo(PROFIT_ZONE_PCT) < 0) return;
        sellNow(coinNm, "profit", BREAK_EVEN_ESCAPE);
    }

    /**
     * 매도 실행 — 잠근 상태에서 잔고를 다시 확인하고 판다. 다른 루프가 이미 팔았으면 아무것도 하지 않는다.
     * 매도 당시 지표를 기록하기 위해 지금 시점 지표를 새로 계산 (실패 시 슬로우 루프 캐시).
     */
    private void sellNow(String coinNm, String type, String reason) {
        synchronized (sellLock) {
            CoinAccount cur = currentHolding(coinNm);
            if (cur == null) return;
            CoinSignalDto signal = coinSignalService.freshSignalOrCached(coinNm);
            if (signal == null) {
                log.warn("{} 지표 없음 — 다음 확인 때 매도 재시도", coinNm);
                return;
            }
            tradeExecutionService.executeSell(coinNm, cur.getBalance().toPlainString(), type, signal,
                    cur.getAvgBuyPrice(), reason);
        }
    }

    /** 지금 계좌에서 이 코인 보유분 (없으면 null) */
    private CoinAccount currentHolding(String coinNm) {
        for (CoinAccount a : exchangeClient.checkCoinAccount()) {
            if (!coinNm.equals(a.getCoinType() + "-" + a.getCoinName())) continue;
            if (a.getBalance() == null || a.getBalance().signum() <= 0) return null;
            if (a.getAvgBuyPrice() == null || a.getAvgBuyPrice().signum() <= 0) return null;
            return a;
        }
        return null;
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
