package com.coin.coin.dto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Component
public class UriBuilderDto {

    @Value("${upbit.url}")
    private String url;

    @Value("${kakao.url}")
    private String kakaoUrl;

    @Value("${kakao.msgUrl}")
    private String kakaoMsgUrl;

    public URI upbitOrderBook(String market) {
        return UriComponentsBuilder
                .fromUriString(url + "orderbook")
                .queryParam("markets", market)
                .build(true)
                .toUri();
    }

    public URI upbitAccount() {
        return UriComponentsBuilder
                .fromUriString(url + "accounts")
                .build(true)
                .toUri();
    }

    public URI upbitOrder() {
        return UriComponentsBuilder
                .fromUriString(url + "orders/test")
                .build(true)
                .toUri();
    }

    public URI kakaoAuthUri() {
        return UriComponentsBuilder
                .fromUriString(kakaoUrl)
                .build(true)
                .toUri();
    }

    public URI kakaoMsgUri() {
        return UriComponentsBuilder
                .fromUriString(kakaoMsgUrl)
                .build(true)
                .toUri();
    }

}
