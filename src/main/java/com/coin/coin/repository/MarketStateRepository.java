package com.coin.coin.repository;

import com.coin.coin.entity.MarketState;
import org.springframework.data.jpa.repository.JpaRepository;

/** 시장 흐름 기록 (market_state, 10/23) */
public interface MarketStateRepository extends JpaRepository<MarketState, Long> {
}
