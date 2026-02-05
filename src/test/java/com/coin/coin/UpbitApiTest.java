package com.coin.coin;

import com.coin.coin.common.CoinCatalog;
import com.coin.coin.config.UpbitJwtGenerator;
import com.coin.coin.dto.CoinPrice;
import com.coin.coin.dto.TradeRequest;
import com.coin.coin.dto.response.OrderBookResponse;
import com.coin.coin.dto.UriBuilderDto;
import com.coin.coin.dto.response.OrderResponse;
import com.coin.coin.service.UpbitApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import java.security.NoSuchAlgorithmException;
import java.util.*;

import static com.coin.coin.dto.CoinPrice.latestCoinPrice;

@SpringBootTest
@ActiveProfiles("local")
public class UpbitApiTest {

    @Autowired
    UriBuilderDto coinUriBuilder;

    @Autowired
    UpbitApi upbitApi;

    @Autowired
    UpbitJwtGenerator jwtGenerator;

    @Test
    @DisplayName("호가 api 호출 테스트")
    void orderBookTest() {
        // given
        List<String> catalogs = CoinCatalog.getAllCoin();

        // when
        List<CoinPrice> priceList = new ArrayList<>();
        for (String catalog : catalogs) {
            OrderBookResponse[] responses = new RestTemplate().getForObject(coinUriBuilder.upbitOrderBook(catalog), OrderBookResponse[].class);

            List<OrderBookResponse> coinPriceList = Optional.ofNullable(responses)
                    .map(Arrays::asList)
                    .orElse(Collections.emptyList());

            priceList.add(latestCoinPrice(coinPriceList));
        }

        // then
        for (CoinPrice price : priceList) {
            System.out.println(price.getMarket() + " 코인 구매 호가 : " + price.getAskPrice());
            System.out.println(price.getMarket() + " 코인 판매 호가 : " + price.getBidPrice());
        }
    }

    @Test
    @DisplayName("코인 구매 테스트")
    void orderTest() throws NoSuchAlgorithmException {
        // given
        String marketNm = "KRW-XRP";
        String sideType = "bid";

        // when
        TradeRequest tradeRequest = TradeRequest.builder()
                .market(marketNm)
                .side(sideType)
                .price("5000")
                .ordType("price")
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwtGenerator.upbitOrderToken(tradeRequest));
        headers.set("accept", "application/json");

        HttpEntity<TradeRequest> entity = new HttpEntity<>(tradeRequest, headers);

        ResponseEntity<OrderResponse> response = new RestTemplate().exchange(
                coinUriBuilder.upbitOrder(),
                HttpMethod.POST,
                entity,
                OrderResponse.class);

        OrderResponse orderResponse = response.getBody();

        // then
        if (Optional.ofNullable(orderResponse).isPresent()) {
            System.out.println("선택한 코인 종류 : " + orderResponse.getMarket());
            System.out.println("주문 방향 : " + orderResponse.getSide());
            System.out.println("주문 유형 : " + orderResponse.getOrdType());
            System.out.println("매수 총액 : " + orderResponse.getPrice());
            System.out.println("요청 수량 : " + orderResponse.getVolume());
            System.out.println("체결된 수량 : " + orderResponse.getExecutedVolume());
            System.out.println("거래 수수료 : " + orderResponse.getPaidFee());
            System.out.println("주문 상태 : " + orderResponse.getState());
        }

    }

    @Test
    @DisplayName("api 자동매매 테스트")
    void tradeTest() {
        upbitApi.tradeCoin();
    }


}
