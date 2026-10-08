package com.coin.coin.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 매매 기록 단일 테이블 (10/7 개편 — 모의매매 기간 "한 테이블에서 전부 보기").
 *
 * <p>trade_type 으로 행 종류를 구분한다.
 * <ul>
 *   <li>매수 — order_price = 주문금액 + 수수료(0.05%), 원 단위 반올림 (예: 6,000 → 6,003)</li>
 *   <li>익절 / 손절 — order_price = 체결금액 - 수수료, 원 단위 반올림. ref_id = 매수 행 id</li>
 *   <li>추적 — 매도 후 15·30·45·60분에 1행씩(10/8 변경, 10/7~8은 1~24시간 매시간). ref_id = 매도 행 id, track_minute = 경과 분,
 *       pnl_rate = 매도 체결가 대비 변화율(%), max_rate/min_rate = 직전 1시간 고가/저가의 매도가 대비 %</li>
 * </ul>
 * 기존 컬럼(rsi=3분봉, phase=60분봉, upper/middle/lower=3분봉 BB, ema5/ema20=15분봉)은 의미 그대로 유지.
 * 신규 컬럼 DDL: docs/ddl/2026-10-07_trade_history_paper.sql
 */
@Entity
@Table(schema = "coin", name = "trade_history")
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class TradeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String market;
    private String tradeType;
    private BigDecimal orderPrice;
    private BigDecimal rsi;
    private String phase;
    private BigDecimal upper;
    private BigDecimal middle;
    private BigDecimal lower;
    private BigDecimal ema5;
    private BigDecimal ema20;
    /** 매도 시 실현손익(원) = (체결금액 - 매도수수료) - (평균매수가×수량 + 매수수수료). 매수/추적 행은 null. */
    private BigDecimal realizedPnl;
    private LocalDateTime tradedAt;

    // ── 10/7 추가 컬럼 ───────────────────────────────────────────────
    /** 매도 행: 매수 행 id / 추적 행: 매도 행 id */
    @Column(name = "ref_id")
    private Long refId;
    /** 매매 사유 (무조건매수 / 익절+1% / 손절-1% / 익절+1.5% / 손절-1.5% (10/7 +0.3%, 10/8 +0.5% 시기 포함) / 매도후Nm, 10/8 이전 추적은 매도후Nh) */
    @Column(name = "reason")
    private String reason;
    /** 추적 행: 매도 후 경과 시간(시) */
    @Column(name = "track_minute")
    private Integer trackMinute;
    /** 체결가 (매수=매도호가, 매도=매수호가). 추적 행은 그 시점 매수호가 */
    @Column(name = "price")
    private BigDecimal price;
    @Column(name = "ask_price")
    private BigDecimal askPrice;
    @Column(name = "bid_price")
    private BigDecimal bidPrice;
    @Column(name = "volume")
    private BigDecimal volume;
    /** 수수료(원) */
    @Column(name = "fee")
    private BigDecimal fee;
    /** 매도: 수수료 포함 순손익률 % / 추적: 매도 체결가 대비 변화율 % */
    @Column(name = "pnl_rate")
    private BigDecimal pnlRate;
    /** 매도 행: 보유 시간(분) */
    @Column(name = "hold_minutes")
    private BigDecimal holdMinutes;
    /** 매도: 보유 중 최고 수익률 %(MFE) / 추적: 직전 1시간 고가의 매도가 대비 % */
    @Column(name = "max_rate")
    private BigDecimal maxRate;
    /** 매도: 보유 중 최저 수익률 %(MAE) / 추적: 직전 1시간 저가의 매도가 대비 % */
    @Column(name = "min_rate")
    private BigDecimal minRate;

    @Column(name = "rsi15m")
    private BigDecimal rsi15m;
    @Column(name = "rsi30m")
    private BigDecimal rsi30m;
    /** 15분봉 국면 */
    @Column(name = "short_phase")
    private String shortPhase;
    /** 240분봉 국면 */
    @Column(name = "long_phase")
    private String longPhase;
    @Column(name = "bb_pos3m")
    private BigDecimal bbPos3m;
    @Column(name = "bb_pos15m")
    private BigDecimal bbPos15m;
    @Column(name = "bb_pos30m")
    private BigDecimal bbPos30m;
    @Column(name = "bb_width3m")
    private BigDecimal bbWidth3m;
    @Column(name = "ema9")
    private BigDecimal ema9;
    @Column(name = "atr_pct")
    private BigDecimal atrPct;
    @Column(name = "vol_ratio")
    private BigDecimal volRatio;
    @Column(name = "macd_hist_pct")
    private BigDecimal macdHistPct;
    @Column(name = "ob_bid_ratio")
    private BigDecimal obBidRatio;
    @Column(name = "spread_pct")
    private BigDecimal spreadPct;
    @Column(name = "chg1h")
    private BigDecimal chg1h;
    @Column(name = "chg24h")
    private BigDecimal chg24h;
    @Column(name = "btc_rsi15m")
    private BigDecimal btcRsi15m;
    @Column(name = "btc_chg1h")
    private BigDecimal btcChg1h;
    /** 기록 시각 - 지표 계산 시각 (초) */
    @Column(name = "signal_age_sec")
    private Integer signalAgeSec;
}
