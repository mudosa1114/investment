package com.coin.coin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class KakaoAuthDto {

    @JsonProperty("accessToken")
    private String accessToken;
    @JsonProperty("refreshToken")
    private String refreshToken;
}
