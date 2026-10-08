package com.coin.coin.repository;

import com.coin.coin.entity.TradeHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TradeHistoryRepository extends JpaRepository<TradeHistory, Long> {

    @Query("SELECT t FROM TradeHistory t WHERE t.market = :market ORDER BY t.tradedAt DESC limit 1")
    TradeHistory findByMarket(@Param("market") String market);

    /** 코인의 가장 최근 매수 행 — 재시작 등으로 메모리 상태가 없을 때 매도 행의 ref_id/보유시간 복원용 (10/7) */
    Optional<TradeHistory> findTopByMarketAndTradeTypeOrderByIdDesc(String market, String tradeType);

    /**
     * 특정 기간 내 거래 내역 집계 (코인별 손익 계산용)
     * 반환: [market, tradeType, SUM(orderPrice), COUNT(*)]
     * 10/7: 매도 후 추적 행('추적')은 거래가 아니므로 제외
     */
    @Query("""
            SELECT t.market, t.tradeType,
                   COALESCE(SUM(t.orderPrice), 0),
                   COUNT(t)
            FROM TradeHistory t
            WHERE t.tradedAt >= :start
              AND t.tradedAt < :end
              AND t.tradeType <> '추적'
            GROUP BY t.market, t.tradeType
            """)
    List<Object[]> aggregateByMarketAndType(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    /** 해당 기간에 거래한 코인 목록 (중복 제거, 추적 행 제외) */
    @Query("""
            SELECT DISTINCT t.market FROM TradeHistory t
            WHERE t.tradedAt >= :start AND t.tradedAt < :end
              AND t.tradeType <> '추적'
            """)
    List<String> findDistinctMarkets(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    /** 당일 전체 실현손익 합산 (circuit breaker 판단용) */
    @Query("""
            SELECT COALESCE(SUM(t.realizedPnl), 0) FROM TradeHistory t
            WHERE t.tradedAt >= :start AND t.realizedPnl IS NOT NULL
            """)
    BigDecimal sumTodayRealizedPnl(@Param("start") LocalDateTime start);

    /**
     * 기간 내 매도 레코드의 코인별 실현손익 집계
     * 반환: [market, SUM(realizedPnl), COUNT(익절), COUNT(손절)]
     */
    @Query("""
            SELECT t.market,
                   COALESCE(SUM(t.realizedPnl), 0),
                   SUM(CASE WHEN t.tradeType = '익절' THEN 1 ELSE 0 END),
                   SUM(CASE WHEN t.tradeType = '손절' THEN 1 ELSE 0 END)
            FROM TradeHistory t
            WHERE t.tradeType IN ('익절', '손절')
              AND t.tradedAt >= :start
              AND t.tradedAt < :end
              AND t.realizedPnl IS NOT NULL
            GROUP BY t.market
            """)
    List<Object[]> sumRealizedPnlByMarket(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    /** 매도 후 추적 대상: since 이후 매도 행 (10/7) */
    @Query("""
            SELECT t FROM TradeHistory t
            WHERE t.tradeType IN ('익절', '손절')
              AND t.tradedAt >= :since
            ORDER BY t.tradedAt
            """)
    List<TradeHistory> findSellsSince(@Param("since") LocalDateTime since);

    /** 매도 행별로 이미 기록된 추적 경과 분의 최댓값 — 반환: [ref_id, MAX(track_minute)] (10/8) */
    @Query("""
            SELECT t.refId, MAX(t.trackMinute) FROM TradeHistory t
            WHERE t.tradeType = '추적'
              AND t.tradedAt >= :since
            GROUP BY t.refId
            """)
    List<Object[]> maxTrackMinuteByRef(@Param("since") LocalDateTime since);
}
