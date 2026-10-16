package com.coin.coin.service;

import com.coin.coin.dto.CoinAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.coin.coin.dto.TradeHistoryDto.pct;

/**
 * 보유 구간 최고·최저 (10/16) — 매수 후 매도 전까지 30초마다 매수호가를 보고, 평균매수가 대비 최고·최저 수익률만 메모리에 둔다.
 *
 * <p>매도 판단은 3분마다(슬로우 루프) 하지만, 그 사이 30초 단위로 얼마나 올랐다/내렸다 했는지를 매도 행의
 * max_rate / min_rate 에 남기기 위한 것이다(TradeExecutionService가 매도 때 {@link #finish}로 가져감).
 * 10/16 본전탈출: 매 확인마다 지금 변화율과 최저를 돌려주고, TradingScheduler가 PositionExitService.checkBreakEvenEscape로 판단한다.
 * 행을 따로 쌓지 않는다.
 *
 * <p>앱을 재시작하면 그 시점부터 다시 잰다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HoldTrackingService {

    private final UpbitExchangeClient exchangeClient;

    private static final class Range {
        final BigDecimal avgBuyPrice;
        BigDecimal max;
        BigDecimal min;

        Range(BigDecimal avgBuyPrice) {
            this.avgBuyPrice = avgBuyPrice;
        }
    }

    private final Map<String, Range> ranges = new ConcurrentHashMap<>();

    /** 이번 확인에서 본 값 — 본전탈출 판단용 (코인, 지금 변화율 %, 보유 중 최저 %) */
    public record Snapshot(String coin, BigDecimal ratePct, BigDecimal minRatePct) {
    }

    /** 패스트 루프(30초)에서 호출 — 보유 코인별 지금 변화율과 최저를 돌려준다 */
    public List<Snapshot> tick(List<CoinAccount> accounts) {
        List<Snapshot> result = new ArrayList<>();
        Set<String> held = new HashSet<>();
        for (CoinAccount a : accounts) {
            String coin = a.getCoinType() + "-" + a.getCoinName();
            if ("KRW-KRW".equals(coin)) continue;
            BigDecimal avg = a.getAvgBuyPrice();
            if (avg == null || avg.signum() <= 0 || a.getBalance() == null || a.getBalance().signum() <= 0) continue;
            held.add(coin);
            try {
                Range r = ranges.get(coin);
                if (r == null || r.avgBuyPrice.compareTo(avg) != 0) { // 새 포지션
                    r = new Range(avg);
                    ranges.put(coin, r);
                }
                BigDecimal rate = pct(avg, exchangeClient.checkCoinPrice(coin).getBidPrice(), 3);
                r.max = r.max == null ? rate : r.max.max(rate);
                r.min = r.min == null ? rate : r.min.min(rate);
                result.add(new Snapshot(coin, rate, r.min));
            } catch (Exception e) {
                log.warn("{} [보유 최고·최저] 가격 확인 실패: {}", coin, e.getMessage());
            }
        }
        ranges.keySet().retainAll(held);
        return result;
    }

    /** 매도 시 호출 — {최고, 최저} 수익률(%)을 돌려주고 지운다. 잰 적이 없으면 null */
    public BigDecimal[] finish(String coin) {
        Range r = ranges.remove(coin);
        return r == null || r.max == null ? null : new BigDecimal[]{r.max, r.min};
    }
}
