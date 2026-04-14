package com.coin.coin.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CoinTickerResponse {

    @JsonProperty("market")
    private String market;

    /** 24시간 누적 거래대금 (KRW) */
    @JsonProperty("acc_trade_price_24h")
    private BigDecimal accTradePrice24h;

    /** 24시간 가격 변동률 (변동성 참고용) */
    @JsonProperty("signed_change_rate")
    private BigDecimal signedChangeRate;
}
