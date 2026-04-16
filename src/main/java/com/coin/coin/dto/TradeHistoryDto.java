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

    /**
     * @param amount      체결 총액 (수수료 차감 전)
     * @param avgBuyPrice 포지션 평균 매수가 (실현손익 계산 기준)
     * @param executedVol 체결 수량
     */
    public static TradeHistory sellHistory(String market, BigDecimal amount,
                                           BigDecimal avgBuyPrice, BigDecimal executedVol,
                                           CoinSignalDto signal) {
        BigDecimal netSell = amount.multiply(new BigDecimal("0.9995"));   // 수수료 차감
        BigDecimal costBasis = avgBuyPrice.multiply(executedVol);         // 매입 원가
        BigDecimal realizedPnl = netSell.subtract(costBasis);             // 실현 손익

        return TradeHistory.builder()
                .market(market)
                .orderPrice(netSell)
                .rsi(signal.getRsi())
                .phase(phaseValue(signal))
                .upper(signal.getBb().get("upper"))
                .middle(signal.getBb().get("middle"))
                .lower(signal.getBb().get("lower"))
                .ema5(signal.getEma().get("ema5"))
                .ema20(signal.getEma().get("ema20"))
                .realizedPnl(realizedPnl)
                .tradedAt(LocalDateTime.now())
                .build();
    }

    private static String phaseValue(CoinSignalDto signal) {
        return signal.getPhase().name();
    }


}
