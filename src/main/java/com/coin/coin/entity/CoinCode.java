package com.coin.coin.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

@Entity
@Table(schema = "coin", name = "coin_code")
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CoinCode {

    @Id
    private String coinCode;

}
