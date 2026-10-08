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
    /** 코인별 포지션 보유 중 15분봉 RSI 최고값 (9/23 추가) — 3분봉 RSI와 나란히 비교하기 위한
     *  섀도우 기록(MomentumStopShadow)용, 실거래 판단에는 쓰지 않음. rsiPeakMap과 동일한 생명주기. */
    public final Map<String, BigDecimal>    rsi15mPeakMap        = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 매수 진입 시점 15분봉 RSI (9/23 추가) — 섀도우 기록 비교용, entryRsiMap의 15분봉 대응. */
    public final Map<String, BigDecimal>    entryRsi15mMap       = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 직전 슬로우 루프 RSI — 진입 시 RSI 상승 방향 확인용 (현재 RSI > 직전 RSI 이어야 진입) */
    public final Map<String, BigDecimal>    prevRsiMap           = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 포지션 보유 중 RSI 최저값 — 관망구간 추가매수(반등 신호) 판단용, 매수 시 현재 RSI로 초기화 */
    public final Map<String, BigDecimal>    rsiTroughMap         = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 관망구간 추가매수 실행 횟수 — 포지션당 최대 3회, 매수/매도 시 초기화 */
    public final Map<String, Integer>       dcaCountMap          = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 마지막 추가매수 시각 — 최소 대기시간(6분) 확보용 */
    public final Map<String, LocalDateTime> lastDcaAtMap         = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 손실구간 상태머신의 현재 관망 라운드(0=미진입, 1=1차관망...) — 9/17 재설계.
     *  0라운드는 "아직 한번도 관망하지 않음"을 의미하며, 손실구간을 벗어나면(회복) 제거된다. */
    public final Map<String, Integer>       lossWatchRoundMap    = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 손실구간 상태머신에서 현재 라운드가 시작된 시점의 평가금액 — "직전 대비 상승/하락"
     *  판단 기준(3라운드 이상에서 사용). 손실 비율 자체의 기준(totalCost)과는 별개다. */
    public final Map<String, BigDecimal>    lossWatchRefPriceMap = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 이익구간 상태머신의 현재 관망 라운드(0=미진입, 1=1차관망) — 9/17 신규.
     *  손실구간과 달리 유예는 1회로 제한되며, 이익구간(+0.3%)을 벗어나면 제거된다. */
    public final Map<String, Integer>       profitWatchRoundMap  = new java.util.concurrent.ConcurrentHashMap<>();

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
    /** 가상 추가매수 로그를 이미 남긴 보유 코인 (9/30) — 포지션당 1회 기록용. 포지션 정리 시
     *  clearPositionState에서 제거. 자정을 넘기는 포지션이 있어 resetDailyStats 대상이 아니다. */
    public final Set<String>               virtualAddBuyLoggedSet   = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ─── 급락 손절 유예 (10/2 추가) — PositionExitService.deferSoftStop 참고 ─────────
    // 포지션 단위 상태라 clearPositionState에서 정리하고, 자정을 넘기는 포지션이 있어 일일 초기화 대상이 아니다.
    /** 손절 유예 해제 예정 시각 — 이 시각 전까지 소프트 손절(손절/RSI모멘텀손절 등)을 보류한다 */
    public final Map<String, LocalDateTime> stopDeferUntilMap      = new java.util.concurrent.ConcurrentHashMap<>();
    /** 손절 유예를 이미 사용한 보유 코인 — 포지션당 1회만 유예 */
    public final Set<String>               stopDeferUsedSet        = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 유예 시작 시점 손실률(%) — 유예 종료 로그에서 "그때 팔았다면"과 비교하기 위한 기록 */
    public final Map<String, BigDecimal>    stopDeferStartLossMap   = new java.util.concurrent.ConcurrentHashMap<>();
    /** 유예 시작 시각 — 유예 종료 로그의 경과시간 계산용 */
    public final Map<String, LocalDateTime> stopDeferStartAtMap    = new java.util.concurrent.ConcurrentHashMap<>();
    /** 익절 유형별 차등 쿨다운 만료 시각 — 정상:3분 / 과열:10분 / 급등:15분 */
    public final Map<String, LocalDateTime> profitCooldownUntilMap   = new java.util.concurrent.ConcurrentHashMap<>();

    // ─── 저유동성 코인 24시간 차단 (9/22 추가) ─────────────────────────
    // 갭방어강제손절(-1.2%, 진입 슬리피지/얇은 호가창 신호)이 동일 코인에서 반복되면
    // 그 코인 자체가 구조적으로 저유동성이라는 뜻 — 승/패와 무관하게(연속손절 카운터와 달리
    // 중간에 이익이 껴도 리셋하지 않음) 누적 집계해 LIQUIDITY_BAN_TRIGGER_COUNT회 도달 시
    // 24시간 매수 차단한다. temporaryBanUntilMap(최대 1h)과 별도 맵으로 관리 — 아래
    // resetDailyStats()가 자정마다 비우는 대상에서 의도적으로 제외한다(24시간 차단이 자정을
    // 넘겨도 유지되어야 하므로). TradeExecutionService.executeSell 참고.
    /** 코인별 갭방어강제손절 누적 횟수(승패/날짜 무관 누적, 차단 등록 시 리셋) */
    public final Map<String, Integer>      gapDefenseLossCountMap = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 저유동성 24시간 차단 만료 시각 — resetDailyStats 대상 아님(자정에 안 지워짐) */
    public final Map<String, LocalDateTime> liquidityBanUntilMap    = new java.util.concurrent.ConcurrentHashMap<>();

    // ─── 10/7 모의매매 전면 개편 (무조건 매수 / ±1% 익절·손절, 노이즈 코인 ±1.5%) ─────────────
    // 아래 맵은 모두 포지션 단위 — 매수 시 등록, 매도 시 제거. 자정 초기화 대상 아님.
    /** 코인별 현재 포지션의 매수 행 id (trade_history.id) — 매도 행 ref_id 연결용 */
    public final Map<String, Long>         buyTradeIdMap  = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 보유 중 최고 수익률 %(MFE) — 패스트 루프(30초)마다 갱신 */
    public final Map<String, BigDecimal>   holdMaxRateMap = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 보유 중 최저 수익률 %(MAE) — 패스트 루프(30초)마다 갱신 */
    public final Map<String, BigDecimal>   holdMinRateMap = new java.util.concurrent.ConcurrentHashMap<>();
    /** 코인별 현재 포지션의 익절·손절 폭(%) — 매수 시 ATR로 결정(1.0 또는 1.5), 매도 시 제거 (10/8) */
    public final Map<String, BigDecimal>   exitPctMap     = new java.util.concurrent.ConcurrentHashMap<>();
    /** 시장 전체 흐름 참고값 — 슬로우 루프마다 갱신, 모든 코인 기록에 같이 남긴다 */
    @Getter
    @Setter
    private volatile BigDecimal btcRsi15m;
    @Getter
    @Setter
    private volatile BigDecimal btcChg1h;

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
        rsiTroughMap.clear();
        rsi15mPeakMap.clear();
        dcaCountMap.clear();
        lastDcaAtMap.clear();
        lossWatchRoundMap.clear();
        lossWatchRefPriceMap.clear();
        profitWatchRoundMap.clear();
        profitCooldownUntilMap.clear();
        log.info("=== 일일 통계 초기화 완료 — 당일퇴출 {}개·임시차단 {}개 해제, 연속손절·트레일링·추가매수 맵 초기화 ===",
                blacklistSize, tempBanSize);
    }
}
