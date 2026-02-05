package com.coin.coin.service;

import com.coin.coin.dto.KakaoAuthDto;
import com.coin.coin.dto.UriBuilderDto;
import com.coin.coin.entity.KakaoToken;
import com.coin.coin.repository.KakaoTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class KakaoService {

    @Value("${kakao.client-id}")
    private String clientId;

    @Value("${kakao.redirect-uri}")
    private String redirectUri;

    @Value("${kakao.client-secret}")
    private String clientSecret;

    private final UriBuilderDto uriBuilderDto;
    private final KakaoTokenRepository tokenRepository;

    public Map<String, String> getKakaoAuth(String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("client_id", clientId);
        params.add("redirect_uri", redirectUri); // 인가 코드 받을 때와 100% 동일해야 함
        params.add("code", code);
        params.add("client_secret", clientSecret);

        HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(params, headers);
        Map<String, String> token = new HashMap<>();
        try {
            KakaoAuthDto kakaoAuthDto = new RestTemplate().postForObject(
                    uriBuilderDto.kakaoAuthUri(),
                    requestEntity,
                    KakaoAuthDto.class
            );

            if (Optional.ofNullable(kakaoAuthDto).isPresent()) {
                token.put("access_token", kakaoAuthDto.getAccessToken());
                token.put("refresh_token", kakaoAuthDto.getRefreshToken());

                return token;
            }

            return token;

        } catch (HttpStatusCodeException e) {
            throw new RuntimeException(e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    public void sendMessage(String message) {
        KakaoToken token = tokenRepository.getKakaoToken();
        String accessToken = token.getAccessToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        String templateObject = "{"
                + "\"object_type\": \"text\","
                + "\"text\": \"" + message + "\","
                + "\"link\": {\"web_url\": \"http://localhost\"}"
                + "}";

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("template_object", templateObject);

        try {
            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
            new RestTemplate().postForEntity(uriBuilderDto.kakaoMsgUri(), request, String.class);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                log.info("Kakao Access-Token 만료, 토큰갱신");
                refreshKakaoToken(token.getRefreshToken());
            }
        }
    }

    private void refreshKakaoToken(String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "refresh_token");
        params.add("client_id", clientId);
        params.add("refresh_token", refreshToken);
        params.add("client_secret", clientSecret);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
        KakaoAuthDto response = new RestTemplate().postForObject(uriBuilderDto.kakaoAuthUri(), request, KakaoAuthDto.class);

        if (response != null) {
            if (response.getRefreshToken() != null) {
                KakaoAuthDto dto = KakaoAuthDto.builder()
                        .accessToken(response.getAccessToken())
                        .refreshToken(response.getRefreshToken())
                        .build();

                KakaoToken token = KakaoToken.updateToken(dto);
                tokenRepository.save(token);
                log.info("Kakao Access + Refresh Token Update");
                return ;
            }

            KakaoAuthDto dto = KakaoAuthDto.builder()
                    .accessToken(response.getAccessToken())
                    .refreshToken(refreshToken)
                    .build();
            KakaoToken token = KakaoToken.updateToken(dto);
            tokenRepository.save(token);
            log.info("Kakao Access Token Update");
        }
    }
}
