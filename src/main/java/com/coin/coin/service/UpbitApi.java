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
    /** 매수 허용 RSI 하한 — 48 미만은 모멘텀 약화 구간, 반등 신뢰도 낮음 */
    private static final BigDecimal RSI_BUY_MIN = BigDecimal.valueOf(48);
    /** 매수 허용 RSI 상한 — 63 이상은 과열 진입 위험, 고점 매수 방지 */
    private static final BigDecimal RSI_BUY_MAX = BigDecimal.valueOf(63);

    // ─── 손익 임계값 상수 ──────────────────────────────────────────────
    /**
     * 점수 손절 활성화 기준: 이 비율 이하 손실 시 슬로우 루프에서 지표 점수 계산 시작 (-0.9%)
     * 점수가 역치 미달이면 포지션 유지 → 강제손절(HARD_STOP_RATE)까지 홀딩
     */
    private static final BigDecimal STOP_SCORE_ACTIVATE_RATE  = new BigDecimal("0.991");
    /** 강제 손절: 지표와 무관하게 이 비율 이하이면 패스트 루프에서 즉시 매도 (-1.4%) */
    private static final BigDecimal HARD_STOP_RATE            = new BigDecimal("0.986");
    /** BULL 국면 점수 익절 기준: +0.8% (상승 추세 — 작은 수익도 빠르게 확정) */
    private static final BigDecimal PROFIT_THRESHOLD_BULL     = new BigDecimal("1.008");
    /** SIDEWAYS 국면 점수 익절 기준: +1.0% (횡보 — 충분한 쿠션 후 실현) */
    private static final BigDecimal PROFIT_THRESHOLD_SIDEWAYS = new BigDecimal("1.010");
    /** BEAR 국면 점수 익절 기준: +0.5% (약세 전환 시 빠른 이탈 우선) */
    private static final BigDecimal PROFIT_THRESHOLD_BEAR     = new BigDecimal("1.005");

    // ─── 트레일링 스탑 설정 ───────────────────────────────────────────
    /** 트레일링 활성화 기준: 투자금 대비 이 비율 이상 수익 시 추적 시작 (+0.8%) */
    private static final BigDecimal TRAILING_ACTIVATE_RATE   = new BigDecimal("1.008");
    /** BULL 국면 트레일링 낙폭: 고점 대비 -0.5% — 상승 추세 출렁임 허용, 더 길게 추적 */
    private static final BigDecimal TRAILING_DROP_BULL       = new BigDecimal("0.005");
    /** SIDEWAYS 국면 트레일링 낙폭: 고점 대비 -0.45% — 중립 기준 */
    private static final BigDecimal TRAILING_DROP_SIDEWAYS   = new BigDecimal("0.0045");
    /** BEAR 국면 트레일링 낙폭: 고점 대비 -0.35% — 약세 빠른 수익 확보 우선 */
    private static final BigDecimal TRAILING_DROP_BEAR       = new BigDecimal("0.0035");

    // ─── 지표 임계값 상수 ──────────────────────────────────────────────
    /** 익절 점수 RSI 가산 기준: RSI > 70 시 과매수 +1점 */
    private static final BigDecimal RSI_OVERBOUGHT = BigDecimal.valueOf(70);
    /** 손절 점수 RSI 가산 기준: RSI < 30 시 과매도 +1점 */
    private static final BigDecimal RSI_LOW        = BigDecimal.valueOf(30);

    // ─── 점수 임계값 ──────────────────────────────────────────────────
    // 익절: phase 무관 ≥ 4 고정 (phase별 차등은 trailing DROP rate로 담당)
    // 손절: BULL ≥ 5 / SIDEWAYS ≥ 4 / BEAR ≥ 3 (약세일수록 빠른 손절)
    private static final int SELL_SCORE_THRESHOLD = 4;

    // ─── Circuit Breaker (일일 손실 한도) ────────────────────────────
    /**
     * 일일 실현손익 한도 (KRW) — 이 금액 이하 손실 시 봇 완전 정지
     * 총 운용 자본의 약 5% 수준으로 설정 권장 (예: 자본 20만원 → -10,000원)
     */
    private static final BigDecimal DAILY_LOSS_HALT_KRW = new BigDecimal("-10000");

    // ─── 재진입 쿨다운 설정 ───────────────────────────────────────────
    /** 손절 직후 최소 대기 시간 (이후 승률 기반 쿨다운 적용) */
    private static final int RE_ENTRY_COOLDOWN_MINUTES  = 3;
    /** 익절 직후 재진입 차단 시간 (SHORT_BULL 전용 진입이므로 짧게 유지) */
    private static final int POST_PROFIT_COOLDOWN_MINUTES = 3;

    // ─── 동적 코인 선정 설정 ──────────────────────────────────────────
    private static final int MAX_COIN_SLOTS = 8;
    private static final int VOLUME_TOP_N   = 20;
    /** 24h 최소 거래대금 (KRW) — 이 미만 코인은 유동성 부족으로 제외 */
    private static final BigDecimal MIN_VOLUME_24H = new BigDecimal("10000000000"); // 100억원
    /** 선정 대상에서 제외할 마켓 (스테이블코인·BTC) */
    private static final Set<String> COIN_EXCLUSIONS = Set.of(
            "KRW-USDT", "KRW-USDC", "KRW-DAI", "KRW-BTC"
    );

    // ─── 지표 캐시 (슬로우 루프가 3분마다 갱신, 패스트 루프가 참조) ─────
    /** volatile: 참조 교체가 원자적으로 보장됨 (슬로우 루프 갱신 → 패스트 루프 즉시 가시) */
    private volatile Map<String, CoinSignalDto> cachedSignalMap = Collections.emptyMap();

    // ─── 트레일링 스탑 / 연속 손절 추적 맵 ──────────────────────────
    /** 코인별 트레일링 고점 평가금액 — 패스트 루프에서 30초마다 갱신 */
    private final Map<String, BigDecimal>   trailingPeakMap     = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 당일 연속 손절 횟수 — 2회→임시차단(1h), 3회→당일 퇴출 */
    private final Map<String, Integer>      consecutiveLossMap   = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * 임시 시간 차단 코인 — 연속 손절 시 등록, 만료 시각(LocalDateTime) 저장
     * · 연속 손절 2회 → now + 1시간
     * · 연속 손절 3회 → now + 5시간 (6h 갱신 주기와 맞물려 자연 재평가)
     */
    private final Map<String, LocalDateTime> temporaryBanUntilMap = new java.util.concurrent.ConcurrentHashMap<>();
    /** 당일 매수 완전 차단 코인 집합 — 현재 연속손절 외 수동 차단 등 확장용, 자정에 초기화 */
    private final Set<String>               dailyBlacklistSet    = java.util.concurrent.ConcurrentHashMap.newKeySet();

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

        // ── 강제 손절: -1.4% (지표 무관, 패스트 루프 즉시 처리) ─────────
        if (profitRate.compareTo(HARD_STOP_RATE) <= 0) {
            log.warn("{} 강제손절 (-1.4%) 평가:{} 투자:{} [단기:{} RSI:{}]",
                    coinNm, sellablePrice.setScale(0, RoundingMode.HALF_UP), totalCost,
                    signal.getShortPhase(), signal.getRsi().setScale(1, RoundingMode.HALF_UP));
            trailingPeakMap.remove(coinNm);
            executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice());
            return;
        }

        // ── 트레일링 익절: +0.8% 진입 후 국면별 낙폭 초과 시 매도 ─────
        // BULL -0.5% / SIDEWAYS -0.45% / BEAR -0.35%
        if (profitRate.compareTo(TRAILING_ACTIVATE_RATE) >= 0) {
            MarketPhase shortPhase  = signal.getShortPhase();
            MarketPhase longPhase   = signal.getPhase();
            MarketPhase effectPhase = (shortPhase != MarketPhase.SIDEWAYS) ? shortPhase : longPhase;
            BigDecimal dropRate     = trailingDropRate(effectPhase);

            BigDecimal peak = trailingPeakMap.getOrDefault(coinNm, sellablePrice);
            if (sellablePrice.compareTo(peak) > 0) {
                trailingPeakMap.put(coinNm, sellablePrice);
                peak = sellablePrice;
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
                executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice());
                return;
            }
            log.info("{} 트레일링모드 고점:{} 현재:{} 스탑라인:{} [{}국면 DROP-{}%]",
                    coinNm,
                    peak.setScale(0, RoundingMode.HALF_UP),
                    sellablePrice.setScale(0, RoundingMode.HALF_UP),
                    trailingStopLine.setScale(0, RoundingMode.HALF_UP),
                    effectPhase, dropRate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP));
        } else {
            trailingPeakMap.remove(coinNm); // 아직 트레일링 미진입 구간 → 리셋
        }
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

        // 강제 손절: -1.4%
        if (profitRate.compareTo(HARD_STOP_RATE) <= 0) {
            log.warn("{} [정지중] 강제손절 (-1.4%) 평가:{} 투자:{} [단기:{} RSI:{}]",
                    coinNm, sellablePrice.setScale(0, RoundingMode.HALF_UP), totalCost,
                    signal.getShortPhase(), signal.getRsi().setScale(1, RoundingMode.HALF_UP));
            trailingPeakMap.remove(coinNm);
            executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice());
            return;
        }

        // 트레일링 익절 (정지 상태에서도 기존 고점 추적 유지, 국면별 DROP 적용)
        if (profitRate.compareTo(TRAILING_ACTIVATE_RATE) >= 0) {
            MarketPhase shortPhase  = signal.getShortPhase();
            MarketPhase longPhase   = signal.getPhase();
            MarketPhase effectPhase = (shortPhase != MarketPhase.SIDEWAYS) ? shortPhase : longPhase;
            BigDecimal dropRate     = trailingDropRate(effectPhase);

            BigDecimal peak = trailingPeakMap.getOrDefault(coinNm, sellablePrice);
            if (sellablePrice.compareTo(peak) > 0) {
                trailingPeakMap.put(coinNm, sellablePrice);
                peak = sellablePrice;
            }
            BigDecimal trailingStopLine = peak.multiply(BigDecimal.ONE.subtract(dropRate));
            if (sellablePrice.compareTo(trailingStopLine) <= 0) {
                log.info("{} [정지중] 트레일링익절 고점:{} 현재:{} [{}국면 DROP-{}%]",
                        coinNm,
                        peak.setScale(0, RoundingMode.HALF_UP),
                        sellablePrice.setScale(0, RoundingMode.HALF_UP),
                        effectPhase, dropRate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP));
                trailingPeakMap.remove(coinNm);
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

        // effectPhase별 익절 기준 차등: BULL +0.8% / SIDEWAYS +1.0% / BEAR +0.5%
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
            executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice());
            return;
        }
        if (effectPhase == MarketPhase.SIDEWAYS && stopSellScore >= SELL_SCORE_THRESHOLD) {
            log.warn("{} 점수손절 [SIDE] 점수:{} [{}] RSI:{} 단기:{} 장기:{}",
                    coinNm, stopSellScore, stopBreakdown,
                    signal.getRsi().setScale(1, RoundingMode.HALF_UP), shortPhase, longPhase);
            trailingPeakMap.remove(coinNm);
            executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice());
            return;
        }
        if (effectPhase == MarketPhase.BEAR && stopSellScore >= SELL_SCORE_THRESHOLD - 1) {
            log.warn("{} 점수손절 [BEAR] 점수:{} [{}] RSI:{} 단기:{} 장기:{}",
                    coinNm, stopSellScore, stopBreakdown,
                    signal.getRsi().setScale(1, RoundingMode.HALF_UP), shortPhase, longPhase);
            trailingPeakMap.remove(coinNm);
            executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal, account.getAvgBuyPrice());
            return;
        }

        // ── 점수 기반 익절: phase 무관 ≥4 고정 ──────────────────────────
        // phase별 차등은 trailing DROP rate가 담당 — 점수 임계는 단순 고정
        if (profitSellScore >= SELL_SCORE_THRESHOLD) {
            log.info("{} 익절실행 [{}] 점수:{} [{}] RSI:{} 단기:{} 장기:{}",
                    coinNm, effectPhase, profitSellScore, profitBreakdown,
                    signal.getRsi().setScale(1, RoundingMode.HALF_UP), shortPhase, longPhase);
            trailingPeakMap.remove(coinNm);
            executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal, account.getAvgBuyPrice());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  최초 매수 — SHORT_BULL 전용, RSI 45~65 구간만 진입
    // ══════════════════════════════════════════════════════════════════
    private void firstPurchaseCoin(Set<String> holdCoinSet,
                                   Map<String, CoinSignalDto> signalMap) {

        for (String coin : codeRepository.findAllCoinCode()) {
            if (holdCoinSet.contains(coin)) continue;

            // ── 수동 당일 차단 코인 ─────────────────────────────────────────
            if (dailyBlacklistSet.contains(coin)) {
                log.info("{} 당일 차단 코인 - 매수 불가", coin);
                continue;
            }

            // ── 임시 시간 차단 (연속 손절 2회→1h / 3회 이상→5h) ────────────
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

            // ── 단기 국면 필터: SHORT_BULL에서만 진입 ──────────────────────
            // SHORT_BULL = 15분봉 EMA20 기울기 상승 → 단기 모멘텀 확인된 구간만 진입
            if (signal.getShortPhase() != MarketPhase.BULL) {
                log.info("{} 단기 국면 차단 [단기:{} — SHORT_BULL 전용]",
                        coin, signal.getShortPhase());
                continue;
            }

            // ── RSI 매수 구간 필터: 45 이상 65 미만 ────────────────────────
            // 45 미만: 과매도 진입 후 추가 하락 위험 (반등 신뢰도 낮음)
            // 65 이상: 과매수 진입 → 고점 매수 위험
            BigDecimal rsi = signal.getRsi();
            if (rsi.compareTo(RSI_BUY_MIN) < 0 || rsi.compareTo(RSI_BUY_MAX) >= 0) {
                log.info("{} RSI 매수 구간 이탈({}) - 보류 [허용: {}~{}]",
                        coin, rsi.setScale(1, RoundingMode.HALF_UP), RSI_BUY_MIN, RSI_BUY_MAX);
                continue;
            }

            // ── 이전 거래 이력 조회 ─────────────────────────────────────────
            Optional<LastTrade> lastTradeOpt = lastTradeRepository.findByMarket(coin);

            // ── 익절 후 쿨다운 (3분) ────────────────────────────────────────
            if (lastTradeOpt.isPresent()) {
                LastTrade lt = lastTradeOpt.get();
                if ("profit".equals(lt.getSellType()) && lt.getTradedAt() != null
                        && lt.getTradedAt().isAfter(
                                LocalDateTime.now().minusMinutes(POST_PROFIT_COOLDOWN_MINUTES))) {
                    log.info("{} 익절 후 쿨다운 중 ({}분 대기) - 재진입 차단",
                            coin, POST_PROFIT_COOLDOWN_MINUTES);
                    continue;
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

            log.info("{} 최초매수 RSI:{} [단기:{} 장기:{} BB:{}]",
                    coin, rsi.setScale(1, RoundingMode.HALF_UP),
                    signal.getShortPhase(), signal.getPhase(), bbPosition(signal));
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
     * <pre>
     *   dropCount < 2         → 샘플 부족, 기본 쿨다운 3분
     *   승률 >= 50%           → 3분   (정상 성과)
     *   승률 30% 이상 50% 미만 → 30분  (성과 저하 경고)
     *   승률 30% 미만          → 2시간 (해당 코인 당일 사실상 거래 중단)
     * </pre>
     */
    private int calcCooldownMinutes(int dropCount, int profitCount) {
        if (dropCount < 2) return RE_ENTRY_COOLDOWN_MINUTES; // 샘플 부족 → 기본 3분

        double winRate = (double) profitCount / (profitCount + dropCount);
        if (winRate >= 0.5) return RE_ENTRY_COOLDOWN_MINUTES; // 3분
        if (winRate >= 0.3) return 30;                         // 30분
        return 120;                                            // 2시간
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
        parts.add("수익구간+3");
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

                // ── 연속 손절 카운트 → 2회: 1시간 차단 / 3회 이상: 5시간 차단 ──
                int lossCount = consecutiveLossMap.merge(coinNm, 1, Integer::sum);
                if (lossCount == 2) {
                    LocalDateTime banUntil = LocalDateTime.now().plusHours(1);
                    temporaryBanUntilMap.put(coinNm, banUntil);
                    log.warn("{} 연속 손절 2회 → 1시간 차단 (해제: {})",
                            coinNm, banUntil.toString().replace("T", " ").substring(0, 16));
                } else if (lossCount >= 3) {
                    LocalDateTime banUntil = LocalDateTime.now().plusHours(5);
                    temporaryBanUntilMap.put(coinNm, banUntil); // 기존 1h 차단 → 5h로 연장
                    log.warn("{} 연속 손절 {}회 → 5시간 차단 (해제: {})",
                            coinNm, lossCount, banUntil.toString().replace("T", " ").substring(0, 16));
                }
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
     * 동적 알트   (5개): 거래량 상위 후보 중 수익 이력 점수로 선정
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
        log.info("=== 코인 목록 갱신 시작 (하이브리드: 고정3 + 동적5) ===");
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
            List<String> majors = List.of("KRW-ETH", "KRW-SOL", "KRW-XRP");

            List<String> krwMarkets = Arrays.stream(markets)
                    .map(MarketResponse::getMarket)
                    .filter(m -> m.startsWith("KRW-"))
                    .filter(m -> !COIN_EXCLUSIONS.contains(m))
                    .filter(m -> !majors.contains(m))  // 메이저는 동적 풀에서 제외
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
            int dynamicSlots = MAX_COIN_SLOTS - majors.size();  // 8 - 3 = 5
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
        trailingPeakMap.clear();
        log.info("=== 일일 통계 초기화 완료 — 당일퇴출 {}개·임시차단 {}개 해제, 연속손절·트레일링 맵 초기화 ===",
                blacklistSize, tempBanSize);
    }
}
