package com.coin.coin.repository;

import com.coin.coin.entity.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TradeRepository extends JpaRepository<Trade, Long> {

    @Query("SELECT t FROM Trade t WHERE t.market = :market")
    Trade findByMarket(@Param("market") String market);
}
