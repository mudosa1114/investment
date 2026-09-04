package com.coin.coin.controller;

import com.coin.coin.service.CoinListService;
import com.coin.coin.service.DailyPnlService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/upbit")
@RequiredArgsConstructor
public class UpbitController {

    private final CoinListService coinListService;
    private final DailyPnlService dailyPnlService;

    @GetMapping("/code")
    public String refreshCode() {
        coinListService.refreshCoinList();
        return "success";
    }

    @GetMapping("/result")
    public String checkResult() {
        dailyPnlService.calculateDailyPnl();
        return "success";
    }


}
