package com.coin.coin.dto;

import com.coin.coin.common.MarketPhase;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.Map;

@Getter
@Builder
@AllArgsConstructor
public class CoinSignalDto {

    private BigDecimal rsi;
    /** 15분봉 기준 RSI (9/23 추가) — 실거래 판단에는 쓰지 않는 관측·비교용.
     *  기존 rsi(3분봉)와 나란히 남겨 RSI모멘텀손절 섀도우 기록(MomentumStopShadow)에 사용한다. */
    private BigDecimal rsi15m;
    /** ATR(3분봉, 9/23 실거래 판단에 편입) — RSI모멘텀손절의 동적 손실허용폭 계산에 사용.
     *  계산 실패 시 BigDecimal.ZERO로 채워지며, 그 경우 PositionExitService의 동적 손실허용폭은
     *  바닥값(BULL_RSI_STOP_LOSS_FLOOR_PCT, -0.5%)으로 안전하게 수렴한다. */
    private BigDecimal atr;
    /** 단기 국면 (15분봉 EMA 기울기 기반) — 주 매수 필터 */
    private MarketPhase shortPhase;
    /** 장기 국면 (60분봉 EMA 기울기 기반) — 보조 안전장치 필터 */
    private MarketPhase phase;
    private Map<String, BigDecimal> ema;
    private Map<String, BigDecimal> bb;
    private CoinPrice price;
}
