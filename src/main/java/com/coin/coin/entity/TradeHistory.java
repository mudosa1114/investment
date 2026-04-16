package com.coin.coin.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(schema = "coin", name = "trade_history")
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class TradeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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
    /** 매도 시 실현손익 = 수령액(수수료 차감) - 평균매수가×체결수량. 매수 레코드는 null. */
    private BigDecimal realizedPnl;
    private LocalDateTime tradedAt;

}
