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
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static com.coin.coin.dto.LastTradeDto.damageTrade;
import static com.coin.coin.dto.LastTradeDto.profitTrade;
import static com.coin.coin.dto.TradeHistoryDto.holdRangeRow;
import static com.coin.coin.dto.TradeHistoryDto.sellRow;

/**
 * 매도 체결 + 기록.
 *
 * <p>10/7 모의매매 개편: 연속손절 임시차단 / 일일 블랙리스트 / 저유동성 차단 / 익절 앵커 / 매도검증(exit_review)
 * 기록을 모두 제거했다(매수 필터가 없어져 의미가 없고, 매도 후 추적은 trade_history '추적'(10/16~ '매도후추적') 행으로 대체).
 * last_trade는 CoinListService의 동적 코인 선정(손절 과다 제외·승률 보정)이 계속 쓰므로 그대로 남긴다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TradeExecutionService {

    private final LastTradeRepository lastTradeRepository;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final UpbitExchangeClient exchangeClient;
    private final TradingStateStore stateStore;
    private final HoldTrackingService holdTrackingService;

    /**
     * @param type   "profit" | "damage"
     * @param signal 매도 당시 지표 (기록용)
     * @param reason 기록용 사유 (익절+1% / 손절-1.5% 등)
     */
    public void executeSell(String coinNm, String volume, String type,
                            CoinSignalDto signal, BigDecimal avgBuyPrice, String reason) {
        OrdersResponse response = exchangeClient.orderCoin(coinNm, "ask", volume);
        try {
            if (!exchangeClient.isPaperMode()) {
                Thread.sleep(2000); // 실거래: 체결 반영 대기
            }
            OrderResponse result = exchangeClient.checkCoin(response.getUuid());
            BigDecimal executedVol = new BigDecimal(result.getExecutedVolume());
            BigDecimal sellUnitPrice = weightedAvgFillPrice(result, executedVol);
            BigDecimal amount = executedVol.multiply(sellUnitPrice);

            // 매수 행 연결 + 보유시간 — 메모리 상태 우선, 없으면(재시작 등) 가장 최근 매수 행
            Long buyRowId = stateStore.buyTradeIdMap.remove(coinNm);
            LocalDateTime entryAt = stateStore.positionEntryTimeMap.remove(coinNm);
            if (buyRowId == null || entryAt == null) {
                Optional<TradeHistory> lastBuy = tradeHistoryRepository.findTopByMarketAndTradeTypeOrderByIdDesc(coinNm, "매수");
                if (lastBuy.isPresent()) {
                    if (buyRowId == null) buyRowId = lastBuy.get().getId();
                    if (entryAt == null) entryAt = lastBuy.get().getTradedAt();
                }
            }
            BigDecimal holdMinutes = entryAt == null ? null
                    : BigDecimal.valueOf(Duration.between(entryAt, LocalDateTime.now()).getSeconds())
                    .divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP);
            // 보유 중 최고·최저 — 30초 확인(HoldTrackingService)과 3분 매도 판단 때 본 값을 합친다 (10/16)
            BigDecimal[] range30s = holdTrackingService.finish(coinNm);
            BigDecimal maxRate = maxOf(stateStore.holdMaxRateMap.remove(coinNm), range30s == null ? null : range30s[0]);
            BigDecimal minRate = minOf(stateStore.holdMinRateMap.remove(coinNm), range30s == null ? null : range30s[1]);
            stateStore.exitPctMap.remove(coinNm);
            stateStore.prevRateMap.remove(coinNm);
            stateStore.dropStreakMap.remove(coinNm);
            stateStore.highAtMap.remove(coinNm);

            String tradeType = "damage".equals(type) ? "손절" : "익절";
            TradeHistory saved = tradeHistoryRepository.save(sellRow(coinNm, tradeType, reason,
                    sellUnitPrice, executedVol, avgBuyPrice, buyRowId, holdMinutes, maxRate, minRate, signal));

            // 보유중추적 — 매수~매도 사이 최고·최저 1행 (10/16). 매도 행 max_rate/min_rate와 같은 값
            if (maxRate != null || minRate != null) {
                try {
                    tradeHistoryRepository.save(holdRangeRow(coinNm, buyRowId, avgBuyPrice, holdMinutes, maxRate, minRate));
                } catch (Exception e) {
                    log.warn("{} 보유중추적 저장 실패: {}", coinNm, e.getMessage());
                }
            }

            log.info("{} [{}] 매도 완료 — 체결가:{} 수량:{} 수령액:{}원 실현손익:{}원({}%) 보유:{}분",
                    coinNm, reason, sellUnitPrice.stripTrailingZeros().toPlainString(), executedVol.toPlainString(),
                    saved.getOrderPrice(), saved.getRealizedPnl(), saved.getPnlRate(), holdMinutes);

            // last_trade — 동적 코인 선정(CoinListService)용 손절/익절 카운트
            Optional<LastTrade> prev = lastTradeRepository.findByMarket(coinNm);
            int lastDropCount = prev.map(LastTrade::getDropCount).orElse(0);
            int lastProfitCount = prev.map(LastTrade::getProfitCount).orElse(0);
            LastTrade lt = "damage".equals(type)
                    ? damageTrade(coinNm, amount, sellUnitPrice, signal).toBuilder()
                    .dropCount(lastDropCount + 1).profitCount(lastProfitCount).build()
                    : profitTrade(coinNm, amount, sellUnitPrice, signal).toBuilder()
                    .dropCount(lastDropCount).profitCount(lastProfitCount + 1).build();
            lastTradeRepository.save(lt);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Sell interrupted", e);
        }
    }

    private static BigDecimal maxOf(BigDecimal a, BigDecimal b) {
        return a == null ? b : b == null ? a : a.max(b);
    }

    private static BigDecimal minOf(BigDecimal a, BigDecimal b) {
        return a == null ? b : b == null ? a : a.min(b);
    }

    /**
     * 주문 결과(result.getTrades())의 실제 체결 내역으로 가중평균 체결가를 계산한다.
     * Σ(체결가×체결량) / Σ체결량. trades가 비어있으면 현재 호가를 폴백으로 사용한다.
     */
    private BigDecimal weightedAvgFillPrice(OrderResponse result, BigDecimal executedVol) {
        if (result.getTrades() == null || result.getTrades().isEmpty()) {
            log.warn("{} 체결 내역(trades) 없음 — 호가 폴백 사용", result.getMarket());
            return exchangeClient.orderPrice(result.getMarket()).get("bidPrice");
        }
        BigDecimal totalFunds = BigDecimal.ZERO;
        BigDecimal totalVol = BigDecimal.ZERO;
        for (OrderResponse.Traders trade : result.getTrades()) {
            BigDecimal vol = new BigDecimal(trade.getVolume());
            BigDecimal price = new BigDecimal(trade.getPrice());
            totalFunds = totalFunds.add(price.multiply(vol));
            totalVol = totalVol.add(vol);
        }
        if (totalVol.compareTo(BigDecimal.ZERO) == 0) {
            return exchangeClient.orderPrice(result.getMarket()).get("bidPrice");
        }
        return totalFunds.divide(totalVol, 10, RoundingMode.HALF_UP);
    }
}
