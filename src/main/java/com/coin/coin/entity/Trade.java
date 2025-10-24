package com.coin.coin.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter
@Table(schema = "public", name = "trade")
public class Trade {

    @Id
    private String market;
    private BigDecimal volume;
    private Integer price;
    private LocalDateTime updateTime;

}
