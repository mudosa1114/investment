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
 * 매도 후 시간 단위 추적 (10/7 추가) — 익절·손절 모든 매도에 대해 매도 후 1~24시간, 매시간 한 번씩
 * 그 시점 가격과 지표를 trade_history에 trade_type='추적' 행으로 남긴다.
 *
 * <ul>
 *   <li>ref_id = 매도 행 id, track_hour = 경과 시간(1~24)</li>
 *   <li>pnl_rate = 매도 체결가 대비 현재 매수호가 변화율(%)</li>
 *   <li>max_rate / min_rate = 직전 1시간(3분봉 20개) 고가·저가의 매도가 대비 %</li>
 *   <li>나머지 지표 컬럼은 매수/매도 행과 동일</li>
 * </ul>
 * 슬로우 루프(3분)마다 호출되므로 실제 기록 시각은 정각에서 최대 3~4분 늦을 수 있다(traded_at에 실제 시각 기록).
 * 앱이 꺼져 있던 시간대는 건너뛰고 다음 도래 시간부터 이어서 기록한다(밀린 시간을 한꺼번에 채우지 않음).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PostSellTrackingService {

    private final TradeHistoryRepository tradeHistoryRepository;
    private final CoinSignalService coinSignalService;

    static final int TRACK_HOURS = 24;
    /** 슬로우 루프 캐시 지표를 그대로 써도 되는 최대 경과 시간(초) — 넘으면 새로 계산 */
    private static final long SIGNAL_FRESH_SECONDS = 240;

    public void track(Map<String, CoinSignalDto> signalMap) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusHours(TRACK_HOURS).minusMinutes(30);

        List<TradeHistory> sells = tradeHistoryRepository.findSellsSince(since);
        if (sells.isEmpty()) return;

        Map<Long, Integer> doneHour = new HashMap<>();
        for (Object[] row : tradeHistoryRepository.maxTrackHourByRef(since)) {
            if (row[0] == null || row[1] == null) continue;
            doneHour.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }

        Map<String, CoinSignalDto> signals = new HashMap<>();
        int saved = 0;
        for (TradeHistory sell : sells) {
            if (sell.getPrice() == null || sell.getPrice().signum() <= 0) continue; // 10/7 이전 형식 매도 행
            int hour = (int) (Duration.between(sell.getTradedAt(), now).toMinutes() / 60);
            if (hour < 1 || hour > TRACK_HOURS) continue;
            if (doneHour.getOrDefault(sell.getId(), 0) >= hour) continue;

            CoinSignalDto signal = signals.get(sell.getMarket());
            if (signal == null) {
                signal = pickSignal(sell.getMarket(), signalMap, now);
                if (signal == null) continue;
                signals.put(sell.getMarket(), signal);
            }
            try {
                tradeHistoryRepository.save(trackRow(sell, hour, signal));
                saved++;
            } catch (Exception e) {
                log.warn("{} 매도후 추적 저장 실패 (매도 id:{}, {}h): {}", sell.getMarket(), sell.getId(), hour, e.getMessage());
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
