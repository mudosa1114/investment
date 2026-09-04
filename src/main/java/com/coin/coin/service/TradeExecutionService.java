package com.coin.coin.service;

import com.coin.coin.dto.CoinSignalDto;
import com.coin.coin.dto.response.OrderResponse;
import com.coin.coin.dto.response.OrdersResponse;
import com.coin.coin.entity.LastTrade;
import com.coin.coin.entity.TradeHistory;
import com.coin.coin.repository.LastTradeRepository;
import com.coin.coin.repository.TradeHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static com.coin.coin.dto.LastTradeDto.damageTrade;
import static com.coin.coin.dto.LastTradeDto.profitTrade;
import static com.coin.coin.dto.TradeHistoryDto.sellHistory;

/**
 * 매도 체결 및 매도 이후 부기(LastTrade/TradeHistory/연속손절·블랙리스트 카운트) 담당
 * (UpbitApi 역할분리, 9/4). 매도 판단 자체는 {@link PositionExitService}가 하고,
 * 여기서는 결정된 매도를 실행·기록만 한다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TradeExecutionService {

    private final LastTradeRepository lastTradeRepository;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final UpbitExchangeClient exchangeClient;
    private final TradingStateStore stateStore;
    private final ExitReviewService exitReviewService;

    public void executeSell(String coinNm, String volume, String type,
                             CoinSignalDto signal, BigDecimal avgBuyPrice, String reason) {
        OrdersResponse response = exchangeClient.orderCoin(coinNm, "ask", volume);
        try {
            Thread.sleep(2000);
            OrderResponse result = exchangeClient.checkCoin(response.getUuid());
            BigDecimal sellUnitPrice = exchangeClient.orderPrice(coinNm).get("bidPrice");
            BigDecimal executedVol   = new BigDecimal(result.getExecutedVolume());
            BigDecimal amount        = executedVol.multiply(sellUnitPrice);

            int lastDropCount   = lastTradeRepository.findByMarket(coinNm)
                    .map(LastTrade::getDropCount).orElse(0);
            int lastProfitCount = lastTradeRepository.findByMarket(coinNm)
                    .map(LastTrade::getProfitCount).orElse(0);

            log.info("{} 판매 완료 - 체결금액:{} type:{}", coinNm, amount, type);
            TradeHistory history = sellHistory(coinNm, amount, avgBuyPrice, executedVol, signal);

            // 매도 판단 사후 검증 레코드 생성 (24시간 가격 추적 시작)
            exitReviewService.recordExitReview(coinNm, type, reason, sellUnitPrice, avgBuyPrice, signal);

            if (type.equals("damage")) {
                LastTrade lt = damageTrade(coinNm, amount, sellUnitPrice, signal)
                        .toBuilder()
                        .dropCount(lastDropCount + 1)
                        .profitCount(lastProfitCount)
                        .build();
                lastTradeRepository.save(lt);
                tradeHistoryRepository.save(history.toBuilder().tradeType("손절").build());

                // ── 연속 손절 카운트 → 3회: 20분 차단 / 5회: 1시간 차단 ──
                // (8/25 거래빈도 확대: 하루 80~100건 목표에서는 손절 몇 번만으로 당일 블랙리스트에
                //  넣으면 거래 기회 자체가 사라짐 — 임계값을 올리고 "당일 블랙리스트"가 아닌
                //  "짧은 임시차단"으로 완화. 대신 아래 일일 누적 카운트가 진짜 부진 코인을 걸러냄)
                // 패-승-패-패: profit 시 카운트 0으로 리셋 → 최대 2 → 차단 미발동
                int lossCount = stateStore.consecutiveLossMap.merge(coinNm, 1, Integer::sum);
                if (lossCount == 3) {
                    LocalDateTime banUntil = LocalDateTime.now().plusMinutes(20);
                    stateStore.temporaryBanUntilMap.put(coinNm, banUntil);
                    log.warn("{} 연속 손절 3회 → 20분 차단 (해제: {})",
                            coinNm, banUntil.toString().replace("T", " ").substring(0, 16));
                } else if (lossCount >= 5) {
                    LocalDateTime banUntil = LocalDateTime.now().plusHours(1);
                    stateStore.temporaryBanUntilMap.put(coinNm, banUntil);
                    stateStore.consecutiveLossMap.remove(coinNm); // 차단 등록 후 카운트 정리
                    log.warn("{} 연속 손절 {}회 → 1시간 차단 (해제: {})",
                            coinNm, lossCount, banUntil.toString().replace("T", " ").substring(0, 16));
                }

                // ── 일일 누적 손절 카운트 → 8회 달성 시 당일 블랙리스트 ──────
                // 연속손절 카운터와 달리 이익이 끼어도 리셋되지 않음
                // (8/25 거래빈도 확대: 3회 → 8회로 상향 — 하루 거래량 자체가 늘어난 만큼
                //  절대 손절 횟수 기준도 비례해서 올려야 정상 변동성까지 블랙리스트로 막지 않음)
                // 목적: 소액 이익 1회가 카운터를 리셋하고 계속 진입하는 패턴 차단
                if (!stateStore.dailyBlacklistSet.contains(coinNm)) {
                    int totalDailyLoss = stateStore.dailyTotalLossMap.merge(coinNm, 1, Integer::sum);
                    if (totalDailyLoss >= 8) {
                        stateStore.dailyBlacklistSet.add(coinNm);
                        log.warn("{} 일일 누적 손절 {}회 → 당일 블랙리스트 (자정 해제) [연속과 무관]",
                                coinNm, totalDailyLoss);
                    }
                }
                return;
            }

            if (type.equals("profit")) {
                LastTrade lt = profitTrade(coinNm, amount, sellUnitPrice, signal)
                        .toBuilder()
                        .dropCount(lastDropCount)
                        .profitCount(lastProfitCount + 1)
                        .profitAnchorPrice(avgBuyPrice) // 익절 시 평균매수가 기록 → 재진입 기준가격으로 활용
                        .build();
                lastTradeRepository.save(lt);
                log.info("{} 익절 후 재진입 앵커 저장 (기준가: {}원) — 앱 재시작 후에도 유지됨",
                        coinNm, avgBuyPrice.setScale(0, java.math.RoundingMode.HALF_UP));
                tradeHistoryRepository.save(history.toBuilder().tradeType("익절").build());

                // 익절 시 연속 손절 카운터 초기화 (손절 패턴 끊김)
                stateStore.consecutiveLossMap.put(coinNm, 0);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Sell interrupted", e);
        }
    }
}
