package com.coin.coin.service;

import com.coin.coin.common.MarketPhase;
import com.coin.coin.dto.CoinAccount;
import com.coin.coin.dto.CoinPrice;
import com.coin.coin.dto.CoinSignalDto;
import com.coin.coin.dto.response.CandleResponse;
import com.coin.coin.dto.response.OrderBookResponse;
import com.coin.coin.entity.TradeHistory;
import com.coin.coin.repository.CoinCodeRepository;
import com.coin.coin.repository.TradeHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.coin.coin.dto.TradeHistoryDto.FEE_RATE;
import static com.coin.coin.dto.TradeHistoryDto.buyRow;

/**
 * 코인 지표 빌드 + 최초 매수.
 *
 * <p>10/7 모의매매 전면 개편: 매수 필터(스프레드/EMA구조/장기phase/RSI 구간·상승/BB위치/익절앵커/
 * 쿨다운/임시차단/블랙리스트)를 전부 삭제했다. 감시 목록(고정 + 동적, CoinListService)에 있고
 * 보유하지 않은 코인은 KRW만 있으면 무조건 산다. 지표는 매매 판단에 쓰지 않고 trade_history에
 * "매매 당시 값"으로 기록만 한다. 이전 필터 로직은 git 이력(a18ce7c 이전)에 그대로 남아 있다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoinSignalService {

    private final CoinCodeRepository codeRepository;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final UpbitExchangeClient exchangeClient;
    private final TechnicalIndicatorService indicatorService;
    private final TradingStateStore stateStore;

    /**
     * 1회 매수 금액. 업비트 최소주문은 5,000원이지만, 매도도 5,000원 미만이면 거부된다
     * (under_min_total_market_ask — 9/3 KRW-INJ가 7시간 매도 불가로 묶였던 사례). 5,000원으로 사면
     * -1% 손절 시점에 이미 4,950원이라 실거래였다면 손절 자체가 불가능하다. 그래서 -1% 손절 + 슬리피지를
     * 넉넉히 견디는 6,000원으로 둔다(실제 차감액 6,003원). 수익률(%) 분석에는 금액이 영향 없다.
     */
    private static final BigDecimal ORDER_AMOUNT = new BigDecimal("6000");

    /** 지표 계산용 캔들 개수 — RSI(Wilder)는 과거 캔들이 많을수록 차트 툴 값에 수렴한다 */
    private static final int CANDLE_COUNT = 100;
    /** 240분봉 국면은 4시간마다 한 봉이라 30분 캐시로 충분 (코인당 API 호출 절약) */
    private static final long LONG_PHASE_CACHE_MINUTES = 30;
    private final Map<String, MarketPhase> longPhaseCache = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> longPhaseCachedAt = new ConcurrentHashMap<>();

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    // ══════════════════════════════════════════════════════════════════
    //  지표 Map 빌드 — 슬로우 루프(3분)
    // ══════════════════════════════════════════════════════════════════
    public Map<String, CoinSignalDto> buildSignalMap(Set<String> holdCoinSet) {
        refreshBtcContext();

        Map<String, CoinSignalDto> map = new HashMap<>();
        // coin_code 목록 + 현재 보유 코인의 합집합 — 목록에서 빠진 코인을 보유 중이어도 매도 기록이 가능하도록
        Set<String> targetCoins = new HashSet<>(codeRepository.findAllCoinCode());
        holdCoinSet.stream().filter(c -> !c.equals("KRW-KRW")).forEach(targetCoins::add);

        for (String coin : targetCoins) {
            CoinSignalDto signal = buildSignal(coin);
            if (signal != null) map.put(coin, signal);
        }
        return map;
    }

    /**
     * 코인 하나의 지표를 지금 시점 기준으로 계산한다. 매도 직전·매도 후 추적에서도 단독 호출한다.
     * API 호출: 3분/15분/30분/60분봉 + 오더북 1회 (+240분봉은 30분 캐시). 실패 시 null.
     */
    public CoinSignalDto buildSignal(String coin) {
        try {
            List<CandleResponse> c3 = exchangeClient.candleResponses(coin, 3, CANDLE_COUNT);
            List<CandleResponse> c15 = exchangeClient.candleResponses(coin, 15, CANDLE_COUNT);
            List<CandleResponse> c30 = exchangeClient.candleResponses(coin, 30, CANDLE_COUNT);
            List<CandleResponse> c60 = exchangeClient.candleResponses(coin, 60, 50);
            if (exchangeClient.isInvalid(c3, 22)
                    || exchangeClient.isInvalid(c15, 40)
                    || exchangeClient.isInvalid(c30, 20)
                    || exchangeClient.isInvalid(c60, 40)) {
                log.warn("{} 캔들 부족 - 지표 계산 스킵", coin);
                return null;
            }

            List<OrderBookResponse> orderBook = exchangeClient.orderBook(coin);
            if (orderBook.isEmpty()) {
                log.warn("{} 오더북 없음 - 지표 계산 스킵", coin);
                return null;
            }
            CoinPrice price = CoinPrice.latestCoinPrice(orderBook);
            BigDecimal bid = price.getBidPrice();
            BigDecimal ask = price.getAskPrice();

            BigDecimal rsi = indicatorService.calculateRsi(c3);
            BigDecimal rsi15m = indicatorService.calculateRsi(c15);
            BigDecimal rsi30m = indicatorService.calculateRsi(c30);
            MarketPhase shortPhase = indicatorService.detectShortTermPhase(c15); // 15분봉
            MarketPhase phase = indicatorService.detectMarketPhase(c60);         // 60분봉 (기존 phase)
            MarketPhase longPhase = longPhase(coin);                             // 240분봉
            Map<String, BigDecimal> ema = indicatorService.calculateEmaCross(c15);
            Map<String, BigDecimal> bb = indicatorService.calculateBollingerBands(c3);
            Map<String, BigDecimal> bb15 = indicatorService.calculateBollingerBands(c15);
            Map<String, BigDecimal> bb30 = indicatorService.calculateBollingerBands(c30);

            // 관측 지표 — 하나가 실패해도 신호 전체를 버리지 않는다
            BigDecimal atr = BigDecimal.ZERO;
            BigDecimal volRatio = null;
            BigDecimal macdHist = null;
            String macdLog = "N/A";
            try {
                atr = indicatorService.calculateAtr(c3);
                // 기존과 같은 의미(최근 22봉 평균 대비)를 유지하려고 22봉만 사용
                volRatio = indicatorService.calculateVolumeRatio(c3.subList(0, Math.min(22, c3.size())));
                Map<String, BigDecimal> macd = indicatorService.calculateMacd(c15);
                macdHist = macd.get("histogram");
                macdLog = String.format("%s/%s/%s",
                        macd.get("macd").setScale(2, RoundingMode.HALF_UP),
                        macd.get("signal").setScale(2, RoundingMode.HALF_UP),
                        macd.get("histogram").setScale(2, RoundingMode.HALF_UP));
            } catch (Exception e) {
                log.warn("{} 관측 지표 계산 실패(무시하고 계속): {}", coin, e.getMessage());
            }
            BigDecimal obBidRatio = UpbitExchangeClient.bidRatio(orderBook);

            // 최근 1시간(3분봉 20개) 고가·저가, 1시간/24시간 변화율
            BigDecimal high1h = null;
            BigDecimal low1h = null;
            for (int i = 0; i < Math.min(20, c3.size()); i++) {
                BigDecimal h = c3.get(i).getHighPrice();
                BigDecimal l = c3.get(i).getLowPrice();
                if (h != null) high1h = high1h == null ? h : high1h.max(h);
                if (l != null) low1h = low1h == null ? l : low1h.min(l);
            }
            BigDecimal chg1h = c3.size() > 20 ? pct(c3.get(20).getTradePrice(), bid) : null;
            BigDecimal chg24h = c60.size() > 24 ? pct(c60.get(24).getTradePrice(), bid) : null;

            BigDecimal bbPos3m = bbPosition(bb, bid);
            BigDecimal bbPos15m = bbPosition(bb15, bid);
            BigDecimal bbPos30m = bbPosition(bb30, bid);
            BigDecimal bbWidth3m = bb.get("middle").signum() > 0
                    ? bb.get("upper").subtract(bb.get("lower"))
                    .divide(bb.get("middle"), 10, RoundingMode.HALF_UP).multiply(HUNDRED)
                    : null;
            BigDecimal atrPct = bid.signum() > 0 ? atr.divide(bid, 10, RoundingMode.HALF_UP).multiply(HUNDRED) : null;
            BigDecimal macdHistPct = (macdHist != null && bid.signum() > 0)
                    ? macdHist.divide(bid, 10, RoundingMode.HALF_UP).multiply(HUNDRED) : null;
            BigDecimal spreadPct = pct(bid, ask);

            // 기존 형식 그대로 + 뒤에 신규 항목 추가 (이전 로그 분석 스크립트와 호환)
            log.info("{} 지표스냅샷 RSI:{} RSI15m:{} BB상단:{} BB중간:{} BB하단:{} EMA5:{} EMA20:{} 데드크로스:{} 가격:{} 단기:{} 장기:{} 거래량배율:{} ATR:{} MACD/시그널/히스토:{} 오더북매수비율:{} 매도호가:{} RSI30m:{} 초장기:{} BB15위치:{} BB30위치:{} 스프레드%:{}",
                    coin, s2(rsi), s2(rsi15m),
                    bb.get("upper"), bb.get("middle"), bb.get("lower"),
                    ema.get("ema5"), ema.get("ema20"), !indicatorService.isGoldenCross(ema),
                    bid, shortPhase, phase,
                    volRatio == null ? "N/A" : s2(volRatio), atr.setScale(4, RoundingMode.HALF_UP), macdLog,
                    obBidRatio.setScale(3, RoundingMode.HALF_UP), ask,
                    s2(rsi30m), longPhase, s2(bbPos15m), s2(bbPos30m),
                    spreadPct == null ? "N/A" : spreadPct.setScale(3, RoundingMode.HALF_UP));

            return CoinSignalDto.builder()
                    .rsi(rsi).rsi15m(rsi15m).rsi30m(rsi30m)
                    .atr(atr)
                    .shortPhase(shortPhase).phase(phase).longPhase(longPhase)
                    .ema(ema).bb(bb).price(price)
                    .bbPos3m(bbPos3m).bbPos15m(bbPos15m).bbPos30m(bbPos30m).bbWidth3m(bbWidth3m)
                    .atrPct(atrPct).volRatio(volRatio).macdHistPct(macdHistPct)
                    .obBidRatio(obBidRatio).spreadPct(spreadPct)
                    .chg1h(chg1h).chg24h(chg24h).high1h(high1h).low1h(low1h)
                    .btcRsi15m(stateStore.getBtcRsi15m()).btcChg1h(stateStore.getBtcChg1h())
                    .computedAt(LocalDateTime.now())
                    .build();
        } catch (Exception e) {
            log.warn("{} 지표 빌드 실패: {}", coin, e.getMessage());
            return null;
        }
    }

    /** 매도 직전 기록용: 지금 시점 지표를 새로 계산, 실패하면 슬로우 루프 캐시로 대체 */
    public CoinSignalDto freshSignalOrCached(String coin) {
        CoinSignalDto fresh = buildSignal(coin);
        return fresh != null ? fresh : stateStore.getCachedSignalMap().get(coin);
    }

    // ══════════════════════════════════════════════════════════════════
    //  최초 매수 — 지표 필터 없음. 미보유 + KRW 충분 → 무조건 매수
    // ══════════════════════════════════════════════════════════════════
    public void firstPurchaseCoin(Set<String> holdCoinSet,
                                  Map<String, CoinSignalDto> signalMap,
                                  List<CoinAccount> accountList) {
        // 안전장치: 무지성 매수는 모의매매 전용. 실거래 모드에서는 절대 신규 매수하지 않는다.
        if (!exchangeClient.isPaperMode()) {
            log.error("[무조건매수] 실거래 모드(trading.paper-mode=false)에서는 무조건 매수 전략을 실행하지 않습니다 — 신규 매수 스킵");
            return;
        }

        BigDecimal remainingKrw = accountList.stream()
                .filter(a -> "KRW".equals(a.getCoinType()) && "KRW".equals(a.getCoinName()))
                .map(CoinAccount::getBalance)
                .findFirst()
                .orElse(BigDecimal.ZERO);
        BigDecimal need = ORDER_AMOUNT.multiply(BigDecimal.ONE.add(FEE_RATE)); // 6,003원

        for (String coin : codeRepository.findAllCoinCode()) {
            if (holdCoinSet.contains(coin)) continue;

            String currency = coin.replace("KRW-", "");
            boolean hasResidualBalance = accountList.stream()
                    .anyMatch(acc -> currency.equals(acc.getCoinName())
                            && acc.getBalance().compareTo(BigDecimal.ZERO) > 0);
            if (hasResidualBalance) continue;

            CoinSignalDto signal = signalMap.get(coin);
            if (signal == null) {
                log.info("{} 지표 없음 — 기록할 수 없어 이번 틱 매수 스킵", coin);
                continue;
            }
            if (remainingKrw.compareTo(need) < 0) {
                log.info("{} 매수 스킵 — KRW 잔고 부족(가용:{}원 필요:{}원)",
                        coin, remainingKrw.setScale(0, RoundingMode.DOWN), need);
                continue;
            }

            try {
                exchangeClient.orderCoin(coin, "bid", ORDER_AMOUNT.toPlainString());
            } catch (Exception e) {
                log.warn("{} 매수 주문 실패: {}", coin, e.getMessage());
                continue;
            }
            remainingKrw = remainingKrw.subtract(need);

            // 체결가·수량: 계좌의 평균매수가/보유수량 (신규 포지션이라 평균매수가 = 체결가)
            BigDecimal fillPrice = signal.getPrice().getAskPrice();
            BigDecimal volume = null;
            try {
                Optional<CoinAccount> acc = exchangeClient.checkCoinAccount().stream()
                        .filter(a -> currency.equals(a.getCoinName()) && "KRW".equals(a.getCoinType()))
                        .findFirst();
                if (acc.isPresent()) {
                    fillPrice = acc.get().getAvgBuyPrice();
                    volume = acc.get().getBalance();
                }
            } catch (Exception e) {
                log.warn("{} 매수 체결 정보 조회 실패 — 지표 시점 매도호가로 기록: {}", coin, e.getMessage());
            }

            TradeHistory saved = tradeHistoryRepository.save(buyRow(coin, ORDER_AMOUNT, fillPrice, volume, signal));
            stateStore.buyTradeIdMap.put(coin, saved.getId());
            stateStore.positionEntryTimeMap.put(coin, LocalDateTime.now());
            stateStore.holdMaxRateMap.remove(coin);
            stateStore.holdMinRateMap.remove(coin);
            BigDecimal exitPct = PositionExitService.exitPctFor(signal);
            stateStore.exitPctMap.put(coin, exitPct);

            log.info("{} [무조건매수] 금액:{}원(수수료포함 {}원) 체결가:{} RSI 3m/15m/30m:{}/{}/{} 국면 15m/60m/240m:{}/{}/{} BB위치 3m/15m/30m:{}/{}/{} 스프레드:{}% ATR:{}% → 익절·손절 ±{}%",
                    coin, ORDER_AMOUNT, saved.getOrderPrice(), fillPrice.stripTrailingZeros().toPlainString(),
                    s2(signal.getRsi()), s2(signal.getRsi15m()), s2(signal.getRsi30m()),
                    signal.getShortPhase(), signal.getPhase(), signal.getLongPhase(),
                    s2(signal.getBbPos3m()), s2(signal.getBbPos15m()), s2(signal.getBbPos30m()),
                    signal.getSpreadPct() == null ? "N/A" : signal.getSpreadPct().setScale(3, RoundingMode.HALF_UP),
                    signal.getAtrPct() == null ? "N/A" : signal.getAtrPct().setScale(3, RoundingMode.HALF_UP),
                    exitPct.stripTrailingZeros().toPlainString());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  보조
    // ══════════════════════════════════════════════════════════════════

    /** 240분봉 국면 — 30분 캐시. 실패하면 null(기록만 비고, 매매에는 영향 없음) */
    private MarketPhase longPhase(String coin) {
        LocalDateTime at = longPhaseCachedAt.get(coin);
        if (at != null && at.isAfter(LocalDateTime.now().minusMinutes(LONG_PHASE_CACHE_MINUTES))) {
            return longPhaseCache.get(coin);
        }
        try {
            List<CandleResponse> c240 = exchangeClient.candleResponses(coin, 240, 50);
            if (exchangeClient.isInvalid(c240, 30)) return null;
            MarketPhase p = indicatorService.detectMarketPhase(c240);
            longPhaseCache.put(coin, p);
            longPhaseCachedAt.put(coin, LocalDateTime.now());
            return p;
        } catch (Exception e) {
            log.warn("{} 240분봉 국면 계산 실패: {}", coin, e.getMessage());
            return null;
        }
    }

    /** 시장 전체 흐름: BTC 15분봉 RSI, BTC 1시간 변화율 — 슬로우 루프마다 1회 */
    private void refreshBtcContext() {
        try {
            List<CandleResponse> btc15 = exchangeClient.candleResponses("KRW-BTC", 15, CANDLE_COUNT);
            if (!exchangeClient.isInvalid(btc15, 40)) {
                stateStore.setBtcRsi15m(indicatorService.calculateRsi(btc15));
            }
            List<CandleResponse> btc3 = exchangeClient.candleResponses("KRW-BTC", 3, 21);
            if (!exchangeClient.isInvalid(btc3, 21)) {
                stateStore.setBtcChg1h(pct(btc3.get(20).getTradePrice(), btc3.get(0).getTradePrice()));
            }
        } catch (Exception e) {
            log.warn("BTC 시장흐름 지표 갱신 실패(이전 값 유지): {}", e.getMessage());
        }
    }

    /** BB 위치 % = (가격 - 하단) / (상단 - 하단) × 100 */
    private static BigDecimal bbPosition(Map<String, BigDecimal> bb, BigDecimal price) {
        BigDecimal range = bb.get("upper").subtract(bb.get("lower"));
        if (range.signum() <= 0) return null;
        return price.subtract(bb.get("lower")).divide(range, 10, RoundingMode.HALF_UP).multiply(HUNDRED);
    }

    /** (target / base - 1) × 100 */
    private static BigDecimal pct(BigDecimal base, BigDecimal target) {
        if (base == null || target == null || base.signum() == 0) return null;
        return target.divide(base, 10, RoundingMode.HALF_UP).subtract(BigDecimal.ONE).multiply(HUNDRED);
    }

    private static String s2(BigDecimal v) {
        return v == null ? "N/A" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
