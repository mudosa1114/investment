package com.coin.coin.service;

import com.coin.coin.common.MarketPhase;
import com.coin.coin.dto.CoinAccount;
import com.coin.coin.dto.CoinSignalDto;
import com.coin.coin.repository.TradeHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 보유 포지션 청산 판단 — 하드 손절/시간 손절/트레일링 익절(패스트 루프)과
 * 지표 점수 기반 익절/손절(슬로우 루프)을 담당한다 (UpbitApi 역할분리, 9/4).
 *
 * <p>실제 매도 체결·기록은 {@link TradeExecutionService}에 위임한다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PositionExitService {

    private final TradeHistoryRepository tradeHistoryRepository;
    private final UpbitExchangeClient exchangeClient;
    private final TechnicalIndicatorService indicatorService;
    private final TradingStateStore stateStore;
    private final TradeExecutionService tradeExecutionService;

    // ─── 손익 임계값 상수 ──────────────────────────────────────────────
    /**
     * 점수 손절 활성화 기준: 이 비율 이하 손실 시 슬로우 루프에서 지표 점수 계산 시작 (-0.9%)
     * 점수가 역치 미달이면 포지션 유지 → 강제손절(HARD_STOP_RATE)까지 홀딩
     */
    static final BigDecimal STOP_SCORE_ACTIVATE_RATE = new BigDecimal("0.991");
    /**
     * 강제 손절: 지표와 무관하게 이 비율 이하이면 패스트 루프에서 즉시 매도 (-1.2%)
     */
    static final BigDecimal HARD_STOP_RATE = new BigDecimal("0.988");
    /**
     * BULL 국면 점수 익절 기준: +0.6% (상승 추세 — 작은 수익도 빠르게 확정)
     * (기존 +0.8% → +0.6%: 8/15-24 로그 10일간 이 임계값 도달로 익절된 사례 0건 —
     * 실제 체결 코인들의 30분 내 평균 변동폭이 목표치에 못 미쳐 전량 시간강제매도/손절로 종료됨)
     */
    static final BigDecimal PROFIT_THRESHOLD_BULL = new BigDecimal("1.006");
    /**
     * SIDEWAYS 국면 점수 익절 기준: +0.7% (횡보 — 충분한 쿠션 후 실현, 기존 +1.0%에서 하향)
     */
    static final BigDecimal PROFIT_THRESHOLD_SIDEWAYS = new BigDecimal("1.007");
    /**
     * BEAR 국면 점수 익절 기준: +0.4% (약세 전환 시 빠른 이탈 우선, 기존 +0.5%에서 하향)
     */
    static final BigDecimal PROFIT_THRESHOLD_BEAR = new BigDecimal("1.004");

    // ─── 트레일링 스탑 설정 ───────────────────────────────────────────
    /**
     * 트레일링 활성화 기준: 투자금 대비 이 비율 이상 수익 시 추적 시작
     * (기존 +0.5% → +0.4%: 실제 익절 체결 평균이 +0.3~0.4%대에 몰려 있어 더 일찍 보호 시작)
     */
    private static final BigDecimal TRAILING_ACTIVATE_RATE = new BigDecimal("1.004");
    /**
     * BULL 국면 트레일링 낙폭: 고점 대비 -0.5% — 상승 추세 출렁임 허용, 더 길게 추적
     */
    private static final BigDecimal TRAILING_DROP_BULL = new BigDecimal("0.005");
    /**
     * SIDEWAYS 국면 트레일링 낙폭: 고점 대비 -0.45% — 중립 기준
     */
    private static final BigDecimal TRAILING_DROP_SIDEWAYS = new BigDecimal("0.0045");
    /**
     * BEAR 국면 트레일링 낙폭: 고점 대비 -0.35% — 약세 빠른 수익 확보 우선
     */
    private static final BigDecimal TRAILING_DROP_BEAR = new BigDecimal("0.0035");

    // ─── 지표 임계값 상수 ──────────────────────────────────────────────
    /**
     * 익절 점수 RSI 가산 기준 + RSI 즉시 익절 기준: RSI > 70 시 과매수
     */
    private static final BigDecimal RSI_OVERBOUGHT = BigDecimal.valueOf(70);
    /**
     * RSI 즉시 익절 최소 수익률: RSI>70 조건과 함께 이 수익률 이상일 때 즉시 매도 (+0.3%)
     */
    private static final BigDecimal RSI_EXIT_MIN_PROFIT = new BigDecimal("1.003");

    // ─── BULL 모멘텀 소진 익절 설정 ─────────────────────────────────
    /**
     * [조건 A] shortPhase+longPhase 모두 BULL 이면서 RSI 이 값 미만 + 수익 중 → 즉시 익절
     */
    private static final BigDecimal BULL_EXHAUST_RSI_ABS = BigDecimal.valueOf(50);
    /**
     * [조건 B / 손절 공용] RSI 고점 대비 이 값 이상 하락 시 모멘텀 소진 판단
     */
    private static final BigDecimal BULL_EXHAUST_RSI_DROP = BigDecimal.valueOf(7);
    /**
     * [조건 B] 최소 수익률 기준 (+0.1%)
     */
    private static final BigDecimal BULL_EXHAUST_MIN_PROFIT = new BigDecimal("1.001");

    // ─── BULL RSI 모멘텀 손절 설정 ───────────────────────────────────
    /**
     * shortPhase+longPhase 모두 BULL + 손실 ≥ -0.5% + RSI 고점 대비 -7 이상 하락 → 조기 손절
     * 점수 손절(BULL≥5) 미달 구간에서 RSI 모멘텀 붕괴를 직접 감지해 -1.4% 강제손절 방어
     */
    private static final BigDecimal BULL_RSI_STOP_MIN_LOSS = new BigDecimal("0.995"); // -0.5%
    /**
     * RSI 모멘텀손절 오발동 방지 — 진입 RSI 대비 실제 상승폭 최소 기준.
     * 8/15-24 로그 분석: 손절 12건 전부 "진입 직후 RSI가 진입값 대비 거의 못 오르고(peak-entry &lt; 3)
     * 바로 하락 반전"한 케이스 — 애초에 모멘텀이 없었던 노이즈성 진입을 "모멘텀 붕괴"로 오판해 손절.
     * peak(rsiPeakMap)가 진입 RSI보다 이 값 이상 올라선 적이 있어야 "진짜 모멘텀이 있었다가 꺾인 것"으로 인정.
     */
    private static final BigDecimal BULL_RSI_STOP_MIN_PEAK_RISE = new BigDecimal("3.0");
    /**
     * RSI 모멘텀손절 최소 보유시간(분) — 진입 직후 1~2회 슬로우 루프 노이즈로 즉시 손절되는 것 방지
     */
    private static final int BULL_RSI_STOP_MIN_HOLD_MINUTES = 6;

    /**
     * 손절 점수 RSI 가산 기준: RSI < 30 시 과매도 +1점
     */
    private static final BigDecimal RSI_LOW = BigDecimal.valueOf(30);

    // ─── 점수 임계값 ──────────────────────────────────────────────────
    // 익절: phase 무관 ≥ 4 고정 (phase별 차등은 trailing DROP rate로 담당)
    // 손절: BULL ≥ 5 / SIDEWAYS ≥ 4 / BEAR ≥ 3 (약세일수록 빠른 손절)
    private static final int SELL_SCORE_THRESHOLD = 4;

    // ─── 시간 손절 설정 ───────────────────────────────────────────────
    // [9/4 재조정] 8/31-9/3 로그(n=125~147) 분석 결과, 전체 청산의 약 78%(114~121건)가
    // 점수익절/모멘텀손절/트레일링 같은 "신호 기반" 청산이 아니라 이 시간강제매도 하나로
    // 종료됨 — 즉 진입 신호가 좋았는지 나빴는지와 무관하게 대부분의 트레이드가 20분 시점의
    // "우연한 그 순간 가격"으로 승패가 갈리고 있었음(승률 33.6%, 손익비 1.55로 손익비는
    // 나쁘지 않은데도 순손실 지속 — 신호 차별화가 출구에서 지워지는 구조). 이게 8/31 도입한
    // 확신도별 포지션 사이징이 실제 데이터에서 예측과 정반대로 뒤집힌(4차 분석 최악 조합이
    // 5차 실측에서 최고 승률) 근본 원인으로 추정 — 출구가 신호와 무관한 노이즈이면 입구 쪽을
    // 아무리 세분화해도 통계가 매 구간 뒤집힐 수밖에 없음.
    // 슬롯 여유 확인: 8/31-9/3 실측 평균 보유시간 39.7분 기준으로도 하루 슬롯 점유는
    // 약 24슬롯-시간/일 (14슬롯×24시간=336슬롯-시간 중 7%) — 보유시간을 다소 늘려도
    // MAX_COIN_SLOTS=14가 병목이 아니므로 거래빈도(진입 횟수)에는 영향 없음.
    // → 점수익절/모멘텀 청산이 실제로 발동할 시간을 벌어주는 방향으로 15/20분 → 20/30분 복원.
    /**
     * 시간 손절 활성화: 매수 후 이 시간(분) 경과 + 손익률 ≤ -0.3% 이면 매도
     */
    private static final int TIME_STOP_LOSS_MINUTES = 20;
    /**
     * 시간 강제 매도: 매수 후 이 시간(분) 경과 시 손익률 무관 강제 매도 (LOSS_MINUTES보다 커야 함)
     */
    private static final int TIME_STOP_FORCE_MINUTES = 30;
    /**
     * 시간 손절 기준 손익률: -0.5% 이하 손실 시 TIME_STOP_LOSS_MINUTES 조건 적용 (기존 -0.3% → 완화)
     */
    private static final BigDecimal TIME_STOP_LOSS_RATE = new BigDecimal("0.995");
    /**
     * 시간강제매도 profit/damage 판정 기준: 수수료 손익분기(매수0.05%+매도0.05%=0.1%) 이상이어야 실질 익절
     */
    private static final BigDecimal TIME_FORCE_PROFIT_MIN = new BigDecimal("1.001");

    // ─── Circuit Breaker (일일 손실 한도) ────────────────────────────
    /**
     * 일일 실현손익 한도 (KRW) — 이 금액 이하 손실 시 봇 완전 정지
     * 총 운용 자본의 약 5% 수준으로 설정 권장 (예: 자본 20만원 → -10,000원)
     */
    private static final BigDecimal DAILY_LOSS_HALT_KRW = new BigDecimal("-10000");

    // ─── 익절 후 재진입 쿨다운 설정 ───────────────────────────────────
    /**
     * 트레일링·점수 정상 익절 후 재진입 차단 시간
     */
    private static final int POST_PROFIT_COOLDOWN_MINUTES = 2;
    /**
     * RSI과매수·BULL모멘텀소진 익절 후 재진입 차단 시간 — 과열 신호이므로 추가 대기
     */
    private static final int POST_PROFIT_COOLDOWN_HOT = 5;
    /**
     * 급등 익절(+2% 이상) 후 재진입 차단 시간 — 되돌림 위험 구간
     */
    private static final int POST_PROFIT_COOLDOWN_SPIKE = 8;
    /**
     * 급등 익절 판단 기준 수익률: 이 이상이면 SPIKE 쿨다운 적용
     */
    private static final BigDecimal PROFIT_SPIKE_THRESHOLD = new BigDecimal("1.02"); // +2%

    // ══════════════════════════════════════════════════════════════════
    //  [패스트 루프용] 현재가 기반 액션: 하드 손절(-0.9%) + 트레일링 익절
    //  DCA 제거 — 진입은 SHORT_BULL 10,000원 단일, 포지션 관리만 담당
    // ══════════════════════════════════════════════════════════════════
    public void executePriceBasedActions(CoinAccount account, String coinNm, CoinSignalDto signal) {

        BigDecimal currentPrice = exchangeClient.checkCoinPrice(coinNm).getBidPrice();
        BigDecimal totalCost = account.getAvgBuyPrice()
                .multiply(account.getBalance()).setScale(0, RoundingMode.CEILING);
        BigDecimal sellablePrice = currentPrice.multiply(account.getBalance());
        BigDecimal profitRate = sellablePrice.divide(totalCost, 10, RoundingMode.HALF_UP);

        // ── 강제 손절: -1.2% (지표 무관, 패스트 루프 즉시 처리) ─────────
        if (profitRate.compareTo(HARD_STOP_RATE) <= 0) {
            log.warn("{} 강제손절 (-1.2%) 평가:{} 투자:{} [단기:{} RSI:{}]",
                    coinNm, sellablePrice.setScale(0, RoundingMode.HALF_UP), totalCost,
                    signal.getShortPhase(), signal.getRsi().setScale(1, RoundingMode.HALF_UP));
            stateStore.trailingPeakMap.remove(coinNm);
            stateStore.positionEntryTimeMap.remove(coinNm);
            stateStore.rsiPeakMap.remove(coinNm);
            tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), "강제손절");
            return;
        }

        // ── 시간 손절: 15분 경과 + 손익 ≤ -0.3% → 매도 ──────────────────
        // ── 시간 강제 매도: 21분 경과 시 손익 무관 강제 매도 ─────────────
        // SHORT_BULL 모멘텀은 통상 15분 내 소진 — 이후 포지션은 자본 묶임
        LocalDateTime entryTime = stateStore.positionEntryTimeMap.get(coinNm);
        if (entryTime != null) {
            long minutesHeld = java.time.Duration.between(entryTime, LocalDateTime.now()).toMinutes();
            BigDecimal profitPct = profitRate.subtract(BigDecimal.ONE)
                    .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);

            // LOSS 체크를 FORCE보다 먼저 — LOSS_MINUTES < FORCE_MINUTES 보장 필요
            if (minutesHeld >= TIME_STOP_LOSS_MINUTES
                    && profitRate.compareTo(TIME_STOP_LOSS_RATE) <= 0) {
                log.warn("{} 시간손절 ({}분 경과, 손익:{}% ≤ -0.3%) — 모멘텀 소진 판단",
                        coinNm, minutesHeld, profitPct);
                stateStore.trailingPeakMap.remove(coinNm);
                stateStore.positionEntryTimeMap.remove(coinNm);
                stateStore.rsiPeakMap.remove(coinNm);
                tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), "시간손절");
                return;
            }
            if (minutesHeld >= TIME_STOP_FORCE_MINUTES) {
                // 수수료 손익분기(0.1%) 이상이어야 profit — 미만은 실질 손실이므로 damage
                String sellType = profitRate.compareTo(TIME_FORCE_PROFIT_MIN) >= 0 ? "profit" : "damage";
                log.warn("{} 시간강제매도 ({}분 경과, 손익:{}%) → {}",
                        coinNm, minutesHeld, profitPct, sellType);
                stateStore.trailingPeakMap.remove(coinNm);
                stateStore.positionEntryTimeMap.remove(coinNm);
                stateStore.rsiPeakMap.remove(coinNm);
                tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), sellType, signal, account.getAvgBuyPrice(), "시간강제매도");
                return;
            }
        }

        // ── 트레일링 익절: +0.8% 진입 후 국면별 낙폭 초과 시 매도 ─────
        // BULL -0.5% / SIDEWAYS -0.45% / BEAR -0.35%
        if (profitRate.compareTo(TRAILING_ACTIVATE_RATE) >= 0) {
            MarketPhase shortPhase = signal.getShortPhase();
            MarketPhase longPhase = signal.getPhase();
            MarketPhase effectPhase = (shortPhase != MarketPhase.SIDEWAYS) ? shortPhase : longPhase;
            BigDecimal dropRate = trailingDropRate(effectPhase);

            // computeIfAbsent: 최초 진입 시만 anchor, 이후 map의 최고점 유지
            BigDecimal peak = stateStore.trailingPeakMap.computeIfAbsent(coinNm, k -> sellablePrice);
            if (sellablePrice.compareTo(peak) > 0) {
                peak = sellablePrice;
                stateStore.trailingPeakMap.put(coinNm, peak); // 최고점 갱신만 허용
            }
            BigDecimal trailingStopLine = peak.multiply(BigDecimal.ONE.subtract(dropRate));

            if (sellablePrice.compareTo(trailingStopLine) <= 0) {
                BigDecimal peakPct = peak.divide(totalCost, 6, RoundingMode.HALF_UP)
                        .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
                BigDecimal currPct = profitRate.subtract(BigDecimal.ONE)
                        .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
                log.info("{} 트레일링익절 고점:{}(+{}%) → 현재:{}(+{}%) [{}국면 DROP-{}%]",
                        coinNm,
                        peak.setScale(0, RoundingMode.HALF_UP), peakPct,
                        sellablePrice.setScale(0, RoundingMode.HALF_UP), currPct,
                        effectPhase, dropRate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP));
                stateStore.trailingPeakMap.remove(coinNm);
                stateStore.positionEntryTimeMap.remove(coinNm);
                stateStore.rsiPeakMap.remove(coinNm);
                registerProfitCooldown(coinNm, sellablePrice, totalCost, POST_PROFIT_COOLDOWN_MINUTES);
                tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice(), "트레일링익절");
                return;
            }
            log.info("{} 트레일링모드 고점:{} 현재:{} 스탑라인:{} [{}국면 DROP-{}%]",
                    coinNm,
                    peak.setScale(0, RoundingMode.HALF_UP),
                    sellablePrice.setScale(0, RoundingMode.HALF_UP),
                    trailingStopLine.setScale(0, RoundingMode.HALF_UP),
                    effectPhase, dropRate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP));
        }
        // else 브랜치 제거: 일시적으로 활성화 임계 아래로 내려가도 고점 유지
        // 고점은 실제 매도 경로(executeSell)에서만 삭제됨
    }

    // ══════════════════════════════════════════════════════════════════
    //  [봇 정지 상태 전용] 하드 손절 + 트레일링 익절만 실행 — 신규매수 차단
    // ══════════════════════════════════════════════════════════════════
    public void executeHardExitsOnly(CoinAccount account, String coinNm, CoinSignalDto signal) {
        BigDecimal currentPrice = exchangeClient.checkCoinPrice(coinNm).getBidPrice();
        BigDecimal totalCost = account.getAvgBuyPrice()
                .multiply(account.getBalance()).setScale(0, RoundingMode.CEILING);
        BigDecimal sellablePrice = currentPrice.multiply(account.getBalance());
        BigDecimal profitRate = sellablePrice.divide(totalCost, 10, RoundingMode.HALF_UP);

        // 강제 손절: -1.2%
        if (profitRate.compareTo(HARD_STOP_RATE) <= 0) {
            log.warn("{} [정지중] 강제손절 (-1.2%) 평가:{} 투자:{} [단기:{} RSI:{}]",
                    coinNm, sellablePrice.setScale(0, RoundingMode.HALF_UP), totalCost,
                    signal.getShortPhase(), signal.getRsi().setScale(1, RoundingMode.HALF_UP));
            stateStore.trailingPeakMap.remove(coinNm);
            stateStore.positionEntryTimeMap.remove(coinNm);
            stateStore.rsiPeakMap.remove(coinNm);
            tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), "강제손절(정지중)");
            return;
        }

        // 트레일링 익절 (정지 상태에서도 기존 고점 추적 유지, 국면별 DROP 적용)
        if (profitRate.compareTo(TRAILING_ACTIVATE_RATE) >= 0) {
            MarketPhase shortPhase = signal.getShortPhase();
            MarketPhase longPhase = signal.getPhase();
            MarketPhase effectPhase = (shortPhase != MarketPhase.SIDEWAYS) ? shortPhase : longPhase;
            BigDecimal dropRate = trailingDropRate(effectPhase);

            BigDecimal peak = stateStore.trailingPeakMap.computeIfAbsent(coinNm, k -> sellablePrice);
            if (sellablePrice.compareTo(peak) > 0) {
                peak = sellablePrice;
                stateStore.trailingPeakMap.put(coinNm, peak);
            }
            BigDecimal trailingStopLine = peak.multiply(BigDecimal.ONE.subtract(dropRate));
            if (sellablePrice.compareTo(trailingStopLine) <= 0) {
                log.info("{} [정지중] 트레일링익절 고점:{} 현재:{} [{}국면 DROP-{}%]",
                        coinNm,
                        peak.setScale(0, RoundingMode.HALF_UP),
                        sellablePrice.setScale(0, RoundingMode.HALF_UP),
                        effectPhase, dropRate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP));
                stateStore.trailingPeakMap.remove(coinNm);
                stateStore.positionEntryTimeMap.remove(coinNm);
                stateStore.rsiPeakMap.remove(coinNm);
                registerProfitCooldown(coinNm, sellablePrice, totalCost, POST_PROFIT_COOLDOWN_MINUTES);
                tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice(), "트레일링익절(정지중)");
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Circuit Breaker — 당일 실현손익 조회
    // ══════════════════════════════════════════════════════════════════

    /**
     * 오늘 00:00 이후 실현손익 합산이 DAILY_LOSS_HALT_KRW 이하이면 true 반환.
     * true 반환 시 신규 매수·DCA·점수 매도를 모두 차단하고 하드 익절/손절만 유지.
     */
    public boolean isDailyLossHaltTriggered() {
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
    //  [슬로우 루프용] 지표 점수 기반 익절
    //  손절·트레일링은 패스트 루프(30초)에서 담당하므로 여기서는 익절만 판단
    // ══════════════════════════════════════════════════════════════════
    public void evaluateScoreBasedExit(CoinAccount account, String coinNm, CoinSignalDto signal) {

        // shortPhase 우선, SIDEWAYS일 때만 longPhase fallback
        MarketPhase shortPhase = signal.getShortPhase();
        MarketPhase longPhase = signal.getPhase();
        MarketPhase effectPhase = (shortPhase != MarketPhase.SIDEWAYS) ? shortPhase : longPhase;

        // indicatorPrice: BB·EMA 등 지표와 동일 시점 → 점수 계산 기준
        // realtimePrice : 실시간 호가 → 손익 구간 판단 기준 (오판 방지)
        BigDecimal indicatorPrice = signal.getPrice().getBidPrice();
        BigDecimal realtimePrice = exchangeClient.checkCoinPrice(coinNm).getBidPrice();
        boolean isGoldenCross = indicatorService.isGoldenCross(signal.getEma());

        BigDecimal totalCost = account.getAvgBuyPrice()
                .multiply(account.getBalance()).setScale(0, RoundingMode.CEILING);
        BigDecimal realtimeSellablePrice = realtimePrice.multiply(account.getBalance());

        // ── RSI 과매수 즉시 익절: RSI > 70 + 수익 ≥ +0.2% ──────────────
        // 트레일링/점수 대기 없이 즉시 매도 — 오버슈팅 고점에서 수익 확보
        // 데드존(+0.2%~+0.5%) 포지션이 RSI 과열 후 되돌아오는 케이스 방어
        if (signal.getRsi().compareTo(RSI_OVERBOUGHT) > 0
                && realtimeSellablePrice.compareTo(totalCost.multiply(RSI_EXIT_MIN_PROFIT)) >= 0) {
            BigDecimal profitPct = realtimeSellablePrice.divide(totalCost, 10, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
            log.info("{} RSI과매수익절 RSI:{} 수익률:+{}% [단기:{} 장기:{}]",
                    coinNm, signal.getRsi().setScale(1, RoundingMode.HALF_UP),
                    profitPct, shortPhase, longPhase);
            stateStore.trailingPeakMap.remove(coinNm);
            stateStore.positionEntryTimeMap.remove(coinNm);
            stateStore.rsiPeakMap.remove(coinNm);
            registerProfitCooldown(coinNm, realtimeSellablePrice, totalCost, POST_PROFIT_COOLDOWN_HOT);
            tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice(), "RSI과매수익절");
            return;
        }

        // ── RSI 피크 갱신 (포지션 보유 중 최고 RSI 추적) ────────────────
        BigDecimal currentRsi = signal.getRsi();
        stateStore.rsiPeakMap.merge(coinNm, currentRsi, BigDecimal::max);
        BigDecimal rsiPeak = stateStore.rsiPeakMap.get(coinNm);

        // ── BULL 모멘텀 소진 익절 (shortPhase+longPhase 모두 BULL 한정) ──
        // 조건 A: RSI < 50 + 수익 중 (모멘텀 붕괴 조기 탈출)
        // 조건 B: RSI 고점 대비 -7 이상 하락 + 수익 ≥ +0.1% (피크 후 되돌림 탈출)
        boolean bothBull = shortPhase == MarketPhase.BULL && longPhase == MarketPhase.BULL;
        boolean profitAny = realtimeSellablePrice.compareTo(totalCost) > 0;
        boolean profitMin = realtimeSellablePrice.compareTo(totalCost.multiply(BULL_EXHAUST_MIN_PROFIT)) >= 0;
        boolean condA = profitAny && currentRsi.compareTo(BULL_EXHAUST_RSI_ABS) < 0;
        boolean condB = profitMin && rsiPeak.subtract(currentRsi).compareTo(BULL_EXHAUST_RSI_DROP) >= 0;

        if (bothBull && (condA || condB)) {
            BigDecimal profitPct = realtimeSellablePrice.divide(totalCost, 10, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
            String trigger = condA
                    ? String.format("RSI<50(현재%.1f)", currentRsi)
                    : String.format("RSI고점대비-%.1f(고점%.1f→현재%.1f)",
                    rsiPeak.subtract(currentRsi).setScale(1, RoundingMode.HALF_UP),
                    rsiPeak.setScale(1, RoundingMode.HALF_UP),
                    currentRsi.setScale(1, RoundingMode.HALF_UP));
            log.info("{} BULL모멘텀소진익절 {} 수익률:+{}%", coinNm, trigger, profitPct);
            stateStore.trailingPeakMap.remove(coinNm);
            stateStore.positionEntryTimeMap.remove(coinNm);
            stateStore.rsiPeakMap.remove(coinNm);
            registerProfitCooldown(coinNm, realtimeSellablePrice, totalCost, POST_PROFIT_COOLDOWN_HOT);
            tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice(), "BULL모멘텀소진익절");
            return;
        }

        // ── BULL RSI 모멘텀 손절 (장기 BULL 한정, 단기는 무관) ──────────
        // 점수 손절(BULL≥5) 미달 구간의 맹점 보완 — RSI 모멘텀 붕괴를 직접 감지
        // bothBull 대신 longPhase==BULL: 단기가 SIDEWAYS/BEAR로 전환된 것 자체가 모멘텀 약화 신호
        // 조건: 장기 BULL + 손실 ≥ -0.5% + RSI 고점 대비 -7 이상 하락 + 현재 RSI < 50 → 조기 손절
        // ※ RSI < 50 추가 이유: RSI가 54, 57 등 아직 BULL 구간이면 -7pt 하락은 단순 눌림목일 수 있음
        //    실제 모멘텀 붕괴는 RSI가 50 이하로 내려왔을 때만 판단 (May15-19 로그에서 오발동 6건 확인)
        // ※ 8/15-24 로그 재분석: 그럼에도 12건 전량 손절(승률 0%, 평균 -0.74%) — 공통적으로 rsiPeak가
        //   진입 RSI 대비 거의 못 올랐다가(진짜 모멘텀 없이) 바로 되돌림. 아래 두 조건 추가로 오발동 억제:
        //   ① 진입 후 최소 보유시간 확보(노이즈성 즉시 반전 배제) ② peak가 진입 RSI보다 실제로 상승했었는지 확인
        boolean longPhaseBull = longPhase == MarketPhase.BULL;
        boolean isLossRange = realtimeSellablePrice.compareTo(totalCost.multiply(BULL_RSI_STOP_MIN_LOSS)) <= 0;
        boolean rsiDropStop = rsiPeak.subtract(currentRsi).compareTo(BULL_EXHAUST_RSI_DROP) >= 0;
        boolean rsiBelowMid = currentRsi.compareTo(BULL_EXHAUST_RSI_ABS) < 0; // RSI < 50

        BigDecimal entryRsi = stateStore.entryRsiMap.getOrDefault(coinNm, currentRsi);
        boolean hadRealMomentum = rsiPeak.subtract(entryRsi).compareTo(BULL_RSI_STOP_MIN_PEAK_RISE) >= 0;
        LocalDateTime entryTimeChk = stateStore.positionEntryTimeMap.get(coinNm);
        boolean heldLongEnough = entryTimeChk == null
                || java.time.Duration.between(entryTimeChk, LocalDateTime.now()).toMinutes() >= BULL_RSI_STOP_MIN_HOLD_MINUTES;

        if (longPhaseBull && isLossRange && rsiDropStop && rsiBelowMid && hadRealMomentum && heldLongEnough) {
            BigDecimal lossPct = realtimeSellablePrice.divide(totalCost, 10, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
            log.warn("{} BULL RSI모멘텀손절 RSI고점대비-{} (진입{}→고점{}→현재{}) 손실:{}%",
                    coinNm,
                    rsiPeak.subtract(currentRsi).setScale(1, RoundingMode.HALF_UP),
                    entryRsi.setScale(1, RoundingMode.HALF_UP),
                    rsiPeak.setScale(1, RoundingMode.HALF_UP),
                    currentRsi.setScale(1, RoundingMode.HALF_UP),
                    lossPct);
            stateStore.trailingPeakMap.remove(coinNm);
            stateStore.positionEntryTimeMap.remove(coinNm);
            stateStore.rsiPeakMap.remove(coinNm);
            tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), "BULL RSI모멘텀손절");
            return;
        }

        // effectPhase별 익절 기준 차등: BULL +0.6% / SIDEWAYS +0.7% / BEAR +0.4%
        BigDecimal profitThreshold;
        if (effectPhase == MarketPhase.BULL) profitThreshold = PROFIT_THRESHOLD_BULL;
        else if (effectPhase == MarketPhase.SIDEWAYS) profitThreshold = PROFIT_THRESHOLD_SIDEWAYS;
        else profitThreshold = PROFIT_THRESHOLD_BEAR;

        boolean isProfitRange = realtimeSellablePrice.compareTo(totalCost.multiply(profitThreshold)) >= 0;
        // 점수 손절 활성화: -0.9% 이상 손실 시 지표 점수 계산 시작
        boolean isStopRange = realtimeSellablePrice.compareTo(totalCost.multiply(STOP_SCORE_ACTIVATE_RATE)) <= 0;

        int profitSellScore = profitSellScore(signal, indicatorPrice, !isGoldenCross, isProfitRange);
        String profitBreakdown = profitScoreBreakdown(signal, indicatorPrice, !isGoldenCross, isProfitRange);
        int stopSellScore = stopLossScore(signal, indicatorPrice, !isGoldenCross, isStopRange);
        String stopBreakdown = stopScoreBreakdown(signal, indicatorPrice, !isGoldenCross, isStopRange);

        BigDecimal thresholdPct = profitThreshold.subtract(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
        BigDecimal profitRatePct = realtimeSellablePrice.divide(totalCost, 10, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        log.info("{} 점수평가 익절:{} 손절:{} [단기:{} 장기:{} RSI:{} 실시간가:{} 수익률:{}% 익절임계:+{}%]",
                coinNm, profitSellScore, stopSellScore, shortPhase, longPhase,
                signal.getRsi().setScale(1, RoundingMode.HALF_UP),
                realtimePrice.setScale(2, RoundingMode.HALF_UP),
                profitRatePct, thresholdPct);

        // ── 점수 기반 손절 (-0.9% 활성화, BULL≥5 / SIDE≥4 / BEAR≥3) ──────
        // 강제손절(-1.4%)은 패스트 루프에서 처리 — 여기서는 지표 확인 후 조기 손절
        if (effectPhase == MarketPhase.BULL && stopSellScore >= SELL_SCORE_THRESHOLD + 1) {
            log.warn("{} 점수손절 [BULL] 점수:{} [{}] RSI:{} 단기:{} 장기:{}",
                    coinNm, stopSellScore, stopBreakdown,
                    signal.getRsi().setScale(1, RoundingMode.HALF_UP), shortPhase, longPhase);
            stateStore.trailingPeakMap.remove(coinNm);
            stateStore.positionEntryTimeMap.remove(coinNm);
            stateStore.rsiPeakMap.remove(coinNm);
            tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), "점수손절[BULL]");
            return;
        }
        if (effectPhase == MarketPhase.SIDEWAYS && stopSellScore >= SELL_SCORE_THRESHOLD) {
            log.warn("{} 점수손절 [SIDE] 점수:{} [{}] RSI:{} 단기:{} 장기:{}",
                    coinNm, stopSellScore, stopBreakdown,
                    signal.getRsi().setScale(1, RoundingMode.HALF_UP), shortPhase, longPhase);
            stateStore.trailingPeakMap.remove(coinNm);
            stateStore.positionEntryTimeMap.remove(coinNm);
            stateStore.rsiPeakMap.remove(coinNm);
            tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), "점수손절[SIDE]");
            return;
        }
        if (effectPhase == MarketPhase.BEAR && stopSellScore >= SELL_SCORE_THRESHOLD - 1) {
            log.warn("{} 점수손절 [BEAR] 점수:{} [{}] RSI:{} 단기:{} 장기:{}",
                    coinNm, stopSellScore, stopBreakdown,
                    signal.getRsi().setScale(1, RoundingMode.HALF_UP), shortPhase, longPhase);
            stateStore.trailingPeakMap.remove(coinNm);
            stateStore.positionEntryTimeMap.remove(coinNm);
            stateStore.rsiPeakMap.remove(coinNm);
            tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), "점수손절[BEAR]");
            return;
        }

        // ── 점수 기반 익절: phase 무관 ≥4 고정 ──────────────────────────
        // 트레일링 활성 중이면 점수 익절 생략 — 트레일링이 더 높은 수익 확보 가능
        // (패스트 루프 트레일링이 슬로우 루프 점수보다 우선순위 상위)
        if (profitSellScore >= SELL_SCORE_THRESHOLD) {
            BigDecimal activePeak = stateStore.trailingPeakMap.get(coinNm);
            if (activePeak != null) {
                log.info("{} 점수익절 스킵 — 트레일링 활성 중 (고점:{}) 점수:{} [{}]",
                        coinNm, activePeak.setScale(0, RoundingMode.HALF_UP),
                        profitSellScore, profitBreakdown);
            } else {
                log.info("{} 익절실행 [{}] 점수:{} [{}] RSI:{} 단기:{} 장기:{}",
                        coinNm, effectPhase, profitSellScore, profitBreakdown,
                        signal.getRsi().setScale(1, RoundingMode.HALF_UP), shortPhase, longPhase);
                stateStore.trailingPeakMap.remove(coinNm);
                stateStore.positionEntryTimeMap.remove(coinNm);
                stateStore.rsiPeakMap.remove(coinNm);
                registerProfitCooldown(coinNm, realtimeSellablePrice, totalCost, POST_PROFIT_COOLDOWN_MINUTES);
                tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice(), "점수익절");
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  점수 계산
    // ══════════════════════════════════════════════════════════════════

    /**
     * 익절 유형별 차등 쿨다운 등록.
     * - 수익률 ≥ +2%(SPIKE_THRESHOLD): SPIKE 쿨다운 (15분) — 급등 후 되돌림 위험
     * - 그 외: baseCooldownMinutes 적용 (정상 3분 / 과열 10분)
     */
    private void registerProfitCooldown(String coinNm,
                                        BigDecimal sellablePrice,
                                        BigDecimal totalCost,
                                        int baseCooldownMinutes) {
        BigDecimal profitRate = sellablePrice.divide(totalCost, 10, RoundingMode.HALF_UP);
        int cooldownMinutes = profitRate.compareTo(PROFIT_SPIKE_THRESHOLD) >= 0
                ? POST_PROFIT_COOLDOWN_SPIKE
                : baseCooldownMinutes;
        LocalDateTime until = LocalDateTime.now().plusMinutes(cooldownMinutes);
        stateStore.profitCooldownUntilMap.put(coinNm, until);
        log.info("{} 익절 쿨다운 등록 ({}분, 해제: {})",
                coinNm, cooldownMinutes,
                until.toString().replace("T", " ").substring(0, 16));
    }

    /**
     * 국면별 트레일링 낙폭 허용치 반환
     * BULL -0.5% / SIDEWAYS -0.45% / BEAR -0.35%
     */
    private BigDecimal trailingDropRate(MarketPhase phase) {
        return switch (phase) {
            case BULL -> TRAILING_DROP_BULL;
            case SIDEWAYS -> TRAILING_DROP_SIDEWAYS;
            default -> TRAILING_DROP_BEAR; // BEAR
        };
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
        int score = 2; // 수익 구간 기본 +2 (3→2 하향: 트레일링 활용도 증가)
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

    /**
     * 손절 점수 (최대 8점, SELL_SCORE_THRESHOLD 이상이면 점수 손절)
     * - 손실 구간 (-0.9%)     +3  (활성화 기준, 미달 시 0 반환)
     * - BB 하단 이탈           +2  (강한 하락 돌파 신호)
     * - 데드크로스 (EMA5<EMA20)+2  (하락 모멘텀 확인)
     * - BB 중간선 이하         +1  (하락 압력 지속)
     * - RSI 30 미만            +1  (과매도권 진입 — 추가 하락 가능성)
     */
    private int stopLossScore(CoinSignalDto signal,
                              BigDecimal price,
                              boolean isDeadCross,
                              boolean isStopRange) {
        if (!isStopRange) return 0;
        int score = 3; // 손실 구간 진입 기본 +3
        if (price.compareTo(signal.getBb().get("lower")) < 0) score += 2; // BB 하단 이탈
        if (isDeadCross) score += 2; // 데드크로스
        if (price.compareTo(signal.getBb().get("middle")) < 0) score += 1; // BB 중간선 이하
        if (signal.getRsi().compareTo(RSI_LOW) < 0) score += 1; // RSI 과매도
        return score;
    }

    /**
     * 손절 점수 근거 문자열 — 매매 실행 로그용
     */
    private String stopScoreBreakdown(CoinSignalDto signal, BigDecimal price,
                                      boolean isDeadCross, boolean isStopRange) {
        if (!isStopRange) return "손실구간미달";
        List<String> parts = new ArrayList<>();
        parts.add("손실구간+3");
        if (price.compareTo(signal.getBb().get("lower")) < 0) parts.add("BB하단이탈+2");
        if (isDeadCross) parts.add("데드크로스+2");
        if (price.compareTo(signal.getBb().get("middle")) < 0) parts.add("BB중간이하+1");
        if (signal.getRsi().compareTo(RSI_LOW) < 0) parts.add("RSI과매도+1");
        return String.join(" ", parts);
    }

    /**
     * 익절 점수 근거 문자열 — 매매 실행 로그용
     * 각 항목이 점수에 기여했는지 표시 (profitSellScore와 동일 로직)
     */
    private String profitScoreBreakdown(CoinSignalDto signal, BigDecimal price,
                                        boolean isDeadCross, boolean isProfitRange) {
        if (!isProfitRange) return "수익구간미달";
        List<String> parts = new ArrayList<>();
        parts.add("수익구간+2");
        if (price.compareTo(signal.getBb().get("upper")) >= 0) parts.add("BB상단+2");
        if (isDeadCross) parts.add("데드크로스+2");
        if (price.compareTo(signal.getBb().get("middle")) >= 0) parts.add("BB중간+1");
        if (signal.getRsi().compareTo(RSI_OVERBOUGHT) > 0) parts.add("RSI과매수+1");
        return String.join(" ", parts);
    }
}
