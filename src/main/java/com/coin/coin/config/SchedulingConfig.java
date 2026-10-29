package com.coin.coin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 스케줄러 스레드 2개 (10/16).
 *
 * <p>10/29~ 30초 패스트 루프는 없어졌지만, 슬로우 루프(3분, 지표 계산 + 장타 — 수십 초 걸림)와
 * 코인 목록 매시 갱신(CoinListService)이 서로 밀리지 않도록 2개를 유지한다.
 */
@Configuration
public class SchedulingConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("scheduling-");
        return scheduler;
    }
}
