package com.coin.coin.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 코인별 일별 손익 집계 테이블 (trade_result)
 *
 * <p>매일 00:10 KST 에 전날(00:00~23:59) 거래 내역을 집계해 저장한다.
 * <ul>
 *   <li>realizedPnl  = 당일 매도 수령액 - 당일 매수 지출액 (일일 현금 흐름 기준)</li>
 *   <li>unrealizedPnl= 집계 시점 보유 평가액 - 보유 매입 원가 (미결 포지션)</li>
 *   <li>totalPnl     = realizedPnl + unrealizedPnl</li>
 * </ul>
 */
@Entity
@Table(schema = "coin", name = "trade_result")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TradeResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 마켓 코드 (e.g. KRW-SOL) */
    private String market;

    /** 집계 대상 날짜 (KST 기준 전날) */
    private LocalDate tradeDate;

    /** 당일 매수 총액 (KRW) */
    private BigDecimal buyAmount;

    /** 당일 매도 수령 총액 (KRW) */
    private BigDecimal sellAmount;

    /** 당일 매수 횟수 */
    private Integer buyCount;

    /** 당일 익절 횟수 */
    private Integer profitCount;

    /** 당일 손절 횟수 */
    private Integer stopCount;

    /**
     * 당일 실현 손익 (= sellAmount - buyAmount)
     * 당일 매수 후 미매도 분은 음수로 기여하며, 누적 합산으로 전체 추이를 파악한다.
     */
    private BigDecimal realizedPnl;

    /**
     * 집계 시점(00:10 KST) 기준 미실현 손익
     * = 현재가 × 보유수량 - 평균매수가 × 보유수량
     * 해당 코인 보유분이 없으면 0.
     */
    private BigDecimal unrealizedPnl;

    /** 총 손익 = realizedPnl + unrealizedPnl */
    private BigDecimal totalPnl;

    /** 집계 실행 시각 */
    private LocalDateTime createdAt;
}
