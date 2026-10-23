package com.coin.coin.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 시장 흐름 기록 (market_state, 10/23 추가) — 슬로우 루프(3분)마다 1행. 매매 판단에는 쓰지 않고 기록만 한다.
 *
 * <p>목적: 장타 결과가 시장 흐름(상승장/하락장)에 따라 달라지는지 — BTC 흐름, 전체 장 흐름, 산 코인의 흐름을
 * 한 번에 비교하기 위해. 산 코인의 흐름은 long_trade(chg1h/3h/6h/24h)와 long_trade_tick에 이미 있다.
 * <ul>
 *   <li>checked_at = 장타 판단(LongTradeService.run)과 같은 시각 → long_trade.entry_at과 그대로 조인된다</li>
 *   <li>btc_chg1h / 6h / 24h = BTC 현재가 대비 1·6·24시간 전 변화율 % (1h는 3분봉, 6h·24h는 15분봉 기준)</li>
 *   <li>mkt_up / mkt_down / mkt_flat = KRW 마켓 전체 코인의 전일 종가 대비 상승·하락·보합 개수 (업비트 앱 "n개 상승"과 같은 기준)</li>
 *   <li>univ_n / univ_up_ratio_1h / univ_med_chg6h = 감시 목록(장타 대상) 코인 수, 그중 1시간 상승 비율(0~1), 6시간 변화율 중앙값 %</li>
 * </ul>
 * 조회에 실패한 값은 null. DDL: docs/ddl/2026-10-23_market_state.sql
 */
@Entity
@Table(schema = "coin", name = "market_state")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MarketState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "checked_at")
    private LocalDateTime checkedAt;
    @Column(name = "btc_chg1h")
    private BigDecimal btcChg1h;
    @Column(name = "btc_chg6h")
    private BigDecimal btcChg6h;
    @Column(name = "btc_chg24h")
    private BigDecimal btcChg24h;
    @Column(name = "mkt_up")
    private Integer mktUp;
    @Column(name = "mkt_down")
    private Integer mktDown;
    @Column(name = "mkt_flat")
    private Integer mktFlat;
    @Column(name = "univ_n")
    private Integer univN;
    @Column(name = "univ_up_ratio_1h")
    private BigDecimal univUpRatio1h;
    @Column(name = "univ_med_chg6h")
    private BigDecimal univMedChg6h;
}
