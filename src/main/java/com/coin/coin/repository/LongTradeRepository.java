package com.coin.coin.repository;

import com.coin.coin.entity.LongTrade;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 장타 모의매매 기록 (long_trade, 10/16) */
public interface LongTradeRepository extends JpaRepository<LongTrade, Long> {

    /** 보유 중(OPEN) 포지션 */
    List<LongTrade> findByStatus(String status);
}
