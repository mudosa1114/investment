package com.coin.coin.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class AccountResponse {

    @JsonProperty("currency")
    private String currency;
    @JsonProperty("balance")
    private String balance;
    @JsonProperty("locked")
    private String locked;
    @JsonProperty("avg_buy_price")
    private String avgBuyPrice;
    @JsonProperty("avg_buy_price_modified")
    private String avgBuyPriceModified;
    @JsonProperty("unit_currency")
    private String unitCurrency;

}
