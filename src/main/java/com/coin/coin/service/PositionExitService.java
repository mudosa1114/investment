package com.coin.coin.service;

import com.coin.coin.common.MarketPhase;
import com.coin.coin.dto.CoinAccount;
import com.coin.coin.dto.CoinSignalDto;
import com.coin.coin.dto.TradeHistoryDto;
import com.coin.coin.dto.response.OrdersResponse;
import com.coin.coin.entity.MomentumStopShadow;
import com.coin.coin.repository.MomentumStopShadowRepository;
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
    private final MomentumStopShadowRepository momentumStopShadowRepository;

    // ─── 손익 임계값 상수 ──────────────────────────────────────────────
    /**
     * 강제 손절: 지표와 무관하게 이 비율 이하이면 패스트 루프에서 즉시 매도 (-1.2%)
     */
    static final BigDecimal HARD_STOP_RATE = new BigDecimal("0.988");
    /**
     * 즉시손절(스마트 조기손절) 기준: 손실구간 상태머신에서 매입조건(9/18~, buyCondition =
     * longPhase==SIDEWAYS)이 충족되지 않으면 이 비율 이하에서 즉시 매도한다 (-1.1%, 9/17 신규,
     * 9/18 판정 기준을 classifyIndicatorOutlook에서 매입조건으로 교체).
     *
     * <p>-1.0/-1.1/-1.2% 중 -1.1%로 결정한 근거:
     * (1) 하드스탑(HARD_STOP_RATE, -1.2%)과 최소 0.1%p 간격을 둬야 진입 슬리피지·단일 틱 급락
     * 상황(실측: THETA 매수→강제손절 0.2초, MIRA 1.3초)에서도 지표 기반 즉시손절이 하드스탑보다
     * 먼저 발동할 여지가 생긴다 — -1.2%와 동일하면 사실상 하드스탑과 중복이라 이 로직 자체가
     * 무의미해진다.
     * (2) 9/14-9/15 BB존 백테스트(지표스냅샷 8,037건)에서 손실구간(-0.9%~-1.2%)이 오히려
     * 반등확률이 가장 높은 구간으로 확인됨(BB하단이탈 15분후 평균수익률 플러스 전환, 전 구간
     * p&lt;0.0001) — 매입조건(장기phase=SIDEWAYS)이 유리한 케이스를 걸러주긴 하지만, 가격 임계값 자체도
     * -1.0%처럼 너무 타이트하게 잡지 않는 편이 반등 여지가 있는 포지션을 조기에 자르는 위험을 줄인다.
     * (3) 관망 횟수 제한 대신 가격 기준을 선택한 취지(user: "보수적으로 잡는게 나아보이는데")에도
     * 너무 이르게 자르지 않는 -1.1%가 더 부합한다.
     */
    static final BigDecimal IMMEDIATE_CUT_RATE = new BigDecimal("0.989");
    /**
     * 라운드 소진 강제손절 최소 손실폭: -0.5% (9/21 추가).
     *
     * <p>9/18판은 손실구간 관망이 3라운드(0→1→2→3)에 도달하면 손실폭과 무관하게 무조건
     * 손절했다. 그런데 9/18~9/20 실거래를 이 규칙으로 돌려보니, 이 사유("손절")로 나간 115건 중
     * 대다수(9/19 발생분 46/49=93.9%, 9/20 발생분 56/56=100%, 9/21 발생분 32/32=100%, 매도 후
     * 24시간 사후추적 기준 ExitReviewService 자체 집계)가 매도 이후 회복(또는 익절임계까지 회복)
     * 했다 — 특히 손실폭 분포를 보면 절반 이상(53%)이 -0.2% 이내의 사실상 노이즈 수준이었는데도
     * "3라운드 지났다"는 이유만으로 잘려나가고 있었다. 매입조건(장기phase=SIDEWAYS)이 여전히
     * 유지되고 있다면 라운드 수 자체보다 실제 손실폭이 의미 있게 벌어졌는지를 우선 봐야 한다고
     * 판단해, 라운드 소진 시점에도 이 값(-0.5%) 이상 손실이 아니면 손절하지 않고 라운드를
     * 계속 연장한다 — 최종 상한은 여전히 IMMEDIATE_CUT_RATE(-1.1%) 백스탑이 잡아준다. -0.5%는
     * 위 115건의 분포(중앙값 -0.16%, p75 -0.34%, -0.5% 이상은 15%)에서 "노이즈성 손실은 봐주고
     * 진짜 벌어지는 손실만 자른다"는 취지에 맞춰 상위 15% 지점 근처로 잡았다 — 표본이 아직
     * 3~4일치라 계속 지켜보며 조정이 필요할 수 있다.
     */
    private static final BigDecimal LOSS_ROUND_EXHAUST_MIN_LOSS_PCT = new BigDecimal("-0.5");
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
     * 이익구간 상태머신 진입 기준: +0.3% (9/17 신규). 이 비율 이상 수익부터 매입조건(9/18~,
     * buyCondition = longPhase==SIDEWAYS) 판정을 시작한다 — 매입조건 충족이면 관망(보유 지속),
     * 미충족이면 즉시 익절. 기존 4개 익절 경로(RSI과매수즉시익절/트레일링익절/RSI모멘텀소진익절/
     * 점수익절)는 그대로 두고, 이 상태머신은 그 경로들이 그 틱에 아직 발동하지 않았을 때만 추가로
     * 작동하는 보조 경로다.
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
     * RSI 모멘텀손절 동적 손실허용폭의 바닥값(최소 -0.5%) — 9/23 ATR 연동 이전의 고정 임계값과
     * 동일. ATR%가 낮은(=변동성 적은) 코인은 이 바닥값이 그대로 적용된다.
     */
    private static final BigDecimal BULL_RSI_STOP_LOSS_FLOOR_PCT = new BigDecimal("0.5");
    /**
     * RSI 모멘텀손절 동적 손실허용폭의 상한(최대 -1.0%) — 즉시손절(IMMEDIATE_CUT_RATE, -1.1%)
     * 백스탑과 최소 0.1%p 간격을 둔다(하드스탑/즉시손절 간 설계 원칙과 동일, 위 IMMEDIATE_CUT_RATE
     * 설명 참고). ATR%가 아무리 커도 이 상한을 넘어서까지 손절을 미루지 않는다.
     */
    private static final BigDecimal BULL_RSI_STOP_LOSS_CAP_PCT = new BigDecimal("1.0");
    /**
     * RSI 모멘텀손절 ATR 반영 배수(9/23 도입).
     *
     * <p>9/9~9/21 사후검증(93.2% 24h내 회복)으로 한 차례(9/23) 실거래에서 섀도우 기록 전용으로
     * 전환했으나, 같은 기간 다른 손절 메커니즘(손절/즉시손절/강제손절)도 비슷하거나 더 높은
     * 조기손절의심 비율을 보여 — ExitReviewService의 손절계열 판정 자체가 구조적으로
     * "조기손절의심" 아니면 "손절정당"만 가능해(더 일찍 잘랐어야 한다는 판정 자체가 없음) 비율만으론
     * 메커니즘 간 우열을 가릴 수 없다는 결론에 도달했다. 대신 코인별 ATR%와 평균 회복폭 사이에
     * 상관관계(피어슨 0.602)가 확인되어 "변동성이 큰 코인일수록 고정폭이 지나치게 타이트해
     * 노이즈에 조기손절된다"는 가설이 뒷받침됨(예: ETH ATR%≈0.15→평균회복 3.00%, META2
     * ATR%≈1.09→평균회복 46.39%). 이에 고정 -0.5% 대신 dynamicLossFloorPct =
     * min(CAP, max(FLOOR, ATR_MULTIPLIER × ATR%)) 로 코인별 동적 손실허용폭을 적용해 실거래를
     * 재개한다 — RSI 조건(7pt 하락/RSI&lt;50/3pt 이상 모멘텀/6분 보유)은 그대로 유지, 손실폭
     * 임계값만 바꾼다. 1.5 배수는 ATR%가 FLOOR/1.5(≈0.33%)를 넘는 코인부터 완화가 시작되도록
     * 잡은 값 — ETH/XRP처럼 ATR% 0.15~0.25대인 유동성 높은 코인은 그대로 바닥값(-0.5%)이
     * 유지되고, VVV/UP2/META2처럼 ATR% 0.5% 이상인 변동성 큰 코인부터 체감 가능한 완화가
     * 적용된다.
     */
    private static final BigDecimal BULL_RSI_STOP_ATR_MULTIPLIER = new BigDecimal("1.5");
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
     *
     * <p>9/18: 3회로 재활성화. 9/18 오전 실거래 로그 분석 결과, 추가매수가 0으로 막혀있는 동안
     * 손실구간 관망 로직이 "STRONG_UP이어도 아무 조치 없이 관망만 반복 → 결국 손절"로 끝나는
     * 패턴이 반복 확인됨(관망을 여러 라운드 거쳐도 손실이 줄지 않고 오히려 소폭 악화된 채 정리됨) —
     * 즉 관망 메커니즘의 설계 의도(강한 신호 확인 시 물타기로 평단 개선)가 추가매수 없이는 실현되지
     * 않고 있었음. 9/17 도입된 엄격한 발동 조건(BB 하단권+RSI반등 동시 확인, 1차 관망 이력 필수)을
     * 신뢰하고 3회까지 허용한다.
     *
     * <p>9/30: 다시 0으로 비활성화. 9/25~27·29 4일간 추가매수 538건의 추가분 자체 손익이 매일
     * 마이너스(합계 약 -5,500원, 하루 손실의 약 30%, 이익 42%/건당 +16원 vs 손실 58%/건당 -29원).
     * 원인: 손실구간이 "평가 &lt; 원가"(사실상 0%)부터라 발동 손실률 중앙값이 -0.20%(호가 흔들림 수준)
     * 였고, 매입조건(장기 SIDEWAYS)은 최초매수 조건과 같아 0라운드에서 99.5%가 통과 — 반등 신호
     * 역할을 못 했다. 지표 여러 개 AND 조건은 발동 자체가 거의 없어(-0.7% 기준 146건 중 0건)
     * 대신 VIRTUAL_ADD_BUY_TRIGGER_PCT 시점을 로그로만 남겨 1~2주 검증 후 재설계한다.
     */
    private static final int ADD_BUY_MAX_COUNT = 0;
    /**
     * 가상 추가매수 기록 기준(%) — 9/30 추가. 포지션 손실률이 처음 이 값 이하가 되는 슬로우 루프
     * 틱에 "[가상추가매수]" 로그를 1회 남긴다(실제 매수 없음). 같은 사이클의 지표스냅샷 로그
     * (오더북매수비율/거래량배율/MACD/데드크로스/RSI15m 등)와 시각으로 이어 붙여, 이후 가격
     * 흐름으로 "어떤 지표 조건에서 추가매수했어야 했는지"를 검증하기 위한 순수 관측용.
     */
    private static final BigDecimal VIRTUAL_ADD_BUY_TRIGGER_PCT = new BigDecimal("-0.7");

    // ─── 급락 손절 유예 (10/2 추가) ─────────────────────────────────────
    // 9/25~10/1 로그 분석: 소프트 손절(손절·RSI모멘텀손절)이 발동한 순간 직전 슬로우 루프 대비
    // 3분봉 RSI가 3pt 이상 급락한 경우(순간적 과매도/꼬리)는 이후 되돌림이 많았고(호가 큰 코인 제외
    // 99건: 120분 유예 + 백스탑 -1.3% 시 평균 -0.52% vs 즉시매도 -0.64%), 완만한 하락 중의 손절은
    // 유예하면 오히려 손실이 커졌다(76건: -0.77% vs -0.61%). 그래서 급락 직후의 소프트 손절만
    // 포지션당 1회, STOP_DEFER_MINUTES 동안 보류한다. 보류 중에는 백스탑을 STOP_DEFER_BACKSTOP_RATE
    // (-1.3%)로 두고(슬로우 루프 즉시손절과 패스트 루프 강제손절 모두), 그 외 포지션의 즉시손절(-1.1%)·
    // 강제손절(-1.2%)은 그대로다. 판단마다 "[손절판단]" 로그, 유예 시작/종료 시 "[손절유예]" 로그를 남긴다.
    /**
     * 손절 유예 사용 여부 — 10/6 비활성화. 10/2~10/5 실거래 43건 결과 유예 판단 시점 평균 -0.65% →
     * 최종 -0.83%(나아짐 15건 / 나빠짐 28건, 즉시매도 대비 약 -990원)로 백테스트(+0.12%p)와 반대로
     * 손해였다. false면 유예를 새로 시작하지 않고 "[손절판단]" 기록만 남긴다(유예 중 백스탑도 쓰이지 않음).
     */
    private static final boolean STOP_DEFER_ENABLED = false;
    /** 유예 발동 기준: 현재 3분봉 RSI - 직전 슬로우 루프 RSI 가 이 값 이하 */
    private static final BigDecimal STOP_DEFER_RSI_DROP = new BigDecimal("-3");
    /** 유예 시간(분) */
    private static final int STOP_DEFER_MINUTES = 120;
    /** 유예 중 백스탑: -1.3% */
    private static final BigDecimal STOP_DEFER_BACKSTOP_RATE = new BigDecimal("0.987");
    /**
     * 추가매수 최소 간격(분) — 같은 반등 신호로 연속 사이클마다 계속 추가하는 것 방지
     */
    private static final int ADD_BUY_MIN_INTERVAL_MINUTES = 6;
    /**
     * 추가매수 1회 금액(KRW) — Upbit 최소 주문금액인 5,000원으로 설정(9/18).
     *
     * <p>과거(9/4) "최초매수" 금액을 5,000원으로 뒀다가 -1.x% 하락만으로 평가금액이 최소금액 밑으로
     * 떨어져 매도 주문 자체가 거부되는 버그가 있었다 — 하지만 그건 5,000원짜리 포지션이 그 자체로
     * 전체 보유수량이었던 경우다. 추가매수는 이미 저확신 7,000원/고확신 13,000원으로 시작된 기존
     * 포지션 위에 얹히는 것이라(Upbit 잔고는 코인 단위로 합산되어 매도도 항상 전체 보유수량 기준으로
     * 나간다), 추가매수 후 총 평가금액은 최소 12,000원(7,000+5,000) 이상이라 -1.1% 즉시손절까지
     * 맞아도 5,000원 최소금액에 걸릴 일이 없다. 그래서 7,000원으로 굳이 여유를 둘 필요 없이
     * 최소금액 그대로 사용한다.
     */
    private static final String ADD_BUY_AMOUNT = "5000";

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
        boolean stopDeferred = isStopDeferred(coinNm);
        BigDecimal hardRate = stopDeferred ? STOP_DEFER_BACKSTOP_RATE : HARD_STOP_RATE;
        if (profitRate.compareTo(hardRate) <= 0 && pastHardStopGrace(coinNm)) {
            if (stopDeferred) {
                log.warn("{} 유예손절 백스탑 (-1.3%, 손절유예 중) 평가:{} 투자:{} [단기:{} RSI:{}]",
                        coinNm, sellablePrice.setScale(0, RoundingMode.HALF_UP), totalCost,
                        signal.getShortPhase(), signal.getRsi().setScale(1, RoundingMode.HALF_UP));
                clearPositionState(coinNm);
                tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), "유예손절");
            } else {
                log.warn("{} 갭방어 강제손절 (-1.2%) 평가:{} 투자:{} [단기:{} RSI:{}]",
                        coinNm, sellablePrice.setScale(0, RoundingMode.HALF_UP), totalCost,
                        signal.getShortPhase(), signal.getRsi().setScale(1, RoundingMode.HALF_UP));
                clearPositionState(coinNm);
                tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), "강제손절");
            }
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

        // 15분봉 RSI 고점 갱신 (9/23 추가) — 섀도우 기록 비교용, 매매 판단에는 사용하지 않음
        BigDecimal currentRsi15m = signal.getRsi15m();
        stateStore.rsi15mPeakMap.merge(coinNm, currentRsi15m, BigDecimal::max);
        BigDecimal rsi15mPeak = stateStore.rsi15mPeakMap.get(coinNm);

        // ── 가상 추가매수 기록 (9/30, 관측 전용 — 매매 판단에 영향 없음) ────────────────
        // 손실률이 처음 VIRTUAL_ADD_BUY_TRIGGER_PCT 이하가 된 순간 포지션당 1회만 기록한다.
        // 아래 RSI모멘텀손절/손실구간 로직보다 먼저 두어, 그 틱에 손절되더라도 기록은 남는다.
        {
            BigDecimal vLossPct = realtimeSellablePrice.divide(totalCost, 10, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100));
            if (vLossPct.compareTo(VIRTUAL_ADD_BUY_TRIGGER_PCT) <= 0
                    && stateStore.virtualAddBuyLoggedSet.add(coinNm)) {
                BigDecimal bbUpper = signal.getBb().get("upper");
                BigDecimal bbLower = signal.getBb().get("lower");
                BigDecimal bid = signal.getPrice().getBidPrice();
                String bbPos = (bbUpper != null && bbLower != null && bbUpper.compareTo(bbLower) > 0)
                        ? bid.subtract(bbLower).divide(bbUpper.subtract(bbLower), 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).toPlainString()
                        : "N/A";
                String vAtrPct = (signal.getAtr() != null && realtimePrice.compareTo(BigDecimal.ZERO) > 0)
                        ? signal.getAtr().divide(realtimePrice, 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).setScale(3, RoundingMode.HALF_UP).toPlainString()
                        : "N/A";
                LocalDateTime vEntry = stateStore.positionEntryTimeMap.get(coinNm);
                long vHeld = vEntry == null ? -1 : java.time.Duration.between(vEntry, LocalDateTime.now()).toMinutes();
                log.info("{} [가상추가매수] 손실 {}% 첫 도달(실매수 없음) 손실:{}% 매수호가:{} 3분RSI:{} 15분RSI:{} BB위치:{}% ATR%:{} 단기:{} 장기:{} 보유:{}분",
                        coinNm, VIRTUAL_ADD_BUY_TRIGGER_PCT, vLossPct.setScale(2, RoundingMode.HALF_UP),
                        bid.stripTrailingZeros().toPlainString(),
                        currentRsi.setScale(1, RoundingMode.HALF_UP),
                        currentRsi15m == null ? "N/A" : currentRsi15m.setScale(1, RoundingMode.HALF_UP).toPlainString(),
                        bbPos, vAtrPct, shortPhase, longPhase, vHeld);
            }
        }

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

        // ── RSI 모멘텀 손절 — 9/23 ATR 기반 동적 손실허용폭으로 재실거래화 ──────────
        // (9/23 한 차례 섀도우 전환 → 같은 날 재검토 후 ATR 연동으로 부활, 상단
        // BULL_RSI_STOP_ATR_MULTIPLIER 설명 참고). RSI 조건(7pt 하락/RSI<50/3pt 이상
        // 모멘텀/6분 보유)은 예전과 동일, 손실폭 임계값만 고정 -0.5%에서 코인별 ATR% 연동
        // 동적값으로 교체했다. momentum_stop_shadow 15분봉 비교 기록은 매도 실행과 별개로
        // 계속 남겨(사용자 확인, 9/23) 캔들 간격 비교 연구를 이어간다.
        BigDecimal atrPct = realtimePrice.compareTo(BigDecimal.ZERO) > 0
                ? signal.getAtr().divide(realtimePrice, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;
        BigDecimal dynamicLossFloorPct = BULL_RSI_STOP_LOSS_FLOOR_PCT
                .max(BULL_RSI_STOP_ATR_MULTIPLIER.multiply(atrPct))
                .min(BULL_RSI_STOP_LOSS_CAP_PCT);
        BigDecimal dynamicLossMultiplier = BigDecimal.ONE.subtract(
                dynamicLossFloorPct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));

        boolean isLossRange = realtimeSellablePrice.compareTo(totalCost.multiply(dynamicLossMultiplier)) <= 0;
        boolean rsiDropStop = rsiPeak.subtract(currentRsi).compareTo(BULL_EXHAUST_RSI_DROP) >= 0;
        boolean rsiBelowMid = currentRsi.compareTo(BULL_EXHAUST_RSI_ABS) < 0; // RSI < 50

        BigDecimal entryRsi = stateStore.entryRsiMap.getOrDefault(coinNm, currentRsi);
        boolean hadRealMomentum = rsiPeak.subtract(entryRsi).compareTo(BULL_RSI_STOP_MIN_PEAK_RISE) >= 0;
        LocalDateTime entryTimeChk = stateStore.positionEntryTimeMap.get(coinNm);
        boolean heldLongEnough = entryTimeChk == null
                || java.time.Duration.between(entryTimeChk, LocalDateTime.now()).toMinutes() >= BULL_RSI_STOP_MIN_HOLD_MINUTES;

        BigDecimal rsiStopLossPct = realtimeSellablePrice.divide(totalCost, 10, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        // 10/2: 조건 충족 시 급락 직후면 유예(매도 보류) — deferSoftStop 참고. 보류되면 아래 손실구간 로직으로 넘어간다.
        if (isLossRange && rsiDropStop && rsiBelowMid && hadRealMomentum && heldLongEnough
                && !deferSoftStop(coinNm, "RSI모멘텀손절", rsiStopLossPct, currentRsi)) {
            BigDecimal lossPct = rsiStopLossPct;
            BigDecimal entryRsi15m = stateStore.entryRsi15mMap.getOrDefault(coinNm, currentRsi15m);
            long heldMinutes = entryTimeChk == null ? 0
                    : java.time.Duration.between(entryTimeChk, LocalDateTime.now()).toMinutes();
            log.info("{} RSI모멘텀손절 ATR동적허용폭-{}%(ATR%:{}) 3분RSI고점대비-{} (진입{}→고점{}→현재{}) 15분RSI(진입{}→고점{}→현재{}) 손실:{}%",
                    coinNm,
                    dynamicLossFloorPct.setScale(2, RoundingMode.HALF_UP),
                    atrPct.setScale(3, RoundingMode.HALF_UP),
                    rsiPeak.subtract(currentRsi).setScale(1, RoundingMode.HALF_UP),
                    entryRsi.setScale(1, RoundingMode.HALF_UP),
                    rsiPeak.setScale(1, RoundingMode.HALF_UP),
                    currentRsi.setScale(1, RoundingMode.HALF_UP),
                    entryRsi15m.setScale(1, RoundingMode.HALF_UP),
                    rsi15mPeak.setScale(1, RoundingMode.HALF_UP),
                    currentRsi15m.setScale(1, RoundingMode.HALF_UP),
                    lossPct);
            momentumStopShadowRepository.save(MomentumStopShadow.builder()
                    .market(coinNm)
                    .capturedAt(LocalDateTime.now())
                    .lossPct(lossPct)
                    .heldMinutes(heldMinutes)
                    .entryRsiShort(entryRsi)
                    .peakRsiShort(rsiPeak)
                    .currentRsiShort(currentRsi)
                    .dropRsiShort(rsiPeak.subtract(currentRsi))
                    .entryRsiMedium(entryRsi15m)
                    .peakRsiMedium(rsi15mPeak)
                    .currentRsiMedium(currentRsi15m)
                    .dropRsiMedium(rsi15mPeak.subtract(currentRsi15m))
                    .build());
            clearPositionState(coinNm);
            tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), "RSI모멘텀손절");
            return;
        }

        // ══════════════════════════════════════════════════════════════
        //  손실구간 상태머신 (9/17 도입 → 9/18 역산검증 기반 재설계) — 0%(totalCost) ~
        //  즉시손절기준(IMMEDIATE_CUT_RATE, -1.1%) 전 구간에서 매 틱마다 판단한다.
        //
        //  9/17판은 BB+RSI 혼합 지표(구 classifyIndicatorOutlook)로 "다음 지표 전망"을
        //  판단했으나, 9/14~9/18 5일치 로그(지표스냅샷 14,157건) 역산검증 결과 그 판정의
        //  방향 적중률이 베이스라인과 다르지 않거나 더 낮았다(손실구간 STRONG_UP 판정 후
        //  3분 뒤 실제 상승 28.1% / 하락 49.4%). 같은 검증에서 유일하게 일관된 우위를 보인
        //  건 코인 자체의 장기(EMA9/20, 60분봉) phase가 SIDEWAYS인지 여부였다(3/15/60분
        //  전 구간에서 베이스라인 대비 +0.7~+2.6%p 리프트, 실거래 시뮬레이션에서도 유일하게
        //  뚜렷한 플러스 기대값). 이하 "매입조건"(buyCondition = longPhase == SIDEWAYS)으로
        //  대체한다 — 최초매수 필터에도 동일 조건이 진입 게이트로 걸려있다(CoinSignalService
        //  참고).
        //
        //  라운드 구조(9/18 사용자 설계):
        //  · 0라운드(최초 진입): 매입조건 충족 → 즉시 추가매수(1/3) + 1차관망 진입.
        //    매입조건 미충족(아직 -1.1% 아님) → 1차관망만 진입(매수 없음).
        //  · 1~2라운드(관망 이후 재판정, 동일 로직 반복 적용):
        //      직전 라운드 시작가 대비 하락 + 매입조건 미충족 → 즉시손절(비율 무관)
        //      직전 라운드 시작가 대비 하락 + 매입조건 충족 → 추가매수 + 다음 라운드 진입
        //      직전 라운드 시작가 대비 상승(매입조건 무관) → 다음 라운드 진입(매수 없음)
        //  · 3라운드 도달 시점의 재판정(9/18판: 결과·손실폭과 무관하게 전부 손절 →
        //    9/21 수정: 손실폭이 LOSS_ROUND_EXHAUST_MIN_LOSS_PCT(-0.5%) 이상 벌어진
        //    경우에만 손절하고, 그보다 작으면(노이즈 수준) 위 1~2라운드와 동일한 로직으로
        //    라운드를 계속 연장한다). 9/18~9/20 실거래 사후검증(ExitReviewService) 결과
        //    이 사유("손절")로 나간 115건 중 93.9~100%가 매도 후 24시간 내 회복돼 손실폭
        //    조건 없이는 조기청산이 너무 잦았음이 확인되어 수정했다 — 상세 근거는
        //    LOSS_ROUND_EXHAUST_MIN_LOSS_PCT 설명 참고. 이 수정으로 라운드가 3을 넘어
        //    계속 늘어날 수 있게 됐지만, 추가매수는 여전히 라운드 전이마다 최대 1회씩이고
        //    ADD_BUY_MAX_COUNT(3)가 상한이라 무한정 물타기로 이어지진 않는다.
        //
        //  loss > IMMEDIATE_CUT_RATE(-1.1%)는 라운드·매입조건과 무관하게 항상 즉시손절
        //  (무조건 backstop)이며, 그마저 못 잡으면 최종적으로 하드스탑(HARD_STOP_RATE,
        //  -1.2%, HARD_STOP_GRACE_SECONDS 유예 적용)이 잡는다.
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

        // 매입조건(9/18) — 최초매수 필터와 동일 기준: 장기(60분봉 EMA9/20) phase가 SIDEWAYS
        boolean buyCondition = longPhase == MarketPhase.SIDEWAYS;

        if (inLossZone) {
            BigDecimal lossPct = realtimeSellablePrice.divide(totalCost, 10, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
            int round = stateStore.lossWatchRoundMap.getOrDefault(coinNm, 0);

            // ── 무조건 backstop: 라운드·매입조건과 무관하게 -1.1% 초과 손실이면 즉시손절 ──
            // 10/2: 손절유예 중이면 백스탑을 -1.3%(STOP_DEFER_BACKSTOP_RATE)로 둔다.
            boolean stopDeferred = isStopDeferred(coinNm);
            BigDecimal cutRate = stopDeferred ? STOP_DEFER_BACKSTOP_RATE : IMMEDIATE_CUT_RATE;
            boolean pastImmediateCut = realtimeSellablePrice.compareTo(totalCost.multiply(cutRate)) <= 0;
            if (pastImmediateCut) {
                String cutReason = stopDeferred ? "유예손절" : "즉시손절";
                log.warn("{} {}({}차) 손실:{}% 장기phase:{}{}",
                        coinNm, cutReason, round, lossPct, longPhase, buyCondition ? "(매입조건 충족)" : "(매입조건 미충족)");
                clearPositionState(coinNm);
                tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), cutReason);
                return;
            }

            int dcaCount = stateStore.dcaCountMap.getOrDefault(coinNm, 0);
            LocalDateTime lastDcaAt = stateStore.lastDcaAtMap.get(coinNm);
            boolean dcaCooldownPassed = lastDcaAt == null
                    || java.time.Duration.between(lastDcaAt, LocalDateTime.now()).toMinutes() >= ADD_BUY_MIN_INTERVAL_MINUTES;
            boolean addBuyEligible = dcaCount < ADD_BUY_MAX_COUNT && dcaCooldownPassed && hasSufficientKrwForAddBuy();

            if (round == 0) {
                // 0라운드: 매입조건 충족이면 즉시 추가매수 + 1차관망, 아니면 1차관망만(매수 없음)
                if (buyCondition && addBuyEligible) {
                    executeLossZoneAddBuy(coinNm, signal, round, lossPct, longPhase, dcaCount);
                    return;
                }
                stateStore.lossWatchRoundMap.put(coinNm, 1);
                stateStore.lossWatchRefPriceMap.put(coinNm, realtimeSellablePrice);
                log.info("{} 손실구간 1차관망 진입 손실:{}% 장기phase:{}{}",
                        coinNm, lossPct, longPhase,
                        (buyCondition && !addBuyEligible) ? "(매입조건 충족, 추가매수 안전장치 미충족)" : "");
                return;
            }

            // 라운드 소진 안전장치(9/21 수정) — 3라운드 이상 도달 + 그 시점 손실폭이
            // LOSS_ROUND_EXHAUST_MIN_LOSS_PCT(-0.5%) 이상 벌어졌을 때만 손절한다. 손실폭이
            // 그보다 작으면(노이즈 수준) 아래 공통 라운드 재판정 로직으로 흘려보내 라운드를
            // 계속 연장한다 — 상세 근거는 상단 LOSS_ROUND_EXHAUST_MIN_LOSS_PCT 설명 참고.
            if (round >= 3 && lossPct.compareTo(LOSS_ROUND_EXHAUST_MIN_LOSS_PCT) <= 0
                    && !deferSoftStop(coinNm, "손절", lossPct, signal.getRsi())) {
                log.warn("{} 손절({}차관망) 손실:{}% 장기phase:{} [관망 {}회 소진 + 손실폭 {}% 이상]",
                        coinNm, round, lossPct, longPhase, round, LOSS_ROUND_EXHAUST_MIN_LOSS_PCT);
                clearPositionState(coinNm);
                tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), "손절");
                return;
            }

            // 라운드 재판정(1라운드 이상 공통, 3라운드 이상인데 손실폭 미달인 경우 포함):
            // 직전 라운드 시작가(lossWatchRefPriceMap) 대비 방향 + 매입조건으로 판단
            BigDecimal refPrice = stateStore.lossWatchRefPriceMap.getOrDefault(coinNm, realtimeSellablePrice);
            String direction = realtimeSellablePrice.compareTo(refPrice) > 0 ? "상승"
                    : realtimeSellablePrice.compareTo(refPrice) < 0 ? "하락" : "동일";
            boolean priceDown = "하락".equals(direction);

            if (priceDown && !buyCondition && !stopDeferred) {
                log.warn("{} 즉시손절({}차관망) 손실:{}% 직전대비:하락 장기phase:{}(매입조건 미충족)",
                        coinNm, round, lossPct, longPhase);
                clearPositionState(coinNm);
                tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice(), "즉시손절");
                return;
            }
            if (priceDown && buyCondition && addBuyEligible) {
                executeLossZoneAddBuy(coinNm, signal, round, lossPct, longPhase, dcaCount);
                return;
            }
            // 그 외(상승 — 매입조건 무관, 또는 하락+매입조건 충족인데 추가매수 안전장치 미충족) → 다음 라운드로
            int nextRound = round + 1;
            stateStore.lossWatchRoundMap.put(coinNm, nextRound);
            stateStore.lossWatchRefPriceMap.put(coinNm, realtimeSellablePrice);
            log.info("{} 손실구간 {}차관망 손실:{}% 직전대비:{} 장기phase:{}{}",
                    coinNm, nextRound, lossPct, direction, longPhase,
                    (priceDown && buyCondition) ? "(매입조건 충족, 추가매수 안전장치 미충족)" : "");
        } else if (inProfitWatchZone) {
            // ══════════════════════════════════════════════════════════════
            //  이익구간 상태머신 (9/17 도입 → 9/18 재설계) — +0.3%(PROFIT_WATCH_START_RATE)
            //  이상 수익일 때 매입조건(장기phase=SIDEWAYS)으로 판단한다. 매입조건 충족이면
            //  관망(보유 지속, 매 틱 재판정), 미충족이면 즉시 익절 — 손실구간과 달리 유예
            //  횟수를 세지 않고, 매입조건이 유지되는 한 계속 보유한다.
            //
            //  기존 4개 익절 경로(RSI과매수즉시익절/트레일링익절/RSI모멘텀소진익절/점수익절)는
            //  전부 그대로 유지하며, 이 상태머신은 그 경로들이 이번 틱에 아직 발동하지 않았을 때만
            //  추가로 작동하는 보조 경로다 — 기존 검증된 로직을 걷어내지 않고 그 위에 얹었다.
            // ══════════════════════════════════════════════════════════════
            int profitRound = stateStore.profitWatchRoundMap.getOrDefault(coinNm, 0);
            BigDecimal profitPct = realtimeSellablePrice.divide(totalCost, 10, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);

            if (buyCondition) {
                stateStore.profitWatchRoundMap.put(coinNm, 1);
                log.info("{} 이익구간 관망 수익:+{}% 장기phase:{}(매입조건 충족)", coinNm, profitPct, longPhase);
            } else {
                log.info("{} 익절({}차) 수익:+{}% 장기phase:{}(매입조건 미충족)", coinNm, profitRound, profitPct, longPhase);
                clearPositionState(coinNm);
                registerProfitCooldown(coinNm, realtimeSellablePrice, totalCost, POST_PROFIT_COOLDOWN_MINUTES);
                tradeExecutionService.executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice(), "익절");
                return;
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
    /** 손절 유예가 현재 진행 중인지 (10/2) */
    private boolean isStopDeferred(String coinNm) {
        LocalDateTime until = stateStore.stopDeferUntilMap.get(coinNm);
        return until != null && LocalDateTime.now().isBefore(until);
    }

    /**
     * 소프트 손절(손절/RSI모멘텀손절) 발동 시 매도를 보류할지 판단한다 (10/2). true면 이번 틱 매도 보류.
     * 유예 진행 중이면 그대로 보류하고, 아니면 직전 슬로우 루프 대비 3분봉 RSI 변화가
     * STOP_DEFER_RSI_DROP 이하(급락 직후)이고 이 포지션에서 아직 유예를 쓰지 않았을 때만 새로 유예한다.
     * 판단할 때마다 "[손절판단]" 로그를 남겨 유예한 건/안 한 건을 나중에 비교할 수 있게 한다.
     */
    private boolean deferSoftStop(String coinNm, String reason, BigDecimal lossPct, BigDecimal currentRsi) {
        if (isStopDeferred(coinNm)) {
            return true;
        }
        BigDecimal prevRsi = stateStore.prevRsiMap.get(coinNm);
        BigDecimal rsiChange = (prevRsi == null || currentRsi == null) ? null : currentRsi.subtract(prevRsi);
        boolean sharpDrop = rsiChange != null && rsiChange.compareTo(STOP_DEFER_RSI_DROP) <= 0;
        boolean alreadyUsed = stateStore.stopDeferUsedSet.contains(coinNm);
        boolean defer = STOP_DEFER_ENABLED && sharpDrop && !alreadyUsed;
        log.info("{} [손절판단] 사유:{} 손실:{}% 3분RSI:{} 직전:{} 변화:{} → {}",
                coinNm, reason, lossPct,
                currentRsi == null ? "N/A" : currentRsi.setScale(1, RoundingMode.HALF_UP).toPlainString(),
                prevRsi == null ? "N/A" : prevRsi.setScale(1, RoundingMode.HALF_UP).toPlainString(),
                rsiChange == null ? "N/A" : rsiChange.setScale(1, RoundingMode.HALF_UP).toPlainString(),
                defer ? "유예" : !STOP_DEFER_ENABLED ? (sharpDrop ? "매도(급락, 유예 꺼짐)" : "매도(완만 하락, 유예 꺼짐)")
                        : (alreadyUsed ? "매도(유예 사용함)" : "매도(완만 하락)"));
        if (!defer) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        stateStore.stopDeferUsedSet.add(coinNm);
        stateStore.stopDeferUntilMap.put(coinNm, now.plusMinutes(STOP_DEFER_MINUTES));
        stateStore.stopDeferStartLossMap.put(coinNm, lossPct);
        stateStore.stopDeferStartAtMap.put(coinNm, now);
        log.info("{} [손절유예] 시작 사유:{} 손실:{}% 최대 {}분, 유예 중 백스탑 -1.3%",
                coinNm, reason, lossPct, STOP_DEFER_MINUTES);
        return true;
    }

    private void clearPositionState(String coinNm) {
        // 10/2: 유예를 거친 포지션이면 종료 기록(유예 시작 손실률·경과시간) — 실제 매도 사유·손익은 바로 다음 매도 로그
        BigDecimal deferStartLoss = stateStore.stopDeferStartLossMap.remove(coinNm);
        LocalDateTime deferStartAt = stateStore.stopDeferStartAtMap.remove(coinNm);
        if (deferStartLoss != null && deferStartAt != null) {
            log.info("{} [손절유예] 종료 — 유예 시작 시 손실:{}% 경과:{}분 (매도 사유·손익은 다음 로그)",
                    coinNm, deferStartLoss, java.time.Duration.between(deferStartAt, LocalDateTime.now()).toMinutes());
        }
        stateStore.stopDeferUntilMap.remove(coinNm);
        stateStore.stopDeferUsedSet.remove(coinNm);
        stateStore.trailingPeakMap.remove(coinNm);
        stateStore.positionEntryTimeMap.remove(coinNm);
        stateStore.rsiPeakMap.remove(coinNm);
        stateStore.rsiTroughMap.remove(coinNm);
        stateStore.rsi15mPeakMap.remove(coinNm);
        stateStore.virtualAddBuyLoggedSet.remove(coinNm);
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
     *
     * <p>9/18: 발동 근거를 RSI저점반등 → 매입조건(장기phase=SIDEWAYS)으로 교체 — 클래스 상단
     * 손실구간 상태머신 설명 참고. rsiTroughMap 갱신은 계속 유지한다 — 이 맵은 RSI모멘텀손절/
     * 소진익절 등 이 메서드와 무관한 다른 로직에서도 참조하는 공용 추적 상태이기 때문.
     */
    /**
     * 추가매수 실행 전 KRW 현금 잔고 확인 (9/22 추가).
     * 9/21 손실폭 조건 도입 이후 라운드가 훨씬 길게(최대 9라운드+) 연장되면서 동시 보유 포지션이
     * 늘어 KRW 현금이 고갈되는 사례(insufficient_funds_bid, 9/21 5건·9/22 오전만 12건)가 발생 —
     * 주문 시도 전 가용 잔고를 확인해 애초에 실패할 주문을 걸지 않도록 한다.
     */
    private boolean hasSufficientKrwForAddBuy() {
        BigDecimal available = exchangeClient.availableKrwBalance();
        return available.compareTo(new BigDecimal(ADD_BUY_AMOUNT)) >= 0;
    }

    private void executeLossZoneAddBuy(String coinNm, CoinSignalDto signal, int round, BigDecimal lossPct,
                                       MarketPhase longPhase, int dcaCount) {
        log.info("{} 손실구간 추가매수({}/{}, {}차관망) 손실:{}% 매입조건 충족(장기phase:{})",
                coinNm, dcaCount + 1, ADD_BUY_MAX_COUNT, round, lossPct, longPhase);
        OrdersResponse addBuyResponse = exchangeClient.orderCoin(coinNm, "bid", ADD_BUY_AMOUNT);
        tradeHistoryRepository.save(TradeHistoryDto.buyHistory(coinNm, ADD_BUY_AMOUNT, signal)
                .toBuilder().tradeType("추가매수").build());
        stateStore.dcaCountMap.put(coinNm, dcaCount + 1);
        stateStore.lastDcaAtMap.put(coinNm, LocalDateTime.now());
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
