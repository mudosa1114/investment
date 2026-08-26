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

    // ─── 매수 설정 ────────────────────────────────────────────────────
    private static final String MIN_ORDER_AMOUNT = "10000";              // 최초 매수 금액 (KRW)
    /** 매수 허용 RSI 하한
     *  (8/25 거래빈도 확대: 43 → 40 — 하루 80~100건 목표를 위해 진입 구간 확장) */
    private static final BigDecimal RSI_BUY_MIN = BigDecimal.valueOf(40);
    /** 매수 허용 RSI 상한 — 과열 진입 방지
     *  (8/25 거래빈도 확대: 55 → 65 — 품질 우선으로 55까지 줄였던 것을 빈도 목표에 맞춰 재확장.
     *   품질 저하는 RSI모멘텀손절 오발동 방지 로직(entryRsiMap/최소보유시간)이 어느 정도 보완) */
    private static final BigDecimal RSI_BUY_MAX = BigDecimal.valueOf(65);
    /** BB 위치 진입 차단 기준: (현재가 - BB하단) / (BB상단 - BB하단) ≥ 이 값이면 고점 진입으로 판단해 차단
     *  (8/25 거래빈도 확대: 0.70 → 0.90 — 진입 가능 구간을 BB 상단 근처까지 확장) */
    private static final BigDecimal BB_ENTRY_MAX_PCT = new BigDecimal("0.90");
    /** RSI 상승 최소폭: 직전 슬로우 루프 대비 RSI 상승폭이 이 값 미만이면 진입 차단 (↑0.1 같은 노이즈 필터링)
     *  (8/25 거래빈도 확대: 2.0 → 0.3 — 3분마다 2.0pt 상승을 요구하는 조건이 진입 기회를 크게 제한했음) */
    private static final BigDecimal RSI_RISE_MIN = new BigDecimal("0.3");

    // ─── 손익 임계값 상수 ──────────────────────────────────────────────
    /**
     * 점수 손절 활성화 기준: 이 비율 이하 손실 시 슬로우 루프에서 지표 점수 계산 시작 (-0.9%)
     * 점수가 역치 미달이면 포지션 유지 → 강제손절(HARD_STOP_RATE)까지 홀딩
     */
    private static final BigDecimal STOP_SCORE_ACTIVATE_RATE  = new BigDecimal("0.991");
    /** 강제 손절: 지표와 무관하게 이 비율 이하이면 패스트 루프에서 즉시 매도 (-1.2%) */
    private static final BigDecimal HARD_STOP_RATE            = new BigDecimal("0.988");
    /** BULL 국면 점수 익절 기준: +0.6% (상승 추세 — 작은 수익도 빠르게 확정)
     *  (기존 +0.8% → +0.6%: 8/15-24 로그 10일간 이 임계값 도달로 익절된 사례 0건 —
     *   실제 체결 코인들의 30분 내 평균 변동폭이 목표치에 못 미쳐 전량 시간강제매도/손절로 종료됨) */
    private static final BigDecimal PROFIT_THRESHOLD_BULL     = new BigDecimal("1.006");
    /** SIDEWAYS 국면 점수 익절 기준: +0.7% (횡보 — 충분한 쿠션 후 실현, 기존 +1.0%에서 하향) */
    private static final BigDecimal PROFIT_THRESHOLD_SIDEWAYS = new BigDecimal("1.007");
    /** BEAR 국면 점수 익절 기준: +0.4% (약세 전환 시 빠른 이탈 우선, 기존 +0.5%에서 하향) */
    private static final BigDecimal PROFIT_THRESHOLD_BEAR     = new BigDecimal("1.004");

    // ─── 트레일링 스탑 설정 ───────────────────────────────────────────
    /** 트레일링 활성화 기준: 투자금 대비 이 비율 이상 수익 시 추적 시작
     *  (기존 +0.5% → +0.4%: 실제 익절 체결 평균이 +0.3~0.4%대에 몰려 있어 더 일찍 보호 시작) */
    private static final BigDecimal TRAILING_ACTIVATE_RATE   = new BigDecimal("1.004");
    /** BULL 국면 트레일링 낙폭: 고점 대비 -0.5% — 상승 추세 출렁임 허용, 더 길게 추적 */
    private static final BigDecimal TRAILING_DROP_BULL       = new BigDecimal("0.005");
    /** SIDEWAYS 국면 트레일링 낙폭: 고점 대비 -0.45% — 중립 기준 */
    private static final BigDecimal TRAILING_DROP_SIDEWAYS   = new BigDecimal("0.0045");
    /** BEAR 국면 트레일링 낙폭: 고점 대비 -0.35% — 약세 빠른 수익 확보 우선 */
    private static final BigDecimal TRAILING_DROP_BEAR       = new BigDecimal("0.0035");

    // ─── 지표 임계값 상수 ──────────────────────────────────────────────
    /** 익절 점수 RSI 가산 기준 + RSI 즉시 익절 기준: RSI > 70 시 과매수 */
    private static final BigDecimal RSI_OVERBOUGHT = BigDecimal.valueOf(70);
    /** RSI 즉시 익절 최소 수익률: RSI>70 조건과 함께 이 수익률 이상일 때 즉시 매도 (+0.3%) */
    private static final BigDecimal RSI_EXIT_MIN_PROFIT = new BigDecimal("1.003");

    // ─── BULL 모멘텀 소진 익절 설정 ─────────────────────────────────
    /** [조건 A] shortPhase+longPhase 모두 BULL 이면서 RSI 이 값 미만 + 수익 중 → 즉시 익절 */
    private static final BigDecimal BULL_EXHAUST_RSI_ABS    = BigDecimal.valueOf(50);
    /** [조건 B / 손절 공용] RSI 고점 대비 이 값 이상 하락 시 모멘텀 소진 판단 */
    private static final BigDecimal BULL_EXHAUST_RSI_DROP   = BigDecimal.valueOf(7);
    /** [조건 B] 최소 수익률 기준 (+0.1%) */
    private static final BigDecimal BULL_EXHAUST_MIN_PROFIT = new BigDecimal("1.001");

    // ─── BULL RSI 모멘텀 손절 설정 ───────────────────────────────────
    /** shortPhase+longPhase 모두 BULL + 손실 ≥ -0.5% + RSI 고점 대비 -7 이상 하락 → 조기 손절
     *  점수 손절(BULL≥5) 미달 구간에서 RSI 모멘텀 붕괴를 직접 감지해 -1.4% 강제손절 방어 */
    private static final BigDecimal BULL_RSI_STOP_MIN_LOSS  = new BigDecimal("0.995"); // -0.5%
    /**
     * RSI 모멘텀손절 오발동 방지 — 진입 RSI 대비 실제 상승폭 최소 기준.
     * 8/15-24 로그 분석: 손절 12건 전부 "진입 직후 RSI가 진입값 대비 거의 못 오르고(peak-entry &lt; 3)
     * 바로 하락 반전"한 케이스 — 애초에 모멘텀이 없었던 노이즈성 진입을 "모멘텀 붕괴"로 오판해 손절.
     * peak(rsiPeakMap)가 진입 RSI보다 이 값 이상 올라선 적이 있어야 "진짜 모멘텀이 있었다가 꺾인 것"으로 인정.
     */
    private static final BigDecimal BULL_RSI_STOP_MIN_PEAK_RISE = new BigDecimal("3.0");
    /** RSI 모멘텀손절 최소 보유시간(분) — 진입 직후 1~2회 슬로우 루프 노이즈로 즉시 손절되는 것 방지 */
    private static final int BULL_RSI_STOP_MIN_HOLD_MINUTES = 6;

    /** 손절 점수 RSI 가산 기준: RSI < 30 시 과매도 +1점 */
    private static final BigDecimal RSI_LOW        = BigDecimal.valueOf(30);

    // ─── 점수 임계값 ──────────────────────────────────────────────────
    // 익절: phase 무관 ≥ 4 고정 (phase별 차등은 trailing DROP rate로 담당)
    // 손절: BULL ≥ 5 / SIDEWAYS ≥ 4 / BEAR ≥ 3 (약세일수록 빠른 손절)
    private static final int SELL_SCORE_THRESHOLD = 4;

    // ─── 시간 손절 설정 ───────────────────────────────────────────────
    // (8/25 거래빈도 확대: 보유시간을 단축해 자본 회전율을 높임 — 포지션이 빨리 정리될수록
    //  같은 자본으로 하루에 더 많은 진입 기회를 만들 수 있음)
    /** 시간 손절 활성화: 매수 후 이 시간(분) 경과 + 손익률 ≤ -0.3% 이면 매도 (기존 25분 → 15분) */
    private static final int TIME_STOP_LOSS_MINUTES  = 15;
    /** 시간 강제 매도: 매수 후 이 시간(분) 경과 시 손익률 무관 강제 매도 (LOSS_MINUTES보다 커야 함, 기존 30분 → 20분) */
    private static final int TIME_STOP_FORCE_MINUTES = 20;
    /** 시간 손절 기준 손익률: -0.5% 이하 손실 시 TIME_STOP_LOSS_MINUTES 조건 적용 (기존 -0.3% → 완화) */
    private static final BigDecimal TIME_STOP_LOSS_RATE = new BigDecimal("0.995");
    /** 시간강제매도 profit/damage 판정 기준: 수수료 손익분기(매수0.05%+매도0.05%=0.1%) 이상이어야 실질 익절 */
    private static final BigDecimal TIME_FORCE_PROFIT_MIN = new BigDecimal("1.001");

    // ─── Circuit Breaker (일일 손실 한도) ────────────────────────────
    /**
     * 일일 실현손익 한도 (KRW) — 이 금액 이하 손실 시 봇 완전 정지
     * 총 운용 자본의 약 5% 수준으로 설정 권장 (예: 자본 20만원 → -10,000원)
     */
    private static final BigDecimal DAILY_LOSS_HALT_KRW = new BigDecimal("-10000");

    // ─── 재진입 쿨다운 설정 ───────────────────────────────────────────
    // (8/25 거래빈도 확대: 하루 80~100건 목표에 맞춰 전 쿨다운을 대폭 단축)
    /** 손절 직후 최소 대기 시간 (이후 승률 기반 쿨다운 적용) */
    private static final int RE_ENTRY_COOLDOWN_MINUTES      = 2;
    /** 트레일링·점수 정상 익절 후 재진입 차단 시간 */
    private static final int POST_PROFIT_COOLDOWN_MINUTES   = 2;
    /** RSI과매수·BULL모멘텀소진 익절 후 재진입 차단 시간 — 과열 신호이므로 추가 대기 */
    private static final int POST_PROFIT_COOLDOWN_HOT       = 5;
    /** 급등 익절(+2% 이상) 후 재진입 차단 시간 — 되돌림 위험 구간 */
    private static final int POST_PROFIT_COOLDOWN_SPIKE     = 8;
    /** 급등 익절 판단 기준 수익률: 이 이상이면 SPIKE 쿨다운 적용 */
    private static final BigDecimal PROFIT_SPIKE_THRESHOLD  = new BigDecimal("1.02"); // +2%

    // ─── 익절 후 재진입 가격 앵커 설정 ──────────────────────────────────
    /** 익절 후 재진입: 앵커가(평균매수가) 초과 시 허용되는 최대 프리미엄 (0.5%) */
    private static final BigDecimal PROFIT_REENTRY_MAX_PREMIUM   = new BigDecimal("0.005");
    /** 익절 후 재진입: 앵커가 초과 구간 진입 허용 최소 RSI — 강한 상승 모멘텀 확인 */
    private static final BigDecimal PROFIT_REENTRY_STRONG_RSI    = new BigDecimal("54");
    /** 익절 후 재진입: 앵커가 초과 구간 진입 허용 최소 RSI 상승폭 */
    private static final BigDecimal PROFIT_REENTRY_STRONG_RISE   = new BigDecimal("3.0");
    /**
     * 익절 후 재진입 앵커 만료 시간(시간) — 이 시간을 넘으면 앵커를 무시하고 정상 진입 허용.
     * 8/25 13:06 재시작 이후 로그 재분석 결과 실제로 걸린 버그: 앵커에 만료가 없어
     * ETH/SOL/XRP/DOGE 등 상승장에서 가격이 오래전 앵커보다 +8~20% 높아진 코인들이
     * "익절 후 재진입 차단"에 전부 무기한 걸려 있었음 — 그 결과 매수 가능한 코인이
     * 사실상 XLM 1종으로 좁아져 (a) 상승장 수익 기회를 전부 놓치고 (b) 거래량도 XLM
     * 하나에 갇혀 낮게 유지됨. 이 앵커는 "익절 직후 바로 되사는 것"만 막으면 충분하므로
     * 몇 시간 지나면 자동 해제되도록 함.
     */
    private static final int PROFIT_ANCHOR_MAX_HOURS = 4;

    // ─── 동적 코인 선정 설정 ──────────────────────────────────────────
    /** (8/25 거래빈도 확대: 8 → 14 — 고정3 + 동적11, DYNAMIC_COIN_WHITELIST 전체가 조건만 맞으면
     *   동시에 편입 가능하도록 확장. 동시 보유 가능한 코인이 많을수록 3분 슬로우 루프당 매수 기회가 늘어남) */
    private static final int MAX_COIN_SLOTS = 14;
    private static final int VOLUME_TOP_N   = 20;
    /** 24h 최소 거래대금 (KRW) — 이 미만 코인은 유동성 부족으로 제외 */
    private static final BigDecimal MIN_VOLUME_24H = new BigDecimal("10000000000"); // 100억원 (기존 200억 → 완화)
    /** 동적 코인 최소 현재가 (KRW) — 이 미만 극저가 코인 제외 (호가 스프레드 문제) */
    private static final BigDecimal COIN_MIN_PRICE = new BigDecimal("10"); // 10원
    /** 선정 대상에서 제외할 마켓 (스테이블코인·BTC) */
    private static final Set<String> COIN_EXCLUSIONS = Set.of(
            "KRW-USDT", "KRW-USDC", "KRW-DAI", "KRW-BTC"
    );
    /**
     * 동적 코인 화이트리스트 — 이 목록에 포함된 코인만 동적 선정 후보로 허용.
     *
     * <p>선정 기준:
     * <ul>
     *   <li>Upbit 장기 상장 (상장 1년 이상) — 신규 상장 소형 코인 배제</li>
     *   <li>시가총액 상위권 또는 Upbit 거래대금 꾸준 유지</li>
     *   <li>May27-31 로그 분석: AZTEC/POKT/RENDER/FF 등 0승 급락 코인 배제 효과</li>
     * </ul>
     */
    private static final Set<String> DYNAMIC_COIN_WHITELIST = Set.of(
            "KRW-ADA",  "KRW-LINK", "KRW-DOT",  "KRW-ATOM",
            "KRW-HBAR", "KRW-TRX",  "KRW-XLM",  "KRW-DOGE",
            "KRW-ETC",  "KRW-NEAR", "KRW-INJ"
    );

    // ─── 동적 선정 품질 필터 상수 ─────────────────────────────────────
    /** 24h 변동률 하한: 이 미만 폭락 코인 제외 (-8%) */
    private static final BigDecimal COIN_24H_CHANGE_MIN = new BigDecimal("-0.08");
    /** 24h 변동률 상한: 이 초과 급등 코인 제외 (+25%) — 되돌림 위험 */
    private static final BigDecimal COIN_24H_CHANGE_MAX = new BigDecimal("0.25");
    /** 1시간 변동률 하한: 이 미만 단기 급락 코인 제외 (-3%) */
    private static final BigDecimal COIN_1H_CHANGE_MIN  = new BigDecimal("-0.03");
    /** 1시간 변동률 상한: 이 초과 단기 급등 코인 제외 (+5%) — 단기 고점 진입 위험 */
    private static final BigDecimal COIN_1H_CHANGE_MAX  = new BigDecimal("0.05");
    /** BB 폭(%) 상한: (upper-lower)/middle 이 초과이면 변동성 극심 코인 제외 */
    private static final BigDecimal COIN_BB_WIDTH_MAX   = new BigDecimal("8");

    // ─── 포지션 진입 시각 추적 ───────────────────────────────────────
    /** 코인별 매수 진입 시각 — 시간 손절 판단용, 매수 시 등록/매도 시 제거 */
    private final Map<String, LocalDateTime> positionEntryTimeMap = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 포지션 보유 중 RSI 최고값 — BULL 모멘텀 소진 감지용, 슬로우 루프에서 갱신 */
    private final Map<String, BigDecimal>    rsiPeakMap           = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 매수 진입 시점 RSI — RSI 모멘텀손절 오발동 방지용(진입 대비 peak 상승폭 검증), 매수 시마다 갱신 */
    private final Map<String, BigDecimal>    entryRsiMap          = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 직전 슬로우 루프 RSI — 진입 시 RSI 상승 방향 확인용 (현재 RSI > 직전 RSI 이어야 진입) */
    private final Map<String, BigDecimal>    prevRsiMap           = new java.util.concurrent.ConcurrentHashMap<>();

    // ─── 지표 캐시 (슬로우 루프가 3분마다 갱신, 패스트 루프가 참조) ─────
    /** volatile: 참조 교체가 원자적으로 보장됨 (슬로우 루프 갱신 → 패스트 루프 즉시 가시) */
    private volatile Map<String, CoinSignalDto> cachedSignalMap = Collections.emptyMap();

    // ─── 트레일링 스탑 / 연속 손절 추적 맵 ──────────────────────────
    /** 코인별 트레일링 고점 평가금액 — 패스트 루프에서 30초마다 갱신 */
    private final Map<String, BigDecimal>   trailingPeakMap     = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 당일 연속 손절 횟수 — 3회→임시차단(20분), 5회→임시차단(1h) (8/25 거래빈도 확대로 완화) */
    private final Map<String, Integer>      consecutiveLossMap   = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 당일 누적 손절 횟수 (승패 무관) — 8회 달성 시 당일 블랙리스트 (8/25: 3→8회 상향)
     *  연속손절 카운터는 이익 시 0으로 리셋되지만, 이 카운터는 이익이 끼어도 리셋 안 함.
     *  예) 손절→손절→이익→손절→손절 이면 연속=2 이지만 누적=4 */
    private final Map<String, Integer>      dailyTotalLossMap    = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * 임시 시간 차단 코인 — 연속 손절 시 등록, 만료 시각(LocalDateTime) 저장
     * (8/25 거래빈도 확대로 완화)
     * · 연속 손절 3회 → now + 20분
     * · 연속 손절 5회 → now + 1시간
     */
    private final Map<String, LocalDateTime> temporaryBanUntilMap = new java.util.concurrent.ConcurrentHashMap<>();
    /** 당일 매수 완전 차단 코인 집합 — 현재 연속손절 외 수동 차단 등 확장용, 자정에 초기화 */
    private final Set<String>               dailyBlacklistSet        = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 익절 유형별 차등 쿨다운 만료 시각 — 정상:3분 / 과열:10분 / 급등:15분 */
    private final Map<String, LocalDateTime> profitCooldownUntilMap   = new java.util.concurrent.ConcurrentHashMap<>();

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
        // RSI 방향 필터용: 새 캐시 교체 전에 현재 RSI를 이전값으로 저장
        cachedSignalMap.forEach((c, sig) -> prevRsiMap.put(c, sig.getRsi()));
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
        firstPurchaseCoin(holdCoinSet, signalMap, accountList);
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
                MarketPhase shortPhase = detectShortTermPhase(emaCandles); // 15분봉 → 단기 국면 (주 필터)
                MarketPhase phase = detectMarketPhase(phaseCandles);       // 60분봉 → 장기 국면 (보조 필터)
                Map<String, BigDecimal> ema = calculateEmaCross(emaCandles);
                Map<String, BigDecimal> bb = calculateBollingerBands(shortCandles);
                CoinPrice price = checkCoinPrice(coin);

                map.put(coin, CoinSignalDto.builder()
                        .rsi(rsi)
                        .shortPhase(shortPhase)
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
    //  [패스트 루프용] 현재가 기반 액션: 하드 손절(-0.9%) + 트레일링 익절
    //  DCA 제거 — 진입은 SHORT_BULL 10,000원 단일, 포지션 관리만 담당
    // ══════════════════════════════════════════════════════════════════
    private void executePriceBasedActions(CoinAccount account, String coinNm, CoinSignalDto signal) {

        BigDecimal currentPrice  = checkCoinPrice(coinNm).getBidPrice();
        BigDecimal totalCost     = account.getAvgBuyPrice()
                .multiply(account.getBalance()).setScale(0, RoundingMode.CEILING);
        BigDecimal sellablePrice = currentPrice.multiply(account.getBalance());
        BigDecimal profitRate    = sellablePrice.divide(totalCost, 10, RoundingMode.HALF_UP);

        // ── 강제 손절: -1.2% (지표 무관, 패스트 루프 즉시 처리) ─────────
        if (profitRate.compareTo(HARD_STOP_RATE) <= 0) {
            log.warn("{} 강제손절 (-1.2%) 평가:{} 투자:{} [단기:{} RSI:{}]",
                    coinNm, sellablePrice.setScale(0, RoundingMode.HALF_UP), totalCost,
                    signal.getShortPhase(), signal.getRsi().setScale(1, RoundingMode.HALF_UP));
            trailingPeakMap.remove(coinNm);
            positionEntryTimeMap.remove(coinNm);
            rsiPeakMap.remove(coinNm);
            executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice());
            return;
        }

        // ── 시간 손절: 15분 경과 + 손익 ≤ -0.3% → 매도 ──────────────────
        // ── 시간 강제 매도: 21분 경과 시 손익 무관 강제 매도 ─────────────
        // SHORT_BULL 모멘텀은 통상 15분 내 소진 — 이후 포지션은 자본 묶임
        LocalDateTime entryTime = positionEntryTimeMap.get(coinNm);
        if (entryTime != null) {
            long minutesHeld = java.time.Duration.between(entryTime, LocalDateTime.now()).toMinutes();
            BigDecimal profitPct = profitRate.subtract(BigDecimal.ONE)
                    .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);

            // LOSS 체크를 FORCE보다 먼저 — LOSS_MINUTES < FORCE_MINUTES 보장 필요
            if (minutesHeld >= TIME_STOP_LOSS_MINUTES
                    && profitRate.compareTo(TIME_STOP_LOSS_RATE) <= 0) {
                log.warn("{} 시간손절 ({}분 경과, 손익:{}% ≤ -0.3%) — 모멘텀 소진 판단",
                        coinNm, minutesHeld, profitPct);
                trailingPeakMap.remove(coinNm);
                positionEntryTimeMap.remove(coinNm);
                rsiPeakMap.remove(coinNm);
                executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice());
                return;
            }
            if (minutesHeld >= TIME_STOP_FORCE_MINUTES) {
                // 수수료 손익분기(0.1%) 이상이어야 profit — 미만은 실질 손실이므로 damage
                String sellType = profitRate.compareTo(TIME_FORCE_PROFIT_MIN) >= 0 ? "profit" : "damage";
                log.warn("{} 시간강제매도 ({}분 경과, 손익:{}%) → {}",
                        coinNm, minutesHeld, profitPct, sellType);
                trailingPeakMap.remove(coinNm);
                positionEntryTimeMap.remove(coinNm);
                rsiPeakMap.remove(coinNm);
                executeSell(coinNm, account.getBalance().toPlainString(), sellType, signal, account.getAvgBuyPrice());
                return;
            }
        }

        // ── 트레일링 익절: +0.8% 진입 후 국면별 낙폭 초과 시 매도 ─────
        // BULL -0.5% / SIDEWAYS -0.45% / BEAR -0.35%
        if (profitRate.compareTo(TRAILING_ACTIVATE_RATE) >= 0) {
            MarketPhase shortPhase  = signal.getShortPhase();
            MarketPhase longPhase   = signal.getPhase();
            MarketPhase effectPhase = (shortPhase != MarketPhase.SIDEWAYS) ? shortPhase : longPhase;
            BigDecimal dropRate     = trailingDropRate(effectPhase);

            // computeIfAbsent: 최초 진입 시만 anchor, 이후 map의 최고점 유지
            BigDecimal peak = trailingPeakMap.computeIfAbsent(coinNm, k -> sellablePrice);
            if (sellablePrice.compareTo(peak) > 0) {
                peak = sellablePrice;
                trailingPeakMap.put(coinNm, peak); // 최고점 갱신만 허용
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
                trailingPeakMap.remove(coinNm);
                positionEntryTimeMap.remove(coinNm);
                rsiPeakMap.remove(coinNm);
                registerProfitCooldown(coinNm, sellablePrice, totalCost, POST_PROFIT_COOLDOWN_MINUTES);
                executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice());
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
    private void executeHardExitsOnly(CoinAccount account, String coinNm, CoinSignalDto signal) {
        BigDecimal currentPrice  = checkCoinPrice(coinNm).getBidPrice();
        BigDecimal totalCost     = account.getAvgBuyPrice()
                .multiply(account.getBalance()).setScale(0, RoundingMode.CEILING);
        BigDecimal sellablePrice = currentPrice.multiply(account.getBalance());
        BigDecimal profitRate    = sellablePrice.divide(totalCost, 10, RoundingMode.HALF_UP);

        // 강제 손절: -1.2%
        if (profitRate.compareTo(HARD_STOP_RATE) <= 0) {
            log.warn("{} [정지중] 강제손절 (-1.2%) 평가:{} 투자:{} [단기:{} RSI:{}]",
                    coinNm, sellablePrice.setScale(0, RoundingMode.HALF_UP), totalCost,
                    signal.getShortPhase(), signal.getRsi().setScale(1, RoundingMode.HALF_UP));
            trailingPeakMap.remove(coinNm);
            positionEntryTimeMap.remove(coinNm);
            rsiPeakMap.remove(coinNm);
            executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice());
            return;
        }

        // 트레일링 익절 (정지 상태에서도 기존 고점 추적 유지, 국면별 DROP 적용)
        if (profitRate.compareTo(TRAILING_ACTIVATE_RATE) >= 0) {
            MarketPhase shortPhase  = signal.getShortPhase();
            MarketPhase longPhase   = signal.getPhase();
            MarketPhase effectPhase = (shortPhase != MarketPhase.SIDEWAYS) ? shortPhase : longPhase;
            BigDecimal dropRate     = trailingDropRate(effectPhase);

            BigDecimal peak = trailingPeakMap.computeIfAbsent(coinNm, k -> sellablePrice);
            if (sellablePrice.compareTo(peak) > 0) {
                peak = sellablePrice;
                trailingPeakMap.put(coinNm, peak);
            }
            BigDecimal trailingStopLine = peak.multiply(BigDecimal.ONE.subtract(dropRate));
            if (sellablePrice.compareTo(trailingStopLine) <= 0) {
                log.info("{} [정지중] 트레일링익절 고점:{} 현재:{} [{}국면 DROP-{}%]",
                        coinNm,
                        peak.setScale(0, RoundingMode.HALF_UP),
                        sellablePrice.setScale(0, RoundingMode.HALF_UP),
                        effectPhase, dropRate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP));
                trailingPeakMap.remove(coinNm);
                positionEntryTimeMap.remove(coinNm);
                rsiPeakMap.remove(coinNm);
                registerProfitCooldown(coinNm, sellablePrice, totalCost, POST_PROFIT_COOLDOWN_MINUTES);
                executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice());
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
    //  [슬로우 루프용] 지표 점수 기반 익절
    //  손절·트레일링은 패스트 루프(30초)에서 담당하므로 여기서는 익절만 판단
    // ══════════════════════════════════════════════════════════════════
    private void evaluateScoreBasedExit(CoinAccount account, String coinNm, CoinSignalDto signal) {

        // shortPhase 우선, SIDEWAYS일 때만 longPhase fallback
        MarketPhase shortPhase  = signal.getShortPhase();
        MarketPhase longPhase   = signal.getPhase();
        MarketPhase effectPhase = (shortPhase != MarketPhase.SIDEWAYS) ? shortPhase : longPhase;

        // indicatorPrice: BB·EMA 등 지표와 동일 시점 → 점수 계산 기준
        // realtimePrice : 실시간 호가 → 손익 구간 판단 기준 (오판 방지)
        BigDecimal indicatorPrice = signal.getPrice().getBidPrice();
        BigDecimal realtimePrice  = checkCoinPrice(coinNm).getBidPrice();
        boolean isGoldenCross     = isGoldenCross(signal.getEma());

        BigDecimal totalCost             = account.getAvgBuyPrice()
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
            trailingPeakMap.remove(coinNm);
            positionEntryTimeMap.remove(coinNm);
            rsiPeakMap.remove(coinNm);
            registerProfitCooldown(coinNm, realtimeSellablePrice, totalCost, POST_PROFIT_COOLDOWN_HOT);
            executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice());
            return;
        }

        // ── RSI 피크 갱신 (포지션 보유 중 최고 RSI 추적) ────────────────
        BigDecimal currentRsi = signal.getRsi();
        rsiPeakMap.merge(coinNm, currentRsi, BigDecimal::max);
        BigDecimal rsiPeak = rsiPeakMap.get(coinNm);

        // ── BULL 모멘텀 소진 익절 (shortPhase+longPhase 모두 BULL 한정) ──
        // 조건 A: RSI < 50 + 수익 중 (모멘텀 붕괴 조기 탈출)
        // 조건 B: RSI 고점 대비 -7 이상 하락 + 수익 ≥ +0.1% (피크 후 되돌림 탈출)
        boolean bothBull  = shortPhase == MarketPhase.BULL && longPhase == MarketPhase.BULL;
        boolean profitAny = realtimeSellablePrice.compareTo(totalCost) > 0;
        boolean profitMin = realtimeSellablePrice.compareTo(totalCost.multiply(BULL_EXHAUST_MIN_PROFIT)) >= 0;
        boolean condA     = profitAny && currentRsi.compareTo(BULL_EXHAUST_RSI_ABS) < 0;
        boolean condB     = profitMin && rsiPeak.subtract(currentRsi).compareTo(BULL_EXHAUST_RSI_DROP) >= 0;

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
            trailingPeakMap.remove(coinNm);
            positionEntryTimeMap.remove(coinNm);
            rsiPeakMap.remove(coinNm);
            registerProfitCooldown(coinNm, realtimeSellablePrice, totalCost, POST_PROFIT_COOLDOWN_HOT);
            executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice());
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
        boolean longPhaseBull  = longPhase == MarketPhase.BULL;
        boolean isLossRange    = realtimeSellablePrice.compareTo(totalCost.multiply(BULL_RSI_STOP_MIN_LOSS)) <= 0;
        boolean rsiDropStop    = rsiPeak.subtract(currentRsi).compareTo(BULL_EXHAUST_RSI_DROP) >= 0;
        boolean rsiBelowMid    = currentRsi.compareTo(BULL_EXHAUST_RSI_ABS) < 0; // RSI < 50

        BigDecimal entryRsi        = entryRsiMap.getOrDefault(coinNm, currentRsi);
        boolean hadRealMomentum    = rsiPeak.subtract(entryRsi).compareTo(BULL_RSI_STOP_MIN_PEAK_RISE) >= 0;
        LocalDateTime entryTimeChk = positionEntryTimeMap.get(coinNm);
        boolean heldLongEnough     = entryTimeChk == null
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
            trailingPeakMap.remove(coinNm);
            positionEntryTimeMap.remove(coinNm);
            rsiPeakMap.remove(coinNm);
            executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice());
            return;
        }

        // effectPhase별 익절 기준 차등: BULL +0.6% / SIDEWAYS +0.7% / BEAR +0.4%
        BigDecimal profitThreshold;
        if      (effectPhase == MarketPhase.BULL)     profitThreshold = PROFIT_THRESHOLD_BULL;
        else if (effectPhase == MarketPhase.SIDEWAYS)  profitThreshold = PROFIT_THRESHOLD_SIDEWAYS;
        else                                           profitThreshold = PROFIT_THRESHOLD_BEAR;

        boolean isProfitRange = realtimeSellablePrice.compareTo(totalCost.multiply(profitThreshold)) >= 0;
        // 점수 손절 활성화: -0.9% 이상 손실 시 지표 점수 계산 시작
        boolean isStopRange   = realtimeSellablePrice.compareTo(totalCost.multiply(STOP_SCORE_ACTIVATE_RATE)) <= 0;

        int profitSellScore    = profitSellScore(signal, indicatorPrice, !isGoldenCross, isProfitRange);
        String profitBreakdown = profitScoreBreakdown(signal, indicatorPrice, !isGoldenCross, isProfitRange);
        int stopSellScore      = stopLossScore(signal, indicatorPrice, !isGoldenCross, isStopRange);
        String stopBreakdown   = stopScoreBreakdown(signal, indicatorPrice, !isGoldenCross, isStopRange);

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
            trailingPeakMap.remove(coinNm);
            positionEntryTimeMap.remove(coinNm);
            rsiPeakMap.remove(coinNm);
            executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice());
            return;
        }
        if (effectPhase == MarketPhase.SIDEWAYS && stopSellScore >= SELL_SCORE_THRESHOLD) {
            log.warn("{} 점수손절 [SIDE] 점수:{} [{}] RSI:{} 단기:{} 장기:{}",
                    coinNm, stopSellScore, stopBreakdown,
                    signal.getRsi().setScale(1, RoundingMode.HALF_UP), shortPhase, longPhase);
            trailingPeakMap.remove(coinNm);
            positionEntryTimeMap.remove(coinNm);
            rsiPeakMap.remove(coinNm);
            executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice());
            return;
        }
        if (effectPhase == MarketPhase.BEAR && stopSellScore >= SELL_SCORE_THRESHOLD - 1) {
            log.warn("{} 점수손절 [BEAR] 점수:{} [{}] RSI:{} 단기:{} 장기:{}",
                    coinNm, stopSellScore, stopBreakdown,
                    signal.getRsi().setScale(1, RoundingMode.HALF_UP), shortPhase, longPhase);
            trailingPeakMap.remove(coinNm);
            positionEntryTimeMap.remove(coinNm);
            rsiPeakMap.remove(coinNm);
            executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice());
            return;
        }

        // ── 점수 기반 익절: phase 무관 ≥4 고정 ──────────────────────────
        // 트레일링 활성 중이면 점수 익절 생략 — 트레일링이 더 높은 수익 확보 가능
        // (패스트 루프 트레일링이 슬로우 루프 점수보다 우선순위 상위)
        if (profitSellScore >= SELL_SCORE_THRESHOLD) {
            BigDecimal activePeak = trailingPeakMap.get(coinNm);
            if (activePeak != null) {
                log.info("{} 점수익절 스킵 — 트레일링 활성 중 (고점:{}) 점수:{} [{}]",
                        coinNm, activePeak.setScale(0, RoundingMode.HALF_UP),
                        profitSellScore, profitBreakdown);
            } else {
                log.info("{} 익절실행 [{}] 점수:{} [{}] RSI:{} 단기:{} 장기:{}",
                        coinNm, effectPhase, profitSellScore, profitBreakdown,
                        signal.getRsi().setScale(1, RoundingMode.HALF_UP), shortPhase, longPhase);
                trailingPeakMap.remove(coinNm);
                positionEntryTimeMap.remove(coinNm);
                rsiPeakMap.remove(coinNm);
                registerProfitCooldown(coinNm, realtimeSellablePrice, totalCost, POST_PROFIT_COOLDOWN_MINUTES);
                executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice());
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  최초 매수 — SHORT_BULL 전용, RSI 45~65 구간만 진입
    // ══════════════════════════════════════════════════════════════════
    private void firstPurchaseCoin(Set<String> holdCoinSet,
                                   Map<String, CoinSignalDto> signalMap,
                                   List<CoinAccount> accountList) {

        for (String coin : codeRepository.findAllCoinCode()) {
            if (holdCoinSet.contains(coin)) continue;

            // ── 이월잔고 방어 ─────────────────────────────────────────────
            // holdCoinSet은 일정 기준 이상 잔고만 포함 — API 지연·잔고 미반영 시
            // 소량 잔고가 누락될 수 있음. accountList를 직접 스캔해 이중 확인.
            // 잔고가 조금이라도 있으면 이월 포지션으로 간주하고 매수 스킵
            String currency = coin.replace("KRW-", "");
            boolean hasResidualBalance = accountList.stream()
                    .anyMatch(acc -> currency.equals(acc.getCoinName())
                                 && acc.getBalance().compareTo(BigDecimal.ZERO) > 0);
            if (hasResidualBalance) {
                log.warn("{} 이월잔고 감지 — holdCoinSet 미반영 소량 잔고 존재, 매수 스킵", coin);
                continue;
            }

            // ── 수동 당일 차단 코인 ─────────────────────────────────────────
            if (dailyBlacklistSet.contains(coin)) {
                log.info("{} 당일 차단 코인 - 매수 불가", coin);
                continue;
            }

            // ── 임시 시간 차단 (연속 손절 3회→20분 / 5회 이상→1h) ────────────
            LocalDateTime banUntil = temporaryBanUntilMap.get(coin);
            if (banUntil != null) {
                if (LocalDateTime.now().isBefore(banUntil)) {
                    long remainMin = java.time.Duration.between(LocalDateTime.now(), banUntil).toMinutes();
                    log.info("{} 임시차단 중 - 잔여 {}분", coin, remainMin + 1);
                    continue;
                } else {
                    temporaryBanUntilMap.remove(coin); // 만료 → 자동 해제
                }
            }

            CoinSignalDto signal = signalMap.get(coin);
            if (signal == null) continue;

            // ── 단기 국면 필터: SHORT_BEAR만 차단 (SHORT_BULL/SIDEWAYS 허용) ──────
            // (8/25 거래빈도 확대: SHORT_BULL 전용 → BEAR만 차단으로 완화.
            //  8/15-24 로그에서 "단기 국면 차단"이 전체 진입 시도의 절반 가까이를 막아
            //  거래빈도 저하의 가장 큰 원인이었음 — 하락 추세만 걸러내고 나머지는 하위 필터
            //  (RSI/EMA/BB)가 품질을 담당하도록 역할 재분배)
            if (signal.getShortPhase() == MarketPhase.BEAR) {
                log.info("{} 단기 국면 차단 [단기:BEAR — 하락 추세 진입 불가]", coin);
                continue;
            }

            // ── 장기 국면 필터: 60분봉 BEAR만 차단 (SIDEWAYS 허용) ──────────────
            // 기존: BULL 전용 → 거래가 너무 적음 (Aug 데이터: 하루 0~1회)
            // 변경: BEAR만 차단, SIDEWAYS에서도 단기 BULL이면 진입 허용
            // SIDEWAYS 진입 시 익절 임계는 SIDEWAYS 기준으로 자동 적용됨 (PROFIT_THRESHOLD_SIDEWAYS)
            if (signal.getPhase() == MarketPhase.BEAR) {
                log.info("{} 장기 국면 차단 [장기:BEAR — 하락 추세 진입 불가]",
                        coin);
                continue;
            }

            // ── EMA 구조 필터: 가격 ≥ EMA9×0.997 AND EMA9 ≥ EMA20×0.999 ──────
            // EMA9 > EMA20 : 단기 추세가 중기 추세 위 (구조 유지)
            // 가격 > EMA9  : 현재가가 단기 추세선 위로 복귀 (조정 이후 회복 확인)
            // EMA5 > EMA20 골든크로스보다 안정적 — EMA5(75분)는 노이즈 과민, EMA9(135분)은 완충
            // (8/25 거래빈도 확대: 엄격한 부등호 대신 0.1~0.3% 버퍼 허용 — 교차 직전/직후 진입 기회 확보)
            {
                BigDecimal ema9  = signal.getEma().get("ema9");
                BigDecimal ema20 = signal.getEma().get("ema20");
                BigDecimal bidPrice = signal.getPrice().getBidPrice();
                BigDecimal ema20Buffer = ema20.multiply(new BigDecimal("0.999"));
                BigDecimal ema9Buffer  = ema9.multiply(new BigDecimal("0.997"));
                if (ema9.compareTo(ema20Buffer) <= 0) {
                    log.info("{} EMA 구조 미달 [EMA9:{} ≤ EMA20:{}] - 진입 차단",
                            coin,
                            ema9.setScale(2, RoundingMode.HALF_UP),
                            ema20.setScale(2, RoundingMode.HALF_UP));
                    continue;
                }
                if (bidPrice.compareTo(ema9Buffer) <= 0) {
                    log.info("{} 가격 EMA9 미달 [가격:{} ≤ EMA9:{}] - 조정 미완료",
                            coin,
                            bidPrice.setScale(2, RoundingMode.HALF_UP),
                            ema9.setScale(2, RoundingMode.HALF_UP));
                    continue;
                }
            }

            // ── RSI 매수 구간 필터: RSI_BUY_MIN 이상 RSI_BUY_MAX 미만 ──────────
            // 과열(RSI_BUY_MAX 이상) 및 하락 모멘텀(RSI_BUY_MIN 미만) 모두 차단
            BigDecimal rsi = signal.getRsi();
            if (rsi.compareTo(RSI_BUY_MIN) < 0 || rsi.compareTo(RSI_BUY_MAX) >= 0) {
                log.info("{} RSI 매수 구간 이탈({}) - 보류 [허용: {}~{}]",
                        coin, rsi.setScale(1, RoundingMode.HALF_UP), RSI_BUY_MIN, RSI_BUY_MAX);
                continue;
            }

            // ── RSI 상승 방향 + 최소 상승폭 필터 ──────────────────────────
            // 직전 슬로우 루프 대비 RSI가 2.0pt 이상 상승해야 진입
            // ↑0.1, ↑0.2 같은 노이즈 수준 상승은 조정 완료로 보지 않음
            BigDecimal prevRsi = prevRsiMap.get(coin);
            if (prevRsi != null) {
                BigDecimal rsiRise = rsi.subtract(prevRsi);
                if (rsiRise.compareTo(RSI_RISE_MIN) < 0) {
                    log.info("{} RSI 상승폭 미달 진입 차단 (직전:{} → 현재:{}, 상승폭:{}pt < {}pt 기준)",
                            coin,
                            prevRsi.setScale(1, RoundingMode.HALF_UP),
                            rsi.setScale(1, RoundingMode.HALF_UP),
                            rsiRise.setScale(1, RoundingMode.HALF_UP),
                            RSI_RISE_MIN);
                    continue;
                }
            } else {
                // prevRsi 없음 = 당일 첫 진입 or 자정 리셋 직후 → 추세 방향 불명
                // 방향 정보 없이 RSI_BUY_MIN~MAX 어디서든 진입 가능하므로 안전 마진으로 밴드 중간값 요구
                // (8/25 거래빈도 확대에 맞춰 52 → 45로 하향 — 넓어진 밴드(40~65)의 중간값 수준)
                BigDecimal RSI_NO_HISTORY_MIN = new BigDecimal("45");
                if (rsi.compareTo(RSI_NO_HISTORY_MIN) < 0) {
                    log.info("{} RSI 방향 이력 없음 + RSI 낮음({}) → 진입 보류 (이력 없을 때 최소 {})",
                            coin, rsi.setScale(1, RoundingMode.HALF_UP), RSI_NO_HISTORY_MIN);
                    continue;
                }
            }

            // ── BB 위치 진입 필터 (70% 이상 → 고점 진입 차단) ────────────────
            {
                BigDecimal upper   = signal.getBb().get("upper");
                BigDecimal lower   = signal.getBb().get("lower");
                BigDecimal bidPrice = signal.getPrice().getBidPrice();
                BigDecimal bbRange = upper.subtract(lower);
                if (bbRange.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal bbPct = bidPrice.subtract(lower)
                            .divide(bbRange, 4, RoundingMode.HALF_UP);
                    if (bbPct.compareTo(BB_ENTRY_MAX_PCT) >= 0) {
                        log.info("{} BB 위치 차단 (BB위치: {}%, 상단70% 초과) - 고점 진입 위험",
                                coin, bbPct.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP));
                        continue;
                    }
                }
            }

            // ── 이전 거래 이력 조회 ─────────────────────────────────────────
            Optional<LastTrade> lastTradeOpt = lastTradeRepository.findByMarket(coin);

            // ── 익절 후 차등 쿨다운 (정상:3분 / 과열:10분 / 급등:15분) ────────
            LocalDateTime profitCoolUntil = profitCooldownUntilMap.get(coin);
            if (profitCoolUntil != null) {
                if (LocalDateTime.now().isBefore(profitCoolUntil)) {
                    long remainMin = java.time.Duration.between(LocalDateTime.now(), profitCoolUntil).toMinutes();
                    log.info("{} 익절 쿨다운 중 (잔여 {}분) - 재진입 차단", coin, remainMin + 1);
                    continue;
                } else {
                    profitCooldownUntilMap.remove(coin); // 만료 → 자동 해제
                }
            }

            // ── 손절 후 재진입 판단 (승률 기반 동적 쿨다운) ────────────────
            if (lastTradeOpt.isPresent() && lastTradeOpt.get().getLastDamagedAt() != null) {
                LocalDateTime lastDamagedAt = lastTradeOpt.get().getLastDamagedAt();
                int dropCount   = Optional.ofNullable(lastTradeOpt.get().getDropCount()).orElse(0);
                int profitCount = Optional.ofNullable(lastTradeOpt.get().getProfitCount()).orElse(0);
                int cooldownMinutes = calcCooldownMinutes(dropCount, profitCount);

                if (lastDamagedAt.isAfter(LocalDateTime.now().minusMinutes(cooldownMinutes))) {
                    int total = dropCount + profitCount;
                    String winRateStr = (total > 0)
                            ? String.format("%.0f%%", (double) profitCount / total * 100) : "-";
                    log.info("{} 손절 후 쿨다운 중 (승률:{}, {}분 대기) - 재진입 차단",
                            coin, winRateStr, cooldownMinutes);
                    continue;
                }
            }

            // ── 익절 후 재진입 가격 앵커 체크 ──────────────────────────────
            // 익절로 판매한 코인 재진입 시: 앵커가(평균매수가) 이하로 복귀해야 진입 허용
            // 앵커가 + 0.5% 이내는 RSI ≥ 54 AND RSI 상승 ≥ 3pt 일 때만 예외 허용
            // DB 기반 관리 — 앱 재시작 후에도 앵커 유지됨
            BigDecimal anchorPrice = lastTradeOpt.map(LastTrade::getProfitAnchorPrice).orElse(null);

            // ── 앵커 만료 체크 (PROFIT_ANCHOR_MAX_HOURS 경과 시 자동 해제) ──────
            // 만료 없이는 상승장에서 가격이 앵커보다 영구히 높게 유지되는 코인이
            // "익절 후 재진입 차단"에 무기한 걸려 매수 자체가 불가능해짐 (8/25-26 로그에서
            // ETH/SOL/XRP/DOGE/ADA/LINK가 전부 이 상태로 갇혀 XLM 1종만 거래되는 문제 확인)
            if (anchorPrice != null) {
                LocalDateTime tradedAt = lastTradeOpt.map(LastTrade::getTradedAt).orElse(null);
                if (tradedAt != null
                        && java.time.Duration.between(tradedAt, LocalDateTime.now()).toHours() >= PROFIT_ANCHOR_MAX_HOURS) {
                    log.info("{} 익절 후 재진입 앵커 만료 ({}시간 경과, 기준가:{}) — 해제하고 정상 진입 허용",
                            coin, PROFIT_ANCHOR_MAX_HOURS, anchorPrice.setScale(0, RoundingMode.HALF_UP));
                    lastTradeOpt.ifPresent(lt -> lastTradeRepository.save(lt.toBuilder().profitAnchorPrice(null).build()));
                    anchorPrice = null;
                }
            }

            if (anchorPrice != null) {
                BigDecimal currentBidPrice = signal.getPrice().getBidPrice();
                BigDecimal anchorCeil = anchorPrice.multiply(BigDecimal.ONE.add(PROFIT_REENTRY_MAX_PREMIUM));

                if (currentBidPrice.compareTo(anchorPrice) <= 0) {
                    // 앵커가 이하 복귀: 일반 진입 조건으로 허용
                    log.info("{} 익절 후 재진입 허용 — 현재가({}) ≤ 기준가({}) 복귀",
                            coin,
                            currentBidPrice.setScale(0, RoundingMode.HALF_UP),
                            anchorPrice.setScale(0, RoundingMode.HALF_UP));
                } else if (currentBidPrice.compareTo(anchorCeil) <= 0) {
                    // 앵커가 초과이나 허용 폭(+0.5%) 이내: 강한 지표 확인 시만 예외 허용
                    BigDecimal rsiRise = prevRsi != null ? rsi.subtract(prevRsi) : BigDecimal.ZERO;
                    boolean strongRsi  = rsi.compareTo(PROFIT_REENTRY_STRONG_RSI) >= 0;
                    boolean strongRise = rsiRise.compareTo(PROFIT_REENTRY_STRONG_RISE) >= 0;
                    if (!strongRsi || !strongRise) {
                        log.info("{} 익절 후 재진입 차단 — 기준가({}) 초과, 강한 지표 미달 (RSI:{} 상승:{}pt / 필요 RSI≥{} 상승≥{}pt)",
                                coin,
                                anchorPrice.setScale(0, RoundingMode.HALF_UP),
                                rsi.setScale(1, RoundingMode.HALF_UP),
                                rsiRise.setScale(1, RoundingMode.HALF_UP),
                                PROFIT_REENTRY_STRONG_RSI, PROFIT_REENTRY_STRONG_RISE);
                        continue;
                    }
                    BigDecimal premiumPct = currentBidPrice.subtract(anchorPrice)
                            .divide(anchorPrice, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
                    log.info("{} 익절 후 재진입 예외 허용 — 기준가({}) +{}% 초과이나 강한 지표 확인 (RSI:{} 상승:{}pt)",
                            coin,
                            anchorPrice.setScale(0, RoundingMode.HALF_UP),
                            premiumPct,
                            rsi.setScale(1, RoundingMode.HALF_UP),
                            rsiRise.setScale(1, RoundingMode.HALF_UP));
                } else {
                    // 앵커가 +0.5% 초과: 완전 차단
                    log.info("{} 익절 후 재진입 차단 — 현재가({}) > 기준가({}) +{}% 한도 초과",
                            coin,
                            currentBidPrice.setScale(0, RoundingMode.HALF_UP),
                            anchorPrice.setScale(0, RoundingMode.HALF_UP),
                            PROFIT_REENTRY_MAX_PREMIUM.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP));
                    continue;
                }
            }

            BigDecimal prevRsiLog = prevRsiMap.get(coin);
            log.info("{} 최초매수 RSI:{}{} [단기:{} 장기:{} EMA9>{} BB:{} 앵커:{}]",
                    coin,
                    rsi.setScale(1, RoundingMode.HALF_UP),
                    prevRsiLog != null
                            ? String.format("(↑%.1f)", rsi.subtract(prevRsiLog).doubleValue())
                            : "",
                    signal.getShortPhase(), signal.getPhase(),
                    signal.getEma().get("ema20").setScale(0, RoundingMode.HALF_UP),
                    bbPosition(signal),
                    anchorPrice != null ? anchorPrice.setScale(0, RoundingMode.HALF_UP) + "원" : "없음");
            OrdersResponse response = orderCoin(coin, "bid", MIN_ORDER_AMOUNT);
            positionEntryTimeMap.put(coin, LocalDateTime.now()); // 시간 손절용 진입 시각 기록
            entryRsiMap.put(coin, rsi); // RSI 모멘텀손절 오발동 방지용 진입 시점 RSI 기록
            tradeHistoryRepository.save(buyHistory(coin, MIN_ORDER_AMOUNT, signal));
            // 재매수 성공 → DB 앵커 해제 (profitAnchorPrice = null)
            lastTradeOpt.ifPresent(lt -> lastTradeRepository.save(lt.toBuilder().profitAnchorPrice(null).build()));
            askSuccessMessage(response);
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
        profitCooldownUntilMap.put(coinNm, until);
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
            case BULL     -> TRAILING_DROP_BULL;
            case SIDEWAYS -> TRAILING_DROP_SIDEWAYS;
            default       -> TRAILING_DROP_BEAR; // BEAR
        };
    }

    /**
     * 승률(dropCount:profitCount 비율) 기반 동적 쿨다운 계산
     * (8/25 거래빈도 확대: 하루 80~100건 목표에 맞춰 전 구간 단축)
     * <pre>
     *   dropCount < 2         → 샘플 부족, 기본 쿨다운 2분
     *   승률 >= 50%           → 2분   (정상 성과)
     *   승률 30% 이상 50% 미만 → 8분   (성과 저하 경고, 기존 15분 → 단축)
     *   승률 30% 미만          → 15분  (기존 30분 → 단축)
     * </pre>
     */
    private int calcCooldownMinutes(int dropCount, int profitCount) {
        if (dropCount < 2) return RE_ENTRY_COOLDOWN_MINUTES; // 샘플 부족 → 기본 2분

        double winRate = (double) profitCount / (profitCount + dropCount);
        if (winRate >= 0.5) return RE_ENTRY_COOLDOWN_MINUTES; // 2분
        if (winRate >= 0.3) return 8;                          // 8분 (기존 15분)
        return 15;                                             // 15분 (기존 30분)
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

    private boolean isGoldenCross(Map<String, BigDecimal> ema) {
        return ema.get("ema5").compareTo(ema.get("ema20")) > 0;
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
        if (price.compareTo(signal.getBb().get("lower")) < 0)   score += 2; // BB 하단 이탈
        if (isDeadCross)                                          score += 2; // 데드크로스
        if (price.compareTo(signal.getBb().get("middle")) < 0)  score += 1; // BB 중간선 이하
        if (signal.getRsi().compareTo(RSI_LOW) < 0)             score += 1; // RSI 과매도
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
        if (price.compareTo(signal.getBb().get("lower")) < 0)   parts.add("BB하단이탈+2");
        if (isDeadCross)                                          parts.add("데드크로스+2");
        if (price.compareTo(signal.getBb().get("middle")) < 0)  parts.add("BB중간이하+1");
        if (signal.getRsi().compareTo(RSI_LOW) < 0)             parts.add("RSI과매도+1");
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
        if (isDeadCross)                                        parts.add("데드크로스+2");
        if (price.compareTo(signal.getBb().get("middle")) >= 0) parts.add("BB중간+1");
        if (signal.getRsi().compareTo(RSI_OVERBOUGHT) > 0)      parts.add("RSI과매수+1");
        return String.join(" ", parts);
    }

    /**
     * BB 내 현재가 위치 — 최초 매수 로그용
     */
    private String bbPosition(CoinSignalDto signal) {
        BigDecimal price = signal.getPrice().getBidPrice();
        if (price.compareTo(signal.getBb().get("upper")) >= 0)  return "상단초과";
        if (price.compareTo(signal.getBb().get("middle")) >= 0) return "중간~상단";
        if (price.compareTo(signal.getBb().get("lower")) >= 0)  return "하단~중간";
        return "하단이탈";
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

    /**
     * 단기 국면 감지 (15분봉 EMA20 기울기 기반) — 주 매수 필터
     *
     * <p>Upbit API 반환 순서: index 0 = 최신봉, index size-1 = 가장 오래된 봉
     * → EMA는 oldest(size-1)부터 시작하여 newest(0)까지 순차 적용
     *
     * <p>임계값 0.05%: 60분봉(0.15%)보다 낮게 설정 — 15분봉은 변동폭이 작아
     * 동일 기준 적용 시 항상 SIDEWAYS 판정될 수 있음
     *
     * <pre>
     *   slope >  0.05% → SHORT_BULL  (단기 상승 추세)
     *   slope < -0.05% → SHORT_BEAR  (단기 하락 추세)
     *   그 외           → SHORT_SIDE  (횡보, SIDEWAYS)
     * </pre>
     */
    private MarketPhase detectShortTermPhase(List<CandleResponse> candles) {
        try {
            if (candles == null || candles.size() < 20) {
                return MarketPhase.SIDEWAYS;
            }

            List<BigDecimal> prices = candles.stream()
                    .map(CandleResponse::getTradePrice)
                    .toList();

            BigDecimal mult = new BigDecimal("2")
                    .divide(BigDecimal.valueOf(21), 10, RoundingMode.HALF_UP); // EMA20

            // oldest(size-1)부터 EMA 계산 시작
            BigDecimal ema     = prices.get(prices.size() - 1);
            BigDecimal prevEma = null;

            for (int i = prices.size() - 2; i >= 0; i--) {
                if (i == 0) {
                    prevEma = ema; // 최신봉 바로 이전 EMA 저장 (기울기 계산용)
                }
                ema = prices.get(i).multiply(mult)
                        .add(ema.multiply(BigDecimal.ONE.subtract(mult)));
            }

            // slope = (최신 EMA - 직전 EMA) / 직전 EMA * 100
            BigDecimal slope = ema.subtract(prevEma)
                    .divide(prevEma, 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            if (slope.compareTo(new BigDecimal("0.05")) > 0)  return MarketPhase.BULL;
            if (slope.compareTo(new BigDecimal("-0.05")) < 0) return MarketPhase.BEAR;
            return MarketPhase.SIDEWAYS;

        } catch (Exception e) {
            log.warn("단기 국면 감지 실패: {}", e.getMessage());
            return MarketPhase.SIDEWAYS;
        }
    }

    private Map<String, BigDecimal> calculateEmaCross(List<CandleResponse> candles) {
        if (candles == null || candles.isEmpty()) {
            return Map.of("ema5", BigDecimal.ZERO, "ema9", BigDecimal.ZERO, "ema20", BigDecimal.ZERO);
        }

        List<BigDecimal> prices = candles.stream()
                .map(CandleResponse::getTradePrice)
                .toList();

        BigDecimal mult5  = new BigDecimal("2").divide(BigDecimal.valueOf(6),  10, RoundingMode.HALF_UP); // EMA5  : 2/(5+1)
        BigDecimal mult9  = new BigDecimal("2").divide(BigDecimal.valueOf(10), 10, RoundingMode.HALF_UP); // EMA9  : 2/(9+1)
        BigDecimal mult20 = new BigDecimal("2").divide(BigDecimal.valueOf(21), 10, RoundingMode.HALF_UP); // EMA20 : 2/(20+1)

        BigDecimal seed = prices.get(prices.size() - 1);
        BigDecimal ema5  = seed;
        BigDecimal ema9  = seed;
        BigDecimal ema20 = seed;

        for (int i = prices.size() - 2; i >= 0; i--) {
            BigDecimal p = prices.get(i);
            ema5  = p.multiply(mult5 ).add(ema5 .multiply(BigDecimal.ONE.subtract(mult5)));
            ema9  = p.multiply(mult9 ).add(ema9 .multiply(BigDecimal.ONE.subtract(mult9)));
            ema20 = p.multiply(mult20).add(ema20.multiply(BigDecimal.ONE.subtract(mult20)));
        }

        return Map.of("ema5", ema5, "ema9", ema9, "ema20", ema20);
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

                // ── 연속 손절 카운트 → 3회: 20분 차단 / 5회: 1시간 차단 ──
                // (8/25 거래빈도 확대: 하루 80~100건 목표에서는 손절 몇 번만으로 당일 블랙리스트에
                //  넣으면 거래 기회 자체가 사라짐 — 임계값을 올리고 "당일 블랙리스트"가 아닌
                //  "짧은 임시차단"으로 완화. 대신 아래 일일 누적 카운트가 진짜 부진 코인을 걸러냄)
                // 패-승-패-패: profit 시 카운트 0으로 리셋 → 최대 2 → 차단 미발동
                int lossCount = consecutiveLossMap.merge(coinNm, 1, Integer::sum);
                if (lossCount == 3) {
                    LocalDateTime banUntil = LocalDateTime.now().plusMinutes(20);
                    temporaryBanUntilMap.put(coinNm, banUntil);
                    log.warn("{} 연속 손절 3회 → 20분 차단 (해제: {})",
                            coinNm, banUntil.toString().replace("T", " ").substring(0, 16));
                } else if (lossCount >= 5) {
                    LocalDateTime banUntil = LocalDateTime.now().plusHours(1);
                    temporaryBanUntilMap.put(coinNm, banUntil);
                    consecutiveLossMap.remove(coinNm); // 차단 등록 후 카운트 정리
                    log.warn("{} 연속 손절 {}회 → 1시간 차단 (해제: {})",
                            coinNm, lossCount, banUntil.toString().replace("T", " ").substring(0, 16));
                }

                // ── 일일 누적 손절 카운트 → 8회 달성 시 당일 블랙리스트 ──────
                // 연속손절 카운터와 달리 이익이 끼어도 리셋되지 않음
                // (8/25 거래빈도 확대: 3회 → 8회로 상향 — 하루 거래량 자체가 늘어난 만큼
                //  절대 손절 횟수 기준도 비례해서 올려야 정상 변동성까지 블랙리스트로 막지 않음)
                // 목적: 소액 이익 1회가 카운터를 리셋하고 계속 진입하는 패턴 차단
                if (!dailyBlacklistSet.contains(coinNm)) {
                    int totalDailyLoss = dailyTotalLossMap.merge(coinNm, 1, Integer::sum);
                    if (totalDailyLoss >= 8) {
                        dailyBlacklistSet.add(coinNm);
                        log.warn("{} 일일 누적 손절 {}회 → 당일 블랙리스트 (자정 해제) [연속과 무관]",
                                coinNm, totalDailyLoss);
                    }
                }
                return;
            }

            if (type.equals("profit")) {
                LastTrade lt = profitTrade(coinNm, amount, sellUnitPrice, signal)
                        .toBuilder()
                        .dropCount(lastDropCount)
                        .profitCount(lastProfitCount + 1)
                        .profitAnchorPrice(avgBuyPrice) // 익절 시 평균매수가 기록 → 재진입 기준가격으로 활용
                        .build();
                lastTradeRepository.save(lt);
                log.info("{} 익절 후 재진입 앵커 저장 (기준가: {}원) — 앱 재시작 후에도 유지됨",
                        coinNm, avgBuyPrice.setScale(0, RoundingMode.HALF_UP));
                tradeHistoryRepository.save(history.toBuilder().tradeType("익절").build());

                // 익절 시 연속 손절 카운터 초기화 (손절 패턴 끊김)
                consecutiveLossMap.put(coinNm, 0);
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
    /**
     * 매일 05:00 코인 목록 갱신 — 하이브리드 선정 (고정 메이저 + 동적 알트)
     *
     * <pre>
     * 고정 메이저 (3개): ETH, SOL, XRP — 유동성·안정성 보장, 항상 포함
     * 동적 알트   (최대 11개): DYNAMIC_COIN_WHITELIST 내 거래량 상위 후보 중 수익 이력 점수로 선정
     *                    (XLM 포함 — 과거 고정 메이저였으나 8/15-24 로그 승률 15%로 저하되어
     *                     동적 풀로 이동, 성과 기반 자동 배제 대상이 됨)
     *
     * 동적 점수 계산:
     *   기본점수 = VOLUME_TOP_N - 거래량 순위  (거래량 1위 → 높은 점수)
     *   이력보정 = (승률 - 0.5) × 20           (샘플 3건 이상일 때만 적용)
     *   제외조건 = 손절 5회 이상 && 익절 1회 이하
     *
     * 카운트 초기화:
     *   신규 진입 코인만 초기화 — 유지 코인의 이력은 보존하여 다음 갱신에 반영
     * </pre>
     */
    @Scheduled(cron = "0 0 0/6 * * *", zone = "Asia/Seoul")  // 00:00, 06:00, 12:00, 18:00
    @Transactional
    public void refreshCoinList() {
        log.info("=== 코인 목록 갱신 시작 (하이브리드: 고정3 + 동적최대11) ===");
        try {
            // ── 0. 이전 목록 스냅샷 (신규 진입 코인 판별용) ─────────────────
            Set<String> prevCoins = new HashSet<>(codeRepository.findAllCoinCode());

            // ── 1. 전체 KRW 마켓 조회 ────────────────────────────────────────
            MarketResponse[] markets = restTemplate.getForObject(
                    coinUriBuilder.upbitMarkets(), MarketResponse[].class);
            if (ObjectUtils.isEmpty(markets)) {
                log.warn("마켓 목록 조회 실패 - 갱신 중단");
                return;
            }

            // 고정 메이저 (BTC 제외 — 3분 단타 기준 변동폭 부족)
            // XLM 제외 (8/15-24 로그 재분석 결과 20건 거래 중 17건 손절, 승률 15% —
            //  가격대가 낮아(~250원) 1틱 변동폭이 커 RSI가 가격 정체 중에도 노이즈로 출렁이고,
            //  그 노이즈로 잦은 손절이 발생. 과거 May27-31 구간의 우량 판정은 최근 국면과 불일치.
            //  DYNAMIC_COIN_WHITELIST에는 남겨둬 거래량·승률이 회복되면 동적 선정으로 자동 재진입 가능,
            //  반대로 계속 부진하면 손절 5회↑&익절 1회↓ 필터로 자동 배제됨 — 고정 메이저처럼 영구 고정되지 않음)
            List<String> majors = List.of("KRW-ETH", "KRW-SOL", "KRW-XRP");

            List<String> krwMarkets = Arrays.stream(markets)
                    .map(MarketResponse::getMarket)
                    .filter(m -> m.startsWith("KRW-"))
                    .filter(m -> !COIN_EXCLUSIONS.contains(m))
                    .filter(m -> !majors.contains(m))         // 메이저는 동적 풀에서 제외
                    .filter(DYNAMIC_COIN_WHITELIST::contains) // 화이트리스트 코인만 허용
                    .toList();

            // ── 2. 티커(24h 거래대금) 일괄 조회 — 50개씩 배치 ───────────────
            List<CoinTickerResponse> allTickers = new ArrayList<>();
            for (int i = 0; i < krwMarkets.size(); i += 50) {
                List<String> batch = krwMarkets.subList(i, Math.min(i + 50, krwMarkets.size()));
                CoinTickerResponse[] batchResult = restTemplate.getForObject(
                        coinUriBuilder.upbitTicker(String.join(",", batch)),
                        CoinTickerResponse[].class);
                if (!ObjectUtils.isEmpty(batchResult)) {
                    allTickers.addAll(Arrays.asList(batchResult));
                }
            }

            if (allTickers.isEmpty()) {
                log.warn("티커 조회 결과 없음 - 갱신 중단");
                return;
            }

            // ── 3. 최소 거래대금 필터 + 거래량 상위 VOLUME_TOP_N개 추출 ──────
            List<CoinTickerResponse> topByVolume = allTickers.stream()
                    .filter(t -> t.getAccTradePrice24h() != null)
                    .filter(t -> t.getAccTradePrice24h().compareTo(MIN_VOLUME_24H) >= 0)
                    // 24h 변동률 필터: 폭락(-8% 미만) 및 급등(+25% 초과) 코인 제외
                    .filter(t -> {
                        BigDecimal cr = t.getSignedChangeRate();
                        if (cr == null) return true; // null이면 통과 (보수적 처리)
                        boolean ok = cr.compareTo(COIN_24H_CHANGE_MIN) >= 0
                                  && cr.compareTo(COIN_24H_CHANGE_MAX) <= 0;
                        if (!ok) log.info("{} 24h변동률 제외 ({}%) — 폭락/급등 구간",
                                t.getMarket(),
                                cr.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP));
                        return ok;
                    })
                    .sorted(Comparator.comparing(CoinTickerResponse::getAccTradePrice24h).reversed())
                    .limit(VOLUME_TOP_N)
                    .toList();

            // ── 4. 동적 점수 산정 ─────────────────────────────────────────────
            Map<String, Integer> scoreMap = new LinkedHashMap<>();
            for (int rank = 0; rank < topByVolume.size(); rank++) {
                String market = topByVolume.get(rank).getMarket();
                int score = VOLUME_TOP_N - rank;  // 거래량 순위 기본 점수 (1위 = 최고점)

                Optional<LastTrade> lt = lastTradeRepository.findByMarket(market);
                if (lt.isPresent()) {
                    int profitCnt = Optional.ofNullable(lt.get().getProfitCount()).orElse(0);
                    int dropCnt   = Optional.ofNullable(lt.get().getDropCount()).orElse(0);

                    // 손절 과다 코인 동적 풀 제외
                    if (dropCnt >= 5 && profitCnt <= 1) {
                        log.info("{} 동적 후보 제외 - 손절 과다 (손절:{}, 익절:{})", market, dropCnt, profitCnt);
                        continue;
                    }

                    // 승률 보정 — 샘플 3건 이상일 때만 적용 (소수 샘플 편향 방지)
                    int total = profitCnt + dropCnt;
                    if (total >= 3) {
                        double winRate = (double) profitCnt / total;
                        score += (int) ((winRate - 0.5) * 20);  // 50% 기준 ±10점
                    }
                }
                scoreMap.put(market, score);
            }

            // ── 5. 점수 상위 후보 중 캔들 충분한 코인 5개 동적 선정 ─────────
            // 상장 초기 코인(KRW-SOON 등)은 캔들 수 부족으로 지표 계산 불가
            // → 선정 단계에서 사전 차단하여 슬로우 루프 WARN 반복 방지
            int dynamicSlots = MAX_COIN_SLOTS - majors.size();  // 8 - 4 = 4
            List<String> dynamicSelected = new ArrayList<>();
            List<Map.Entry<String, Integer>> sortedCandidates = scoreMap.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .toList();

            for (Map.Entry<String, Integer> entry : sortedCandidates) {
                if (dynamicSelected.size() >= dynamicSlots) break;
                String market = entry.getKey();
                try {
                    List<CandleResponse> c3  = candleResponses(market, 3, 22);
                    List<CandleResponse> c15 = candleResponses(market, 15, 30);
                    List<CandleResponse> c60 = candleResponses(market, 60, 50);
                    if (isInvalid(c3, 15) || isInvalid(c15, 20) || isInvalid(c60, 40)) {
                        log.info("{} 동적 후보 제외 - 캔들 부족 (상장 초기 또는 거래 중단)", market);
                        continue;
                    }

                    // ── 품질 필터 ①: BB 폭 — 변동성 극심 코인 제외 ────────────────
                    Map<String, BigDecimal> bb = calculateBollingerBands(c3);
                    BigDecimal bbMiddle = bb.get("middle");
                    if (bbMiddle.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal bbWidth = bb.get("upper").subtract(bb.get("lower"))
                                .divide(bbMiddle, 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100));
                        if (bbWidth.compareTo(COIN_BB_WIDTH_MAX) > 0) {
                            log.info("{} 동적 후보 제외 - BB폭 과대 ({}%) — 변동성 극심",
                                    market, bbWidth.setScale(1, RoundingMode.HALF_UP));
                            continue;
                        }
                    }

                    // ── 품질 필터 ②: 최소 현재가 — 극저가 코인 제외 ──────────────
                    // 10원 미만 코인: 호가 단위(0.01원) 대비 변동폭이 너무 커
                    // 예) MBL 1.69원 → 1틱 = 0.59%, 목표 +0.8% 도달에 2틱 필요
                    BigDecimal curPrice  = c60.get(0).getTradePrice();
                    if (curPrice.compareTo(COIN_MIN_PRICE) < 0) {
                        log.info("{} 동적 후보 제외 - 현재가 {}원 < 최소{}원 (호가 스프레드 과대)",
                                market, curPrice, COIN_MIN_PRICE);
                        continue;
                    }

                    // ── 품질 필터 ③: 1시간 변동률 — 단기 급등락 코인 제외 ──────────
                    // c60: index 0 = 가장 최근 캔들, index 1 = 1시간 전 캔들
                    BigDecimal prevPrice = c60.get(1).getTradePrice();
                    if (prevPrice.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal change1h = curPrice.subtract(prevPrice)
                                .divide(prevPrice, 4, RoundingMode.HALF_UP);
                        if (change1h.compareTo(COIN_1H_CHANGE_MIN) < 0) {
                            log.info("{} 동적 후보 제외 - 1h 급락 ({}%) — 단기 하락 추세",
                                    market, change1h.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP));
                            continue;
                        }
                        if (change1h.compareTo(COIN_1H_CHANGE_MAX) > 0) {
                            log.info("{} 동적 후보 제외 - 1h 급등 ({}%) — 단기 고점 위험",
                                    market, change1h.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP));
                            continue;
                        }
                    }

                    // ── 품질 필터 ④: 장기 EMA20 기울기 — 하락 추세 코인 제외 ────────
                    // detectMarketPhase(c60): BULL = EMA20 기울기 양수, BEAR/SIDEWAYS = 제외
                    MarketPhase longPhase = detectMarketPhase(c60);
                    if (longPhase == MarketPhase.BEAR) {
                        log.info("{} 동적 후보 제외 - 장기 EMA20 하락 ({})", market, longPhase);
                        continue;
                    }

                } catch (Exception e) {
                    log.info("{} 동적 후보 제외 - 캔들 조회 실패: {}", market, e.getMessage());
                    continue;
                }
                dynamicSelected.add(market);
            }

            // ── 6. 최종 목록 = 고정 메이저 + 동적 알트 ─────────────────────
            Set<String> finalSelected = new LinkedHashSet<>();
            finalSelected.addAll(majors);
            finalSelected.addAll(dynamicSelected);

            if (finalSelected.size() < majors.size()) {
                log.warn("동적 선정 코인 없음 - 메이저만으로 유지");
            }

            // ── 7. coin_code 테이블 교체 ──────────────────────────────────────
            codeRepository.deleteAllInBatch();
            codeRepository.saveAll(finalSelected.stream()
                    .map(m -> CoinCode.builder().coinCode(m).build())
                    .toList());

            // ── 8. 신규 진입 코인만 카운트 초기화 ───────────────────────────
            //    유지 코인의 누적 이력은 보존 → 다음 갱신 점수에 반영
            List<String> newlyAdded = finalSelected.stream()
                    .filter(m -> !prevCoins.contains(m))
                    .toList();
            if (!newlyAdded.isEmpty()) {
                lastTradeRepository.resetCountsByMarkets(newlyAdded);
                log.info("신규 진입 코인 카운트 초기화: {}", newlyAdded);
            }

            log.info("=== 코인 목록 갱신 완료 [고정:{} 동적:{} 신규초기화:{}] ===",
                    majors, dynamicSelected, newlyAdded);

        } catch (Exception e) {
            log.error("코인 목록 갱신 중 오류 발생: {}", e.getMessage(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  일일 통계 초기화 (매일 자정)
    // ══════════════════════════════════════════════════════════════════

    /**
     * 자정에 당일 블랙리스트·연속손절 맵·트레일링 맵을 초기화한다.
     *
     * <p>블랙리스트는 "당일" 단위로 동작 — 어제 연속 손절 코인도 오늘 새벽 refreshCoinList
     * 갱신 후 새로운 조건으로 다시 평가받도록 자정에 해제한다.
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void resetDailyStats() {
        int blacklistSize = dailyBlacklistSet.size();
        int tempBanSize   = temporaryBanUntilMap.size();
        dailyBlacklistSet.clear();
        temporaryBanUntilMap.clear();
        consecutiveLossMap.clear();
        dailyTotalLossMap.clear();
        trailingPeakMap.clear();
        rsiPeakMap.clear();
        profitCooldownUntilMap.clear();
        log.info("=== 일일 통계 초기화 완료 — 당일퇴출 {}개·임시차단 {}개 해제, 연속손절·트레일링 맵 초기화 ===",
                blacklistSize, tempBanSize);
    }
}
