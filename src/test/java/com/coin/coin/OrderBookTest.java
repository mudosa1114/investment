package com.coin.coin;

import com.coin.coin.dto.OrderBookResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@SpringBootTest
@ActiveProfiles("local")
public class OrderBookTest {

    @Test
    @DisplayName("호가 api 호출 테스트")
    void orderBookTest() {
        // given
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.set("accept", "application/json");

        String url = "https://api.upbit.com/v1/orderbook?markets=KRW-BTC";

        // when
        ResponseEntity<OrderBookResponse[]> response = restTemplate
                .exchange(
                        url,
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        OrderBookResponse[].class);

        List<OrderBookResponse> orderbookList = Optional.ofNullable(response.getBody())
                .map(Arrays::asList)
                .orElse(Collections.emptyList());

        // then
        String buyingPrice = orderbookList.get(0).getOrderBookUnits().get(0).getAskPrice().toString();
        String sellingPrice = orderbookList.get(0).getOrderBookUnits().get(0).getBidPrice().toString();
        Instant instant = Instant.ofEpochMilli(orderbookList.get(0).getTimestamp());

        ZoneId zoneId = ZoneId.systemDefault();

        LocalDateTime localDateTime = LocalDateTime.ofInstant(instant, zoneId);

        System.out.println(buyingPrice);
        System.out.println(sellingPrice);
        System.out.println(localDateTime);

        assertThat(orderbookList).isNotNull();
    }
}
