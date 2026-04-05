package com.coin.coin.repository;

import com.coin.coin.entity.TradeHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TradeHistoryRepository extends JpaRepository<TradeHistory, Long> {

    @Query("SELECT t FROM TradeHistory t WHERE t.market = :market")
    TradeHistory findByMarket(@Param("market") String market);

    // 특정 날짜의 거래 내역 조회
    List<TradeHistory> findAllByTradedAtBetween(LocalDateTime start, LocalDateTime end);

    // 특정 날짜 + 코인별 거래 내역
    List<TradeHistory> findAllByMarketAndTradedAtBetween(
            String market, LocalDateTime start, LocalDateTime end);
}
