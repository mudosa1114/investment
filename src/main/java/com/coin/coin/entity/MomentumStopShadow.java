package com.coin.coin.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * RSI모멘텀손절 섀도우 기록 (momentum_stop_shadow, 9/23 도입).
 *
 * <p>실거래에는 더 이상 반영되지 않는(9/23 제거) RSI모멘텀손절 조건 — 손실 ≥ -0.5% +
 * RSI(3분봉) 고점대비 -7 이상 하락 + 현재 RSI(3분봉) &lt; 50 + 진입 대비 실제 모멘텀 有 +
 * 최소 보유시간 경과 — 이 그대로 충족되는 매 순간을 "실제 매도 없이" 기록만 한다.
 *
 * <p>목적: 같은 순간의 3분봉 RSI와 15분봉 RSI(rsi15m, {@link com.coin.coin.dto.CoinSignalDto}
 * 참고)를 나란히 남겨서, "3분봉 기준으로는 모멘텀 붕괴처럼 보였던 순간이 15분봉 기준으로도
 * 똑같이 붕괴로 보이는지"를 나중에 비교 검증하기 위함. 15분봉 RSI 쪽이 같은 순간에 하락폭이
 * 훨씬 작다면(노이즈에 덜 민감하다면) 캔들 간격을 늘리는 방향이 근거를 얻는 것이고, 15분봉도
 * 비슷하게 무너진다면 캔들 간격 문제가 아니라 임계값 자체의 문제로 봐야 한다.
 *
 * <p>매매 판단에는 전혀 관여하지 않는 순수 관측·기록용 테이블 — PositionExitService의
 * 손실구간 상태머신(관망/라운드)이 이 순간들을 실제로는 어떻게 처리하는지는 그쪽 로그로
 * 별도 추적된다.
 */
@Entity
@Table(schema = "coin", name = "momentum_stop_shadow")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MomentumStopShadow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String market;

    /** 이 조건이 충족된(= 옛 로직이면 매도했을) 시각 */
    private LocalDateTime capturedAt;

    /** 이 순간의 평가손실률(%, 음수) — 옛 BULL_RSI_STOP_MIN_LOSS 조건 기준값 */
    private BigDecimal lossPct;

    /** 포지션 진입 후 경과 시간(분) */
    private Long heldMinutes;

    // ─── 3분봉 RSI (기존 실거래 판단 기준이었던 값) — 필드명에 숫자를 넣지 않는다
    //     (JPA 기본 네이밍전략이 "Rsi3m"처럼 문자 바로 뒤에 오는 숫자 경계를 일관되게
    //     스네이크케이스로 못 쪼갤 수 있어 컬럼명이 예측 불가능해짐 — Short/Medium으로 대체) ──
    private BigDecimal entryRsiShort;
    private BigDecimal peakRsiShort;
    private BigDecimal currentRsiShort;
    /** peakRsiShort - currentRsiShort (클수록 급격한 하락) */
    private BigDecimal dropRsiShort;

    // ─── 15분봉 RSI (비교용 신규 관측치) ──────────────────────────────
    private BigDecimal entryRsiMedium;
    private BigDecimal peakRsiMedium;
    private BigDecimal currentRsiMedium;
    private BigDecimal dropRsiMedium;
}
