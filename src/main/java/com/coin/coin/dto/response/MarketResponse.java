package com.coin.coin.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MarketResponse {

    @JsonProperty("market")
    private String market;

    /** 유의종목 여부 (NONE / CAUTION) — market/all?is_details=true 일 때만 채워짐 (10/15) */
    @JsonProperty("market_warning")
    private String marketWarning;

    /** 신규 형식 시장 경보 — warning = 유의종목 지정 여부 (10/15) */
    @JsonProperty("market_event")
    private MarketEvent marketEvent;

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MarketEvent {
        @JsonProperty("warning")
        private Boolean warning;
    }

    /** 유의종목이면 true (구 market_warning, 신 market_event.warning 중 하나라도) */
    @JsonIgnore
    public boolean isWarning() {
        return "CAUTION".equals(marketWarning)
                || (marketEvent != null && Boolean.TRUE.equals(marketEvent.getWarning()));
    }
}
