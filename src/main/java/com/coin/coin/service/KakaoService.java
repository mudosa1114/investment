package com.coin.coin.service;

import com.coin.coin.dto.KakaoAuthDto;
import com.coin.coin.dto.UriBuilderDto;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class KakaoService {

    @Value("${kakao.client-id}")
    private String clientId;

    @Value("${kakao.redirect-uri}")
    private String redirectUri;

    @Value("${kakao.client-secret}")
    private String clientSecret;

    private final UriBuilderDto uriBuilderDto;

    public String getKakaoAuth() {
        String body = "grant_type=authorization_code"
                + "&client_id=" + clientId
                + "&redirect_uri=" + redirectUri
                + "&code=AuthToken"
                + "&client_secret=" + clientSecret;

        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/x-www-form-urlencoded;charset=utf-8");
        HttpEntity<String> requestEntity = new HttpEntity<>(body, headers);

        KakaoAuthDto kakaoAuthDto = new RestTemplate().postForObject(uriBuilderDto.kakaoAuthUri(), requestEntity, KakaoAuthDto.class);
        if (Optional.ofNullable(kakaoAuthDto).isPresent()) {
            return kakaoAuthDto.getAccessToken();
        }
        return "Fail";
    }

    public String sendMessage() {
        String accessToken = "";
        String message = "test";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("Content-Type", "application/x-www-form-urlencoded;charset=utf-8");

        HttpEntity<String> requestEntity = new HttpEntity<>(message, headers);

        return null;
    }
}
