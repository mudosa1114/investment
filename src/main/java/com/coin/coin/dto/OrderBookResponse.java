package com.coin.coin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Builder
@AllArgsConstructor
public class OrderBookResponse {

    @JsonProperty("market")
    private String market;
    @JsonProperty("timestamp")
    private Long timestamp;
    @JsonProperty("orderbook_units")
    private List<OrderBookUnits> orderBookUnits;

    @Getter
    public static class OrderBookUnits {
        @JsonProperty("ask_price")
        private BigDecimal askPrice;
        @JsonProperty("bid_price")
        private BigDecimal bidPrice;
    }

}
