package com.coin.coin.service;

import com.coin.coin.dto.CoinAccount;
import com.coin.coin.dto.CoinSignalDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 매매 루프 오케스트레이터.
 *
 * <p>10/16 개편 — 매매 판단은 전부 3분 단위로 통일, 30초 루프는 기록만:
 * <ul>
 *   <li>슬로우 루프(3분, fixedRate): 보유 코인 매도 판단 → 지표 계산 → exit_review 마무리 → 신규 매수
 *       (매도 후 추적 중인 코인은 매수 보류)</li>
 *   <li>패스트 루프(30초, fixedRate): 모의 지정가 체결 확인, 보유 중 최고·최저(30초 확인, 매도 행에 기록),
 *       본전탈출(손실이었다가 +0.1% 되면 즉시 매도 — 10/16, 30초 루프의 유일한 매매),
 *       매도후추적(매도 후 3·6·9·12·15분 + 구간 최고·최저)</li>
 * </ul>
 * 슬로우 루프에서 장타 모의매매(LongTradeService, 10/16)도 함께 돈다 — 기록은 long_trade, 경로는 long_trade_tick(10/21).
 * 10/21: 단타 신규 매수 중단(SHORT_TRADING_ENABLED=false). 지표 계산은 장타가 쓰므로 계속한다.
 * 두 루프는 스케줄러 스레드 2개(SchedulingConfig)로 나란히 돈다.
 * 10/7~10/15: 매도 판단은 패스트 루프(30초), 가상매매(VirtualTradeService)도 패스트 루프 — 10/16 폐지.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TradingScheduler {

    private final UpbitExchangeClient exchangeClient;
    private final TradingStateStore stateStore;
    private final PositionExitService positionExitService;
    private final CoinSignalService coinSignalService;
    private final ExitReviewService exitReviewService;
    private final PostSellTrackingService postSellTrackingService;
    private final HoldTrackingService holdTrackingService;
    private final LongTradeService longTradeService;

    /**
     * 단타 신규 매수 여부 (10/21: 중단 — 장타에 집중). false면 단타는 새로 사지 않고,
     * 이미 들고 있는 단타 포지션만 기존 규칙대로 정리된다(그 매도·매도후추적 행까지만 trade_history에 남고 이후로는 쌓이지 않음).
     */
    private static final boolean SHORT_TRADING_ENABLED = false;

    // ══════════════════════════════════════════════════════════════════
    //  패스트 루프 (30초) — 기록만 (매매 판단 없음)
    // ══════════════════════════════════════════════════════════════════
    @Scheduled(fixedRate = 30, timeUnit = TimeUnit.SECONDS)
    public void fastTrackLoop() {
        // 모의매매: 지정가 가정 주문 체결 확인 (실거래 모드에서는 아무것도 안 함)
        try {
            exchangeClient.checkPaperLimitOrders();
        } catch (Exception e) {
            log.warn("[지정가가정] 체결 확인 중 예외: {}", e.getMessage());
        }

        // 보유 중인 코인 30초 확인 — 최고·최저 갱신(매도 때 매도 행 max_rate/min_rate) + 본전탈출 판단 (10/16)
        try {
            for (HoldTrackingService.Snapshot snap : holdTrackingService.tick(exchangeClient.checkCoinAccount())) {
                try {
                    positionExitService.checkBreakEvenEscape(snap.coin(), snap.ratePct(), snap.minRatePct());
                } catch (Exception e) {
                    log.error("{} 본전탈출 처리 중 예외: {}", snap.coin(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.warn("[보유 최고·최저] 예외: {}", e.getMessage());
        }

        // 매도 후 3분 간격 5번(15분) 추적
        try {
            postSellTrackingService.track();
        } catch (Exception e) {
            log.warn("[매도후추적] 예외: {}", e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  슬로우 루프 (3분) — 매도 판단, 지표 계산, 신규 매수
    // ══════════════════════════════════════════════════════════════════
    @Scheduled(fixedRate = 3, timeUnit = TimeUnit.MINUTES)
    public void slowTradeLoop() {
        // 1. 매도 판단 — 루프 시작 시점에 바로 (3분 간격 유지)
        for (CoinAccount account : exchangeClient.checkCoinAccount()) {
            String coinNm = account.getCoinType() + "-" + account.getCoinName();
            if ("KRW-KRW".equals(coinNm)) continue;
            try {
                positionExitService.checkExit(account, coinNm);
            } catch (Exception e) {
                log.error("{} 익절/손절 처리 중 예외 — 이 코인만 스킵: {}", coinNm, e.getMessage(), e);
            }
        }

        // 2. 매도 반영된 계좌로 지표 계산
        List<CoinAccount> accountList = exchangeClient.checkCoinAccount();
        Set<String> holdCoinSet = accountList.stream()
                .map(a -> a.getCoinType() + "-" + a.getCoinName())
                .collect(Collectors.toSet());

        Map<String, CoinSignalDto> signalMap = coinSignalService.buildSignalMap(holdCoinSet);
        stateStore.getCachedSignalMap().forEach((c, sig) -> stateStore.prevRsiMap.put(c, sig.getRsi()));
        stateStore.setCachedSignalMap(signalMap);

        // 10/7 이전 매도분의 exit_review 추적 마무리 (신규 매도는 더 이상 exit_review에 등록하지 않음)
        try {
            exitReviewService.updateExitReviews(signalMap);
        } catch (Exception e) {
            log.warn("exit_review 갱신 중 예외: {}", e.getMessage());
        }

        // 장타 모의매매 (10/16) — 직전 3·6시간 크게 빠진 유동 코인 6시간 보유. long_trade 테이블, 단타와 무관
        try {
            longTradeService.run(signalMap);
        } catch (Exception e) {
            log.error("장타 처리 중 예외: {}", e.getMessage(), e);
        }

        // 3. 단타 신규 매수 — 매도 후 추적(15분) 중인 코인은 보류. 10/21~ 중단
        if (!SHORT_TRADING_ENABLED) return;
        try {
            Set<String> tracking = postSellTrackingService.trackingMarkets();
            coinSignalService.firstPurchaseCoin(holdCoinSet, signalMap, accountList, tracking);
        } catch (Exception e) {
            log.error("신규 매수 단계 중 예외 발생 — 이번 틱 신규 매수 스킵: {}", e.getMessage(), e);
        }
    }
}
