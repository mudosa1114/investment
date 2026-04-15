package com.coin.coin.repository;

import com.coin.coin.entity.TradeResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface TradeResultRepository extends JpaRepository<TradeResult, Long> {

    /** 특정 날짜의 전체 코인 손익 조회 */
    List<TradeResult> findByTradeDate(LocalDate tradeDate);

    /** 특정 코인의 전체 기간 손익 이력 조회 */
    @Query("SELECT r FROM TradeResult r WHERE r.market = :market ORDER BY r.tradeDate ASC")
    List<TradeResult> findByMarketOrderByDate(@Param("market") String market);

    /** 전체 코인 누적 총손익 합계 */
    @Query("SELECT COALESCE(SUM(r.totalPnl), 0) FROM TradeResult r")
    java.math.BigDecimal sumTotalPnl();

    /** 특정 날짜 중복 저장 방지 확인 */
    boolean existsByMarketAndTradeDate(String market, LocalDate tradeDate);
}
