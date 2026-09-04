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
 * 매매 루프 오케스트레이터 — 패스트 루프(30초)/슬로우 루프(3분)만 담당하고
 * 실제 판단·실행은 각 서비스에 위임한다 (UpbitApi 역할분리, 9/4).
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

    // ══════════════════════════════════════════════════════════════════
    //  패스트 루프 (30초) — 현재가 기반: 하드 익절/손절, DCA
    //  캔들 지표를 조회하지 않으므로 API 호출 최소화
    // ══════════════════════════════════════════════════════════════════
    @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void fastPriceCheck() {
        // 슬로우 루프가 한 번도 실행되지 않은 초기 상태라면 스킵
        if (stateStore.getCachedSignalMap().isEmpty()) {
            log.info("지표 캐시 미준비 - 슬로우 루프 대기 중");
            return;
        }

        boolean halted = positionExitService.isDailyLossHaltTriggered();

        List<CoinAccount> accountList = exchangeClient.checkCoinAccount();
        for (CoinAccount account : accountList) {
            String coinNm = account.getCoinType() + "-" + account.getCoinName();
            if ("KRW-KRW".equals(coinNm)) continue;

            CoinSignalDto signal = stateStore.getCachedSignalMap().get(coinNm);
            if (signal == null) continue;

            if (halted) {
                // 정지 상태: 하드 익절·손절만 실행 (DCA 차단)
                positionExitService.executeHardExitsOnly(account, coinNm, signal);
            } else {
                positionExitService.executePriceBasedActions(account, coinNm, signal);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  슬로우 루프 (3분) — 캔들 지표 기반: 점수 익절/손절, 최초 매수
    //  3분봉이 최단 캔들이므로 이보다 짧은 주기는 동일한 지표를 반복 계산할 뿐
    // ══════════════════════════════════════════════════════════════════
    @Scheduled(fixedDelay = 3, timeUnit = TimeUnit.MINUTES)
    public void slowIndicatorCheck() {
        List<CoinAccount> accountList = exchangeClient.checkCoinAccount();

        Set<String> holdCoinSet = accountList.stream()
                .map(a -> a.getCoinType() + "-" + a.getCoinName())
                .collect(Collectors.toSet());

        // 지표 빌드 후 캐시 갱신 (패스트 루프가 즉시 새 캐시 참조)
        Map<String, CoinSignalDto> signalMap = coinSignalService.buildSignalMap(holdCoinSet);
        // RSI 방향 필터용: 새 캐시 교체 전에 현재 RSI를 이전값으로 저장
        stateStore.getCachedSignalMap().forEach((c, sig) -> stateStore.prevRsiMap.put(c, sig.getRsi()));
        stateStore.setCachedSignalMap(signalMap);

        // 매도 판단 사후 검증 — signalMap에 이미 조회된 현재가에 편승, 추가 API 호출 없음
        // (봇 정지 상태에서도 가격 추적 자체는 계속되어야 하므로 Circuit Breaker 이전에 실행)
        exitReviewService.updateExitReviews(signalMap);

        // ── Circuit Breaker: 일일 손실 한도 도달 시 매매 전면 중단 ────
        if (positionExitService.isDailyLossHaltTriggered()) {
            log.warn("=== 봇 정지 상태 — 점수 매매·신규 매수 스킵 (하드 익절/손절은 패스트 루프에서 유지) ===");
            return;
        }

        // ── 보유 코인 점수 기반 익절/손절 ────────────────────────────
        for (CoinAccount account : accountList) {
            String coinNm = account.getCoinType() + "-" + account.getCoinName();
            if ("KRW-KRW".equals(coinNm)) continue;

            CoinSignalDto signal = signalMap.get(coinNm);
            if (signal == null) {
                log.warn("{} 지표 데이터 없음, 스킵", coinNm);
                continue;
            }
            positionExitService.evaluateScoreBasedExit(account, coinNm, signal);
        }

        // ── 미보유 코인 최초 매수 ────────────────────────────────────
        coinSignalService.firstPurchaseCoin(holdCoinSet, signalMap, accountList);
    }
}
