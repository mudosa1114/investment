package com.coin.coin.dto;

import com.coin.coin.common.MarketPhase;
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
    private String phase;
    private BigDecimal upper;
    private BigDecimal middle;
    private BigDecimal lower;
    private BigDecimal ema5;
    private BigDecimal ema20;
    private LocalDateTime tradedAt;

    public static TradeHistory buyHistory(String market, String price, CoinSignalDto signal) {
        return TradeHistory.builder()
                .market(market)
                .tradeType("매수")
                .orderPrice(new BigDecimal(price))
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

    public static TradeHistory sellHistory(String market, BigDecimal amount, CoinSignalDto signal) {
        BigDecimal totalSellPrice = amount.multiply(new BigDecimal("0.9995"));

        return TradeHistory.builder()
                .market(market)
                .orderPrice(totalSellPrice)
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
