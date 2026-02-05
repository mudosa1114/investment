package com.coin.coin.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@RequiredArgsConstructor
public enum CoinCatalog {

    KRW_ETC("KRW-ETC"),
    KRW_XRP("KRW-XRP"),
    KRW_SOL("KRW-SOL"),
    KRW_DOT("KRW-DOT"),
    KRW_TRX("KRW-TRX"),
    KRW_ADA("KRW-ADA"),
    KRW_AVAX("KRW-AVAX"),
    KRW_LINK("KRW-LINK");

    private final String code;

    public static List<String> getAllCoin() {
        return Arrays.stream(CoinCatalog.values())
                .map(CoinCatalog::getCode)
                .collect(Collectors.toList());
    }
}
