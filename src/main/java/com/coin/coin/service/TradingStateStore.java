package com.coin.coin.service;

import com.coin.coin.dto.CoinSignalDto;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * 매매 루프 간 공유되는 인메모리 상태 — 포지션 진입시각/RSI 추적, 지표 캐시,
 * 트레일링/쿨다운/차단 맵을 한 곳에서 소유한다 (UpbitApi 역할분리, 9/4).
 *
 * <p>여러 서비스(CoinSignalService/PositionExitService/TradeExecutionService/
 * TradingScheduler)가 이 맵들을 직접 읽고 쓴다 — 원래 UpbitApi 하나가 갖고 있던
 * 필드를 그대로 옮긴 것으로, 각 맵의 소유권 자체를 재설계하지는 않았다.
 */
@Component
@Slf4j
public class TradingStateStore {

    // ─── 포지션 진입 시각 추적 ───────────────────────────────────────
    /** 코인별 매수 진입 시각 — 시간 손절 판단용, 매수 시 등록/매도 시 제거 */
    public final Map<String, LocalDateTime> positionEntryTimeMap = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 포지션 보유 중 RSI 최고값 — BULL 모멘텀 소진 감지용, 슬로우 루프에서 갱신 */
    public final Map<String, BigDecimal>    rsiPeakMap           = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 매수 진입 시점 RSI — RSI 모멘텀손절 오발동 방지용(진입 대비 peak 상승폭 검증), 매수 시마다 갱신 */
    public final Map<String, BigDecimal>    entryRsiMap          = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 직전 슬로우 루프 RSI — 진입 시 RSI 상승 방향 확인용 (현재 RSI > 직전 RSI 이어야 진입) */
    public final Map<String, BigDecimal>    prevRsiMap           = new java.util.concurrent.ConcurrentHashMap<>();

    // ─── 지표 캐시 (슬로우 루프가 3분마다 갱신, 패스트 루프가 참조) ─────
    /** volatile: 참조 교체가 원자적으로 보장됨 (슬로우 루프 갱신 → 패스트 루프 즉시 가시) */
    @Getter
    @Setter
    private volatile Map<String, CoinSignalDto> cachedSignalMap = Collections.emptyMap();

    // ─── 트레일링 스탑 / 연속 손절 추적 맵 ──────────────────────────
    /** 코인별 트레일링 고점 평가금액 — 패스트 루프에서 30초마다 갱신 */
    public final Map<String, BigDecimal>   trailingPeakMap     = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 당일 연속 손절 횟수 — 3회→임시차단(20분), 5회→임시차단(1h) (8/25 거래빈도 확대로 완화) */
    public final Map<String, Integer>      consecutiveLossMap   = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 당일 누적 손절 횟수 (승패 무관) — 8회 달성 시 당일 블랙리스트 (8/25: 3→8회 상향)
     *  연속손절 카운터는 이익 시 0으로 리셋되지만, 이 카운터는 이익이 끼어도 리셋 안 함.
     *  예) 손절→손절→이익→손절→손절 이면 연속=2 이지만 누적=4 */
    public final Map<String, Integer>      dailyTotalLossMap    = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * 임시 시간 차단 코인 — 연속 손절 시 등록, 만료 시각(LocalDateTime) 저장
     * (8/25 거래빈도 확대로 완화)
     * · 연속 손절 3회 → now + 20분
     * · 연속 손절 5회 → now + 1시간
     */
    public final Map<String, LocalDateTime> temporaryBanUntilMap = new java.util.concurrent.ConcurrentHashMap<>();
    /** 당일 매수 완전 차단 코인 집합 — 현재 연속손절 외 수동 차단 등 확장용, 자정에 초기화 */
    public final Set<String>               dailyBlacklistSet        = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 익절 유형별 차등 쿨다운 만료 시각 — 정상:3분 / 과열:10분 / 급등:15분 */
    public final Map<String, LocalDateTime> profitCooldownUntilMap   = new java.util.concurrent.ConcurrentHashMap<>();

    // ══════════════════════════════════════════════════════════════════
    //  일일 통계 초기화 (매일 자정)
    // ══════════════════════════════════════════════════════════════════

    /**
     * 자정에 당일 블랙리스트·연속손절 맵·트레일링 맵을 초기화한다.
     *
     * <p>블랙리스트는 "당일" 단위로 동작 — 어제 연속 손절 코인도 오늘 새벽 refreshCoinList
     * 갱신 후 새로운 조건으로 다시 평가받도록 자정에 해제한다.
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void resetDailyStats() {
        int blacklistSize = dailyBlacklistSet.size();
        int tempBanSize   = temporaryBanUntilMap.size();
        dailyBlacklistSet.clear();
        temporaryBanUntilMap.clear();
        consecutiveLossMap.clear();
        dailyTotalLossMap.clear();
        trailingPeakMap.clear();
        rsiPeakMap.clear();
        profitCooldownUntilMap.clear();
        log.info("=== 일일 통계 초기화 완료 — 당일퇴출 {}개·임시차단 {}개 해제, 연속손절·트레일링 맵 초기화 ===",
                blacklistSize, tempBanSize);
    }
}
