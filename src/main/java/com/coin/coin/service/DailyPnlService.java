package com.coin.coin.service;

import com.coin.coin.dto.CoinAccount;
import com.coin.coin.entity.TradeResult;
import com.coin.coin.repository.TradeHistoryRepository;
import com.coin.coin.repository.TradeResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 일별 손익 집계 — 매일 00:10 KST (UpbitApi 역할분리, 9/4).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DailyPnlService {

    private final TradeHistoryRepository tradeHistoryRepository;
    private final TradeResultRepository tradeResultRepository;
    private final UpbitExchangeClient exchangeClient;

    /**
     * 전날(KST 00:00 ~ 23:59:59) 거래 내역을 코인별로 집계해 trade_result 에 저장한다.
     *
     * <p>실현 손익(realizedPnl) = trade_history.realized_pnl 합산
     * (매도 시점에 "수령액 - 평균매수가×체결수량"으로 즉시 계산됨)
     * <p>미실현 손익(unrealizedPnl) = 집계 시점 현재가 × 보유수량 - 평균매수가 × 보유수량
     */
    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Seoul")
    @Transactional
    public void calculateDailyPnl() {
        LocalDate targetDate = LocalDate.now().minusDays(1);
        LocalDateTime start = targetDate.atStartOfDay();
        LocalDateTime end = targetDate.plusDays(1).atStartOfDay();

        log.info("=== 일별 손익 집계 시작: {} ===", targetDate);

        // 1. 대상 날짜에 거래한 코인 목록
        List<String> markets = tradeHistoryRepository.findDistinctMarkets(start, end);
        if (markets.isEmpty()) {
            log.info("집계 대상 거래 없음 - 종료");
            return;
        }

        // 2. 매수 통계 (금액·건수)
        List<Object[]> buyAgg = tradeHistoryRepository.aggregateByMarketAndType(start, end);
        Map<String, BigDecimal> buyAmountMap = new HashMap<>();
        Map<String, BigDecimal> sellAmountMap = new HashMap<>();
        Map<String, Integer> buyCountMap = new HashMap<>();

        for (Object[] row : buyAgg) {
            String market = (String) row[0];
            String tradeType = (String) row[1];
            BigDecimal sum = (BigDecimal) row[2];
            int count = ((Number) row[3]).intValue();
            if ("매수".equals(tradeType)) {
                buyAmountMap.put(market, sum);
                buyCountMap.put(market, count);
            } else {
                sellAmountMap.merge(market, sum, BigDecimal::add);
            }
        }

        // 3. 실현손익 집계 — trade_history.realized_pnl 직접 합산
        //    [market, SUM(realized_pnl), COUNT(익절), COUNT(손절)]
        List<Object[]> pnlAgg = tradeHistoryRepository.sumRealizedPnlByMarket(start, end);
        Map<String, BigDecimal> realizedMap = new HashMap<>();
        Map<String, Integer> profitCntMap = new HashMap<>();
        Map<String, Integer> stopCntMap = new HashMap<>();

        for (Object[] row : pnlAgg) {
            String market = (String) row[0];
            realizedMap.put(market, (BigDecimal) row[1]);
            profitCntMap.put(market, ((Number) row[2]).intValue());
            stopCntMap.put(market, ((Number) row[3]).intValue());
        }

        // 4. 현재 보유 잔고 조회 (미실현 손익 계산용)
        List<CoinAccount> accounts = exchangeClient.checkCoinAccount();
        Map<String, CoinAccount> accountMap = accounts.stream()
                .filter(a -> !a.getCoinName().equals("KRW"))
                .collect(Collectors.toMap(
                        a -> a.getCoinType() + "-" + a.getCoinName(),
                        a -> a,
                        (a, b) -> a
                ));

        // 5. 코인별 TradeResult 저장
        LocalDateTime now = LocalDateTime.now();
        for (String market : markets) {
            if (tradeResultRepository.existsByMarketAndTradeDate(market, targetDate)) {
                log.info("{} {} 이미 집계됨 - 스킵", market, targetDate);
                continue;
            }

            BigDecimal realized = realizedMap.getOrDefault(market, BigDecimal.ZERO);
            BigDecimal buyAmt = buyAmountMap.getOrDefault(market, BigDecimal.ZERO);
            BigDecimal sellAmt = sellAmountMap.getOrDefault(market, BigDecimal.ZERO);
            int buyCnt = buyCountMap.getOrDefault(market, 0);
            int profitCnt = profitCntMap.getOrDefault(market, 0);
            int stopCnt = stopCntMap.getOrDefault(market, 0);

            // 미실현 손익: 집계 시점 현재 보유분
            BigDecimal unrealized = BigDecimal.ZERO;
            CoinAccount account = accountMap.get(market);
            if (account != null && account.getBalance().compareTo(BigDecimal.ZERO) > 0) {
                try {
                    BigDecimal currentPrice = exchangeClient.checkCoinPrice(market).getBidPrice();
                    BigDecimal costBasis = account.getAvgBuyPrice().multiply(account.getBalance());
                    BigDecimal currentValue = currentPrice.multiply(account.getBalance());
                    unrealized = currentValue.subtract(costBasis);
                } catch (Exception e) {
                    log.warn("{} 현재가 조회 실패 - 미실현 손익 0 처리: {}", market, e.getMessage());
                }
            }

            TradeResult result = TradeResult.builder()
                    .market(market)
                    .tradeDate(targetDate)
                    .buyAmount(buyAmt)
                    .sellAmount(sellAmt)
                    .buyCount(buyCnt)
                    .profitCount(profitCnt)
                    .stopCount(stopCnt)
                    .realizedPnl(realized)
                    .unrealizedPnl(unrealized)
                    .totalPnl(realized.add(unrealized))
                    .createdAt(now)
                    .build();

            tradeResultRepository.save(result);
            log.info("{} {} 손익 저장 완료 - 실현:{}, 미실현:{}, 합계:{}",
                    market, targetDate, realized, unrealized, realized.add(unrealized));
        }

        // 6. 당일 전체 합계 로그
        BigDecimal cumulative = tradeResultRepository.sumTotalPnl();
        log.info("=== 일별 손익 집계 완료 / 전체 누적 총손익: {}원 ===", cumulative);
    }
}
