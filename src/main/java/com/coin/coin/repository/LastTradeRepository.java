package com.coin.coin.repository;

import com.coin.coin.entity.LastTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LastTradeRepository extends JpaRepository<LastTrade, Long> {

    @Query("SELECT t FROM LastTrade t WHERE t.market = :market")
    Optional<LastTrade> findByMarket(@Param("market") String market);
}
