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
 * 매도 후 추적 — 익절·손절·가상매도 후 15·30·45·60분 시점의 가격과 지표를
 * trade_history에 trade_type='추적' 행으로 남긴다.
 *
 * <p>10/8 변경: 1~24시간 매시간 → 15분 단위 60분까지.
 * <p>10/13 변경: 코인 단위 묶음 추적. 어떤 코인의 매도로 추적이 시작되면(= 기준 매도) 그 추적 60분이
 * 끝날 때까지 같은 코인의 다른 매도는 새로 추적하지 않는다. 무조건 매수라 같은 코인을 몇 분 간격으로
 * 반복 매매하면 거의 같은 시각·같은 가격의 추적 행이 중복으로 쌓였기 때문. 60분이 지난 뒤의 첫 매도가
 * 다음 기준 매도가 된다. 실매도와 가상매도도 코인이 같으면 하나로 묶는다.
 * 추적하지 않은 매도의 이후 가격은 같은 코인 기준 매도의 추적 행에서 시각을 맞춰 보면 된다.
 * <ul>
 *   <li>ref_id = 기준 매도 행 id, track_minute = 경과 분(15/30/45/60)</li>
 *   <li>pnl_rate = 기준 매도 체결가 대비 현재 매수호가 변화율(%)</li>
 *   <li>max_rate / min_rate = 직전 15분(3분봉 5개) 고가·저가의 매도가 대비 %</li>
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
        int firstMinute = TRACK_MINUTES[0];
        // 기록 대상 창(최근 70분) + 그 이전 기준 매도의 추적 구간(60분)까지 함께 읽어 묶음 여부를 판단
        LocalDateTime trackSince = now.minusMinutes(lastMinute + LATE_TOLERANCE_MINUTES);
        LocalDateTime lookSince = trackSince.minusMinutes(lastMinute);

        List<TradeHistory> sells = tradeHistoryRepository.findSellsSince(lookSince); // 시간순
        if (sells.isEmpty()) return;

        Map<Long, Integer> doneMinute = new HashMap<>();
        for (Object[] row : tradeHistoryRepository.maxTrackMinuteByRef(lookSince)) {
            if (row[0] == null || row[1] == null) continue;
            doneMinute.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }

        // 코인별 기준 매도 판정 (시간순):
        //  - 이미 추적 행이 있으면 기준 매도
        //  - 직전 기준 매도의 60분 구간 안이면 묶음(추적 안 함)
        //  - 구간 밖이고 아직 첫 추적 시점(15분+여유) 전이면 새 기준 매도
        //  - 그보다 오래됐는데 추적 행이 없으면(앱 중단 등) 기준 아님
        Map<String, LocalDateTime> anchorEnd = new HashMap<>();
        Map<String, CoinSignalDto> signals = new HashMap<>();
        int saved = 0;
        int grouped = 0;
        for (TradeHistory sell : sells) {
            if (sell.getPrice() == null || sell.getPrice().signum() <= 0) continue; // 10/7 이전 형식 매도 행
            String market = sell.getMarket();
            LocalDateTime soldAt = sell.getTradedAt();
            long elapsed = Duration.between(soldAt, now).toMinutes();

            boolean anchor;
            if (doneMinute.containsKey(sell.getId())) {
                anchor = true;
            } else {
                LocalDateTime end = anchorEnd.get(market);
                if (end != null && !soldAt.isAfter(end)) {
                    if (!soldAt.isBefore(trackSince)) grouped++;
                    continue;
                }
                anchor = elapsed <= firstMinute + LATE_TOLERANCE_MINUTES;
            }
            if (!anchor) continue;
            anchorEnd.put(market, soldAt.plusMinutes(lastMinute));

            if (soldAt.isBefore(trackSince)) continue; // 묶음 판정용으로만 읽은 과거 매도

            // 지금까지 도래한 가장 최근 추적 시점
            int due = 0;
            for (int m : TRACK_MINUTES) {
                if (elapsed >= m) due = m;
            }
            if (due == 0) continue;
            if (doneMinute.getOrDefault(sell.getId(), 0) >= due) continue;

            CoinSignalDto signal = signals.get(market);
            if (signal == null) {
                signal = pickSignal(market, signalMap, now);
                if (signal == null) continue;
                signals.put(market, signal);
            }
            try {
                tradeHistoryRepository.save(trackRow(sell, due, signal));
                saved++;
            } catch (Exception e) {
                log.warn("{} 매도후 추적 저장 실패 (매도 id:{}, {}분): {}", market, sell.getId(), due, e.getMessage());
            }
        }
        if (saved > 0) {
            log.info("[매도후추적] {}건 기록 (최근 70분 매도 중 같은 코인 추적 구간에 묶여 생략 {}건)", saved, grouped);
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
