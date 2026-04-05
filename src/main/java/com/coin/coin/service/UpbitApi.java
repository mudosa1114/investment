package com.coin.coin.service;

import com.coin.coin.common.MarketPhase;
import com.coin.coin.config.UpbitJwtGenerator;
import com.coin.coin.dto.CoinAccount;
import com.coin.coin.dto.TradeRequest;
import com.coin.coin.dto.UriBuilderDto;
import com.coin.coin.dto.response.*;
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
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.NoSuchAlgorithmException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void tradeCoin() {
        BigDecimal dropThreshold = new BigDecimal("0.985");
        BigDecimal profitThreshold = new BigDecimal("1.003");
        BigDecimal stopLossLimit = new BigDecimal("0.975");
        BigDecimal maxInvestCost = new BigDecimal("30000");
        BigDecimal todayRealizedLoss = BigDecimal.ZERO;

        String addOrderAmount = "5000";

        List<CoinAccount> accountList = checkCoinAccount();

        Set<String> holdCoinList = new HashSet<>(
                accountList.stream()
                        .map(acc -> acc.getCoinType() + "-" + acc.getCoinName())
                        .toList()
        );

        boolean isLowLiquidity = isLowLiquidityTime();
        if (!isLowLiquidity) {
            firstPurchaseCoin(holdCoinList);
        }

        MarketPhase marketPhase = detectMarketPhase();

        for (CoinAccount account : accountList) {
            String holdCoinNm = account.getCoinType() + "-" + account.getCoinName();
            if (holdCoinNm.equals("KRW-KRW")) {
                continue;
            }

            Map<String, BigDecimal> prices = orderPrice(holdCoinNm);
            BigDecimal rsi = calculateRsi(holdCoinNm);
            BigDecimal totalCost = account.getAvgBuyPrice().multiply(account.getBalance()).setScale(0, RoundingMode.CEILING);
            BigDecimal sellablePrice = prices.get("bidPrice").multiply(account.getBalance());
            BigDecimal profit = totalCost.multiply(profitThreshold);
            BigDecimal addPurchase = totalCost.multiply(dropThreshold);
            BigDecimal finStopLoss = totalCost.multiply(stopLossLimit);

            // 0.3% 이상 수익이 발생한 경우 익절
            boolean isProfitRange = sellablePrice.compareTo(profit) >= 0;
            if (isProfitRange) {
                String volume = account.getBalance().toPlainString();
                String type = "profit";
                executeSell(holdCoinNm, volume, type, rsi);
            }

            // 2.5% 이상 하락 + rsi 지수 30 미만 또는 1.5% 이상 하락한 상태에서 rsi 가 65을 초과하는 경우 손절
            boolean isDecreaseLoss = sellablePrice.compareTo(finStopLoss) <= 0
                    && rsi.compareTo(BigDecimal.valueOf(30)) < 0;
            boolean isHighRsi = sellablePrice.compareTo(addPurchase) < 0
                    && rsi.compareTo(BigDecimal.valueOf(65)) > 0;
            if (isDecreaseLoss || isHighRsi) {
                log.warn("{} 손절 매도 실행 - 3% 초과 하락", holdCoinNm);
                String volume = account.getBalance().toPlainString();
                String type = "damage";
                executeSell(holdCoinNm, volume, type, rsi);
            }

            // 거래가 활발하지 않을 시간 + BEAR 시장에서 추가매수 패스
            if (isLowLiquidity || marketPhase == MarketPhase.BEAR) {
                log.info("매수/매도 실행없이 관망");
                continue;
            }

            // 1.5% 내 손실 && 최대 구매가능 금액 안넘기면 추가구매
            boolean isDropped = sellablePrice.compareTo(addPurchase) >= 0
                    && totalCost.compareTo(maxInvestCost) <= 0;
            boolean isOversold = rsi.compareTo(BigDecimal.valueOf(50)) <= 0
                    && rsi.compareTo(BigDecimal.valueOf(30)) >= 0;
            if (isDropped && isOversold) {
                OrdersResponse response = orderCoin(holdCoinNm, "bid", addOrderAmount);
                log.info("{} 코인 손실로 인한 추가구매", holdCoinNm);
                tradeHistoryRepository.save(buyHistory(holdCoinNm, addOrderAmount, rsi));
                askSuccessMessage(response);
            }
        }
    }

    private void firstPurchaseCoin(Set<String> holdCoinList) {
        codeRepository.findAllCoinCode().stream()
                .filter(catalog -> !holdCoinList.contains(catalog))
                .forEach(catalog -> {
                    BigDecimal rsi = calculateRsi(catalog);
                    if (rsi.compareTo(BigDecimal.valueOf(60)) > 0) {
                        log.info("{} rsi 수치 높으므로 구매 보류", catalog);
                        return;
                    }

                    Map<String, BigDecimal> prices = orderPrice(catalog);
                    Optional<LastTrade> lastTradeObj = lastTradeRepository.findByMarket(catalog);
                    if (lastTradeObj.isPresent()) {
                        LastTrade lastTrade = lastTradeObj.get();
                        boolean rsiConditionNotMet = rsi.compareTo(lastTrade.getRsi().subtract(new BigDecimal("5"))) >= 0 ||
                                rsi.compareTo(BigDecimal.valueOf(30)) < 0;
                        if (lastTrade.getDropCount() >= 1 && rsiConditionNotMet) {
                            log.info("{} 손절 1회 이상, rsi 조건 미충족", catalog);
                            return;
                        }

                        // 케이스 A: 하락 후 재진입 - 가격 1% 하락 + RSI 안정권
                        boolean isPriceDropped = prices.get("bidPrice")
                                .compareTo(lastTrade.getAvgPrice().multiply(new BigDecimal("0.99"))) < 0;
                        boolean isRsiStable = rsi.compareTo(BigDecimal.valueOf(30)) > 0;
                        boolean caseA = isPriceDropped && isRsiStable;

                        // 케이스 B: 상승 추세 재진입 - RSI 5p 하락 + 가격이 익절가 ±2% 이내
                        boolean isRsiCooledDown = rsi
                                .compareTo(lastTrade.getRsi().subtract(new BigDecimal("5"))) <= 0;
                        boolean isPriceNearProfit = prices.get("bidPrice")
                                .compareTo(lastTrade.getAvgPrice().multiply(new BigDecimal("1.01"))) <= 0;
                        boolean caseB = isRsiCooledDown && isPriceNearProfit;

                        if (!caseA || !caseB) {
                            log.info("{} 익절 후 재구매 조건 미충족", catalog);
                            return;
                        }
                    }

                    String minAmount = "10000";
                    log.info("{} 미보유 코인, 최소금액 구매 진행.", catalog);
                    OrdersResponse response = orderCoin(catalog, "bid", minAmount);
                    tradeHistoryRepository.save(buyHistory(catalog, minAmount, rsi));

                    if (lastTradeObj.isPresent()) {
                        LastTrade lastTrade = lastTradeObj.get();
                        lastTrade = lastTrade.toBuilder().dropCount(0).build();
                        lastTradeRepository.save(lastTrade);
                    }
                    askSuccessMessage(response);
                });
    }


    private void executeSell(String coinNm, String volume, String type, BigDecimal rsi) {
        OrdersResponse response = orderCoin(coinNm, "ask", volume);
        try {
            Thread.sleep(2000);
            OrderResponse result = checkCoin(response.getUuid());

            BigDecimal avgPrice = orderPrice(coinNm).get("bidPrice");
            BigDecimal amount = new BigDecimal(result.getExecutedVolume())
                    .multiply(avgPrice);
            log.info("{} 판매 완료, 체결금액: {}", coinNm, amount);

            TradeHistory tradeHistory = sellHistory(coinNm, amount, rsi);
            if (type.equals("damage")) {
                tradeHistory = tradeHistory.toBuilder().tradeType("손절").build();
                lastTradeRepository.save(damageTrade(coinNm, amount, avgPrice, rsi));
                tradeHistoryRepository.save(tradeHistory);
                return;
            }

            if (type.equals("profit")) {
                tradeHistory = tradeHistory.toBuilder().tradeType("익절").build();
                lastTradeRepository.save(profitTrade(coinNm, amount, avgPrice, rsi));
                tradeHistoryRepository.save(tradeHistory);
            }


        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Sell interrupted", e);
        }
    }

    private Map<String, BigDecimal> orderPrice(String market) {
        OrderBookResponse[] responses = restTemplate.getForObject(coinUriBuilder.upbitOrderBook(market), OrderBookResponse[].class);

        List<OrderBookResponse> solCoinList = Optional.ofNullable(responses)
                .map(Arrays::asList)
                .orElse(Collections.emptyList());

        Map<String, BigDecimal> priceMap = new HashMap<>();
        priceMap.put("askPrice", solCoinList.get(0).getOrderBookUnits().get(0).getAskPrice());
        priceMap.put("bidPrice", solCoinList.get(0).getOrderBookUnits().get(0).getBidPrice());

        return priceMap;
    }

    private List<CoinAccount> checkCoinAccount() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwtGenerator.upbitJwtToken());
        headers.set("accept", "application/json");

        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<AccountResponse[]> response = restTemplate.exchange(
                coinUriBuilder.upbitAccount(),
                HttpMethod.GET,
                entity,
                AccountResponse[].class
        );

        List<AccountResponse> accountResponse = Optional.ofNullable(response.getBody())
                .map(Arrays::asList)
                .orElse(Collections.emptyList());

        List<CoinAccount> accountList = new ArrayList<>();
        for (AccountResponse account : accountResponse) {
            accountList.add(CoinAccount.coinAccount(account));
        }

        return accountList;
    }

    private OrderResponse checkCoin(String uuid) {
        String queryString = "uuid=" + uuid;

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwtGenerator.upbitJwtTokenWithQuery(queryString));
        headers.set("accept", "application/json");

        HttpEntity<String> entity = new HttpEntity<>(headers);

        return restTemplate.exchange(
                coinUriBuilder.upbitOrder(uuid),
                HttpMethod.GET,
                entity,
                OrderResponse.class
        ).getBody();
    }

    private OrdersResponse orderCoin(String market, String side, String value) {
        TradeRequest tradeRequest = TradeRequest.builder().build();
        if (side.equals("bid")) {
            tradeRequest = tradeRequest.toBuilder()
                    .market(market)
                    .side(side)
                    .price(value)
                    .ordType("price")
                    .build();
        }

        if (side.equals("ask")) {
            tradeRequest = tradeRequest.toBuilder()
                    .market(market)
                    .side(side)
                    .volume(value)
                    .ordType("market")
                    .build();
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + jwtGenerator.upbitOrderToken(tradeRequest));
            headers.set("accept", "application/json");

            HttpEntity<TradeRequest> entity = new HttpEntity<>(tradeRequest, headers);

            ResponseEntity<OrdersResponse> response = restTemplate.exchange(
                    coinUriBuilder.upbitOrder(),
                    HttpMethod.POST,
                    entity,
                    OrdersResponse.class);

            return response.getBody();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    private BigDecimal calculateRsi(String market) {
        int period = 14;
        CandleResponse[] candles = restTemplate.getForObject(
                coinUriBuilder.upbitCandles(market, period + 1),
                CandleResponse[].class
        );

        if (candles == null || candles.length < period + 1) {
            log.warn("{} RSI 계산 불가 - 캔들 데이터 부족", market);
            return BigDecimal.valueOf(50); // 중립값 반환
        }

        List<BigDecimal> closePrices = Arrays.stream(candles)
                .map(CandleResponse::getTradePrice)
                .collect(Collectors.toList());
        Collections.reverse(closePrices);

        BigDecimal avgGain = BigDecimal.ZERO;
        BigDecimal avgLoss = BigDecimal.ZERO;

        for (int i = 1; i <= period; i++) {
            BigDecimal change = closePrices.get(i).subtract(closePrices.get(i - 1));
            if (change.compareTo(BigDecimal.ZERO) > 0) {
                avgGain = avgGain.add(change);
            } else {
                avgLoss = avgLoss.add(change.abs());
            }
        }

        avgGain = avgGain.divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);
        avgLoss = avgLoss.divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);

        if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.valueOf(100);
        }

        BigDecimal rs = avgGain.divide(avgLoss, 10, RoundingMode.HALF_UP);

        return BigDecimal.valueOf(100)
                .subtract(BigDecimal.valueOf(100)
                        .divide(BigDecimal.ONE.add(rs), 10, RoundingMode.HALF_UP));
    }

    private MarketPhase detectMarketPhase() {
        try {
            int period = 22;
            CandleResponse[] candles = restTemplate.getForObject(
                    coinUriBuilder.upbitCandles("KRW-BTC", period),
                    CandleResponse[].class
            );

            if (candles == null || candles.length < period) return MarketPhase.SIDEWAYS;

            List<BigDecimal> prices = Arrays.stream(candles)
                    .map(CandleResponse::getTradePrice)
                    .collect(Collectors.toList());
            Collections.reverse(prices);

            // EMA20 계산
            BigDecimal multiplier = new BigDecimal("2")
                    .divide(BigDecimal.valueOf(20 + 1), 10, RoundingMode.HALF_UP);
            BigDecimal ema = prices.get(0);
            for (int i = 1; i < 20; i++) {
                ema = prices.get(i).multiply(multiplier)
                        .add(ema.multiply(BigDecimal.ONE.subtract(multiplier)));
            }

            // 직전 EMA vs 최신 EMA 기울기
            BigDecimal prevEma = ema;
            BigDecimal latestEma = prices.get(19).multiply(multiplier)
                    .add(prevEma.multiply(BigDecimal.ONE.subtract(multiplier)));

            BigDecimal slope = latestEma.subtract(prevEma)
                    .divide(prevEma, 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            BigDecimal bullThreshold = new BigDecimal("0.05");
            BigDecimal bearThreshold = new BigDecimal("-0.05");

            if (slope.compareTo(bullThreshold) > 0) return MarketPhase.BULL;
            if (slope.compareTo(bearThreshold) < 0) return MarketPhase.BEAR;
            return MarketPhase.SIDEWAYS;

        } catch (Exception e) {
            log.warn("시장 국면 감지 실패, SIDEWAYS 반환: {}", e.getMessage());
            return MarketPhase.SIDEWAYS;
        }
    }

    private boolean isLowLiquidityTime() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Seoul"));
        return now.isAfter(LocalTime.of(4, 0)) && now.isBefore(LocalTime.of(8, 0));
    }

    private void askSuccessMessage(OrdersResponse response) {
        log.info("{} 코인 구매 성공", response.getMarket());
    }
}
