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
@Table(schema = "public", name = "last_trade")
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
    private Integer dropCount;
    private Integer viewCount;
    private LocalDateTime tradedAt;
}
