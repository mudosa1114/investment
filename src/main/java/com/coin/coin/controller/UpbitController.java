package com.coin.coin.controller;

import com.coin.coin.service.UpbitApi;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/upbit")
@RequiredArgsConstructor
public class UpbitController {

    private final UpbitApi upbitApi;


}
