package com.coin.coin.service;

import com.coin.coin.dto.CoinAccount;
import com.coin.coin.dto.CoinSignalDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 청산 판단 — 10/7 모의매매 전면 개편으로 단순화.
 *
 * <p>지표·점수·트레일링·관망 라운드·RSI 모멘텀·추가매수 로직을 전부 삭제하고, 패스트 루프(30초)에서
 * 현재 매수호가 / 평균매수가 만으로 판단한다.
 * <ul>
 *   <li>+0.3% 이상 → 즉시 익절</li>
 *   <li>-1.0% 이하 → 즉시 손절</li>
 * </ul>
 * 기준은 가격 변화율(수수료 제외)이다. 수수료(왕복 0.1%)를 빼면 실제 손익은 익절 약 +0.2%, 손절 약 -1.1%.
 * 이전 청산 로직은 git 이력(a18ce7c 이전)에 그대로 남아 있다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PositionExitService {

    private final UpbitExchangeClient exchangeClient;
    private final TradingStateStore stateStore;
    private final TradeExecutionService tradeExecutionService;
    private final CoinSignalService coinSignalService;

    /** 익절 기준: 매수호가 ≥ 평균매수가 × 1.003 */
    public static final BigDecimal TAKE_PROFIT_RATE = new BigDecimal("1.003");
    /** 손절 기준: 매수호가 ≤ 평균매수가 × 0.990 */
    public static final BigDecimal STOP_LOSS_RATE = new BigDecimal("0.990");
    /** ExitReviewService(구 매도검증)가 참조하는 익절 임계값 — 호환용 */
    static final BigDecimal PROFIT_THRESHOLD = TAKE_PROFIT_RATE;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 패스트 루프(30초)에서 보유 코인마다 호출 */
    public void checkExit(CoinAccount account, String coinNm) {
        BigDecimal avg = account.getAvgBuyPrice();
        BigDecimal balance = account.getBalance();
        if (avg == null || avg.signum() <= 0 || balance == null || balance.signum() <= 0) return;

        BigDecimal bid = exchangeClient.checkCoinPrice(coinNm).getBidPrice();
        BigDecimal rate = bid.divide(avg, 10, RoundingMode.HALF_UP);
        BigDecimal ratePct = rate.subtract(BigDecimal.ONE).multiply(HUNDRED).setScale(3, RoundingMode.HALF_UP);

        // 보유 중 최고/최저 수익률 (MFE/MAE) — 30초 간격 표본
        stateStore.holdMaxRateMap.merge(coinNm, ratePct, BigDecimal::max);
        stateStore.holdMinRateMap.merge(coinNm, ratePct, BigDecimal::min);

        String type;
        String reason;
        if (rate.compareTo(TAKE_PROFIT_RATE) >= 0) {
            type = "profit";
            reason = "익절+0.3%";
        } else if (rate.compareTo(STOP_LOSS_RATE) <= 0) {
            type = "damage";
            reason = "손절-1%";
        } else {
            return;
        }

        log.info("{} [{}] 평균매수가:{} 매수호가:{} 변화율:{}% (보유중 최고:{}% 최저:{}%)",
                coinNm, reason, avg.stripTrailingZeros().toPlainString(), bid.stripTrailingZeros().toPlainString(),
                ratePct, stateStore.holdMaxRateMap.get(coinNm), stateStore.holdMinRateMap.get(coinNm));

        // 매도 당시 지표를 기록하기 위해 지금 시점 지표를 새로 계산 (실패 시 슬로우 루프 캐시)
        CoinSignalDto signal = coinSignalService.freshSignalOrCached(coinNm);
        if (signal == null) {
            log.warn("{} 지표 없음 — 다음 틱에 매도 재시도", coinNm);
            return;
        }
        tradeExecutionService.executeSell(coinNm, balance.toPlainString(), type, signal, avg, reason);
    }
}
