package com.coin.coin.service;

import com.coin.coin.config.UpbitJwtGenerator;
import com.coin.coin.dto.CoinAccount;
import com.coin.coin.dto.CoinPrice;
import com.coin.coin.dto.TradeRequest;
import com.coin.coin.dto.UriBuilderDto;
import com.coin.coin.dto.response.AccountResponse;
import com.coin.coin.dto.response.CandleResponse;
import com.coin.coin.dto.response.OrderBookResponse;
import com.coin.coin.dto.response.OrderResponse;
import com.coin.coin.dto.response.OrdersResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.coin.coin.dto.CoinPrice.latestCoinPrice;

/**
 * Upbit REST API 순수 클라이언트 — 캔들/현재가/계좌/주문 조회 및 주문 실행만 담당한다.
 * 매매 판단(지표 해석, 진입/청산 로직)은 여기서 하지 않는다 (UpbitApi 역할분리, 9/4).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class UpbitExchangeClient {

    private final RestTemplate restTemplate;
    private final UriBuilderDto coinUriBuilder;
    private final UpbitJwtGenerator jwtGenerator;

    public List<CandleResponse> candleResponses(String market, int unit, int period) {
        CandleResponse[] candles = restTemplate.getForObject(
                coinUriBuilder.upbitCandles(market, unit, period),
                CandleResponse[].class
        );
        return ObjectUtils.isEmpty(candles) ? null : Arrays.asList(candles);
    }

    public boolean isInvalid(List<CandleResponse> candles, int minSize) {
        return candles == null || candles.size() < minSize;
    }

    public CoinPrice checkCoinPrice(String market) {
        OrderBookResponse[] res = restTemplate.getForObject(
                coinUriBuilder.upbitOrderBook(market), OrderBookResponse[].class);
        return latestCoinPrice(Optional.ofNullable(res)
                .map(Arrays::asList).orElse(Collections.emptyList()));
    }

    public Map<String, BigDecimal> orderPrice(String market) {
        OrderBookResponse[] res = restTemplate.getForObject(
                coinUriBuilder.upbitOrderBook(market), OrderBookResponse[].class);
        List<OrderBookResponse> list = Optional.ofNullable(res)
                .map(Arrays::asList).orElse(Collections.emptyList());
        return Map.of(
                "askPrice", list.get(0).getOrderBookUnits().get(0).getAskPrice(),
                "bidPrice", list.get(0).getOrderBookUnits().get(0).getBidPrice()
        );
    }

    public List<CoinAccount> checkCoinAccount() {
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

    public OrderResponse checkCoin(String uuid) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " +
                jwtGenerator.upbitJwtTokenWithQuery("uuid=" + uuid));
        headers.set("accept", "application/json");

        return restTemplate.exchange(
                coinUriBuilder.upbitOrder(uuid), HttpMethod.GET,
                new HttpEntity<>(headers), OrderResponse.class).getBody();
    }

    public OrdersResponse orderCoin(String market, String side, String value) {
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

    public void askSuccessMessage(OrdersResponse response) {
        log.info("{} 코인 최초 구매 성공", response.getMarket());
    }
}
