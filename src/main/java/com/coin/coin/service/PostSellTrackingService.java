package com.coin.coin.service;

import com.coin.coin.dto.CoinPrice;
import com.coin.coin.entity.TradeHistory;
import com.coin.coin.repository.TradeHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.coin.coin.dto.TradeHistoryDto.pct;
import static com.coin.coin.dto.TradeHistoryDto.trackRow;

/**
 * 매도 후 추적 — "안 팔고 더 들고 있었다면"을 보기 위해 익절·손절 후 3·6·9·12·15분 시점을
 * trade_history에 trade_type='매도후추적' 행으로 남긴다(10/16 이전 행은 '추적').
 *
 * <p>10/16 변경: 15·30·45·60분 → 매매 판단 주기(3분)에 맞춰 3분 간격 5번(15분).
 * 패스트 루프(30초)에서 호출해 가격을 보고, 각 3분 구간의 30초 기준 최고·최저를 함께 남긴다.
 * 기록 시각은 해당 시점에서 최대 30초 늦을 수 있다(traded_at에 실제 시각).
 * 추적 중인 코인은 추적이 끝날 때까지 다시 매수하지 않는다({@link #trackingMarkets()}).
 * <ul>
 *   <li>ref_id = 매도 행 id, track_minute = 경과 분(3/6/9/12/15)</li>
 *   <li>pnl_rate = 매도 체결가 대비 현재 매수호가 변화율(%)</li>
 *   <li>max_rate / min_rate = 직전 3분 구간(첫 행은 매도 직후부터) 30초 확인 기준 최고·최저 매수호가의 매도가 대비 %</li>
 *   <li>지표 컬럼 = 슬로우 루프 캐시 지표(최대 3분 전, signal_age_sec)</li>
 * </ul>
 * 늦어서 지나친 시점은 건너뛰고 도래한 가장 최근 시점 하나만 기록한다. 구간 최고·최저는 메모리에 있어
 * 앱을 재시작하면 그 구간은 재시작 이후 값만 반영된다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PostSellTrackingService {

    private final TradeHistoryRepository tradeHistoryRepository;
    private final UpbitExchangeClient exchangeClient;
    private final TradingStateStore stateStore;

    /** 추적 간격(분) — 매매 판단 주기와 같게 */
    public static final int TRACK_INTERVAL_MINUTES = 3;
    /** 추적 횟수 — 3·6·9·12·15분 */
    public static final int TRACK_COUNT = 5;
    private static final int TRACK_END_MINUTES = TRACK_INTERVAL_MINUTES * TRACK_COUNT;
    /** 마지막 시점 이후 이 시간(초)까지 기록 못 하면 포기 — 매수 차단도 이때 풀린다 */
    private static final int GIVE_UP_SECONDS = 120;

    /** 매도 행 id → 현재 3분 구간의 최고·최저 매수호가 */
    private static final class Window {
        BigDecimal max;
        BigDecimal min;
    }

    private final Map<Long, Window> windows = new ConcurrentHashMap<>();

    /** 패스트 루프(30초)에서 호출 */
    public void track() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusSeconds(TRACK_END_MINUTES * 60L + GIVE_UP_SECONDS);
        List<TradeHistory> sells = realSells(since);
        if (sells.isEmpty()) {
            windows.clear();
            return;
        }
        Map<Long, Integer> done = doneMinutes(since);
        Map<String, CoinPrice> prices = new HashMap<>();
        Set<Long> active = new HashSet<>();

        for (TradeHistory sell : sells) {
            int doneMin = done.getOrDefault(sell.getId(), 0);
            if (doneMin >= TRACK_END_MINUTES) continue;
            active.add(sell.getId());
            String market = sell.getMarket();
            try {
                CoinPrice p = prices.get(market);
                if (p == null) {
                    p = exchangeClient.checkCoinPrice(market);
                    prices.put(market, p);
                }
                BigDecimal bid = p.getBidPrice();
                Window w = windows.computeIfAbsent(sell.getId(), id -> new Window());
                w.max = w.max == null ? bid : w.max.max(bid);
                w.min = w.min == null ? bid : w.min.min(bid);

                long elapsedMin = Duration.between(sell.getTradedAt(), now).getSeconds() / 60;
                int due = (int) Math.min(TRACK_END_MINUTES, elapsedMin / TRACK_INTERVAL_MINUTES * TRACK_INTERVAL_MINUTES);
                if (due <= doneMin) continue;

                BigDecimal base = sell.getPrice();
                TradeHistory saved = tradeHistoryRepository.save(trackRow(sell, due, bid, p.getAskPrice(),
                        pct(base, w.max, 3), pct(base, w.min, 3), stateStore.getCachedSignalMap().get(market)));
                windows.remove(sell.getId()); // 다음 3분 구간 새로 시작
                log.debug("{} [매도후추적] {}분 매도가 대비:{}% (구간 최고:{}% 최저:{}%)",
                        market, due, saved.getPnlRate(), saved.getMaxRate(), saved.getMinRate());
            } catch (Exception e) {
                log.warn("{} 매도후 추적 실패 (매도 id:{}): {}", market, sell.getId(), e.getMessage());
            }
        }
        windows.keySet().retainAll(active);
    }

    /**
     * 매도 후 추적 중인 코인 — 최근 매도 중 15분 추적이 아직 안 끝난 코인. 이 코인들은 매수하지 않는다.
     * 마지막 시점 + {@value #GIVE_UP_SECONDS}초가 지나면 기록 여부와 관계없이 풀린다.
     */
    public Set<String> trackingMarkets() {
        LocalDateTime since = LocalDateTime.now().minusSeconds(TRACK_END_MINUTES * 60L + GIVE_UP_SECONDS);
        List<TradeHistory> sells = realSells(since);
        Set<String> result = new HashSet<>();
        if (sells.isEmpty()) return result;
        Map<Long, Integer> done = doneMinutes(since);
        for (TradeHistory sell : sells) {
            if (done.getOrDefault(sell.getId(), 0) < TRACK_END_MINUTES) result.add(sell.getMarket());
        }
        return result;
    }

    private List<TradeHistory> realSells(LocalDateTime since) {
        return tradeHistoryRepository.findSellsSince(since).stream()
                .filter(t -> !"가상매도".equals(t.getTradeType()))
                .filter(t -> t.getPrice() != null && t.getPrice().signum() > 0)
                .toList();
    }

    private Map<Long, Integer> doneMinutes(LocalDateTime since) {
        Map<Long, Integer> done = new HashMap<>();
        for (Object[] row : tradeHistoryRepository.maxTrackMinuteByRef(since)) {
            if (row[0] == null || row[1] == null) continue;
            done.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }
        return done;
    }
}
