package com.coin.coin.service;

import com.coin.coin.common.MarketPhase;
import com.coin.coin.dto.CoinSignalDto;
import com.coin.coin.dto.response.CandleResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 순수 기술적 지표 계산 — RSI/EMA/볼린저밴드/시장국면 판정.
 * 외부 상태(계좌·주문·DB)에 의존하지 않는 순수 함수 모음 (UpbitApi 역할분리, 9/4).
 */
@Component
@Slf4j
public class TechnicalIndicatorService {

    public BigDecimal calculateRsi(List<CandleResponse> candles) {
        int period = 14;
        if (candles.size() < period + 1) {
            log.warn("RSI 계산 불가 - 캔들 부족");
            return BigDecimal.ZERO;
        }

        List<BigDecimal> close = candles.stream()
                .map(CandleResponse::getTradePrice)
                .toList();

        BigDecimal gain = BigDecimal.ZERO;
        BigDecimal loss = BigDecimal.ZERO;

        // index: size-1(oldest) ~ size-period (period개 diff 계산)
        for (int i = close.size() - 1; i >= close.size() - period; i--) {
            BigDecimal diff = close.get(i - 1).subtract(close.get(i)); // 최신-과거
            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                gain = gain.add(diff);
            } else loss = loss.add(diff.abs());
        }

        BigDecimal avgGain = gain.divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);
        BigDecimal avgLoss = loss.divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);

        // Wilder's smoothing: 나머지 봉 적용
        for (int i = close.size() - period - 1; i >= 1; i--) {
            BigDecimal diff = close.get(i - 1).subtract(close.get(i));
            BigDecimal g = diff.compareTo(BigDecimal.ZERO) > 0 ? diff : BigDecimal.ZERO;
            BigDecimal l = diff.compareTo(BigDecimal.ZERO) < 0 ? diff.abs() : BigDecimal.ZERO;

            avgGain = avgGain.multiply(BigDecimal.valueOf(period - 1))
                    .add(g)
                    .divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);
            avgLoss = avgLoss.multiply(BigDecimal.valueOf(period - 1))
                    .add(l)
                    .divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);
        }

        if (avgLoss.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.valueOf(100);

        BigDecimal rs = avgGain.divide(avgLoss, 10, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(100)
                .subtract(BigDecimal.valueOf(100)
                        .divide(BigDecimal.ONE.add(rs), 10, RoundingMode.HALF_UP));
    }

    public MarketPhase detectMarketPhase(List<CandleResponse> candles) {
        try {
            if (candles.size() < 30) {
                return MarketPhase.SIDEWAYS;
            }

            List<BigDecimal> prices = candles.stream()
                    .map(CandleResponse::getTradePrice)
                    .toList();

            BigDecimal mult = new BigDecimal("2")
                    .divide(BigDecimal.valueOf(21), 10, RoundingMode.HALF_UP);

            // 가장 오래된 가격부터 시작
            BigDecimal ema = prices.get(prices.size() - 1);
            BigDecimal prevEma = null;

            for (int i = prices.size() - 2; i >= 0; i--) {
                if (i == 0) {
                    prevEma = ema;  // 최신 봉 바로 이전 EMA 저장
                }
                ema = prices.get(i).multiply(mult)
                        .add(ema.multiply(BigDecimal.ONE.subtract(mult)));
            }

            // ema = 현재(최신) EMA20
            BigDecimal slope = ema.subtract(prevEma)
                    .divide(prevEma, 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            if (slope.compareTo(new BigDecimal("0.15")) > 0) return MarketPhase.BULL;
            if (slope.compareTo(new BigDecimal("-0.15")) < 0) return MarketPhase.BEAR;
            return MarketPhase.SIDEWAYS;

        } catch (Exception e) {
            log.warn("시장 국면 감지 실패: {}", e.getMessage());
            return MarketPhase.SIDEWAYS;
        }
    }

    /**
     * 단기 국면 감지 (15분봉 EMA20 기울기 기반) — 주 매수 필터
     *
     * <p>Upbit API 반환 순서: index 0 = 최신봉, index size-1 = 가장 오래된 봉
     * → EMA는 oldest(size-1)부터 시작하여 newest(0)까지 순차 적용
     *
     * <p>임계값 0.05%: 60분봉(0.15%)보다 낮게 설정 — 15분봉은 변동폭이 작아
     * 동일 기준 적용 시 항상 SIDEWAYS 판정될 수 있음
     *
     * <pre>
     *   slope >  0.05% → SHORT_BULL  (단기 상승 추세)
     *   slope < -0.05% → SHORT_BEAR  (단기 하락 추세)
     *   그 외           → SHORT_SIDE  (횡보, SIDEWAYS)
     * </pre>
     */
    public MarketPhase detectShortTermPhase(List<CandleResponse> candles) {
        try {
            if (candles == null || candles.size() < 20) {
                return MarketPhase.SIDEWAYS;
            }

            List<BigDecimal> prices = candles.stream()
                    .map(CandleResponse::getTradePrice)
                    .toList();

            BigDecimal mult = new BigDecimal("2")
                    .divide(BigDecimal.valueOf(21), 10, RoundingMode.HALF_UP); // EMA20

            // oldest(size-1)부터 EMA 계산 시작
            BigDecimal ema = prices.get(prices.size() - 1);
            BigDecimal prevEma = null;

            for (int i = prices.size() - 2; i >= 0; i--) {
                if (i == 0) {
                    prevEma = ema; // 최신봉 바로 이전 EMA 저장 (기울기 계산용)
                }
                ema = prices.get(i).multiply(mult)
                        .add(ema.multiply(BigDecimal.ONE.subtract(mult)));
            }

            // slope = (최신 EMA - 직전 EMA) / 직전 EMA * 100
            BigDecimal slope = ema.subtract(prevEma)
                    .divide(prevEma, 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            if (slope.compareTo(new BigDecimal("0.05")) > 0) return MarketPhase.BULL;
            if (slope.compareTo(new BigDecimal("-0.05")) < 0) return MarketPhase.BEAR;
            return MarketPhase.SIDEWAYS;

        } catch (Exception e) {
            log.warn("단기 국면 감지 실패: {}", e.getMessage());
            return MarketPhase.SIDEWAYS;
        }
    }

    public Map<String, BigDecimal> calculateEmaCross(List<CandleResponse> candles) {
        if (candles == null || candles.isEmpty()) {
            return Map.of("ema5", BigDecimal.ZERO, "ema9", BigDecimal.ZERO, "ema20", BigDecimal.ZERO);
        }

        List<BigDecimal> prices = candles.stream()
                .map(CandleResponse::getTradePrice)
                .toList();

        BigDecimal mult5 = new BigDecimal("2").divide(BigDecimal.valueOf(6), 10, RoundingMode.HALF_UP); // EMA5  : 2/(5+1)
        BigDecimal mult9 = new BigDecimal("2").divide(BigDecimal.valueOf(10), 10, RoundingMode.HALF_UP); // EMA9  : 2/(9+1)
        BigDecimal mult20 = new BigDecimal("2").divide(BigDecimal.valueOf(21), 10, RoundingMode.HALF_UP); // EMA20 : 2/(20+1)

        BigDecimal seed = prices.get(prices.size() - 1);
        BigDecimal ema5 = seed;
        BigDecimal ema9 = seed;
        BigDecimal ema20 = seed;

        for (int i = prices.size() - 2; i >= 0; i--) {
            BigDecimal p = prices.get(i);
            ema5 = p.multiply(mult5).add(ema5.multiply(BigDecimal.ONE.subtract(mult5)));
            ema9 = p.multiply(mult9).add(ema9.multiply(BigDecimal.ONE.subtract(mult9)));
            ema20 = p.multiply(mult20).add(ema20.multiply(BigDecimal.ONE.subtract(mult20)));
        }

        return Map.of("ema5", ema5, "ema9", ema9, "ema20", ema20);
    }

    public Map<String, BigDecimal> calculateBollingerBands(List<CandleResponse> candles) {
        int period = 20;
        if (candles == null || candles.size() < period) {
            return Map.of("upper", BigDecimal.ZERO, "middle", BigDecimal.ZERO, "lower", BigDecimal.ZERO);
        }

        // 최신 20개만 사용 (index 0~19)
        List<BigDecimal> prices = candles.subList(0, period).stream()
                .map(CandleResponse::getTradePrice)
                .toList();

        BigDecimal sma = prices.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);

        BigDecimal variance = prices.stream()
                .map(p -> p.subtract(sma).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);

        BigDecimal stdDev = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));

        return Map.of(
                "upper", sma.add(stdDev.multiply(new BigDecimal("2"))),
                "middle", sma,
                "lower", sma.subtract(stdDev.multiply(new BigDecimal("2")))
        );
    }

    public boolean isGoldenCross(Map<String, BigDecimal> ema) {
        return ema.get("ema5").compareTo(ema.get("ema20")) > 0;
    }

    /**
     * BB 내 현재가 위치 — 최초 매수 로그용
     */
    public String bbPosition(CoinSignalDto signal) {
        BigDecimal price = signal.getPrice().getBidPrice();
        if (price.compareTo(signal.getBb().get("upper")) >= 0) return "상단초과";
        if (price.compareTo(signal.getBb().get("middle")) >= 0) return "중간~상단";
        if (price.compareTo(signal.getBb().get("lower")) >= 0) return "하단~중간";
        return "하단이탈";
    }
}
