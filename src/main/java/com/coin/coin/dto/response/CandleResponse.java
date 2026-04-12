package com.coin.coin.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class CandleResponse {

    @JsonProperty("market")
    private String market;
    @JsonProperty("trade_price")
    private BigDecimal tradePrice;
    @JsonProperty("opening_price")
    private BigDecimal openingPrice;
    @JsonProperty("high_price")
    private BigDecimal highPrice;
    @JsonProperty("low_price")
    private BigDecimal lowPrice;
}
