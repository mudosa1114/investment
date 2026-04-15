package com.coin.coin.repository;

import com.coin.coin.entity.TradeHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TradeHistoryRepository extends JpaRepository<TradeHistory, Long> {

    @Query("SELECT t FROM TradeHistory t WHERE t.market = :market ORDER BY t.tradedAt DESC limit 1")
    TradeHistory findByMarket(@Param("market") String market);

    /**
     * 특정 기간 내 거래 내역 집계 (코인별 손익 계산용)
     * 반환: [market, tradeType, SUM(orderPrice), COUNT(*)]
     */
    @Query("""
            SELECT t.market, t.tradeType,
                   COALESCE(SUM(t.orderPrice), 0),
                   COUNT(t)
            FROM TradeHistory t
            WHERE t.tradedAt >= :start
              AND t.tradedAt < :end
            GROUP BY t.market, t.tradeType
            """)
    List<Object[]> aggregateByMarketAndType(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    /** 해당 기간에 거래한 코인 목록 (중복 제거) */
    @Query("""
            SELECT DISTINCT t.market FROM TradeHistory t
            WHERE t.tradedAt >= :start AND t.tradedAt < :end
            """)
    List<String> findDistinctMarkets(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
}
