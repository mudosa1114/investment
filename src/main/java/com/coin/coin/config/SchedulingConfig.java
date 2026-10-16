package com.coin.coin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 스케줄러 스레드 2개 (10/16).
 *
 * <p>기본값(1개)이면 슬로우 루프(3분, 지표 계산 + 매도 + 매수 — 수십 초 걸림)가 도는 동안 패스트 루프(30초 보유 최고·최저 확인·
 * 매도후추적)가 밀려 30초 간격이 깨진다. 2개로 두 루프를 나란히 돌린다.
 * 모의계좌(PaperTradingService)는 synchronized, 상태 맵(TradingStateStore)은 ConcurrentHashMap이라 동시 접근에 안전하다.
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
