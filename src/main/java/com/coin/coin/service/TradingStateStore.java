package com.coin.coin.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 슬로우 루프 간 공유되는 인메모리 상태 — 시장 전체 흐름(BTC) 참고값.
 *
 * <p>10/29: 단타 코드 제거와 함께 단타용 포지션·쿨다운·차단·추적 맵(진입시각, RSI 최고·최저, 트레일링,
 * 연속손절, 블랙리스트, 손절 유예, 최고·최저 수익률 등)과 자정 초기화를 모두 삭제했다. 이전 내용은 git 이력에 남아 있다.
 */
@Component
public class TradingStateStore {

    /** BTC 15분봉 RSI·1시간 변화율 % — 슬로우 루프마다 갱신, 장타 진입 기록에 같이 남긴다 */
    @Getter
    @Setter
    private volatile BigDecimal btcRsi15m;
    @Getter
    @Setter
    private volatile BigDecimal btcChg1h;
    /** BTC 6시간·24시간 변화율 % (10/23, market_state 기록용 — 15분봉 기준) */
    @Getter
    @Setter
    private volatile BigDecimal btcChg6h;
    @Getter
    @Setter
    private volatile BigDecimal btcChg24h;
}
