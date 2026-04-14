package com.coin.coin.service;

import com.coin.coin.common.MarketPhase;
import com.coin.coin.config.UpbitJwtGenerator;
import com.coin.coin.dto.*;
import com.coin.coin.dto.response.*;
import com.coin.coin.entity.CoinCode;
import com.coin.coin.entity.LastTrade;
import com.coin.coin.entity.TradeHistory;
import com.coin.coin.repository.CoinCodeRepository;
import com.coin.coin.repository.LastTradeRepository;
import com.coin.coin.repository.TradeHistoryRepository;
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
    private final KakaoService messageService;

    // ─── 매매 임계값 상수 ──────────────────────────────────────────────
    private static final BigDecimal PROFIT_THRESHOLD = new BigDecimal("1.007");  // +0.7% 익절 (손익비 개선)
    private static final BigDecimal STOP_LOSS_LIMIT  = new BigDecimal("0.990");  // -1.0% 손절
    private static final BigDecimal MAX_INVEST_BULL  = new BigDecimal("25000");  // BULL 최대 투자금
    private static final BigDecimal MAX_INVEST_SIDE  = new BigDecimal("20000");  // SIDEWAYS/BEAR 최대 투자금
    private static final String ADD_ORDER_AMOUNT = "5000";
    private static final String MIN_ORDER_AMOUNT = "10000";

    // ─── 지표 임계값 상수 ──────────────────────────────────────────────
    private static final BigDecimal RSI_OVERBOUGHT = BigDecimal.valueOf(70);
    private static final BigDecimal RSI_MID        = BigDecimal.valueOf(60);
    private static final BigDecimal RSI_ENTRY_MIN  = BigDecimal.valueOf(45);  // 매수 RSI 하한 (30~44 진입 차단)
    private static final BigDecimal RSI_LOW        = BigDecimal.valueOf(30);

    // ─── 점수 임계값 ───────────────────────────────────────────────────
    private static final int BUY_SCORE_THRESHOLD = 3;
    private static final int SELL_SCORE_THRESHOLD = 4;

    // ─── 동적 코인 선정 설정 ──────────────────────────────────────────
    private static final int MAX_COIN_SLOTS = 8;        // 최대 보유 코인 종류
    private static final int VOLUME_TOP_N   = 20;       // 거래량 상위 N개 후보
    /** 선정 대상에서 제외할 마켓 (스테이블코인 등) */
    private static final Set<String> COIN_EXCLUSIONS = Set.of(
            "KRW-USDT", "KRW-USDC", "KRW-DAI", "KRW-BTC"
    );

    @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void tradeCoin() {
        List<CoinAccount> accountList = checkCoinAccount();

        Set<String> holdCoinSet = accountList.stream()
                .map(a -> a.getCoinType() + "-" + a.getCoinName())
                .collect(Collectors.toSet());

        // ── 1) 코인별 지표를 한 번씩만 조회해 Map에 적재 ──────────────
        Map<String, CoinSignalDto> signalMap = buildSignalMap();

        // ── 2) 미보유 코인 최초 매수 ──────────────────────────────────
        firstPurchaseCoin(holdCoinSet, signalMap);

        // ── 3) 보유 코인 매도 / 추가매수 판단 ─────────────────────────
        for (CoinAccount account : accountList) {
            String coinNm = account.getCoinType() + "-" + account.getCoinName();
            if (coinNm.equals("KRW-KRW")) {
                continue;
            }

            CoinSignalDto signal = signalMap.get(coinNm);
            if (signal == null) {
                log.warn("{} 지표 데이터 없음, 스킵", coinNm);
                continue;
            }
            evaluateHoldCoin(account, coinNm, signal);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  지표 Map 빌드 (캔들 조회 최소화)
    // ══════════════════════════════════════════════════════════════════
    private Map<String, CoinSignalDto> buildSignalMap() {
        Map<String, CoinSignalDto> map = new HashMap<>();

        for (String coin : codeRepository.findAllCoinCode()) {
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
    //  보유 코인 매도 / 추가매수 평가
    // ══════════════════════════════════════════════════════════════════
    private void evaluateHoldCoin(CoinAccount account, String coinNm, CoinSignalDto signal) {

        MarketPhase phase = signal.getPhase();
        BigDecimal currentPrice = signal.getPrice().getBidPrice();
        boolean isGoldenCross = isGoldenCross(signal.getEma());

        BigDecimal totalCost = account.getAvgBuyPrice()
                .multiply(account.getBalance()).setScale(0, RoundingMode.CEILING);
        BigDecimal sellablePrice = currentPrice.multiply(account.getBalance());
        BigDecimal profitLine = totalCost.multiply(PROFIT_THRESHOLD);
        BigDecimal damagedLine = totalCost.multiply(STOP_LOSS_LIMIT);

        boolean isProfitRange = sellablePrice.compareTo(profitLine) >= 0;
        int profitSellScore = profitSellScore(signal, currentPrice,
                !isGoldenCross, isProfitRange);

        boolean isDamageRange = sellablePrice.compareTo(damagedLine) <= 0;
        int stopScore = stopLossScore(currentPrice, signal, isDamageRange, !isGoldenCross);

        int buyScore = calcBuyScore(signal, currentPrice);
        log.info("{} 코인 익절 점수 : {}, 손절 점수 {}, 추가구매 점수 {}",
                coinNm, profitSellScore, stopScore, buyScore);

        // ── 익절 판단 ──────────────────────────────────────────────────
        if (phase == MarketPhase.BULL && profitSellScore >= SELL_SCORE_THRESHOLD + 1) {
            log.info("{} 익절 실행 - 매도점수: {}", coinNm, profitSellScore);
            executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal);
            return;
        }

        if (phase == MarketPhase.SIDEWAYS && profitSellScore >= SELL_SCORE_THRESHOLD) {
            log.info("{} 익절 실행 - 매도점수: {}", coinNm, profitSellScore);
            executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal);
            return;
        }

        if (phase == MarketPhase.BEAR && profitSellScore >= SELL_SCORE_THRESHOLD - 1) {
            log.info("{} 익절 실행 - 매도점수: {}", coinNm, profitSellScore);
            executeSell(coinNm, account.getBalance().toPlainString(), "profit", signal);
            return;
        }

        // ── 손절 판단 ──────────────────────────────────────────────────
        // BEAR: 빠른 손절 (score >= 3), SIDEWAYS: 중간 (score >= 4), BULL: 여유 (score >= 5)
        if (phase == MarketPhase.BEAR && stopScore >= SELL_SCORE_THRESHOLD - 1) {
            log.warn("{} 손절 실행 [BEAR], 점수 : {}", coinNm, stopScore);
            executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal);
            return;
        }

        if (phase == MarketPhase.SIDEWAYS && stopScore >= SELL_SCORE_THRESHOLD) {
            log.warn("{} 손절 실행 [SIDEWAYS], 점수 : {}", coinNm, stopScore);
            executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal);
            return;
        }

        if (phase == MarketPhase.BULL && stopScore >= SELL_SCORE_THRESHOLD + 1) {
            log.warn("{} 손절 실행 [BULL], 점수 : {}", coinNm, stopScore);
            executeSell(coinNm, account.getBalance().toPlainString(), "damage", signal);
            return;
        }

        // ── 추가매수 판단 ──────────────────────────────────────────────
        // 골든크로스 필수 조건
        if (!isGoldenCross) {
            log.info("{} 골든크로스 미충족 - 추가매수 보류", coinNm);
            return;
        }
        // BEAR: 점수 4 이상, SIDEWAYS/BULL: 점수 3 이상 + phase별 최대 투자금
        int addBuyMinScore = (phase == MarketPhase.BEAR) ? BUY_SCORE_THRESHOLD + 1 : BUY_SCORE_THRESHOLD;
        BigDecimal maxInvest = (phase == MarketPhase.BULL) ? MAX_INVEST_BULL : MAX_INVEST_SIDE;

        if (buyScore >= addBuyMinScore && totalCost.compareTo(maxInvest) < 0) {
            log.info("{} 추가매수 실행 - 매수점수: {}", coinNm, buyScore);
            OrdersResponse response = orderCoin(coinNm, "bid", ADD_ORDER_AMOUNT);
            tradeHistoryRepository.save(buyHistory(coinNm, ADD_ORDER_AMOUNT, signal));
            askSuccessMessage(response);
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

            MarketPhase phase = signal.getPhase();
            boolean isGoldenCross = isGoldenCross(signal.getEma());
            BigDecimal currentPrice = signal.getPrice().getBidPrice();

            // ── 골든크로스 필수 조건 ──────────────────────────────────
            if (!isGoldenCross) {
                log.info("{} 골든크로스 미충족 - 최초 매수 보류", coin);
                continue;
            }

            int buyScore = calcBuyScore(signal, currentPrice);
            // ── LastTrade 기반 재진입 필터 ─────────────────────────────
            Optional<LastTrade> lastTradeOpt = lastTradeRepository.findByMarket(coin);
            if (lastTradeOpt.isPresent()) {
                LastTrade lastTrade = lastTradeOpt.get();

                // ── 손절 후 30분 재진입 차단 ──────────────────────────
                if (lastTrade.getLastDamagedAt() != null &&
                        lastTrade.getLastDamagedAt().isAfter(LocalDateTime.now().minusMinutes(30))) {
                    log.info("{} 손절 후 30분 냉각 중 - 재진입 차단", coin);
                    continue;
                }

                if (phase == MarketPhase.BEAR && lastTrade.getDropCount() >= 3 && buyScore < BUY_SCORE_THRESHOLD + 2) {
                    log.info("{} 손절 3회이상 시장 BEAR, 매수점수 5점 못넘김 ({}) - 최초 매수 보류", coin, buyScore);
                    continue;
                }

                if (phase == MarketPhase.SIDEWAYS && lastTrade.getDropCount() >= 3 && buyScore < BUY_SCORE_THRESHOLD + 1) {
                    log.info("{} 손절 3회이상, 시장 SIDEWAYS 매수점수 4점 못넘김 ({}) - 최초 매수 보류", coin, buyScore);
                    continue;
                }

                if (phase == MarketPhase.BULL && lastTrade.getDropCount() >= 3 && buyScore < BUY_SCORE_THRESHOLD) {
                    log.info("{} 손절 3회이상, 시장 BULL 매수점수 3점 못넘김 ({}) - 최초 매수 보류", coin, buyScore);
                    continue;
                }
            }

            // ── 볼린저 매수 점수 검증 ──────────────────────────────────
            if (phase == MarketPhase.BEAR) {
                if (buyScore < BUY_SCORE_THRESHOLD + 1) {
                    log.info("{} BEAR 상태 매수점수 4점 미만 - 최초 매수 보류", coin);
                    continue;
                }
            }

            if (buyScore < BUY_SCORE_THRESHOLD) {
                log.info("{} 매수 점수 3점 미만 - 최초 매수 보류", coin);
                continue;
            }

            log.info("{} 최초 매수 진행 - 매수점수:{}", coin, buyScore);
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
     * 매수 점수 (최대 5점, 골든크로스는 필수 조건으로 별도 체크)
     * - RSI 구간              +2  (BULL: 45~70, SIDEWAYS/BEAR: 45~60)
     * - 볼린저 하단 터치       +2
     * - 볼린저 중간선 아래      +1
     */
    private int calcBuyScore(CoinSignalDto signal, BigDecimal price) {
        int score = 0;
        // BULL 상승장에서는 RSI 60~70 구간도 정상 모멘텀으로 진입 허용
        BigDecimal rsiUpperBound = (signal.getPhase() == MarketPhase.BULL) ? RSI_OVERBOUGHT : RSI_MID;
        if (signal.getRsi().compareTo(RSI_ENTRY_MIN) > 0 && signal.getRsi().compareTo(rsiUpperBound) < 0) {
            score += 2;
        }
        if (price.compareTo(signal.getBb().get("lower")) <= 0) {
            score += 2;
        }
        if (price.compareTo(signal.getBb().get("middle")) < 0) {
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
                .stream().map(CoinAccount::coinAccount).collect(Collectors.toList());
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

    private void executeSell(String coinNm, String volume, String type, CoinSignalDto signal) {
        OrdersResponse response = orderCoin(coinNm, "ask", volume);
        try {
            Thread.sleep(2000);
            OrderResponse result = checkCoin(response.getUuid());
            BigDecimal avgPrice = orderPrice(coinNm).get("bidPrice");
            BigDecimal amount = new BigDecimal(result.getExecutedVolume()).multiply(avgPrice);
            int lastDropCount = lastTradeRepository.findByMarket(coinNm)
                    .map(LastTrade::getDropCount).orElse(0);
            int lastProfitCount = lastTradeRepository.findByMarket(coinNm)
                    .map(LastTrade::getProfitCount).orElse(0);

            log.info("{} 판매 완료 - 체결금액:{} type:{}", coinNm, amount, type);
            TradeHistory history = sellHistory(coinNm, amount, signal);

            if (type.equals("damage")) {
                LastTrade lt = damageTrade(coinNm, amount, avgPrice, signal)
                        .toBuilder()
                        .dropCount(lastDropCount + 1)
                        .profitCount(lastProfitCount)
                        .build();
                lastTradeRepository.save(lt);
                tradeHistoryRepository.save(history.toBuilder().tradeType("손절").build());
                return;
            }

            if (type.equals("profit")) {
                LastTrade lt = profitTrade(coinNm, amount, avgPrice, signal)
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
                    .collect(Collectors.toList());

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

            // 3. 거래대금 기준 상위 VOLUME_TOP_N개 추출
            List<CoinTickerResponse> top = allTickers.stream()
                    .filter(t -> t.getAccTradePrice24h() != null)
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
                    .collect(Collectors.toList());

            if (selected.isEmpty()) {
                log.warn("선정된 코인 없음 - 갱신 중단");
                return;
            }

            // 6. coin_code 테이블 교체
            codeRepository.deleteAllInBatch();
            List<CoinCode> newCodes = selected.stream()
                    .map(m -> CoinCode.builder().coinCode(m).build())
                    .collect(Collectors.toList());
            codeRepository.saveAll(newCodes);

            log.info("=== 코인 목록 갱신 완료: {} ===", selected);

        } catch (Exception e) {
            log.error("코인 목록 갱신 중 오류 발생: {}", e.getMessage(), e);
        }
    }
}
