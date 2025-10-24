package com.coin.coin;

import com.coin.coin.dto.CoinPrice;
import com.coin.coin.service.UpbitApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
public class OrderBookTest {

    @Autowired
    UpbitApi api;

    @Test
    @DisplayName("호가 api 호출 테스트")
    void orderBookTest() {
        // given
        String market = "KRW-SOL";

        // when
        CoinPrice price = api.checkCoinPrice(market);

        // then
        System.out.println(price.getBidPrice());
    }
}
