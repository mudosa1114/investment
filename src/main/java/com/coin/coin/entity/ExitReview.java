package com.coin.coin.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 매도(익절/손절) 판단의 사후 검증 기록 (exit_review)
 *
 * <p>매도 시점의 지표 스냅샷을 남기고, 매도 이후 24시간 동안 해당 코인의 가격을
 * 계속 추적해 "그 매도 판단이 실제로 맞았는가"를 사후 검증한다.
 * <ul>
 *   <li>손절(damage)인데 이후 가격이 회복됐다면 → 손절이 너무 일렀을 가능성(조기손절의심)</li>
 *   <li>익절(profit)인데 이후 가격이 더 올랐다면 → 너무 일찍 팔았을 가능성(조기익절의심)</li>
 * </ul>
 * 가격 추적은 slowIndicatorCheck(3분 루프)가 어차피 후보 코인 전체의 현재가를 매번
 * 조회하는 데 편승(buildSignalMap 결과 재사용)해서, 별도의 추가 API 호출 없이 이뤄진다.
 */
@Entity
@Table(schema = "coin", name = "exit_review")
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class ExitReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String market;

    /** "profit" / "damage" — executeSell에 전달된 type 그대로 */
    private String sellType;

    /** 매도를 촉발한 구체적 로직 태그 (예: 강제손절, 시간강제매도, 트레일링익절, 점수손절, BULL모멘텀소진익절 등) */
    private String sellReason;

    /** 매도 체결 단가 (검증 기준가) */
    private BigDecimal sellPrice;

    /** 매수 평균가 (원가) — 매도가 실제 수익/손실 구간이었는지 참고용 */
    private BigDecimal avgBuyPrice;

    /** 매도 시점 지표 스냅샷 */
    private BigDecimal entryRsi;
    private String entryShortPhase;
    private String entryPhase;

    /** 매도 시점의 점수 익절 임계값(%) — effectPhase 기준. 사후 "익절임계까지 회복했는가" 판정에 사용 */
    private BigDecimal profitTargetPct;

    private LocalDateTime sellTime;

    /** 추적 종료 예정 시각 (sellTime + 24시간) */
    private LocalDateTime trackUntil;

    /** 매도 이후 관측된 최고가 / 최저가 (3분 주기로 갱신되는 연속 추적치) */
    private BigDecimal peakPrice;
    private LocalDateTime peakAt;
    private BigDecimal troughPrice;
    private LocalDateTime troughAt;

    /** 고정 체크포인트 — 해당 시점 경과 후 처음 관측된 가격 (근사치, 최대 3분 오차) */
    private BigDecimal priceAt15m;
    private BigDecimal priceAt1h;
    private BigDecimal priceAt3h;
    private BigDecimal priceAt6h;
    private BigDecimal priceAt24h;

    private Boolean reviewComplete;

    /** 추적 종료 시 계산되는 판정 결과 (예: "조기손절의심(익절임계도달)", "손절정당(회복없음)" 등) */
    private String verdict;
}
