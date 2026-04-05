package com.coin.coin.dto;

import com.coin.coin.entity.TradeHistory;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder(toBuilder = true)
public class TradeHistoryDto {

    private Long id;
    private String market;
    private String tradeType;
    private BigDecimal orderPrice;
    private BigDecimal rsi;
    private LocalDateTime tradedAt;

    public static TradeHistory buyHistory(String market, String price, BigDecimal rsi) {
        return TradeHistory.builder()
                .market(market)
                .tradeType("매수")
                .orderPrice(new BigDecimal(price))
                .rsi(rsi)
                .tradedAt(LocalDateTime.now())
                .build();
    }

    public static TradeHistory sellHistory(String market, BigDecimal amount, BigDecimal rsi) {
        BigDecimal totalSellPrice = amount.multiply(new BigDecimal("0.9995"));

        return TradeHistory.builder()
                .market(market)
                .orderPrice(totalSellPrice)
                .rsi(rsi)
                .tradedAt(LocalDateTime.now())
                .build();
    }


}
