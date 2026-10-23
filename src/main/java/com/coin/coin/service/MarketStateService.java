package com.coin.coin.service;

import com.coin.coin.dto.CoinSignalDto;
import com.coin.coin.dto.UriBuilderDto;
import com.coin.coin.dto.response.CoinTickerResponse;
import com.coin.coin.dto.response.MarketResponse;
import com.coin.coin.entity.MarketState;
import com.coin.coin.repository.MarketStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 시장 흐름 기록 (10/23) — 슬로우 루프의 장타 판단 직전에 market_state 1행을 남긴다. 매매 판단에는 쓰지 않는다.
 *
 * <p>추가 API 호출: 3분마다 KRW 전체 시세 일괄 조회 약 5회(+ 마켓 목록은 1시간 캐시).
 * BTC 값은 슬로우 루프가 이미 갱신한 TradingStateStore 값, 감시 목록 값은 이미 계산된 신호를 쓴다.
 * 어떤 단계가 실패해도 그 값만 null로 남기고 매매에는 영향을 주지 않는다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MarketStateService {

    private final MarketStateRepository marketStateRepository;
    private final TradingStateStore stateStore;
    private final RestTemplate restTemplate;
    private final UriBuilderDto coinUriBuilder;

    /** 시세 일괄 조회 1회에 넣는 마켓 수 (CoinListService와 같게) */
    private static final int TICKER_BATCH = 50;
    /** 시세 조회 사이 쉼 (ms) — 업비트 시세 API 초당 제한 여유 */
    private static final long TICKER_CALL_INTERVAL_MS = 120;
    /** KRW 마켓 목록 캐시 유지 시간(분) */
    private static final long MARKET_CACHE_MINUTES = 60;

    private List<String> krwMarkets = List.of();
    private LocalDateTime krwMarketsAt;

    public void record(LocalDateTime now, Map<String, CoinSignalDto> signalMap) {
        try {
            MarketState.MarketStateBuilder b = MarketState.builder()
                    .checkedAt(now)
                    .btcChg1h(r(stateStore.getBtcChg1h(), 3))
                    .btcChg6h(r(stateStore.getBtcChg6h(), 3))
                    .btcChg24h(r(stateStore.getBtcChg24h(), 3));
            fillUniverse(b, signalMap);
            fillMarketCounts(b, now);
            marketStateRepository.save(b.build());
        } catch (Exception e) {
            log.warn("시장 흐름 기록 실패: {}", e.getMessage());
        }
    }

    /** 감시 목록(장타 대상) — 1시간 상승 비율, 6시간 변화율 중앙값 */
    private void fillUniverse(MarketState.MarketStateBuilder b, Map<String, CoinSignalDto> signalMap) {
        List<BigDecimal> chg1h = new ArrayList<>();
        List<BigDecimal> chg6h = new ArrayList<>();
        for (CoinSignalDto s : signalMap.values()) {
            if (s == null) continue;
            if (s.getChg1h() != null) chg1h.add(s.getChg1h());
            if (s.getChg6h() != null) chg6h.add(s.getChg6h());
        }
        b.univN(signalMap.size());
        if (!chg1h.isEmpty()) {
            long up = chg1h.stream().filter(v -> v.signum() > 0).count();
            b.univUpRatio1h(BigDecimal.valueOf(up).divide(BigDecimal.valueOf(chg1h.size()), 3, RoundingMode.HALF_UP));
        }
        if (!chg6h.isEmpty()) {
            chg6h.sort(BigDecimal::compareTo);
            int n = chg6h.size();
            BigDecimal med = n % 2 == 1 ? chg6h.get(n / 2)
                    : chg6h.get(n / 2 - 1).add(chg6h.get(n / 2)).divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
            b.univMedChg6h(r(med, 3));
        }
    }

    /** KRW 마켓 전체 — 전일 종가 대비 상승·하락·보합 개수 (업비트 앱과 같은 기준) */
    private void fillMarketCounts(MarketState.MarketStateBuilder b, LocalDateTime now) {
        try {
            List<String> markets = krwMarkets(now);
            if (markets.isEmpty()) return;
            int up = 0, down = 0, flat = 0;
            for (int i = 0; i < markets.size(); i += TICKER_BATCH) {
                if (i > 0) Thread.sleep(TICKER_CALL_INTERVAL_MS);
                List<String> batch = markets.subList(i, Math.min(i + TICKER_BATCH, markets.size()));
                CoinTickerResponse[] res = restTemplate.getForObject(
                        coinUriBuilder.upbitTicker(String.join(",", batch)), CoinTickerResponse[].class);
                if (res == null) return; // 일부만 센 값은 남기지 않음
                for (CoinTickerResponse t : res) {
                    BigDecimal rate = t.getSignedChangeRate();
                    if (rate == null) continue;
                    if (rate.signum() > 0) up++;
                    else if (rate.signum() < 0) down++;
                    else flat++;
                }
            }
            b.mktUp(up).mktDown(down).mktFlat(flat);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("전체 장 상승·하락 개수 조회 실패: {}", e.getMessage());
        }
    }

    private List<String> krwMarkets(LocalDateTime now) {
        if (krwMarketsAt != null && !krwMarkets.isEmpty() && krwMarketsAt.plusMinutes(MARKET_CACHE_MINUTES).isAfter(now)) {
            return krwMarkets;
        }
        MarketResponse[] res = restTemplate.getForObject(coinUriBuilder.upbitMarkets(), MarketResponse[].class);
        if (res == null) return krwMarkets;
        List<String> list = new ArrayList<>();
        for (MarketResponse m : res) {
            if (m.getMarket() != null && m.getMarket().startsWith("KRW-")) list.add(m.getMarket());
        }
        if (!list.isEmpty()) {
            krwMarkets = list;
            krwMarketsAt = now;
        }
        return krwMarkets;
    }

    private static BigDecimal r(BigDecimal v, int scale) {
        return v == null ? null : v.setScale(scale, RoundingMode.HALF_UP);
    }
}
