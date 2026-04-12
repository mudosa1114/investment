package com.coin.coin.dto;

import com.coin.coin.common.MarketPhase;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.Map;

@Getter
@Builder
@AllArgsConstructor
public class CoinSignalDto {

    private BigDecimal rsi;
    private MarketPhase phase;
    private Map<String, BigDecimal> ema;
    private Map<String, BigDecimal> bb;
    private CoinPrice price;
}
