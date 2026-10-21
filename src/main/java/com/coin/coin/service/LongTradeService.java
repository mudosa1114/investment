package com.coin.coin.service;

import com.coin.coin.dto.CoinPrice;
import com.coin.coin.dto.CoinSignalDto;
import com.coin.coin.entity.LongTrade;
import com.coin.coin.entity.LongTradeTick;
import com.coin.coin.repository.CoinCodeRepository;
import com.coin.coin.repository.LongTradeRepository;
import com.coin.coin.repository.LongTradeTickRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 장타 모의매매 (10/16 추가) — 직전 N시간 크게 빠진 유동 코인을 사서 6시간 들고 있다가 판다.
 *
 * <p>근거: 9/30~10/14 3분 스냅샷에서 1~6시간 되돌림(빠진 코인은 반등)이 뚜렷했고, 6시간 보유하면
 * 수수료·스프레드를 내고도 플러스였다(독립 진입 기준 6h-3 +0.98%, 3h-3 +0.76%, 6h-5 +1.56% / 건, 앞·뒤 기간 모두 플러스).
 * 표본이 작아(조건당 20~60건) 앞으로의 실제 결과로 검증하는 것이 목적.
 *
 * <ul>
 *   <li>대상: 감시 목록의 유동 코인, 스프레드 0.4% 미만. 10/21~ 목록 = 유니버스(조건 만족 코인, 거래대금 순 최대 40개, 매시간 갱신)</li>
 *   <li>동시 보유 코인 최대 {@value #MAX_HOLD_COINS}개 (10/21) — 이미 들고 있는 코인에 다른 조건으로 들어가는 건 개수에 안 셈.
 *       신호가 한꺼번에 많이 뜨면 많이 빠진 순서로 채운다</li>
 *   <li>진입 제외 (10/21): 최근 {@value #EXCLUDE_HOURS}시간 안에 "6시간보유"로 손실 마감한 코인 중
 *       한 번도 양전 못 했거나(max_rate ≤ 0) 고점을 먼저 찍고 빠진(max_at < min_at) 코인은 모든 조건에서 새로 사지 않는다.
 *       근거: 2주 백테스트에서 이런 코인의 다음 30시간 장타 평균 -0.11~-0.38% (그 외 +1.44%)</li>
 *   <li>거래 활발 재확인 (10/21): 감시 목록 코인은 매시간 다시 확인하지 않고 아래 두 시점에만 1번 확인한다.
 *       직전 3시간 거래대금이 24h 평소 속도의 50% 미만이면 감시 목록에서 뺀다(보유 중인 다른 조건 포지션은 그대로 관리).
 *       <ol>
 *         <li>보유 코인의 장타가 손실(pnl_rate &lt; 0)로 끝났을 때</li>
 *         <li>보유하지 않은 코인에 신규 진입하기 직전 — 탈락하면 진입하지 않는다</li>
 *       </ol></li>
 *   <li>조건 3가지를 동시에, 서로 독립적으로 시험 — 조건마다 코인당 포지션 1개</li>
 *   <li>진입: 매도호가로 6,000원 시장가 매수 가정. 손절 없음(최저치는 기록)</li>
 *   <li>청산 (10/19~, 3분 확인 · 진입 매도호가 대비 매수호가 %, 수수료 전):
 *       <ol>
 *         <li>+{@value #TAKE_PROFIT_TEXT}% 이상 → 즉시 매도 (익절+2%)</li>
 *         <li>보유 중 -0.5% 이하까지 빠진 적이 있고 지금 +0.5% 이상 → 즉시 매도 (손실회복+0.5%)</li>
 *         <li>보유 중 +1.5% 이상 찍은 적이 있고 지금 +0.3% 이하로 내려왔으면 → 즉시 매도 (고점후하락, 10/19 추가)
 *             — 꽤 올랐다가 이익을 다 돌려주고 손실로 끝나는 경우를 막는 안전장치</li>
 *         <li>모두 아니면 진입 6시간 후 매도 (6시간보유)</li>
 *       </ol>
 *       근거: 9/30~10/14 백테스트에서 6h-3 건당 +0.96% → +1.14%, 상위 5건 제외 -0.27% → +0.37%.
 *       10/16~19 하락장에서는 6시간 보유 -0.96% → 약 -0.2~-0.7%</li>
 *   <li>조기 매도해도 진입 6시간이 될 때까지 같은 조건·코인으로 다시 사지 않는다(status=WATCH) — 백테스트와 같은 구조.
 *       6시간 시점의 매수호가를 hold6h_* 로 남겨 "6시간 들고 있었다면"과 비교한다</li>
 *   <li>모의계좌와 무관한 가상 매매 — 단타와 서로 영향 없음. 기록은 long_trade 테이블</li>
 * </ul>
 * 슬로우 루프(3분)에서 호출. 지표는 슬로우 루프가 이미 계산한 것을 쓰므로 추가 API 호출은
 * 보유 중인데 감시 목록에서 빠진 코인의 호가 조회뿐이다. 보유 상태는 DB(status=OPEN)에 있어 재시작해도 이어진다.
 *
 * <p>10/21: 포지션마다 3분 확인 때 경로를 long_trade_tick에 1행씩 남긴다(진입 시점 0분 포함).
 * 일찍 판 포지션(WATCH)도 진입 6시간까지 그림자 경로(SHADOW)로 계속 남긴다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LongTradeService {

    private final LongTradeRepository longTradeRepository;
    private final LongTradeTickRepository longTradeTickRepository;
    private final CoinCodeRepository codeRepository;
    private final UpbitExchangeClient exchangeClient;
    private final CoinListService coinListService;

    /** 조건: code, 직전 몇 시간 변화율을 볼지, 그 변화율이 이 값 이하이면 매수 */
    public record Strategy(String code, int lookbackHours, BigDecimal dropPct) {
    }

    public static final List<Strategy> STRATEGIES = List.of(
            new Strategy("6h-3", 6, new BigDecimal("-3")),
            new Strategy("3h-3", 3, new BigDecimal("-3")),
            new Strategy("6h-5", 6, new BigDecimal("-5")));

    /** 진입 제외 기간(시간) — 6시간보유 손실(양전 못 함 / 고점 후 하락) 코인 */
    static final int EXCLUDE_HOURS = 24;
    /** 동시에 보유하는 코인 수 상한 (OPEN 기준, 조건이 달라도 같은 코인은 1개로 셈) */
    static final int MAX_HOLD_COINS = 30;
    /** 보유 시간(분) */
    public static final long HOLD_MINUTES = 360;
    /** ① 이 수익률(%) 이상이면 즉시 매도 */
    static final BigDecimal TAKE_PROFIT_PCT = new BigDecimal("2");
    static final String TAKE_PROFIT_TEXT = "2";
    /** ② 보유 중 최저가 이 값(%) 이하였던 적이 있고 */
    static final BigDecimal RECOVERY_LOSS_PCT = new BigDecimal("-0.5");
    /** ② 지금 이 수익률(%) 이상이면 즉시 매도 */
    static final BigDecimal RECOVERY_EXIT_PCT = new BigDecimal("0.5");
    /** ③ 보유 중 최고가 이 값(%) 이상이었던 적이 있고 */
    static final BigDecimal TRAIL_PEAK_PCT = new BigDecimal("1.5");
    /** ③ 지금 이 수익률(%) 이하로 내려오면 즉시 매도 (수수료 빼고 약 +0.2% 확보) */
    static final BigDecimal TRAIL_EXIT_PCT = new BigDecimal("0.3");
    /** 이 스프레드(%) 이상이면 진입하지 않음 */
    private static final BigDecimal MAX_SPREAD_PCT = new BigDecimal("0.4");
    /** 1회 매수 금액(원) — 단타와 같게 */
    private static final BigDecimal ORDER_AMOUNT = new BigDecimal("6000");
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0005");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** 슬로우 루프 지표의 호가를 그대로 써도 되는 최대 경과(초) */
    private static final long SIGNAL_FRESH_SECONDS = 240;

    private static final String OPEN = "OPEN";
    private static final String WATCH = "WATCH";
    private static final String CLOSED = "CLOSED";

    public void run(Map<String, CoinSignalDto> signalMap) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, CoinPrice> prices = new HashMap<>();
        Set<String> stillOpen = new HashSet<>();
        Set<String> lossClosed = new HashSet<>();

        // 1. 보유 중 — 최고·최저 갱신, 청산 규칙 확인
        for (LongTrade p : longTradeRepository.findByStatus(OPEN)) {
            stillOpen.add(key(p.getStrategy(), p.getMarket()));
            try {
                CoinPrice price = prices.computeIfAbsent(p.getMarket(), m -> priceOf(m, signalMap, now));
                BigDecimal bid = price.getBidPrice();
                BigDecimal rate = pct(p.getEntryPrice(), bid);
                if (p.getMaxRate() == null || rate.compareTo(p.getMaxRate()) > 0) {
                    p.setMaxRate(rate);
                    p.setMaxAt(now);
                }
                if (p.getMinRate() == null || rate.compareTo(p.getMinRate()) < 0) {
                    p.setMinRate(rate);
                    p.setMinAt(now);
                }
                p.setUpdatedAt(now);
                long heldSec = Duration.between(p.getEntryAt(), now).getSeconds();
                saveTick(p, now, heldSec, "HOLD", bid, rate, p.getMaxRate(), p.getMinRate(), signalMap.get(p.getMarket()));
                String reason = null;
                if (rate.compareTo(TAKE_PROFIT_PCT) >= 0) {
                    reason = "익절+" + TAKE_PROFIT_TEXT + "%";
                } else if (p.getMinRate().compareTo(RECOVERY_LOSS_PCT) <= 0 && rate.compareTo(RECOVERY_EXIT_PCT) >= 0) {
                    reason = "손실회복+0.5%";
                } else if (p.getMaxRate().compareTo(TRAIL_PEAK_PCT) >= 0 && rate.compareTo(TRAIL_EXIT_PCT) <= 0) {
                    reason = "고점후하락+0.3%";
                } else if (heldSec >= HOLD_MINUTES * 60) {
                    reason = "6시간보유";
                }
                if (reason != null) {
                    close(p, bid, heldSec, now, reason);
                    if (p.getPnlRate() != null && p.getPnlRate().signum() < 0) lossClosed.add(p.getMarket());
                    // 6시간을 채우고 판 건은 바로 재진입 가능, 조기 매도는 WATCH로 6시간까지 재진입 금지
                    if ("6시간보유".equals(reason)) stillOpen.remove(key(p.getStrategy(), p.getMarket()));
                } else {
                    longTradeRepository.save(p);
                }
            } catch (Exception e) {
                log.warn("{} [장타 {}] 보유 확인 실패: {}", p.getMarket(), p.getStrategy(), e.getMessage());
            }
        }

        // 1-2. 조기 매도 건 — 진입 6시간 시점의 매수호가를 그림자로 기록. 그때까지는 같은 조건·코인 재진입 금지
        for (LongTrade p : longTradeRepository.findByStatus(WATCH)) {
            if (now.equals(p.getExitAt())) { // 이번 확인에서 방금 판 건 — 위에서 HOLD 행을 이미 남김
                stillOpen.add(key(p.getStrategy(), p.getMarket()));
                continue;
            }
            long heldSec = Duration.between(p.getEntryAt(), now).getSeconds();
            boolean done = heldSec >= HOLD_MINUTES * 60;
            if (!done) stillOpen.add(key(p.getStrategy(), p.getMarket()));
            try {
                CoinPrice price = prices.computeIfAbsent(p.getMarket(), m -> priceOf(m, signalMap, now));
                BigDecimal bid = price.getBidPrice();
                BigDecimal rate = pct(p.getEntryPrice(), bid);
                // 그림자 경로의 최고·최저는 마지막 경로 행에서 이어간다
                BigDecimal runMax = p.getMaxRate();
                BigDecimal runMin = p.getMinRate();
                var last = longTradeTickRepository.findTopByTradeIdOrderByIdDesc(p.getId());
                if (last.isPresent()) {
                    runMax = last.get().getRunMax();
                    runMin = last.get().getRunMin();
                }
                runMax = runMax == null ? rate : runMax.max(rate);
                runMin = runMin == null ? rate : runMin.min(rate);
                saveTick(p, now, heldSec, "SHADOW", bid, rate, runMax, runMin, signalMap.get(p.getMarket()));
                if (done) {
                    p.setHold6hAt(now);
                    p.setHold6hPrice(bid);
                    p.setHold6hPnlRate(netRate(p.getEntryPrice(), bid));
                    p.setStatus(CLOSED);
                    p.setUpdatedAt(now);
                    longTradeRepository.save(p);
                }
            } catch (Exception e) {
                log.warn("{} [장타 {}] 그림자 추적 실패: {}", p.getMarket(), p.getStrategy(), e.getMessage());
                if (done) stillOpen.add(key(p.getStrategy(), p.getMarket()));
            }
        }

        // 1-3. 손실로 끝난 코인 — 거래 활발 1번 재확인, 미달이면 감시 목록에서 제외
        for (String coin : lossClosed) {
            String inactive = coinListService.checkInactive(coin);
            if (inactive != null) coinListService.removeFromList(coin, "장타 손실 마감 후 " + inactive);
        }

        // 2. 신규 진입 — 대상 코인 중 신호가 뜬 것. 많이 빠진 순서로, 동시 보유 코인 상한까지
        Set<String> heldCoins = new HashSet<>();
        for (LongTrade p : longTradeRepository.findByStatus(OPEN)) heldCoins.add(p.getMarket());
        Set<String> excluded = new HashSet<>(longTradeRepository.findExcludedMarkets(now.minusHours(EXCLUDE_HOURS)));
        record Candidate(String coin, Strategy st, BigDecimal chg, CoinSignalDto s) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (String coin : codeRepository.findAllCoinCode()) {
            if (excluded.contains(coin)) continue; // 10/21: ETH/SOL/XRP도 다른 코인과 같은 기준으로 진입
            CoinSignalDto s = signalMap.get(coin);
            if (s == null || s.getPrice() == null) continue;
            if (s.getSpreadPct() == null || s.getSpreadPct().compareTo(MAX_SPREAD_PCT) >= 0) continue;
            for (Strategy st : STRATEGIES) {
                BigDecimal chg = st.lookbackHours() == 6 ? s.getChg6h() : s.getChg3h();
                if (chg == null || chg.compareTo(st.dropPct()) > 0) continue;
                if (stillOpen.contains(key(st.code(), coin))) continue;
                candidates.add(new Candidate(coin, st, chg, s));
            }
        }
        candidates.sort(Comparator.comparing(Candidate::chg));
        int skippedByCap = 0;
        Set<String> inactiveCoins = new HashSet<>();
        for (Candidate c : candidates) {
            if (!heldCoins.contains(c.coin()) && heldCoins.size() >= MAX_HOLD_COINS) {
                skippedByCap++;
                continue;
            }
            // 미보유 코인 신규 진입 직전 — 거래 활발 1번 확인, 미달이면 진입 안 하고 감시 목록에서 제외
            if (!heldCoins.contains(c.coin())) {
                if (inactiveCoins.contains(c.coin())) continue;
                String inactive = coinListService.checkInactive(c.coin());
                if (inactive != null) {
                    inactiveCoins.add(c.coin());
                    coinListService.removeFromList(c.coin(), "장타 진입 직전 " + inactive);
                    continue;
                }
            }
            try {
                open(c.coin(), c.st(), c.chg(), c.s(), now);
                stillOpen.add(key(c.st().code(), c.coin()));
                heldCoins.add(c.coin());
            } catch (Exception e) {
                log.warn("{} [장타 {}] 진입 기록 실패: {}", c.coin(), c.st().code(), e.getMessage());
            }
        }
        if (skippedByCap > 0) {
            log.info("[장타] 동시 보유 코인 {}개 상한으로 진입 {}건 보류", MAX_HOLD_COINS, skippedByCap);
        }
    }

    private void open(String coin, Strategy st, BigDecimal chg, CoinSignalDto s, LocalDateTime now) {
        BigDecimal ask = s.getPrice().getAskPrice();
        LongTrade saved = longTradeRepository.save(LongTrade.builder()
                .strategy(st.code())
                .market(coin)
                .status(OPEN)
                .entryAt(now)
                .entryPrice(ask)
                .entryBid(s.getPrice().getBidPrice())
                .spreadPct(r(s.getSpreadPct(), 3))
                .triggerChg(r(chg, 3))
                .chg1h(r(s.getChg1h(), 3))
                .chg3h(r(s.getChg3h(), 3))
                .chg6h(r(s.getChg6h(), 3))
                .chg24h(r(s.getChg24h(), 3))
                .rsi15m(r(s.getRsi15m(), 2))
                .rsi30m(r(s.getRsi30m(), 2))
                .bbPos15m(r(s.getBbPos15m(), 2))
                .btcChg1h(r(s.getBtcChg1h(), 3))
                .orderAmount(ORDER_AMOUNT)
                .updatedAt(now)
                .build());
        BigDecimal bid = s.getPrice().getBidPrice();
        BigDecimal rate0 = pct(ask, bid);
        saveTick(saved, now, 0, "HOLD", bid, rate0, rate0, rate0, s);
        log.info("{} [장타 매수 {}] 체결가:{} 직전{}h {}%", coin, st.code(),
                ask.stripTrailingZeros().toPlainString(), st.lookbackHours(), r(chg, 2));
    }

    private void close(LongTrade p, BigDecimal bid, long heldSec, LocalDateTime now, String reason) {
        BigDecimal cost = ORDER_AMOUNT.multiply(BigDecimal.ONE.add(FEE_RATE));                       // 6,003원
        BigDecimal qty = ORDER_AMOUNT.divide(p.getEntryPrice(), 12, RoundingMode.HALF_UP);
        BigDecimal proceeds = qty.multiply(bid).multiply(BigDecimal.ONE.subtract(FEE_RATE));
        BigDecimal pnl = proceeds.subtract(cost);
        boolean held6h = "6시간보유".equals(reason);
        p.setStatus(held6h ? CLOSED : WATCH);
        p.setExitAt(now);
        p.setExitPrice(bid);
        p.setExitReason(reason);
        p.setHoldMinutes(BigDecimal.valueOf(heldSec).divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP));
        p.setRealizedPnl(pnl.setScale(0, RoundingMode.HALF_UP));
        p.setPnlRate(pnl.divide(cost, 10, RoundingMode.HALF_UP).multiply(HUNDRED).setScale(3, RoundingMode.HALF_UP));
        if (held6h) { // 6시간 보유로 판 건은 그림자 = 실제
            p.setHold6hAt(now);
            p.setHold6hPrice(bid);
            p.setHold6hPnlRate(p.getPnlRate());
        }
        longTradeRepository.save(p);
        log.info("{} [장타 매도 {} {}] 체결가:{} 손익:{}원({}%)", p.getMarket(), p.getStrategy(), reason,
                bid.stripTrailingZeros().toPlainString(), p.getRealizedPnl(), p.getPnlRate());
    }

    /** 이번 슬로우 루프에서 계산한 호가가 있으면 그대로, 없으면(목록에서 빠진 코인) 새로 조회 */
    private CoinPrice priceOf(String market, Map<String, CoinSignalDto> signalMap, LocalDateTime now) {
        CoinSignalDto s = signalMap.get(market);
        if (s != null && s.getPrice() != null && s.getComputedAt() != null
                && Duration.between(s.getComputedAt(), now).getSeconds() <= SIGNAL_FRESH_SECONDS) {
            return s.getPrice();
        }
        return exchangeClient.checkCoinPrice(market);
    }

    /** 경로 1행 저장 (10/21). 실패해도 매매 판단에는 영향 없음 */
    private void saveTick(LongTrade p, LocalDateTime now, long heldSec, String phase, BigDecimal bid, BigDecimal rate,
                          BigDecimal runMax, BigDecimal runMin, CoinSignalDto s) {
        try {
            boolean fresh = s != null && s.getComputedAt() != null
                    && Duration.between(s.getComputedAt(), now).getSeconds() <= SIGNAL_FRESH_SECONDS;
            longTradeTickRepository.save(LongTradeTick.builder()
                    .tradeId(p.getId())
                    .tickAt(now)
                    .elapsedMin((int) (heldSec / 60))
                    .phase(phase)
                    .bid(bid)
                    .rate(rate)
                    .runMax(runMax)
                    .runMin(runMin)
                    .rsi15m(fresh ? r(s.getRsi15m(), 2) : null)
                    .bbPos15m(fresh ? r(s.getBbPos15m(), 2) : null)
                    .build());
        } catch (Exception e) {
            log.warn("{} [장타 {}] 경로 기록 실패: {}", p.getMarket(), p.getStrategy(), e.getMessage());
        }
    }

    /** 수수료(매수·매도 각 0.05%) 포함 순손익률 % */
    private static BigDecimal netRate(BigDecimal entryAsk, BigDecimal bid) {
        BigDecimal net = bid.multiply(BigDecimal.ONE.subtract(FEE_RATE));
        BigDecimal cost = entryAsk.multiply(BigDecimal.ONE.add(FEE_RATE));
        return net.divide(cost, 10, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                .multiply(HUNDRED).setScale(3, RoundingMode.HALF_UP);
    }

    private static String key(String strategy, String market) {
        return strategy + "|" + market;
    }

    private static BigDecimal pct(BigDecimal base, BigDecimal target) {
        return target.divide(base, 10, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                .multiply(HUNDRED).setScale(3, RoundingMode.HALF_UP);
    }

    private static BigDecimal r(BigDecimal v, int scale) {
        return v == null ? null : v.setScale(scale, RoundingMode.HALF_UP);
    }
}
