package com.coin.coin.dto;

import com.coin.coin.common.MarketPhase;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 코인별 지표 묶음.
 *
 * <p>10/7 모의매매 전면 개편: 매수 판단에는 지표를 쓰지 않는다(무조건 매수). 매도는 ±1% 고정, 10/8부터 3분 ATR% ≥ 0.5인 코인만 ±1.5%.
 * 이 DTO는 매매 "당시 지표를 기록"하는 용도로만 쓰이며, trade_history 한 행에 그대로 옮겨 담긴다.
 * 가격 단위 값(BB 밴드, EMA, ATR)은 코인마다 가격대가 달라 비교가 안 되므로, 분석용으로는
 * 가격 대비 %(bbPos*, atrPct 등)로 정규화한 값을 함께 둔다.
 */
@Getter
@Builder
@AllArgsConstructor
public class CoinSignalDto {

    /** 3분봉 RSI(14) */
    private BigDecimal rsi;
    /** 15분봉 RSI(14) */
    private BigDecimal rsi15m;
    /** 30분봉 RSI(14) — 10/7 추가 */
    private BigDecimal rsi30m;
    /** 3분봉 ATR(14), 가격 단위 */
    private BigDecimal atr;
    /** 단기 국면 (15분봉 EMA20 기울기) */
    private MarketPhase shortPhase;
    /** 기존 phase (60분봉 EMA20 기울기) — trade_history.phase 컬럼 */
    private MarketPhase phase;
    /** 장기 국면 (240분봉 EMA20 기울기) — 10/7 추가, 실패 시 null */
    private MarketPhase longPhase;
    /** 15분봉 EMA5/9/20 */
    private Map<String, BigDecimal> ema;
    /** 3분봉 BB(20, 2σ) upper/middle/lower */
    private Map<String, BigDecimal> bb;
    private CoinPrice price;

    // ── 10/7 추가: 정규화된 관측 지표 (매매 판단에는 사용하지 않음) ──────────
    /** BB 위치 % = (매수호가 - 하단) / (상단 - 하단) × 100. 0=하단, 100=상단, 범위 밖이면 음수/100 초과 */
    private BigDecimal bbPos3m;
    private BigDecimal bbPos15m;
    private BigDecimal bbPos30m;
    /** 3분봉 BB 폭 % = (상단 - 하단) / 중간 × 100 */
    private BigDecimal bbWidth3m;
    /** 3분봉 ATR / 매수호가 × 100 */
    private BigDecimal atrPct;
    /** 3분봉 최신 거래량 / 최근 22봉 평균 */
    private BigDecimal volRatio;
    /** 15분봉 MACD 히스토그램 / 매수호가 × 100 */
    private BigDecimal macdHistPct;
    /** 오더북 상위 호가 매수잔량 비율 (0.5 초과 = 매수 우위) */
    private BigDecimal obBidRatio;
    /** (매도호가 - 매수호가) / 매수호가 × 100 */
    private BigDecimal spreadPct;
    /** 최근 1시간 가격 변화율 % (3분봉 20개 전 종가 대비) */
    private BigDecimal chg1h;
    /** 최근 24시간 가격 변화율 % (60분봉 24개 전 종가 대비) */
    private BigDecimal chg24h;
    /** 최근 1시간(3분봉 20개) 최고가 / 최저가 — 매도 후 추적 행의 구간 고점·저점 계산용 */
    private BigDecimal high1h;
    private BigDecimal low1h;
    /** 시장 전체 흐름 참고용: BTC 15분봉 RSI, BTC 1시간 변화율 % */
    private BigDecimal btcRsi15m;
    private BigDecimal btcChg1h;
    /** 이 지표를 계산한 시각 — 기록 시점과의 차이(signal_age_sec) 계산용 */
    private LocalDateTime computedAt;
}
