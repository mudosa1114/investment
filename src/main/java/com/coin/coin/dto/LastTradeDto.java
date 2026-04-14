package com.coin.coin.dto;

import com.coin.coin.common.MarketPhase;
import com.coin.coin.entity.LastTrade;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder(toBuilder = true)
public class LastTradeDto {

    private String market;
    private String sellType;
    private BigDecimal totalSellPrice;
    private BigDecimal avgPrice;
    private BigDecimal rsi;
    private MarketPhase phase;
    private BigDecimal upper;
    private BigDecimal middle;
    private BigDecimal lower;
    private BigDecimal ema5;
    private BigDecimal ema20;
    private Integer dropCount;
    private Integer profitCount;
    private LocalDateTime tradedAt;

    public static LastTrade damageTrade(String market, BigDecimal amount, BigDecimal avgPrice, CoinSignalDto signal) {
        LocalDateTime now = LocalDateTime.now();
        return LastTrade.builder()
                .market(market)
                .sellType("손절")
                .totalSellPrice(amount)
                .avgPrice(avgPrice)
                .rsi(signal.getRsi())
                .phase(phaseValue(signal))
                .upper(signal.getBb().get("upper"))
                .middle(signal.getBb().get("middle"))
                .lower(signal.getBb().get("lower"))
                .ema5(signal.getEma().get("ema5"))
                .ema20(signal.getEma().get("ema20"))
                .lastDamagedAt(now)
                .tradedAt(now)
                .build();
    }

    public static LastTrade profitTrade(String market, BigDecimal amount, BigDecimal avgPrice, CoinSignalDto signal) {
        return LastTrade.builder()
                .market(market)
                .sellType("익절")
                .totalSellPrice(amount)
                .avgPrice(avgPrice)
                .dropCount(0)
                .rsi(signal.getRsi())
                .phase(phaseValue(signal))
                .upper(signal.getBb().get("upper"))
                .middle(signal.getBb().get("middle"))
                .lower(signal.getBb().get("lower"))
                .ema5(signal.getEma().get("ema5"))
                .ema20(signal.getEma().get("ema20"))
                .tradedAt(LocalDateTime.now())
                .build();
    }

    private static String phaseValue(CoinSignalDto signal) {
        return signal.getPhase().name();
    }
}
