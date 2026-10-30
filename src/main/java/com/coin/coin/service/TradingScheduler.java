package com.coin.coin.service;

import com.coin.coin.dto.CoinSignalDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 매매 루프 — 슬로우 루프(3분, fixedRate) 하나: 감시 목록 지표 계산 → 장타(LongTradeService).
 *
 * <p>10/29: 단타 코드 제거. 단타 매도 판단(3분, 계좌 보유 코인 전체 대상)·30초 패스트 루프(본전탈출·보유 최고·최저·
 * 매도후추적·모의 지정가 확인)·exit_review 갱신·단타 신규 매수를 모두 삭제했다. 이제 계좌의 코인을 자동으로 파는 로직은
 * 장타 청산(LongTradeService, 장타가 직접 산 포지션만)뿐이다. 이전 로직은 git 이력에 남아 있다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TradingScheduler {

    private final CoinSignalService coinSignalService;
    private final LongTradeService longTradeService;

    @Scheduled(fixedRate = 3, timeUnit = TimeUnit.MINUTES)
    public void slowTradeLoop() {
        Map<String, CoinSignalDto> signalMap;
        try {
            // 감시 목록 + 장타 보유·그림자 코인 (10/30: 감시 목록에서 빠진 코인도 tick 지표를 남기기 위해)
            signalMap = coinSignalService.buildSignalMap(longTradeService.trackedMarkets());
        } catch (Exception e) {
            log.error("지표 계산 중 예외 — 이번 루프 스킵: {}", e.getMessage(), e);
            return;
        }
        // 장타 (10/16~) — 직전 3·6시간 크게 빠진 코인 매수·청산. 기록은 long_trade / long_trade_tick / market_state
        try {
            longTradeService.run(signalMap);
        } catch (Exception e) {
            log.error("장타 처리 중 예외: {}", e.getMessage(), e);
        }
    }
}
