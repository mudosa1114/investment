package com.coin.coin.repository;

import com.coin.coin.entity.LongTradeTick;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 장타 포지션 3분 경로 (long_trade_tick, 10/21) */
public interface LongTradeTickRepository extends JpaRepository<LongTradeTick, Long> {

    /** 포지션의 마지막 경로 행 — 그림자 구간(SHADOW)의 최고·최저를 이어가기 위해 */
    Optional<LongTradeTick> findTopByTradeIdOrderByIdDesc(Long tradeId);
}
