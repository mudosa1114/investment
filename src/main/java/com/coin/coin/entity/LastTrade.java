package com.coin.coin.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(schema = "coin", name = "last_trade")
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class LastTrade {

    @Id
    private String market;
    private String sellType;
    private BigDecimal totalSellPrice;
    private BigDecimal avgPrice;
    private BigDecimal rsi;
    private String phase;
    private BigDecimal upper;
    private BigDecimal middle;
    private BigDecimal lower;
    private BigDecimal ema5;
    private BigDecimal ema20;
    private Integer dropCount;
    private Integer profitCount;
    private LocalDateTime lastDamagedAt;  // 최근 손절 시각 (재진입 쿨다운 기준)
    private LocalDateTime tradedAt;
}
