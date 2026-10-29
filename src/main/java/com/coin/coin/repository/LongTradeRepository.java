package com.coin.coin.repository;

import com.coin.coin.entity.LongTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

import java.util.List;

/** 장타 모의매매 기록 (long_trade, 10/16) */
public interface LongTradeRepository extends JpaRepository<LongTrade, Long> {

    /** 상태별 포지션 — OPEN(보유 중) / WATCH(조기 매도 후 6시간 그림자 대기) */
    List<LongTrade> findByStatus(String status);

    /**
     * 진입 제외 코인 (10/21) — since 이후 "6시간보유"·"비상손절-7%"(10/22)로 손실 마감한 장타 중
     * 한 번도 양전 못 했거나(max_rate ≤ 0) 고점을 먼저 찍고 빠진(max_at < min_at) 건의 코인.
     * 10/29: 지금 모드(실전 REAL / 모의 VIRTUAL·null)의 거래만 본다 — 모드를 바꾸면 처음부터 시작.
     */
    @Query("""
            SELECT DISTINCT t.market FROM LongTrade t
            WHERE t.exitReason IN ('6시간보유', '비상손절-7%')
              AND t.pnlRate < 0
              AND t.exitAt >= :since
              AND (t.maxRate <= 0 OR t.maxAt < t.minAt)
              AND (t.orderMode = :mode OR (:mode = 'VIRTUAL' AND t.orderMode IS NULL))
            """)
    List<String> findExcludedMarkets(@Param("since") LocalDateTime since, @Param("mode") String mode);

    /** since 이후 장타 진입이 있었던 코인 (10/27, 6h-3M 시장 조건 — 최근 30분 동시 신호 코인 수) */
    @Query("SELECT DISTINCT t.market FROM LongTrade t WHERE t.entryAt > :since")
    List<String> findMarketsEnteredSince(@Param("since") LocalDateTime since);
}
