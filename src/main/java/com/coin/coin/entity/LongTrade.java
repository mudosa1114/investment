package com.coin.coin.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 장타 모의매매 기록 (long_trade, 10/16 추가) — 포지션 1개당 1행.
 *
 * <p>"직전 N시간 크게 빠진 유동 코인을 사서 6시간 들고 있기" 전략을 단타(trade_history)와 분리해 기록한다.
 * 모의계좌 잔고와 무관한 가상 매매다(단타가 같은 코인을 들고 있어도 서로 영향 없음).
 * <ul>
 *   <li>strategy — 조건 코드: 6h-3(직전 6시간 -3% 이하), 3h-3(직전 3시간 -3% 이하), 6h-5(직전 6시간 -5% 이하),
 *       6h-3M(10/27~, 6h-3 + 시장 전체 약세일 때만) — 10/27까지는 조건마다 독립적으로 코인당 1포지션.
 *       10/28~ ANY = 조건 중 하나라도 맞으면 코인당 1포지션, 맞은 조건은 matched_conditions에</li>
 *   <li>status — OPEN(보유 중) / WATCH(조기 매도 후 6시간 시점 그림자 가격 대기, 10/19~) / CLOSED(완료)</li>
 *   <li>exit_reason — 익절+2% / 손실회복+0.5% / 고점후하락+0.3% (10/19~10/27) → 고점대비-0.6% (10/27~) / 비상손절-7% (10/22~) / 6시간보유</li>
 *   <li>max_at / min_at — 최고·최저를 찍은 시각 (10/19~)</li>
 *   <li>hold6h_* — 조기 매도해도 "원래대로 6시간 들고 있었다면" 진입 6시간 시점의 매수호가와 순손익률 (10/19~).
 *       6시간 보유로 판 건은 실제 매도 값과 같다</li>
 *   <li>entry_price = 진입 시 매도호가(시장가 매수 가정), exit_price = 청산 시 매수호가</li>
 *   <li>pnl_rate = 수수료(매수·매도 각 0.05%) 포함 순손익률 %, realized_pnl = 6,000원 매수 기준 손익(원)</li>
 *   <li>max_rate / min_rate = 보유 중 진입가 대비 최고·최저 매수호가 % (3분 확인, 수수료 전)</li>
 * </ul>
 * DDL: docs/ddl/2026-10-16_long_trade.sql, 2026-10-19_long_trade_exit_rules.sql, 2026-10-28_long_trade_one_position.sql
 */
@Entity
@Table(schema = "coin", name = "long_trade")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LongTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String strategy;
    /** 진입 때 맞은 조건 전부 (10/28~, 예: "6h-3,6h-5,3h-3,6h-3M") — strategy = ANY 행만 */
    @Column(name = "matched_conditions")
    private String matchedConditions;
    /** 맞은 조건 개수 (10/28~, 6h-3M 표시 포함) */
    @Column(name = "match_count")
    private Integer matchCount;
    /** 진입 시점 시장 약세 여부 (10/28~, 6h 중앙값 ≤ -0.43% 또는 최근 30분 신호 코인 3개 이상) */
    @Column(name = "market_weak")
    private Boolean marketWeak;
    private String market;
    private String status;

    @Column(name = "entry_at")
    private LocalDateTime entryAt;
    @Column(name = "entry_price")
    private BigDecimal entryPrice;
    @Column(name = "entry_bid")
    private BigDecimal entryBid;
    @Column(name = "spread_pct")
    private BigDecimal spreadPct;
    /** 진입을 일으킨 변화율 (6h 조건이면 chg6h, 3h 조건이면 chg3h. 10/28~ ANY 행은 맞은 조건 중 가장 많이 빠진 값) */
    @Column(name = "trigger_chg")
    private BigDecimal triggerChg;
    @Column(name = "chg1h")
    private BigDecimal chg1h;
    @Column(name = "chg3h")
    private BigDecimal chg3h;
    @Column(name = "chg6h")
    private BigDecimal chg6h;
    @Column(name = "chg24h")
    private BigDecimal chg24h;
    @Column(name = "rsi15m")
    private BigDecimal rsi15m;
    @Column(name = "rsi30m")
    private BigDecimal rsi30m;
    @Column(name = "bb_pos15m")
    private BigDecimal bbPos15m;
    @Column(name = "btc_chg1h")
    private BigDecimal btcChg1h;
    @Column(name = "order_amount")
    private BigDecimal orderAmount;

    @Column(name = "max_rate")
    private BigDecimal maxRate;
    @Column(name = "min_rate")
    private BigDecimal minRate;
    @Column(name = "max_at")
    private LocalDateTime maxAt;
    @Column(name = "min_at")
    private LocalDateTime minAt;

    @Column(name = "exit_at")
    private LocalDateTime exitAt;
    @Column(name = "exit_price")
    private BigDecimal exitPrice;
    @Column(name = "exit_reason")
    private String exitReason;
    @Column(name = "hold_minutes")
    private BigDecimal holdMinutes;
    @Column(name = "pnl_rate")
    private BigDecimal pnlRate;
    @Column(name = "realized_pnl")
    private BigDecimal realizedPnl;
    @Column(name = "hold6h_at")
    private LocalDateTime hold6hAt;
    @Column(name = "hold6h_price")
    private BigDecimal hold6hPrice;
    @Column(name = "hold6h_pnl_rate")
    private BigDecimal hold6hPnlRate;
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
