package com.coin.coin.repository;

import com.coin.coin.entity.ExitReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ExitReviewRepository extends JpaRepository<ExitReview, Long> {

    /** 아직 추적이 끝나지 않은(24시간 미경과) 검증 레코드 전체 — 3분 루프에서 갱신 대상 */
    @Query("SELECT e FROM ExitReview e WHERE e.reviewComplete = false")
    List<ExitReview> findPending();

    /** 특정 기간에 완료된 검증 레코드 — 일별 집계 리포트용 */
    @Query("""
            SELECT e FROM ExitReview e
            WHERE e.reviewComplete = true
              AND e.sellTime >= :start
              AND e.sellTime < :end
            """)
    List<ExitReview> findCompletedBetween(@Param("start") LocalDateTime start,
                                           @Param("end") LocalDateTime end);
}
