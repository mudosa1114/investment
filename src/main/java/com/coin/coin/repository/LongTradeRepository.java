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
     * 진입 제외 코인 (10/21) — since 이후 "6시간보유"로 손실 마감한 장타 중
     * (11/3: 비상손절은 여기서 빼고 LongTradeService의 "비상손절 후 재구매는 조건 3개 이상"으로 대체)
     * 한 번도 양전 못 했거나(max_rate ≤ 0) 고점을 먼저 찍고 빠진(max_at < min_at) 건의 코인.
     * 10/29: 지금 모드(실전 REAL / 모의 VIRTUAL·null)의 거래만 본다 — 모드를 바꾸면 처음부터 시작.
     */
    @Query("""
            SELECT DISTINCT t.market FROM LongTrade t
            WHERE t.exitReason = '6시간보유'
              AND t.pnlRate < 0
              AND t.exitAt >= :since
              AND (t.maxRate <= 0 OR t.maxAt < t.minAt)
              AND (t.orderMode = :mode OR (:mode = 'VIRTUAL' AND t.orderMode IS NULL))
            """)
    List<String> findExcludedMarkets(@Param("since") LocalDateTime since, @Param("mode") String mode);

    /**
     * 코인별 가장 최근 장타의 [market, exitReason, entryAt] (11/3, 비상손절 후 재구매 제한용) — 지금 모드 거래만.
     * 가장 최근 장타가 아직 보유 중(OPEN)인 코인은 빠진다(어차피 재진입 금지).
     * 10/27 이전 조건별 포지션은 같은 코인·같은 진입 시각에 여러 행일 수 있다(매도 사유는 같음).
     */
    @Query("""
            SELECT t.market, t.exitReason, t.entryAt FROM LongTrade t
            WHERE t.status <> 'OPEN'
              AND (t.orderMode = :mode OR (:mode = 'VIRTUAL' AND t.orderMode IS NULL))
              AND t.entryAt = (SELECT MAX(t2.entryAt) FROM LongTrade t2
                               WHERE t2.market = t.market
                                 AND (t2.orderMode = :mode OR (:mode = 'VIRTUAL' AND t2.orderMode IS NULL)))
            """)
    List<Object[]> findLastTrades(@Param("mode") String mode);

    /** since 이후 장타 진입이 있었던 코인 (10/27, 6h-3M 시장 조건 — 최근 30분 동시 신호 코인 수) */
    @Query("SELECT DISTINCT t.market FROM LongTrade t WHERE t.entryAt > :since")
    List<String> findMarketsEnteredSince(@Param("since") LocalDateTime since);
}
