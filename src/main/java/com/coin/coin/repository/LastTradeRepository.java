package com.coin.coin.repository;

import com.coin.coin.entity.LastTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LastTradeRepository extends JpaRepository<LastTrade, Long> {

    @Query("SELECT t FROM LastTrade t WHERE t.market = :market")
    Optional<LastTrade> findByMarket(@Param("market") String market);

    /**
     * 동적 코인 목록 갱신 후 호출 — 새 주기의 점수 판단을 위해
     * drop/profit 카운트를 초기화한다.
     */
    @Modifying
    @Query("UPDATE LastTrade t SET t.dropCount = 0, t.profitCount = 0")
    void resetAllCounts();
}
