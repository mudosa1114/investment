package com.coin.coin.repository;

import com.coin.coin.entity.TradeHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TradeHistoryRepository extends JpaRepository<TradeHistory, Long> {

    @Query("SELECT t FROM TradeHistory t WHERE t.market = :market ORDER BY t.tradedAt DESC limit 1")
    TradeHistory findByMarket(@Param("market") String market);

}
