package com.coin.coin.dto;

import com.coin.coin.entity.TradeHistory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * trade_history 행 생성기 (10/7 개편). 매수 / 매도(익절·손절) / 매도 후 추적 3종류를 같은 테이블에
 * 같은 지표 컬럼 구성으로 남긴다.
 *
 * <p>반올림 규칙: 금액(원) → 소수점 없음, 지표(RSI·BB위치·비율·변화율) → 소수 2자리,
 * 가격 대비 아주 작은 %(수익률·ATR%·스프레드%·BB폭·MACD%) → 소수 3자리.
 * 가격 단위 원값(체결가·호가·BB 밴드·EMA)은 반올림하지 않는다 — 10원대 코인은 2자리로 자르면
 * 밴드 폭 자체가 뭉개지기 때문.
 */
public final class TradeHistoryDto {

    public static final BigDecimal FEE_RATE = new BigDecimal("0.0005");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private TradeHistoryDto() {
    }

    /** 매수 행 — order_price = 주문금액 × 1.0005 반올림(원). reason 예: 매수:무조건(고정) / 매수:필터통과(유동) */
    public static TradeHistory buyRow(String market, String reason, BigDecimal orderAmount, BigDecimal fillPrice,
                                      BigDecimal volume, CoinSignalDto signal) {
        BigDecimal fee = orderAmount.multiply(FEE_RATE);
        return withIndicators(TradeHistory.builder(), signal)
                .market(market)
                .tradeType("매수")
                .reason(reason)
                .orderPrice(orderAmount.add(fee).setScale(0, RoundingMode.HALF_UP))
                .fee(fee.setScale(2, RoundingMode.HALF_UP))
                .price(fillPrice)
                .volume(volume)
                .tradedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 매도 행 — order_price = 체결금액 - 수수료 (원 반올림),
     * realized_pnl = order_price 기준 순수령액 - (평균매수가×수량 + 매수수수료).
     */
    public static TradeHistory sellRow(String market, String tradeType, String reason,
                                       BigDecimal fillPrice, BigDecimal volume, BigDecimal avgBuyPrice,
                                       Long buyRowId, BigDecimal holdMinutes,
                                       BigDecimal maxRate, BigDecimal minRate, CoinSignalDto signal) {
        BigDecimal gross = fillPrice.multiply(volume);
        BigDecimal fee = gross.multiply(FEE_RATE);
        BigDecimal net = gross.subtract(fee);
        BigDecimal cost = avgBuyPrice.multiply(volume).multiply(BigDecimal.ONE.add(FEE_RATE));
        BigDecimal pnl = net.subtract(cost);
        BigDecimal pnlRate = cost.signum() > 0
                ? pnl.divide(cost, 10, RoundingMode.HALF_UP).multiply(HUNDRED).setScale(3, RoundingMode.HALF_UP)
                : null;
        return withIndicators(TradeHistory.builder(), signal)
                .market(market)
                .tradeType(tradeType)
                .reason(reason)
                .orderPrice(net.setScale(0, RoundingMode.HALF_UP))
                .realizedPnl(pnl.setScale(0, RoundingMode.HALF_UP))
                .fee(fee.setScale(2, RoundingMode.HALF_UP))
                .price(fillPrice)
                .volume(volume)
                .refId(buyRowId)
                .pnlRate(pnlRate)
                .holdMinutes(holdMinutes)
                .maxRate(maxRate)
                .minRate(minRate)
                .tradedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 매도후추적 행 (10/16: 매도 후 3·6·9·12·15분, trade_type '추적' → '매도후추적') — sellRow 기준 minute 분 경과 시점의 호가.
     * pnl_rate = 매도 체결가 대비 현재 매수호가 변화율(%),
     * max_rate / min_rate = 직전 3분 구간의 30초 확인 기준 최고·최저 매수호가의 매도가 대비 %.
     * 지표 컬럼은 슬로우 루프 캐시 지표(최대 3분 전, signal_age_sec 참고). 캐시 지표가 없으면 비운다.
     */
    public static TradeHistory trackRow(TradeHistory sellRow, int minute, BigDecimal bid, BigDecimal ask,
                                        BigDecimal maxRate, BigDecimal minRate, CoinSignalDto signal) {
        TradeHistory.TradeHistoryBuilder b = signal != null ? withIndicators(TradeHistory.builder(), signal) : TradeHistory.builder();
        return b.market(sellRow.getMarket())
                .tradeType("매도후추적") // 10/16~ (이전: 추적)
                .reason("매도후" + minute + "m")
                .refId(sellRow.getId())
                .trackMinute(minute)
                .price(bid)
                .askPrice(ask)
                .bidPrice(bid)
                .pnlRate(pct(sellRow.getPrice(), bid, 3))
                .maxRate(maxRate)
                .minRate(minRate)
                .tradedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 보유중추적 행 (10/16) — 매수~매도 사이 30초 확인 기준 최고·최저 수익률. 포지션당 1행, 매도 직후 저장.
     * ref_id = 매수 행 id(매도 행과 같은 매수 행을 가리킴), price = 평균매수가(기준가),
     * max_rate / min_rate = 평균매수가 대비 최고·최저 매수호가 %(수수료 전), hold_minutes = 보유 시간.
     */
    public static TradeHistory holdRangeRow(String market, Long buyRowId, BigDecimal avgBuyPrice,
                                            BigDecimal holdMinutes, BigDecimal maxRate, BigDecimal minRate) {
        return TradeHistory.builder()
                .market(market)
                .tradeType("보유중추적")
                .reason("보유중 최고·최저")
                .refId(buyRowId)
                .price(avgBuyPrice)
                .holdMinutes(holdMinutes)
                .maxRate(maxRate)
                .minRate(minRate)
                .tradedAt(LocalDateTime.now())
                .build();
    }

    /** 15분봉 EMA5 ≤ EMA20 → true (로그 '데드크로스' 값과 동일). EMA 값이 없거나 0이면 null */
    private static Boolean deadCross(java.util.Map<String, BigDecimal> ema) {
        if (ema == null) return null;
        BigDecimal e5 = ema.get("ema5");
        BigDecimal e20 = ema.get("ema20");
        if (e5 == null || e20 == null || e5.signum() <= 0 || e20.signum() <= 0) return null;
        return e5.compareTo(e20) <= 0;
    }

    /** 세 종류 행에 공통으로 들어가는 지표 컬럼 */
    private static TradeHistory.TradeHistoryBuilder withIndicators(TradeHistory.TradeHistoryBuilder b,
                                                                  CoinSignalDto s) {
        Integer age = s.getComputedAt() == null ? null
                : (int) Duration.between(s.getComputedAt(), LocalDateTime.now()).getSeconds();
        return b
                .rsi(r(s.getRsi(), 2))
                .rsi15m(r(s.getRsi15m(), 2))
                .rsi30m(r(s.getRsi30m(), 2))
                .phase(s.getPhase() == null ? null : s.getPhase().name())
                .shortPhase(s.getShortPhase() == null ? null : s.getShortPhase().name())
                .longPhase(s.getLongPhase() == null ? null : s.getLongPhase().name())
                .upper(s.getBb() == null ? null : s.getBb().get("upper"))
                .middle(s.getBb() == null ? null : s.getBb().get("middle"))
                .lower(s.getBb() == null ? null : s.getBb().get("lower"))
                .ema5(s.getEma() == null ? null : s.getEma().get("ema5"))
                .ema9(s.getEma() == null ? null : s.getEma().get("ema9"))
                .ema20(s.getEma() == null ? null : s.getEma().get("ema20"))
                .deadCross(deadCross(s.getEma()))
                .askPrice(s.getPrice() == null ? null : s.getPrice().getAskPrice())
                .bidPrice(s.getPrice() == null ? null : s.getPrice().getBidPrice())
                .bbPos3m(r(s.getBbPos3m(), 2))
                .bbPos15m(r(s.getBbPos15m(), 2))
                .bbPos30m(r(s.getBbPos30m(), 2))
                .bbWidth3m(r(s.getBbWidth3m(), 3))
                .atrPct(r(s.getAtrPct(), 3))
                .volRatio(r(s.getVolRatio(), 2))
                .macdHistPct(r(s.getMacdHistPct(), 3))
                .obBidRatio(r(s.getObBidRatio(), 2))
                .spreadPct(r(s.getSpreadPct(), 3))
                .chg1h(r(s.getChg1h(), 2))
                .chg24h(r(s.getChg24h(), 2))
                .btcRsi15m(r(s.getBtcRsi15m(), 2))
                .btcChg1h(r(s.getBtcChg1h(), 2))
                .signalAgeSec(age);
    }

    private static BigDecimal r(BigDecimal v, int scale) {
        return v == null ? null : v.setScale(scale, RoundingMode.HALF_UP);
    }

    /** (target / base - 1) × 100 */
    public static BigDecimal pct(BigDecimal base, BigDecimal target, int scale) {
        if (base == null || target == null || base.signum() == 0) return null;
        return target.divide(base, 10, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                .multiply(HUNDRED).setScale(scale, RoundingMode.HALF_UP);
    }
}
