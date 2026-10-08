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
 * <p>10/7 모의매매 전면 개편:
 * <ul>
 *   <li>패스트 루프(30초): 보유 코인 ±1% 익절·손절, 3분 ATR% ≥ 0.5 코인만 ±1.5%</li>
 *   <li>슬로우 루프(3분): 지표 계산(기록용) → 매도 후 추적 기록 → 미보유 코인 무조건 매수</li>
 * </ul>
 * 일일 손실 한도(Circuit Breaker)와 점수 기반 매도는 모의매매 기간 동안 제거 — 데이터 수집이 목적이라
 * 중간에 멈추지 않는다.
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

    // ══════════════════════════════════════════════════════════════════
    //  패스트 루프 (30초) — 익절/손절
    // ══════════════════════════════════════════════════════════════════
    @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void fastPriceCheck() {
        // 모의매매: 지정가 가정 주문 체결 확인 (실거래 모드에서는 아무것도 안 함)
        try {
            exchangeClient.checkPaperLimitOrders();
        } catch (Exception e) {
            log.warn("[지정가가정] 체결 확인 중 예외: {}", e.getMessage());
        }

        List<CoinAccount> accountList = exchangeClient.checkCoinAccount();
        for (CoinAccount account : accountList) {
            String coinNm = account.getCoinType() + "-" + account.getCoinName();
            if ("KRW-KRW".equals(coinNm)) continue;
            try {
                positionExitService.checkExit(account, coinNm);
            } catch (Exception e) {
                log.error("{} 익절/손절 처리 중 예외 — 이 코인만 스킵: {}", coinNm, e.getMessage(), e);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  슬로우 루프 (3분) — 지표 계산(기록용), 매도 후 추적, 무조건 매수
    // ══════════════════════════════════════════════════════════════════
    @Scheduled(fixedDelay = 3, timeUnit = TimeUnit.MINUTES)
    public void slowIndicatorCheck() {
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

        // 매도 후 1~24시간 매시간 가격·지표 기록 (trade_history '추적' 행)
        try {
            postSellTrackingService.track(signalMap);
        } catch (Exception e) {
            log.error("매도 후 추적 중 예외: {}", e.getMessage(), e);
        }

        // 미보유 코인 무조건 매수
        try {
            coinSignalService.firstPurchaseCoin(holdCoinSet, signalMap, accountList);
        } catch (Exception e) {
            log.error("신규 매수 단계 중 예외 발생 — 이번 틱 신규 매수 스킵: {}", e.getMessage(), e);
        }
    }
}
