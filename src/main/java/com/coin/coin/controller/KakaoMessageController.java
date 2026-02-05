package com.coin.coin.controller;

import com.coin.coin.service.KakaoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/kakao")
@RequiredArgsConstructor
public class KakaoMessageController {

    private final KakaoService kakaoService;

    @GetMapping("/auth")
    public Map<String, String> auth(@RequestParam("code") String code) {
        return kakaoService.getKakaoAuth(code);
    }

    @GetMapping("/msg")
    public void message(@RequestParam("msg") String msg) {
        kakaoService.sendMessage(msg);
    }

}
