package com.coin.coin.service;

import com.coin.coin.dto.CoinSignalDto;
import com.coin.coin.entity.TradeHistory;
import com.coin.coin.repository.TradeHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.coin.coin.dto.TradeHistoryDto.trackRow;

/**
 * 매도 후 추적 — 익절·손절 모든 매도에 대해 매도 후 15·30·45·60분 시점의 가격과 지표를
 * trade_history에 trade_type='추적' 행으로 남긴다.
 *
 * <p>10/8 변경: 1~24시간 매시간 → 15분 단위 60분까지. 단타라 24시간을 볼 일이 없고,
 * 매도 폭(±1%/±1.5%) 검증에는 매도 후 1시간이면 충분하다.
 * <ul>
 *   <li>ref_id = 매도 행 id, track_minute = 경과 분(15/30/45/60)</li>
 *   <li>pnl_rate = 매도 체결가 대비 현재 매수호가 변화율(%)</li>
 *   <li>max_rate / min_rate = 직전 15분(3분봉 5개) 고가·저가의 매도가 대비 %</li>
 *   <li>나머지 지표 컬럼은 매수/매도 행과 동일</li>
 * </ul>
 * 슬로우 루프(3분)마다 호출되므로 실제 기록 시각은 해당 시점에서 최대 3~4분 늦을 수 있다(traded_at에 실제 시각).
 * 앱이 꺼져 있던 동안 지나간 시점은 건너뛰고, 도래한 가장 최근 시점 하나만 기록한다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PostSellTrackingService {

    private final TradeHistoryRepository tradeHistoryRepository;
    private final CoinSignalService coinSignalService;

    /** 추적 시점(매도 후 경과 분) */
    static final int[] TRACK_MINUTES = {15, 30, 45, 60};
    /** 마지막 시점 이후 이 시간(분)까지 늦어도 기록 — 그보다 늦으면(앱 중단 등) 포기 */
    private static final int LATE_TOLERANCE_MINUTES = 10;
    /** 슬로우 루프 캐시 지표를 그대로 써도 되는 최대 경과 시간(초) — 넘으면 새로 계산 */
    private static final long SIGNAL_FRESH_SECONDS = 240;

    public void track(Map<String, CoinSignalDto> signalMap) {
        LocalDateTime now = LocalDateTime.now();
        int lastMinute = TRACK_MINUTES[TRACK_MINUTES.length - 1];
        LocalDateTime since = now.minusMinutes(lastMinute + LATE_TOLERANCE_MINUTES);

        List<TradeHistory> sells = tradeHistoryRepository.findSellsSince(since);
        if (sells.isEmpty()) return;

        Map<Long, Integer> doneMinute = new HashMap<>();
        for (Object[] row : tradeHistoryRepository.maxTrackMinuteByRef(since)) {
            if (row[0] == null || row[1] == null) continue;
            doneMinute.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }

        Map<String, CoinSignalDto> signals = new HashMap<>();
        int saved = 0;
        for (TradeHistory sell : sells) {
            if (sell.getPrice() == null || sell.getPrice().signum() <= 0) continue; // 10/7 이전 형식 매도 행
            long elapsed = Duration.between(sell.getTradedAt(), now).toMinutes();

            // 지금까지 도래한 가장 최근 추적 시점
            int due = 0;
            for (int m : TRACK_MINUTES) {
                if (elapsed >= m) due = m;
            }
            if (due == 0 || elapsed > lastMinute + LATE_TOLERANCE_MINUTES) continue;
            if (doneMinute.getOrDefault(sell.getId(), 0) >= due) continue;

            CoinSignalDto signal = signals.get(sell.getMarket());
            if (signal == null) {
                signal = pickSignal(sell.getMarket(), signalMap, now);
                if (signal == null) continue;
                signals.put(sell.getMarket(), signal);
            }
            try {
                tradeHistoryRepository.save(trackRow(sell, due, signal));
                saved++;
            } catch (Exception e) {
                log.warn("{} 매도후 추적 저장 실패 (매도 id:{}, {}분): {}", sell.getMarket(), sell.getId(), due, e.getMessage());
            }
        }
        if (saved > 0) {
            log.info("[매도후추적] {}건 기록 (추적 중 매도 {}건)", saved, sells.size());
        }
    }

    /** 감시 목록 코인은 방금 계산한 슬로우 루프 지표를 재사용, 목록에서 빠진 코인은 새로 계산 */
    private CoinSignalDto pickSignal(String market, Map<String, CoinSignalDto> signalMap, LocalDateTime now) {
        CoinSignalDto cached = signalMap.get(market);
        if (cached != null && cached.getComputedAt() != null
                && Duration.between(cached.getComputedAt(), now).getSeconds() <= SIGNAL_FRESH_SECONDS) {
            return cached;
        }
        return coinSignalService.buildSignal(market);
    }
}
