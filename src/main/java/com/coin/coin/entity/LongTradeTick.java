package com.coin.coin.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 장타 포지션 3분 경로 (long_trade_tick, 10/21 추가) — 장타 포지션마다 3분 확인 때 1행.
 *
 * <p>목적: ① 3시간 이후 손실 중인 포지션이 이후 회복하는지, 몇 %에서 정리하는 게 맞는지
 * ② 고점을 찍고 손실로 끝난 포지션을, 이익 건은 건드리지 않고 구할 매도 규칙이 있는지를 실제 경로로 분석.
 * <ul>
 *   <li>trade_id = long_trade.id, elapsed_min = 진입 후 경과 분 (0 = 진입 시점)</li>
 *   <li>rate = 진입 매도호가 대비 매수호가 % (수수료 전, long_trade.max_rate와 같은 기준)</li>
 *   <li>run_max / run_min = 진입부터 이 시점까지 경로의 최고·최저 (SHADOW 구간도 이어서)</li>
 *   <li>phase = HOLD(보유 중) / SHADOW(이미 매도, 진입 6시간까지 그림자 추적)</li>
 *   <li>rsi15m / bb_pos15m = 그 시점 15분 지표. 10/30~ bb_pos30m / chg1h / vol_ratio / ob_bid_ratio 추가.
 *       10/30부터 감시 목록에서 빠진 보유·그림자 코인도 지표를 계산해 남긴다(이전에는 감시 목록 코인만, 나머지는 null)</li>
 * </ul>
 * DDL: docs/ddl/2026-10-21_long_trade_tick.sql
 */
@Entity
@Table(schema = "coin", name = "long_trade_tick")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LongTradeTick {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "trade_id")
    private Long tradeId;
    @Column(name = "tick_at")
    private LocalDateTime tickAt;
    @Column(name = "elapsed_min")
    private Integer elapsedMin;
    private String phase;
    private BigDecimal bid;
    private BigDecimal rate;
    @Column(name = "run_max")
    private BigDecimal runMax;
    @Column(name = "run_min")
    private BigDecimal runMin;
    @Column(name = "rsi15m")
    private BigDecimal rsi15m;
    @Column(name = "bb_pos15m")
    private BigDecimal bbPos15m;
    /** 30분봉 BB 위치 % (10/30) */
    @Column(name = "bb_pos30m")
    private BigDecimal bbPos30m;
    /** 최근 1시간 변화율 % — 3분봉 20개 전 대비 매수호가 (10/30) */
    @Column(name = "chg1h")
    private BigDecimal chg1h;
    /** 거래량 배율 — 최근 3분봉 거래량 / 평균 (10/30) */
    @Column(name = "vol_ratio")
    private BigDecimal volRatio;
    /** 호가창 매수잔량 비율 0~1 (0.5 미만 = 매도 우위, 10/30) */
    @Column(name = "ob_bid_ratio")
    private BigDecimal obBidRatio;
}
