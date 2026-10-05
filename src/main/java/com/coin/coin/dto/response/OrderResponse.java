package com.coin.coin.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
@AllArgsConstructor
public class OrderResponse {

    @JsonProperty("market")
    private String market;
    @JsonProperty("uuid")
    private String uuid;
    @JsonProperty("side")
    private String side;
    @JsonProperty("ord_type")
    private String ordType;
    @JsonProperty("price")
    private String price;
    @JsonProperty("state")
    private String state;
    @JsonProperty("created_at")
    private String createdAt;
    @JsonProperty("volume")
    private String volume;
    @JsonProperty("remaining_volume")
    private String remainingVolume;
    @JsonProperty("executed_volume")
    private String executedVolume;
    @JsonProperty("reserved_fee")
    private String reservedFee;
    @JsonProperty("remaining_fee")
    private String remainingFee;
    @JsonProperty("paid_fee")
    private String paidFee;
    @JsonProperty("locked")
    private String locked;
    @JsonProperty("prevented_locked")
    private String preventedLocked;
    @JsonProperty("trades_count")
    private String tradesCount;
    @JsonProperty("trades")
    private List<Traders> trades;

    @Getter
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class Traders {
        @JsonProperty("market")
        private String market;
        @JsonProperty("uuid")
        private String uuid;
        @JsonProperty("price")
        private String price;
        @JsonProperty("volume")
        private String volume;
        @JsonProperty("funds")
        private String funds;
        @JsonProperty("trend")
        private String trend;
        @JsonProperty("created_at")
        private String created_at;
        @JsonProperty("side")
        private String side;
    }


}
