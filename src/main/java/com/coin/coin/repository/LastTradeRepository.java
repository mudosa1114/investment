package com.coin.coin.repository;

import com.coin.coin.entity.LastTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LastTradeRepository extends JpaRepository<LastTrade, Long> {

    @Query("SELECT t FROM LastTrade t WHERE t.market = :market")
    Optional<LastTrade> findByMarket(@Param("market") String market);

    /**
     * 특정 마켓의 drop/profit 카운트만 초기화.
     * 동적 목록 갱신 시 신규 진입 코인에만 사용 — 기존 유지 코인의 이력은 보존.
     */
    @Modifying
    @Query("UPDATE LastTrade t SET t.dropCount = 0, t.profitCount = 0 WHERE t.market IN :markets")
    void resetCountsByMarkets(@Param("markets") List<String> markets);
}
