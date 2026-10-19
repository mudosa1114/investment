package com.coin.coin.repository;

import com.coin.coin.entity.LongTrade;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 장타 모의매매 기록 (long_trade, 10/16) */
public interface LongTradeRepository extends JpaRepository<LongTrade, Long> {

    /** 상태별 포지션 — OPEN(보유 중) / WATCH(조기 매도 후 6시간 그림자 대기) */
    List<LongTrade> findByStatus(String status);
}
