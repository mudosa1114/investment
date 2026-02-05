package com.coin.coin.dto;

import com.coin.coin.dto.response.OrderBookResponse;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class CoinPrice {

    private String market;
    private BigDecimal askPrice;
    private BigDecimal bidPrice;
    private LocalDateTime timestamp;

    public static CoinPrice latestCoinPrice(List<OrderBookResponse> orderbookList) {
        return CoinPrice.builder()
                .market(orderbookList.get(0).getMarket())
                .askPrice(orderbookList.get(0).getOrderBookUnits().get(0).getAskPrice())
                .bidPrice(orderbookList.get(0).getOrderBookUnits().get(0).getBidPrice())
                .build();
    }
}
