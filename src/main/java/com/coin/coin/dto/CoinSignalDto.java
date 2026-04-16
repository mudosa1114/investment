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
    /** 단기 국면 (15분봉 EMA 기울기 기반) — 주 매수 필터 */
    private MarketPhase shortPhase;
    /** 장기 국면 (60분봉 EMA 기울기 기반) — 보조 안전장치 필터 */
    private MarketPhase phase;
    private Map<String, BigDecimal> ema;
    private Map<String, BigDecimal> bb;
    private CoinPrice price;
}
