package com.coin.coin.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(schema = "public", name = "coin_code")
public class CoinCode {

    @Id
    private String coinCode;

}
