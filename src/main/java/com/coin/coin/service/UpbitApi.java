package com.coin.coin.service;

import com.coin.coin.common.CoinCatalog;
import com.coin.coin.config.UpbitJwtGenerator;
import com.coin.coin.dto.TradeRequest;
import com.coin.coin.dto.response.AccountResponse;
import com.coin.coin.dto.CoinAccount;
import com.coin.coin.dto.response.OrderBookResponse;
import com.coin.coin.dto.UriBuilderDto;
import com.coin.coin.dto.response.OrderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class UpbitApi {

    private final UriBuilderDto coinUriBuilder;
    private final UpbitJwtGenerator jwtGenerator;
    private final KakaoService messageService;

    public void tradeCoin() {
        String askPriceTxt = "askPrice";
        String bidPriceTxt = "bidPrice";

        BigDecimal transactionFee = new BigDecimal(("1.0005"));
        List<CoinAccount> accountList = checkCoinAccount();

        Set<String> holdCoinList = new HashSet<>(
                accountList.stream()
                        .map(acc -> acc.getCoinType() + "-" + acc.getCoinName())
                        .toList()
        );

        List<CoinCatalog> nonHoldCoinList = Arrays
                .stream(CoinCatalog.values())
                .filter(catalog -> !holdCoinList.contains(catalog.getCode()))
                .toList();

        if (!nonHoldCoinList.isEmpty()) {
            for (CoinCatalog catalog : nonHoldCoinList) {
                log.info("{} 미보유 코인, 구매 진행.", catalog.getCode());
                // 코인 구매로직
                BigDecimal askPrice = orderPrice(catalog.getCode()).get(askPriceTxt);

            }
        }

        for (CoinAccount account : accountList) {
            String holdCoinNm = account.getCoinType() + "-" + account.getCoinName();
            if (holdCoinNm.equals("KRW-KRW")) {
                continue;
            }
            log.info("{} 코인 자동매매 시작", holdCoinNm);

            BigDecimal avgPrice = account.getAvgBuyPrice().multiply(transactionFee);
            BigDecimal currAskPrice = orderPrice(holdCoinNm).get(bidPriceTxt);
            BigDecimal maxInvestCost = new BigDecimal("100000");

            boolean isDropped = currAskPrice.compareTo(avgPrice) <= 0;
            boolean isProfitRange = avgPrice.multiply(new BigDecimal("1.03")).compareTo(currAskPrice) >= 0;
            boolean isWithinInvestLimit = account.getAvgBuyPrice()
                    .multiply(account.getBalance())
                    .compareTo(maxInvestCost) <= 0;

            // 코인 가격이 기존과 같거나 하락시 추가구매
            if (isDropped && isWithinInvestLimit) {
                log.info("{} 코인 추가구매", holdCoinNm);
                continue;
            }

            // 3% 이상 수익이 발생한 경우 코인 판매
            if (isProfitRange) {
                log.info("{} 코인 판매", holdCoinNm);
            }
        }
    }

    private Map<String, BigDecimal> orderPrice(String market) {
        OrderBookResponse[] responses = new RestTemplate().getForObject(coinUriBuilder.upbitOrderBook(market), OrderBookResponse[].class);

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

        ResponseEntity<AccountResponse[]> response = new RestTemplate().exchange(
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

    private String askCoin(String market, BigDecimal askPrice) {
        TradeRequest tradeRequest = TradeRequest.builder()
                .market(market)
                .side("bid")
                .price(askPrice.toString())
                .ordType("price")
                .timeInForce("ioc")
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwtGenerator.upbitJwtToken());
        headers.set("accept", "application/json");

        HttpEntity<TradeRequest> entity = new HttpEntity<>(tradeRequest, headers);

        ResponseEntity<OrderResponse> response = new RestTemplate().exchange(
                coinUriBuilder.upbitOrder(),
                HttpMethod.POST,
                entity,
                OrderResponse.class);

        return "success";
    }
}
