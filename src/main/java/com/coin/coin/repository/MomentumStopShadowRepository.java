package com.coin.coin.repository;

import com.coin.coin.entity.MomentumStopShadow;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * RSI모멘텀손절 섀도우 기록(momentum_stop_shadow) 저장소 — 3분봉 RSI vs 15분봉 RSI
 * 비교 데이터를 쌓기만 하는 순수 기록용. 현재는 별도 조회 쿼리 없이 save()만 사용한다
 * (분석은 DB에서 직접 SQL로 조회할 예정 — MomentumStopShadow 클래스 설명 참고).
 */
public interface MomentumStopShadowRepository extends JpaRepository<MomentumStopShadow, Long> {
}
