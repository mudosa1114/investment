package com.coin.coin.controller;

import com.coin.coin.service.KakaoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/kakao")
@RequiredArgsConstructor
public class KakaoMessageController {

    private final KakaoService kakaoService;

    @GetMapping("/auth")
    public String auth() {
        return kakaoService.getKakaoAuth();
    }

}
