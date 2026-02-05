package com.coin.coin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TradeRequest {

    private String market;
    private String side;
    private String volume;
    private String price;
    @JsonProperty("ord_type")
    private String ordType;
    @JsonProperty("time_in_force")
    private String timeInForce;

}
