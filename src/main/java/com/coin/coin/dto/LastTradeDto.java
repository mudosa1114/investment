package com.coin.coin.dto;

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
    private Integer dropCount;
    private Integer viewCount;
    private LocalDateTime tradedAt;

    public static LastTrade damageTrade(String market, BigDecimal amount, BigDecimal avgPrice, BigDecimal rsi) {
        return LastTrade.builder()
                .market(market)
                .sellType("손절")
                .totalSellPrice(amount)
                .avgPrice(avgPrice)
                .rsi(rsi)
                .dropCount(1)
                .tradedAt(LocalDateTime.now())
                .build();
    }

    public static LastTrade profitTrade(String market, BigDecimal amount, BigDecimal avgPrice, BigDecimal rsi) {
        return LastTrade.builder()
                .market(market)
                .sellType("익절")
                .totalSellPrice(amount)
                .avgPrice(avgPrice)
                .dropCount(0)
                .rsi(rsi)
                .tradedAt(LocalDateTime.now())
                .build();
    }
}
