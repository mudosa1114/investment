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
     * 강제 손절: 지표와 무관하게 이 비율 이하이면 패스트 루프에서 즉시 매도 (-1.2%)
     */
    static final BigDecimal HARD_STOP_RATE = new BigDecimal("0.988");
    /**
     * 즉시손절(스마트 조기손절) 기준: 손실구간 상태머신에서 classifyIndicatorOutlook 판정이
     * 강한상승이 아니면 이 비율 이하에서 즉시 매도한다 (-1.1%, 9/17 신규).
     *
     * <p>-1.0/-1.1/-1.2% 중 -1.1%로 결정한 근거:
     * (1) 하드스탑(HARD_STOP_RATE, -1.2%)과 최소 0.1%p 간격을 둬야 진입 슬리피지·단일 틱 급락
     * 상황(실측: THETA 매수→강제손절 0.2초, MIRA 1.3초)에서도 지표 기반 즉시손절이 하드스탑보다
     * 먼저 발동할 여지가 생긴다 — -1.2%와 동일하면 사실상 하드스탑과 중복이라 이 로직 자체가
     * 무의미해진다.
     * (2) 9/14-9/15 BB존 백테스트(지표스냅샷 8,037건)에서 손실구간(-0.9%~-1.2%)이 오히려
     * 반등확률이 가장 높은 구간으로 확인됨(BB하단이탈 15분후 평균수익률 플러스 전환, 전 구간
     * p&lt;0.0001) — classifyIndicatorOutlook이 강한상승 신호를 걸러주긴 하지만, 가격 임계값 자체도
     * -1.0%처럼 너무 타이트하게 잡지 않는 편이 반등 여지가 있는 포지션을 조기에 자르는 위험을 줄인다.
     * (3) 관망 횟수 제한 대신 가격 기준을 선택한 취지(user: "보수적으로 잡는게 나아보이는데")에도
     * 너무 이르게 자르지 않는 -1.1%가 더 부합한다.
     */
    static final BigDecimal IMMEDIATE_CUT_RATE = new BigDecimal("0.989");
    /**
     * 갭방어 강제손절 최소 보유시간(초) — 9/14 추가.
     * 저유동성 코인은 매수 체결 자체가 평균매수가를 호가창 위쪽으로 밀어올려(진입 슬리피지)
     * 매수 직후 원가 대비 이미 마이너스로 찍히는 경우가 있음(실측: 9/9~9/13 손실률 상위 10건 중
     * 8건이 갭방어 강제손절, 그중 THETA는 매수→강제손절 0.2초, MIRA는 1.3초 만에 발동 — 투자금이
     * 주문액보다 4~6.5% 높게 체결된 상태에서 즉시 재매도까지 겹쳐 -1.2% 라벨과 무관하게 실제
     * -1.9%~-6.5% 손실이 실현됨). 매수 직후 이 유예시간 동안은 갭방어 체크를 건너뛰어 진입
     * 슬리피지가 가라앉을 시간을 준다 — 하드스탑 조건 자체(도달 시 즉시 청산)는 그대로 유지하며,
     * 유예 종료 후에는 다음 패스트 루프 틱(최대 30초 후)에 정상 발동한다.
     */
    private static final int HARD_STOP_GRACE_SECONDS = 90;
    /**
     * 점수 익절 기준: +0.6% (9/9: 국면 차등 폐지, 3구간 근사평균으로 통일 — 클래스 상단 설명 참고)
     */
    static final BigDecimal PROFIT_THRESHOLD = new BigDecimal("1.006");
    /**
     * 이익구간 상태머신 진입 기준: +0.3% (9/17 신규). 이 비율 이상 수익부터 classifyIndicatorOutlook
     * 판정을 시작한다 — 강한하락이면 조건 없이 즉시익절, 강한상승이면 현행유지, 애매하면 1차관망
     * 후 재판정(관망 이후에도 강한상승이 아니면 바로 익절 — 손실구간과 달리 유예는 1회로 제한).
     * 기존 4개 익절 경로(RSI과매수즉시익절/트레일링익절/RSI모멘텀소진익절/점수익절)는 그대로 두고,
     * 이 상태머신은 그 경로들이 그 틱에 아직 발동하지 않았을 때만 추가로 작동하는 보조 경로다.
     */
    private static final BigDecimal PROFIT_WATCH_START_RATE = new BigDecimal("1.003");

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
    /**
     * 트레일링 낙폭(확장판): 고점 대비 -0.8% — 9/14 신규.
     * RSI 모멘텀이 아직 살아있다고 판단되면(과매수 아니고 고점 대비 크게 안 꺾였으면) 이 넓은
     * 낙폭을 적용해 더 오래 들고 간다. 9/9에 국면(phase) 차등을 폐지한 이유는 phase가 수익률
     * 예측력이 없거나 역전됐기 때문(로그 실측, n=3,568) — 이번엔 국면이 아니라 이 프로젝트에서
     * 유일하게 방향 예측력이 검증된 RSI로 "상승 여력"을 판단한다(trailingDropRate 참고). 안전장치
     * (고점 대비 하락 시 매도)는 그대로 유지되므로, 판단이 틀려도 손실은 이 낙폭만큼으로 제한된다.
     */
    private static final BigDecimal TRAILING_DROP_WIDE = new BigDecimal("0.008");

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

    // ─── 손실구간 추가매수(조건부 DCA) 설정 (9/9 도입, 9/17 재설계) ─────
    // 9/17: 손실구간 상태머신(classifyIndicatorOutlook) 도입에 맞춰 재설계. 기존엔 0%~-0.9%
    // "관망구간"에서만, RSI 저점 대비 반등(+3)만으로 발동했으나 — 이제는 (1) 0%~즉시손절기준
    // (IMMEDIATE_CUT_RATE, -1.1%) 전 구간에서 (2) classifyIndicatorOutlook이 "강한상승"으로 판정하고
    // (RSI 반등 조건에 더해 BB 하단권 위치까지 함께 확인, 상단권이면 강한상승 판정 자체가 안 됨)
    // (3) 해당 포지션이 이미 최소 한 번 "관망"을 거친 경우에만 발동 — 조건이 구조적으로 더 엄격해졌다.
    /**
     * 추가매수 강한상승 판정용 — 포지션 보유 중 RSI 최저점 대비 이만큼 반등 (+3.0).
     * classifyIndicatorOutlook의 강한상승 신호 중 하나로 사용 (BB 하단권 위치와 OR 조건).
     */
    private static final BigDecimal ADD_BUY_RSI_REBOUND_MIN = new BigDecimal("3.0");
    /**
     * 추가매수 최대 횟수 (포지션당) — 손실 확대 위험을 제한
     *
     * <p>9/13 응급 비활성화(0으로 설정): 9/9~9/13 실측 결과 RSI 저점대비 +3 반등 단독 기준이 지나치게
     * 헐거워 5일간 544회(포지션당 평균 1회 이상) 발동 — 최초매수(483건)보다 추가매수(544건)가 더
     * 많았음. 포지션 단위로는 추가매수가 있었던 사이클의 승률이 오히려 더 높았지만(54.7% vs 30.7%),
     * 발동 빈도 자체가 너무 커서 하루 투입 자본이 거의 2배로 늘었고(9/8 매수액 70.7만원 → 9/9
     * 146.3만원) 일일 순손실이 9/8 -4,424원 → 9/9 -10,415원으로 확대된 뒤 이후에도 -4,352~-6,309원
     * 대에 머물며 이전보다 계속 나쁨 — 개별 판단의 방향성보다 규모 확대가 문제였다고 판단해 임시
     * 비활성화한다.
     *
     * <p>9/17: 발동 조건 자체가 훨씬 엄격해졌다(BB 하단권 동시 확인 + 1차 관망 이력 필수) — 그러나
     * 이 값은 실거래 자본 투입 규모를 직접 좌우하므로 임의로 재활성화하지 않고 0으로 유지한다.
     * 재활성화하려면 몇 회까지 허용할지 명시적으로 정한 뒤 값을 바꿔야 한다.
     */
    private static final int ADD_BUY_MAX_COUNT = 0;
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

    /**
     * 갭방어 강제손절 유예시간(HARD_STOP_GRACE_SECONDS) 경과 여부.
     * positionEntryTimeMap에 진입시각이 없으면(맵 초기화 직후 등 예외 상황) 안전하게 "경과함"으로
     * 처리해 하드스탑이 무력화되지 않도록 한다 — 유예는 어디까지나 진입 직후 슬리피지 노이즈만
     * 걸러내기 위한 것이지, 하드스탑 자체를 약화시키기 위한 것이 아니다.
     */
    private boolean pastHardStopGrace(String coinNm) {
        LocalDateTime entryTime = stateStore.positionEntryTimeMap.get(coinNm);
        return entryTime == null
                || java.time.Duration.between(entryTime, LocalDateTime.now()).getSeconds() >= HARD_STOP_GRACE_SECONDS;
    }

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
        // 9/14: 매수 직후 HARD_STOP_GRACE_SECONDS(90초) 동안은 스킵 — 상단 상수 설명 참고.
        if (profitRate.compareTo(HARD_STOP_RATE) <= 0 && pastHardStopGrace(coinNm)) {
            log.warn("{} 갭방어 강제손절 (-1.2%) 평가:{} 투자:{} [단기:{} RSI:{}]",
                    coinNm, sellablePrice.setScale(0, RoundingMode.HALF_UP), totalCost,
                    signal.getShortPhase(), signal.getRsi().setScale(1, RoundingMode.HALF_UP));
            clearPositionState(coinNm);
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

        // 강제 손절: -1.2% (9/14: 동일 유예 적용, pastHardStopGrace 참고)
        if (profitRate.compareTo(HARD_STOP_RATE) <= 0 && pastHardStopGrace(coinNm)) {
            log.warn("{} [정지중] 강제손절 (-1.2%) 평가:{} 투자:{} [단기:{} RSI:{}]",
                    coinNm, sellablePrice.setScale(0, RoundingMode.HALF_UP), totalCost,
                    signal.getShortPhase(), signal.getRsi().setScale(1, RoundingMode.HALF_UP));
            clearPositionState(coinNm);
            tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), "강제손절(정지중)");
            return;
        }

        // 트레일링 익절 (정지 상태에서도 기존 고점 추적 유지, 국면별 DROP 적용)
        if (profitRate.compareTo(TRAILING_ACTIVATE_RATE) >= 0) {
            MarketPhase shortPhase = signal.getShortPhase();
            MarketPhase longPhase = signal.getPhase();
            MarketPhase effectPhase = (shortPhase != MarketPhase.SIDEWAYS) ? shortPhase : longPhase;
            BigDecimal dropRate = trailingDropRate(coinNm, signal.getRsi());

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
                clearPositionState(coinNm);
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
            clearPositionState(coinNm);
            registerProfitCooldown(coinNm, realtimeSellablePrice, totalCost, POST_PROFIT_COOLDOWN_HOT);
            tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice(), "RSI과매수익절");
            return;
        }

        // ── 트레일링 익절 (9/7: 패스트 루프 → 슬로우 루프 이동, 3분마다 갱신) ──
        // +0.4% 진입 후 국면별 낙폭 초과 시 매도 — "적당히 오르면 판다"
        // BULL -0.5% / SIDEWAYS -0.45% / BEAR -0.35%
        if (realtimeSellablePrice.compareTo(totalCost.multiply(TRAILING_ACTIVATE_RATE)) >= 0) {
            BigDecimal trailDropRate = trailingDropRate(coinNm, signal.getRsi());

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
                clearPositionState(coinNm);
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
            clearPositionState(coinNm);
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
            clearPositionState(coinNm);
            tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), "RSI모멘텀손절");
            return;
        }

        // ══════════════════════════════════════════════════════════════
        //  손실구간 상태머신 (9/17 신규, 동일자 재설계) — 0%(totalCost) ~ 즉시손절기준
        //  (IMMEDIATE_CUT_RATE, -1.1%) 전 구간에서 매 틱마다 판단한다. 라운드(N차관망)가 깊어질수록
        //  조건이 엄격해지는 구조다 — 1~2차는 "다음 지표 전망"만으로 판단하고, 3차부터는 "직전
        //  관망 시점 대비 가격이 올랐는가"까지 함께 요구해 관망이 무한정 이어지지 않도록 자연
        //  수렴시킨다(별도의 관망 횟수 상한을 두지 않기로 한 결정과 양립).
        //
        //  손실 비율(라운드 진입 여부·즉시손절 여부 판단)은 항상 totalCost(= account.getAvgBuyPrice()
        //  × balance, Upbit 계좌 API가 추가매수를 반영해 자동 갱신하는 실제 평균매수가) 기준으로
        //  매 틱 새로 계산한다. 반면 "직전 대비 상승/하락"(3라운드 이상에서만 사용)은 현재 라운드가
        //  시작된 시점의 평가금액(lossWatchRefPriceMap)을 기준으로 한다 — 손실 폭 자체를 보는 기준과
        //  라운드 내 방향성을 보는 기준이 서로 다른 목적이라는 점을 사용자가 명시적으로 구분했다.
        //
        //  0라운드(최초 진입, 아직 관망한 적 없음): 강한하락이면 즉시손절, 그 외(애매함/강한상승)면
        //  1차관망으로 진입 — 최초 판정에서는 강한상승이어도 바로 추가매수하지 않는다.
        //  1~2라운드: 강한상승→추가매수, 애매함→다음 라운드, 강한하락→손절. 방향(직전 대비 상승/
        //  하락)은 이 구간에서는 결과에 영향을 주지 않고 로그에만 남긴다 — 사용자가 6가지 조합을
        //  직접 나열해 확인한 결과 방향과 무관하게 전망만으로 결론이 동일했다.
        //  3라운드 이상: 직전 라운드 대비 가격이 올랐을 때만 추가매수/관망 연장을 허용하고, 그 외
        //  (가격 하락, 또는 가격은 올랐지만 강한하락 전망)는 전부 손절한다.
        //
        //  loss > IMMEDIATE_CUT_RATE(-1.1%)는 라운드·전망과 무관하게 항상 즉시손절(무조건
        //  backstop)이며, 그마저 못 잡으면 최종적으로 하드스탑(HARD_STOP_RATE, -1.2%,
        //  HARD_STOP_GRACE_SECONDS 유예 적용)이 잡는다.
        // ══════════════════════════════════════════════════════════════
        boolean inLossZone = realtimeSellablePrice.compareTo(totalCost) < 0;
        boolean inProfitWatchZone = !inLossZone
                && realtimeSellablePrice.compareTo(totalCost.multiply(PROFIT_WATCH_START_RATE)) >= 0;

        if (!inLossZone) {
            // 손실구간을 벗어나면(회복) 라운드 상태 초기화 — 다음에 다시 손실구간에 들어오면 0라운드부터 재시작
            stateStore.lossWatchRoundMap.remove(coinNm);
            stateStore.lossWatchRefPriceMap.remove(coinNm);
        }
        if (!inProfitWatchZone) {
            // 이익구간(+0.3% 이상)을 벗어나면 관망 이력 초기화 — 다음에 다시 진입하면 0라운드부터 재시작
            stateStore.profitWatchRoundMap.remove(coinNm);
        }

        if (inLossZone) {
            IndicatorOutlook outlook = classifyIndicatorOutlook(signal, indicatorPrice, currentRsi, rsiPeak, rsiTrough);
            BigDecimal lossPct = realtimeSellablePrice.divide(totalCost, 10, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
            int round = stateStore.lossWatchRoundMap.getOrDefault(coinNm, 0);

            // ── 무조건 backstop: 라운드·전망과 무관하게 -1.1% 초과 손실이면 즉시손절 ──
            boolean pastImmediateCut = realtimeSellablePrice.compareTo(totalCost.multiply(IMMEDIATE_CUT_RATE)) <= 0;
            if (pastImmediateCut) {
                log.warn("{} 즉시손절({}차) 손실:{}% 전망:{} [BB중간:{} RSI:{}(고점{}/저점{})]",
                        coinNm, round, lossPct, outlook,
                        signal.getBb().get("middle").setScale(0, RoundingMode.HALF_UP),
                        currentRsi.setScale(1, RoundingMode.HALF_UP),
                        rsiPeak.setScale(1, RoundingMode.HALF_UP), rsiTrough.setScale(1, RoundingMode.HALF_UP));
                clearPositionState(coinNm);
                tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), "즉시손절");
                return;
            }

            int dcaCount = stateStore.dcaCountMap.getOrDefault(coinNm, 0);
            LocalDateTime lastDcaAt = stateStore.lastDcaAtMap.get(coinNm);
            boolean dcaCooldownPassed = lastDcaAt == null
                    || java.time.Duration.between(lastDcaAt, LocalDateTime.now()).toMinutes() >= ADD_BUY_MIN_INTERVAL_MINUTES;
            boolean addBuyEligible = dcaCount < ADD_BUY_MAX_COUNT && dcaCooldownPassed;

            if (round == 0) {
                // 0라운드: 최초 판정 — 강한하락만 즉시손절, 그 외는 1차관망으로 진입
                if (outlook == IndicatorOutlook.STRONG_DOWN) {
                    log.warn("{} 즉시손절(최초판정) 손실:{}% 전망:강한하락", coinNm, lossPct);
                    clearPositionState(coinNm);
                    tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), "즉시손절");
                    return;
                }
                stateStore.lossWatchRoundMap.put(coinNm, 1);
                stateStore.lossWatchRefPriceMap.put(coinNm, realtimeSellablePrice);
                log.info("{} 손실구간 1차관망 진입 손실:{}% 전망:{}", coinNm, lossPct, outlook);
                return;
            }

            BigDecimal refPrice = stateStore.lossWatchRefPriceMap.getOrDefault(coinNm, realtimeSellablePrice);
            String direction = realtimeSellablePrice.compareTo(refPrice) > 0 ? "상승"
                    : realtimeSellablePrice.compareTo(refPrice) < 0 ? "하락" : "동일";

            if (round <= 2) {
                // 1~2라운드: 전망만으로 판단 — 방향(상승/하락)은 로그용으로만 기록, 결과에 영향 없음
                if (outlook == IndicatorOutlook.STRONG_UP && addBuyEligible) {
                    executeLossZoneAddBuy(coinNm, signal, round, lossPct, currentRsi, rsiTrough, dcaCount);
                    return;
                } else if (outlook == IndicatorOutlook.STRONG_DOWN) {
                    log.warn("{} 손절({}차관망) 손실:{}% 직전대비:{} 전망:강한하락", coinNm, round, lossPct, direction);
                    clearPositionState(coinNm);
                    tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), "손절");
                    return;
                } else {
                    // 애매함, 혹은 강한상승인데 추가매수 안전장치(횟수/간격) 미충족 → 다음 라운드로
                    int nextRound = round + 1;
                    stateStore.lossWatchRoundMap.put(coinNm, nextRound);
                    stateStore.lossWatchRefPriceMap.put(coinNm, realtimeSellablePrice);
                    log.info("{} 손실구간 {}차관망 손실:{}% 직전대비:{} 전망:{}{}",
                            coinNm, nextRound, lossPct, direction, outlook,
                            outlook == IndicatorOutlook.STRONG_UP ? " (추가매수 안전장치 미충족으로 관망 유지)" : "");
                }
            } else {
                // 3라운드 이상: 직전 라운드 대비 상승했을 때만 추가매수/관망 연장 허용, 그 외는 전부 손절
                boolean priceUp = "상승".equals(direction);
                if (priceUp && outlook == IndicatorOutlook.STRONG_UP && addBuyEligible) {
                    executeLossZoneAddBuy(coinNm, signal, round, lossPct, currentRsi, rsiTrough, dcaCount);
                    return;
                } else if (priceUp && (outlook == IndicatorOutlook.AMBIGUOUS || outlook == IndicatorOutlook.STRONG_UP)) {
                    // 강한상승이지만 추가매수 안전장치 미충족인 경우도 애매함과 동일하게 관망 연장
                    int nextRound = round + 1;
                    stateStore.lossWatchRoundMap.put(coinNm, nextRound);
                    stateStore.lossWatchRefPriceMap.put(coinNm, realtimeSellablePrice);
                    log.info("{} 손실구간 {}차관망 손실:{}% 직전대비:상승 전망:{}{}",
                            coinNm, nextRound, lossPct, outlook,
                            outlook == IndicatorOutlook.STRONG_UP ? " (추가매수 안전장치 미충족으로 관망 유지)" : "");
                } else {
                    log.warn("{} 손절({}차관망) 손실:{}% 직전대비:{} 전망:{} [3라운드 이상 — 상승하지 않으면 손절]",
                            coinNm, round, lossPct, direction, outlook);
                    clearPositionState(coinNm);
                    tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), "손절");
                    return;
                }
            }
        } else if (inProfitWatchZone) {
            // ══════════════════════════════════════════════════════════════
            //  이익구간 상태머신 (9/17 신규) — +0.3%(PROFIT_WATCH_START_RATE) 이상 수익일 때
            //  classifyIndicatorOutlook으로 다음 지표를 판단한다. 강한상승이면 현행유지(그대로
            //  보유), 강한하락이면 조건 없이 즉시익절, 애매하면 1차 관망 후 재판정한다 — 손실구간과
            //  달리 유예는 1회로 제한(1차관망 이후에도 강한상승이 아니면 바로 익절).
            //
            //  기존 4개 익절 경로(RSI과매수즉시익절/트레일링익절/RSI모멘텀소진익절/점수익절)는
            //  전부 그대로 유지하며, 이 상태머신은 그 경로들이 이번 틱에 아직 발동하지 않았을 때만
            //  추가로 작동하는 보조 경로다 — 기존 검증된 로직을 걷어내지 않고 그 위에 얹었다.
            // ══════════════════════════════════════════════════════════════
            IndicatorOutlook outlook = classifyIndicatorOutlook(signal, indicatorPrice, currentRsi, rsiPeak, rsiTrough);
            int profitRound = stateStore.profitWatchRoundMap.getOrDefault(coinNm, 0);
            BigDecimal profitPct = realtimeSellablePrice.divide(totalCost, 10, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);

            if (outlook == IndicatorOutlook.STRONG_DOWN) {
                log.info("{} 익절({}차) 수익:+{}% 전망:강한하락", coinNm, profitRound, profitPct);
                clearPositionState(coinNm);
                registerProfitCooldown(coinNm, realtimeSellablePrice, totalCost, POST_PROFIT_COOLDOWN_MINUTES);
                tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice(), "익절");
                return;
            } else if (outlook == IndicatorOutlook.STRONG_UP) {
                // 현행유지 — 관망 이력을 리셋해 다음번 애매함에도 새로 1회 유예를 준다
                stateStore.profitWatchRoundMap.remove(coinNm);
                log.info("{} 이익구간 현행유지 수익:+{}% 전망:강한상승", coinNm, profitPct);
            } else if (profitRound >= 1) {
                // 1차 관망 이후에도 강한상승이 아니면(=여전히 애매함) 바로 익절
                log.info("{} 익절({}차관망 이후) 수익:+{}% 전망:애매함(강한상승 아님)", coinNm, profitRound, profitPct);
                clearPositionState(coinNm);
                registerProfitCooldown(coinNm, realtimeSellablePrice, totalCost, POST_PROFIT_COOLDOWN_MINUTES);
                tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice(), "익절");
                return;
            } else {
                stateStore.profitWatchRoundMap.put(coinNm, 1);
                log.info("{} 이익구간 1차관망 진입 수익:+{}% 전망:애매함", coinNm, profitPct);
            }
        }

        // 익절 기준: 국면 무관 단일 +0.6% (9/9: 국면 차등 폐지)
        BigDecimal profitThreshold = PROFIT_THRESHOLD;

        boolean isProfitRange = realtimeSellablePrice.compareTo(totalCost.multiply(profitThreshold)) >= 0;

        int profitSellScore = profitSellScore(signal, indicatorPrice, !isGoldenCross, isProfitRange);
        String profitBreakdown = profitScoreBreakdown(signal, indicatorPrice, !isGoldenCross, isProfitRange);

        BigDecimal thresholdPct = profitThreshold.subtract(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
        BigDecimal profitRatePct = realtimeSellablePrice.divide(totalCost, 10, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        log.info("{} 점수평가 익절:{} [단기:{} 장기:{} RSI:{} 실시간가:{} 수익률:{}% 익절임계:+{}%]",
                coinNm, profitSellScore, shortPhase, longPhase,
                signal.getRsi().setScale(1, RoundingMode.HALF_UP),
                realtimePrice.setScale(2, RoundingMode.HALF_UP),
                profitRatePct, thresholdPct);

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
                clearPositionState(coinNm);
                registerProfitCooldown(coinNm, realtimeSellablePrice, totalCost, POST_PROFIT_COOLDOWN_MINUTES);
                tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice(), "점수익절");
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  점수 계산
    // ══════════════════════════════════════════════════════════════════

    /**
     * 포지션 종료(매도) 시 코인별 추적 상태를 일괄 초기화한다 (9/17: 반복되던 7줄 블록을
     * 메서드로 통합 — 새 추적 맵 추가 시 누락 없이 한 곳만 수정하면 됨).
     */
    private void clearPositionState(String coinNm) {
        stateStore.trailingPeakMap.remove(coinNm);
        stateStore.positionEntryTimeMap.remove(coinNm);
        stateStore.rsiPeakMap.remove(coinNm);
        stateStore.rsiTroughMap.remove(coinNm);
        stateStore.dcaCountMap.remove(coinNm);
        stateStore.lastDcaAtMap.remove(coinNm);
        stateStore.lossWatchRoundMap.remove(coinNm);
        stateStore.lossWatchRefPriceMap.remove(coinNm);
        stateStore.profitWatchRoundMap.remove(coinNm);
    }

    /**
     * 손실구간 상태머신의 추가매수 실행 — 평단을 낮춘 뒤 손실구간 라운드 상태만 초기화한다
     * (트레일링/RSI피크 등 다른 추적 상태는 유지 — 포지션 자체는 계속 보유 중이므로).
     * 다음 틱부터는 낮아진 평단(totalCost) 기준으로 0라운드부터 다시 평가된다.
     */
    private void executeLossZoneAddBuy(String coinNm, CoinSignalDto signal, int round, BigDecimal lossPct,
                                       BigDecimal currentRsi, BigDecimal rsiTrough, int dcaCount) {
        log.info("{} 손실구간 추가매수({}/{}, {}차관망) 손실:{}% 전망:강한상승 RSI저점{}→현재{}",
                coinNm, dcaCount + 1, ADD_BUY_MAX_COUNT, round, lossPct,
                rsiTrough.setScale(1, RoundingMode.HALF_UP), currentRsi.setScale(1, RoundingMode.HALF_UP));
        OrdersResponse addBuyResponse = exchangeClient.orderCoin(coinNm, "bid", ADD_BUY_AMOUNT);
        tradeHistoryRepository.save(TradeHistoryDto.buyHistory(coinNm, ADD_BUY_AMOUNT, signal)
                .toBuilder().tradeType("추가매수").build());
        stateStore.dcaCountMap.put(coinNm, dcaCount + 1);
        stateStore.lastDcaAtMap.put(coinNm, LocalDateTime.now());
        stateStore.rsiTroughMap.put(coinNm, currentRsi); // 추가매수 이후 새 저점 기준 재설정
        stateStore.lossWatchRoundMap.remove(coinNm);
        stateStore.lossWatchRefPriceMap.remove(coinNm);
        exchangeClient.askSuccessMessage(addBuyResponse);
    }

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
     * 트레일링 낙폭 결정 (9/14, 국면 대신 RSI 기반으로 "상승 여력" 판단 — 상단 TRAILING_DROP_WIDE
     * 설명 참고). RSI가 아직 과매수(70) 미만이고 고점 대비 BULL_EXHAUST_RSI_DROP(7) 이상 꺾이지
     * 않았으면 "모멘텀 지속 가능성 높음"으로 보고 낙폭을 넓혀(TRAILING_DROP_WIDE) 더 버틴다.
     * 과매수이거나 고점 대비 크게 꺾였으면 "하락/횡보 가능성"으로 보고 기존 낙폭(TRAILING_DROP)
     * 그대로 유지한다. rsiPeakMap에 값이 없으면(추적 시작 직후 등) 현재 RSI를 그대로 고점으로 간주
     * — 아직 꺾인 적이 없으므로 안전하게 "모멘텀 지속"쪽으로 처리된다.
     */
    private BigDecimal trailingDropRate(String coinNm, BigDecimal currentRsi) {
        BigDecimal rsiPeak = stateStore.rsiPeakMap.getOrDefault(coinNm, currentRsi);
        boolean notOverbought = currentRsi.compareTo(RSI_OVERBOUGHT) < 0;
        boolean noPeakPullback = rsiPeak.subtract(currentRsi).compareTo(BULL_EXHAUST_RSI_DROP) < 0;
        return (notOverbought && noPeakPullback) ? TRAILING_DROP_WIDE : TRAILING_DROP;
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
     * "다음 지표" 전망 분류 결과 (9/17 신규) — 손실구간·이익구간 상태머신이 공통으로 사용한다.
     * STRONG_DOWN: 하락 가능성 높음 → 손실구간에서는 즉시손절기준 이하일 때 즉시손절,
     *              이익구간에서는 조건 없이 즉시익절
     * AMBIGUOUS  : 방향 불분명 → 관망(양쪽 상태머신 모두 유예 라운드로 진입)
     * STRONG_UP  : 상승 가능성 높음 → 손실구간에서는 즉시손절 보류(조건 충족 시 추가매수 대상),
     *              이익구간에서는 현행유지(보유 지속)
     */
    private enum IndicatorOutlook { STRONG_DOWN, AMBIGUOUS, STRONG_UP }

    /**
     * 다음-틱 전망 분류 — RSI + BB만 사용한다. phase(BULL/BEAR)는 9/9 검증에서 예측력이
     * 없거나 역전됨이 확인되어 제외했고, 데드크로스는 9/16 BB존 백테스트에서 15/30분 기준
     * 유의성이 없어(p=0.48/0.81) 이 판단에는 포함하지 않는다(60분에서만 약하게 유의(p=0.01)했으나
     * 이 상태머신들은 3분 주기 즉시 판단이 목적이라 부적합). 손실구간뿐 아니라 이익구간
     * 상태머신(9/17 추가)도 동일한 분류 결과를 재사용한다 — 방향성 판단 신호 자체는
     * 손실/이익 여부와 무관하게 동일하게 유효하다는 전제.
     *
     * <p>강한상승(반등 가능성 높음) 신호 — 9/14-15 BB존 백테스트(8,037건) 근거:
     * · BB 중간선 미만(하단권, 하단이탈/하단~중간) — 이 구간이 60분 후 하락확률이 가장 낮고
     *   (41.8%, 상단초과 66.1% 대비) 15분 후 평균수익률이 플러스로 전환(전 구간 p&lt;0.0001)
     * · RSI가 포지션 보유 중 최저점 대비 ADD_BUY_RSI_REBOUND_MIN(+3) 이상 반등 — 이미 저점
     *   이탈이 시작된 신호 (기존 3주 RSI 백테스트, RSI 30-40 구간 72.1% 상승반전과 같은 결)
     *
     * <p>강한하락(추가하락 가능성 높음) 신호:
     * · BB 중간선 이상(상단권, 상단초과/중간~상단) — 위 백테스트에서 하락 지속확률이 상대적으로 높은 구간
     * · RSI가 고점 대비 BULL_EXHAUST_RSI_DROP(7) 이상 이미 꺾였고, 아직 저점 반등 신호는 없음
     *
     * <p>두 신호가 동시에 성립하거나(예: 상단권인데 RSI는 저점 대비 반등 중) 둘 다 성립하지
     * 않으면 AMBIGUOUS로 분류해 관망한다 — 확신 없는 상황에서 섣불리 손절/추가매수하지 않는다.
     */
    private IndicatorOutlook classifyIndicatorOutlook(CoinSignalDto signal, BigDecimal price,
                                                     BigDecimal currentRsi, BigDecimal rsiPeak, BigDecimal rsiTrough) {
        boolean bbLowerHalf = price.compareTo(signal.getBb().get("middle")) < 0;
        boolean bbUpperHalf = !bbLowerHalf;
        boolean rsiReboundingFromTrough = currentRsi.subtract(rsiTrough).compareTo(ADD_BUY_RSI_REBOUND_MIN) >= 0;
        boolean rsiFallingNoRebound = rsiPeak.subtract(currentRsi).compareTo(BULL_EXHAUST_RSI_DROP) >= 0
                && !rsiReboundingFromTrough;

        boolean bullishSignal = bbLowerHalf || rsiReboundingFromTrough;
        boolean bearishSignal = bbUpperHalf && rsiFallingNoRebound;

        if (bullishSignal && !bearishSignal) return IndicatorOutlook.STRONG_UP;
        if (bearishSignal && !bullishSignal) return IndicatorOutlook.STRONG_DOWN;
        return IndicatorOutlook.AMBIGUOUS;
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
