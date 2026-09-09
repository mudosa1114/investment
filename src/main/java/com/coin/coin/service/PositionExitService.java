package com.coin.coin.service;

import com.coin.coin.common.MarketPhase;
import com.coin.coin.dto.CoinAccount;
import com.coin.coin.dto.CoinSignalDto;
import com.coin.coin.dto.TradeHistoryDto;
import com.coin.coin.dto.response.OrdersResponse;
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
 * 보유 포지션 청산 판단.
 *
 * <p>9/7 구조 개편: 코인 단타는 오래 들고 있을 이유가 없다는 전제 하에 "3분마다 조회해서
 * 적당히 오르면 판다 / 적당히 떨어지면 관망한다 / 너무 떨어지면 손절하고 회복 가능성을 본다"
 * 3원칙으로 재구성했다. 지표(RSI/EMA/BB/phase)가 애초에 3분봉 기준이라 그보다 촘촘한 시간
 * 단위 판단은 같은 지표를 반복 계산할 뿐이므로, 패스트 루프(30초)는 캔들 갱신 사이의 급락(갭)
 * 방어용 하드손절 하나만 담당하고 — 트레일링 익절·점수 손절/익절을 포함한 실질적인 모든 매도
 * 판단은 슬로우 루프(3분, evaluateScoreBasedExit)로 통합했다. 시간 경과만으로 손익과 무관하게
 * 매도하던 시간강제매도/시간손절은 폐지 — exit_review 데이터 검증 결과(9/4-9/6, n=48) 시간강제매도
 * damage 48건 중 48건(100%)이 24시간 내 회복(44건 익절임계 도달)했고 정당한 손절은 0건으로 확인되어,
 * 손익과 무관한 시계 기반 청산이 실제로 손실을 키우는 구조였음이 데이터로 확인됨.
 *
 * <p>9/9 국면 의존도 제거: 로그 실측 백테스트(8/15-9/6, "점수평가" 라인 3,568건) 결과 BULL/BEAR
 * 국면(EMA20 단기 기울기)이 이후 수익률에 예측력이 없거나 오히려 역전됨을 확인(30분후 기준 BULL
 * -0.134%p/60.6%음전환, BEAR +0.139%p) — 반면 RSI는 방향·강도 모두 일관 검증(RSI≥70 -0.24%p/86.4%
 * 음전환, RSI 30~40 +0.16%p/72.1%양전환). 이에 따라 익절/트레일링/점수손절의 국면별 차등과 BULL 전용
 * 게이트를 모두 제거하고 단일 기준 + RSI 기반 판단으로 통일했다. phase는 로그 표기용으로만 유지한다.
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
     * 점수 익절 기준: +0.6% (9/9: 국면 차등 폐지, 3구간 근사평균으로 통일 — 클래스 상단 설명 참고)
     */
    static final BigDecimal PROFIT_THRESHOLD = new BigDecimal("1.006");

    // ─── 트레일링 스탑 설정 ───────────────────────────────────────────
    /**
     * 트레일링 활성화 기준: 투자금 대비 이 비율 이상 수익 시 추적 시작
     * (기존 +0.5% → +0.4%: 실제 익절 체결 평균이 +0.3~0.4%대에 몰려 있어 더 일찍 보호 시작)
     */
    private static final BigDecimal TRAILING_ACTIVATE_RATE = new BigDecimal("1.004");
    /**
     * 트레일링 낙폭: 고점 대비 -0.45% (9/9: 국면 차등 폐지, 3구간 중간값으로 통일)
     */
    private static final BigDecimal TRAILING_DROP = new BigDecimal("0.0045");

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

    // ─── 관망구간 추가매수(조건부 DCA) 설정 (9/9 신규) ─────────────────
    // 0% ~ STOP_SCORE_ACTIVATE_RATE(-0.9%) 구간은 익절/손절 어느 쪽도 판단하지 않는 "관망" 구간이었음.
    // 로그 실측 결과 이 구간에서 아무 방어 없이 하드손절(-1.2%)까지 흘러간 손절이 전체 손절의 35.5%를
    // 차지 — 시계 기반 매도(폐지된 시간강제매도)로 되돌아가는 대신, RSI가 저점 대비 반등하는 회복
    // 신호가 확인될 때만 평단을 낮추는 추가매수로 대응한다. 과거(4월) DCA는 "-1.5% 하락 시 지표 무관
    // 무조건 추가매수"였던 반면, 이번엔 반드시 RSI 반등 확인 후에만 실행 — 무지성 물타기가 아니다.
    /**
     * 추가매수 트리거: 포지션 보유 중 RSI 최저점 대비 이만큼 반등하면 회복 신호로 판단 (+3.0)
     */
    private static final BigDecimal ADD_BUY_RSI_REBOUND_MIN = new BigDecimal("3.0");
    /**
     * 추가매수 최대 횟수 (포지션당) — 손실 확대 위험을 제한
     */
    private static final int ADD_BUY_MAX_COUNT = 3;
    /**
     * 추가매수 최소 간격(분) — 같은 반등 신호로 연속 사이클마다 계속 추가하는 것 방지
     */
    private static final int ADD_BUY_MIN_INTERVAL_MINUTES = 6;
    /**
     * 추가매수 1회 금액(KRW) — Upbit 최소 주문금액(5,000원) 대비 여유를 둔 고정값.
     * 5,000원에 근접한 금액으로 샀다가 소폭만 더 하락해도 평가금액이 최소금액 밑으로 떨어져
     * 매도 주문 자체가 거부되는 버그가 과거(9/4) 있었음 — 같은 문제를 피하기 위해 저확신 최초매수와
     * 동일한 7,000원(하드스탑 -1.2%를 맞아도 평가금액 약 6,916원으로 최소금액 위 유지)을 사용한다.
     */
    private static final String ADD_BUY_AMOUNT = "7000";

    // ─── 점수 임계값 ──────────────────────────────────────────────────
    // 익절/손절 모두 국면 무관 단일 임계 ≥4 (9/9: 국면별 차등 폐지, phase는 로그 표기용으로만 유지)
    private static final int SELL_SCORE_THRESHOLD = 4;

    // ─── 9/7 구조 개편 ────────────────────────────────────────────────
    // 시간 경과만으로 손익과 무관하게 매도하던 시간손절/시간강제매도(TIME_STOP_LOSS_MINUTES/
    // TIME_STOP_FORCE_MINUTES) 폐지. exit_review 데이터(9/4-9/6, n=48)로 검증한 결과 시간강제매도
    // damage 48건 전부(100%) 24시간 내 회복 — 회복 여지가 있는 포지션을 시계만 보고 손절 처리해
    // 손실을 키우고 있었음. 트레일링 익절도 패스트 루프에서 슬로우 루프(3분)로 이동해 "3분마다
    // 조회해서 적당히 오르면 판다/적당히 떨어지면 관망한다/너무 떨어지면 손절한다" 구조로 통일.

    // ─── 9/9 국면 의존도 제거 ─────────────────────────────────────────
    // 로그 실측 백테스트(8/15-9/6, n=3,568) 결과 BULL/BEAR 국면이 이후 수익률에 예측력이 없거나
    // 역전(BULL 30분후 -0.134%p, BEAR +0.139%p)됨을 확인. RSI는 방향·강도 모두 일관되게 검증됨
    // (RSI≥70 -0.24%p 86.4% 음전환, RSI 30~40 +0.16%p 72.1% 양전환). 이에 따라 익절임계/트레일링
    // 낙폭/점수손절임계의 국면별 차등, BULL 전용 게이트(모멘텀소진익절/RSI모멘텀손절)를 모두 제거하고
    // 단일 기준 + RSI 기반 판단으로 통일. phase(shortPhase/longPhase/effectPhase)는 로그 표기 등
    // 정보 제공 목적으로만 유지하며 매도 판단 분기에는 더 이상 사용하지 않는다.

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
    //  [패스트 루프용] 갭(급락) 방어 전용 하드손절 — 캔들 갱신 주기(3분) 사이에
    //  발생하는 급락으로부터만 보호한다. 트레일링 익절·점수 손절/익절은 전부
    //  슬로우 루프(evaluateScoreBasedExit)로 이동 (9/7 구조 개편).
    // ══════════════════════════════════════════════════════════════════
    public void executePriceBasedActions(CoinAccount account, String coinNm, CoinSignalDto signal) {

        BigDecimal currentPrice = exchangeClient.checkCoinPrice(coinNm).getBidPrice();
        BigDecimal totalCost = account.getAvgBuyPrice()
                .multiply(account.getBalance()).setScale(0, RoundingMode.CEILING);
        BigDecimal sellablePrice = currentPrice.multiply(account.getBalance());
        BigDecimal profitRate = sellablePrice.divide(totalCost, 10, RoundingMode.HALF_UP);

        // ── 갭방어 강제손절: -1.2% (지표 무관, 패스트 루프 즉시 처리) ─────
        if (profitRate.compareTo(HARD_STOP_RATE) <= 0) {
            log.warn("{} 갭방어 강제손절 (-1.2%) 평가:{} 투자:{} [단기:{} RSI:{}]",
                    coinNm, sellablePrice.setScale(0, RoundingMode.HALF_UP), totalCost,
                    signal.getShortPhase(), signal.getRsi().setScale(1, RoundingMode.HALF_UP));
            stateStore.trailingPeakMap.remove(coinNm);
            stateStore.positionEntryTimeMap.remove(coinNm);
            stateStore.rsiPeakMap.remove(coinNm);
            stateStore.rsiTroughMap.remove(coinNm);
            stateStore.dcaCountMap.remove(coinNm);
            stateStore.lastDcaAtMap.remove(coinNm);
            tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), "강제손절");
        }
        // 그 외 판단(트레일링 익절/점수 익절/점수 손절)은 슬로우 루프(3분)에서 처리 — evaluateScoreBasedExit 참고.
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
            stateStore.rsiTroughMap.remove(coinNm);
            stateStore.dcaCountMap.remove(coinNm);
            stateStore.lastDcaAtMap.remove(coinNm);
            tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), "강제손절(정지중)");
            return;
        }

        // 트레일링 익절 (정지 상태에서도 기존 고점 추적 유지, 국면별 DROP 적용)
        if (profitRate.compareTo(TRAILING_ACTIVATE_RATE) >= 0) {
            MarketPhase shortPhase = signal.getShortPhase();
            MarketPhase longPhase = signal.getPhase();
            MarketPhase effectPhase = (shortPhase != MarketPhase.SIDEWAYS) ? shortPhase : longPhase;
            BigDecimal dropRate = trailingDropRate();

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
                stateStore.rsiTroughMap.remove(coinNm);
                stateStore.dcaCountMap.remove(coinNm);
                stateStore.lastDcaAtMap.remove(coinNm);
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
    //  [슬로우 루프용, 3분] 트레일링 익절 + 지표 점수 기반 익절/손절 — "적당히 오르면
    //  판다 / 너무 떨어지면 손절한다"를 모두 여기서 판단한다 (9/7). 패스트 루프(30초)는
    //  갭 방어용 하드손절 하나만 담당.
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
            stateStore.rsiTroughMap.remove(coinNm);
            stateStore.dcaCountMap.remove(coinNm);
            stateStore.lastDcaAtMap.remove(coinNm);
            registerProfitCooldown(coinNm, realtimeSellablePrice, totalCost, POST_PROFIT_COOLDOWN_HOT);
            tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice(), "RSI과매수익절");
            return;
        }

        // ── 트레일링 익절 (9/7: 패스트 루프 → 슬로우 루프 이동, 3분마다 갱신) ──
        // +0.4% 진입 후 국면별 낙폭 초과 시 매도 — "적당히 오르면 판다"
        // BULL -0.5% / SIDEWAYS -0.45% / BEAR -0.35%
        if (realtimeSellablePrice.compareTo(totalCost.multiply(TRAILING_ACTIVATE_RATE)) >= 0) {
            BigDecimal trailDropRate = trailingDropRate();

            // computeIfAbsent: 최초 진입 시만 anchor, 이후 map의 최고점 유지
            BigDecimal peak = stateStore.trailingPeakMap.computeIfAbsent(coinNm, k -> realtimeSellablePrice);
            if (realtimeSellablePrice.compareTo(peak) > 0) {
                peak = realtimeSellablePrice;
                stateStore.trailingPeakMap.put(coinNm, peak); // 최고점 갱신만 허용
            }
            BigDecimal trailingStopLine = peak.multiply(BigDecimal.ONE.subtract(trailDropRate));

            if (realtimeSellablePrice.compareTo(trailingStopLine) <= 0) {
                BigDecimal peakPct = peak.divide(totalCost, 6, RoundingMode.HALF_UP)
                        .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
                BigDecimal currPct = realtimeSellablePrice.divide(totalCost, 6, RoundingMode.HALF_UP)
                        .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
                log.info("{} 트레일링익절 고점:{}(+{}%) → 현재:{}(+{}%) [{}국면 DROP-{}%]",
                        coinNm,
                        peak.setScale(0, RoundingMode.HALF_UP), peakPct,
                        realtimeSellablePrice.setScale(0, RoundingMode.HALF_UP), currPct,
                        effectPhase, trailDropRate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP));
                stateStore.trailingPeakMap.remove(coinNm);
                stateStore.positionEntryTimeMap.remove(coinNm);
                stateStore.rsiPeakMap.remove(coinNm);
                stateStore.rsiTroughMap.remove(coinNm);
                stateStore.dcaCountMap.remove(coinNm);
                stateStore.lastDcaAtMap.remove(coinNm);
                registerProfitCooldown(coinNm, realtimeSellablePrice, totalCost, POST_PROFIT_COOLDOWN_MINUTES);
                tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice(), "트레일링익절");
                return;
            }
            log.info("{} 트레일링모드 고점:{} 현재:{} 스탑라인:{} [{}국면 DROP-{}%]",
                    coinNm,
                    peak.setScale(0, RoundingMode.HALF_UP),
                    realtimeSellablePrice.setScale(0, RoundingMode.HALF_UP),
                    trailingStopLine.setScale(0, RoundingMode.HALF_UP),
                    effectPhase, trailDropRate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP));
        }
        // else 브랜치 제거: 일시적으로 활성화 임계 아래로 내려가도 고점 유지 — 고점은 실제 매도 경로에서만 삭제

        // ── RSI 피크/저점 갱신 (포지션 보유 중 최고·최저 RSI 추적) ──────
        BigDecimal currentRsi = signal.getRsi();
        stateStore.rsiPeakMap.merge(coinNm, currentRsi, BigDecimal::max);
        BigDecimal rsiPeak = stateStore.rsiPeakMap.get(coinNm);
        stateStore.rsiTroughMap.merge(coinNm, currentRsi, BigDecimal::min);
        BigDecimal rsiTrough = stateStore.rsiTroughMap.get(coinNm);

        // ── RSI 모멘텀 소진 익절 (9/9: BULL 국면 게이트 제거 — RSI 자체가 검증된 신호) ──
        // 조건 A: RSI < 50 + 수익 중 (모멘텀 붕괴 조기 탈출)
        // 조건 B: RSI 고점 대비 -7 이상 하락 + 수익 ≥ +0.1% (피크 후 되돌림 탈출)
        boolean profitAny = realtimeSellablePrice.compareTo(totalCost) > 0;
        boolean profitMin = realtimeSellablePrice.compareTo(totalCost.multiply(BULL_EXHAUST_MIN_PROFIT)) >= 0;
        boolean condA = profitAny && currentRsi.compareTo(BULL_EXHAUST_RSI_ABS) < 0;
        boolean condB = profitMin && rsiPeak.subtract(currentRsi).compareTo(BULL_EXHAUST_RSI_DROP) >= 0;

        if (condA || condB) {
            BigDecimal profitPct = realtimeSellablePrice.divide(totalCost, 10, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
            String trigger = condA
                    ? String.format("RSI<50(현재%.1f)", currentRsi)
                    : String.format("RSI고점대비-%.1f(고점%.1f→현재%.1f)",
                    rsiPeak.subtract(currentRsi).setScale(1, RoundingMode.HALF_UP),
                    rsiPeak.setScale(1, RoundingMode.HALF_UP),
                    currentRsi.setScale(1, RoundingMode.HALF_UP));
            log.info("{} RSI모멘텀소진익절 {} 수익률:+{}%", coinNm, trigger, profitPct);
            stateStore.trailingPeakMap.remove(coinNm);
            stateStore.positionEntryTimeMap.remove(coinNm);
            stateStore.rsiPeakMap.remove(coinNm);
            stateStore.rsiTroughMap.remove(coinNm);
            stateStore.dcaCountMap.remove(coinNm);
            stateStore.lastDcaAtMap.remove(coinNm);
            registerProfitCooldown(coinNm, realtimeSellablePrice, totalCost, POST_PROFIT_COOLDOWN_HOT);
            tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice(), "RSI모멘텀소진익절");
            return;
        }

        // ── RSI 모멘텀 손절 (9/9: 장기 BULL 게이트 제거 — RSI 자체가 검증된 신호) ──
        // 점수 손절 미달 구간의 맹점 보완 — RSI 모멘텀 붕괴를 직접 감지
        // 조건: 손실 ≥ -0.5% + RSI 고점 대비 -7 이상 하락 + 현재 RSI < 50 → 조기 손절
        // ※ RSI < 50 추가 이유: RSI가 54, 57 등 아직 높은 구간이면 -7pt 하락은 단순 눌림목일 수 있음
        //    실제 모멘텀 붕괴는 RSI가 50 이하로 내려왔을 때만 판단 (오발동 방지, 기존 로그 검증)
        // ※ 8/15-24 로그 재분석: 그럼에도 12건 전량 손절(승률 0%, 평균 -0.74%) — 공통적으로 rsiPeak가
        //   진입 RSI 대비 거의 못 올랐다가(진짜 모멘텀 없이) 바로 되돌림. 아래 두 조건 추가로 오발동 억제:
        //   ① 진입 후 최소 보유시간 확보(노이즈성 즉시 반전 배제) ② peak가 진입 RSI보다 실제로 상승했었는지 확인
        boolean isLossRange = realtimeSellablePrice.compareTo(totalCost.multiply(BULL_RSI_STOP_MIN_LOSS)) <= 0;
        boolean rsiDropStop = rsiPeak.subtract(currentRsi).compareTo(BULL_EXHAUST_RSI_DROP) >= 0;
        boolean rsiBelowMid = currentRsi.compareTo(BULL_EXHAUST_RSI_ABS) < 0; // RSI < 50

        BigDecimal entryRsi = stateStore.entryRsiMap.getOrDefault(coinNm, currentRsi);
        boolean hadRealMomentum = rsiPeak.subtract(entryRsi).compareTo(BULL_RSI_STOP_MIN_PEAK_RISE) >= 0;
        LocalDateTime entryTimeChk = stateStore.positionEntryTimeMap.get(coinNm);
        boolean heldLongEnough = entryTimeChk == null
                || java.time.Duration.between(entryTimeChk, LocalDateTime.now()).toMinutes() >= BULL_RSI_STOP_MIN_HOLD_MINUTES;

        if (isLossRange && rsiDropStop && rsiBelowMid && hadRealMomentum && heldLongEnough) {
            BigDecimal lossPct = realtimeSellablePrice.divide(totalCost, 10, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
            log.warn("{} RSI모멘텀손절 RSI고점대비-{} (진입{}→고점{}→현재{}) 손실:{}%",
                    coinNm,
                    rsiPeak.subtract(currentRsi).setScale(1, RoundingMode.HALF_UP),
                    entryRsi.setScale(1, RoundingMode.HALF_UP),
                    rsiPeak.setScale(1, RoundingMode.HALF_UP),
                    currentRsi.setScale(1, RoundingMode.HALF_UP),
                    lossPct);
            stateStore.trailingPeakMap.remove(coinNm);
            stateStore.positionEntryTimeMap.remove(coinNm);
            stateStore.rsiPeakMap.remove(coinNm);
            stateStore.rsiTroughMap.remove(coinNm);
            stateStore.dcaCountMap.remove(coinNm);
            stateStore.lastDcaAtMap.remove(coinNm);
            tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), "RSI모멘텀손절");
            return;
        }

        // ── 관망구간 조건부 추가매수 (9/9 신규, 위 상수 설명 참고) ───────
        // 0%(totalCost) ~ -0.9%(STOP_SCORE_ACTIVATE_RATE) 사이의 손실이면서, 익절/손절 어느 조건도
        // 아직 활성화되지 않은 순수 "관망" 구간에서만 판단한다.
        boolean inWatchLossZone = realtimeSellablePrice.compareTo(totalCost) < 0
                && realtimeSellablePrice.compareTo(totalCost.multiply(STOP_SCORE_ACTIVATE_RATE)) > 0;
        boolean rsiRebounding = currentRsi.subtract(rsiTrough).compareTo(ADD_BUY_RSI_REBOUND_MIN) >= 0;
        int dcaCount = stateStore.dcaCountMap.getOrDefault(coinNm, 0);
        LocalDateTime lastDcaAt = stateStore.lastDcaAtMap.get(coinNm);
        boolean dcaCooldownPassed = lastDcaAt == null
                || java.time.Duration.between(lastDcaAt, LocalDateTime.now()).toMinutes() >= ADD_BUY_MIN_INTERVAL_MINUTES;

        if (inWatchLossZone && rsiRebounding && dcaCount < ADD_BUY_MAX_COUNT && dcaCooldownPassed) {
            BigDecimal lossPct = realtimeSellablePrice.divide(totalCost, 10, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
            log.info("{} 관망구간 추가매수({}/{}) RSI저점{}→현재{}(+{}) 손실:{}%",
                    coinNm, dcaCount + 1, ADD_BUY_MAX_COUNT,
                    rsiTrough.setScale(1, RoundingMode.HALF_UP), currentRsi.setScale(1, RoundingMode.HALF_UP),
                    currentRsi.subtract(rsiTrough).setScale(1, RoundingMode.HALF_UP), lossPct);
            OrdersResponse addBuyResponse = exchangeClient.orderCoin(coinNm, "bid", ADD_BUY_AMOUNT);
            tradeHistoryRepository.save(TradeHistoryDto.buyHistory(coinNm, ADD_BUY_AMOUNT, signal)
                    .toBuilder().tradeType("추가매수").build());
            stateStore.dcaCountMap.put(coinNm, dcaCount + 1);
            stateStore.lastDcaAtMap.put(coinNm, LocalDateTime.now());
            stateStore.rsiTroughMap.put(coinNm, currentRsi); // 추가매수 이후 새 저점 기준 재설정
            exchangeClient.askSuccessMessage(addBuyResponse);
            return;
        }

        // 익절 기준: 국면 무관 단일 +0.6% (9/9: 국면 차등 폐지)
        BigDecimal profitThreshold = PROFIT_THRESHOLD;

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

        // ── 점수 기반 손절 (-0.9% 활성화, 국면 무관 단일 임계 ≥4, 9/9 통일) ──
        // 강제손절(-1.2%)은 패스트 루프에서 처리 — 여기서는 지표 확인 후 조기 손절
        if (stopSellScore >= SELL_SCORE_THRESHOLD) {
            log.warn("{} 점수손절 점수:{} [{}] RSI:{} 단기:{} 장기:{}",
                    coinNm, stopSellScore, stopBreakdown,
                    signal.getRsi().setScale(1, RoundingMode.HALF_UP), shortPhase, longPhase);
            stateStore.trailingPeakMap.remove(coinNm);
            stateStore.positionEntryTimeMap.remove(coinNm);
            stateStore.rsiPeakMap.remove(coinNm);
            stateStore.rsiTroughMap.remove(coinNm);
            stateStore.dcaCountMap.remove(coinNm);
            stateStore.lastDcaAtMap.remove(coinNm);
            tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), "점수손절");
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
                stateStore.rsiTroughMap.remove(coinNm);
                stateStore.dcaCountMap.remove(coinNm);
                stateStore.lastDcaAtMap.remove(coinNm);
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
     * 트레일링 낙폭 허용치 반환 (9/9: 국면 무관 단일값)
     */
    private BigDecimal trailingDropRate() {
        return TRAILING_DROP;
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
