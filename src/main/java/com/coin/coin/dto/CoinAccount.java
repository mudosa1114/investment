package com.coin.coin.dto;

import com.coin.coin.dto.response.AccountResponse;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class CoinAccount {

    private String coinName;
    private String coinType;
    private BigDecimal balance;
    private BigDecimal avgBuyPrice;

    public static CoinAccount coinAccount(AccountResponse response) {
        return CoinAccount.builder()
                .coinName(response.getCurrency())
                .coinType(response.getUnitCurrency())
                .balance(new BigDecimal(response.getBalance()))
                .avgBuyPrice(new BigDecimal(response.getAvgBuyPrice()))
                .build();
    }
}