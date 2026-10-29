package com.coin.coin.service;

import com.coin.coin.dto.CoinPrice;
import com.coin.coin.dto.CoinAccount;
import com.coin.coin.dto.CoinSignalDto;
import com.coin.coin.dto.response.OrderResponse;
import com.coin.coin.dto.response.OrdersResponse;
import com.coin.coin.entity.LongTrade;
import com.coin.coin.entity.LongTradeTick;
import com.coin.coin.repository.CoinCodeRepository;
import com.coin.coin.repository.LongTradeRepository;
import com.coin.coin.repository.LongTradeTickRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 *   <li>동시 보유 코인 최대 trading.long-max-hold-coins개(기본 30, 10/21). 신호가 한꺼번에 많이 뜨면 많이 빠진 순서로 채운다</li>
 *   <li>코인당 1포지션 (10/28~): 아래 진입 조건 중 하나라도 맞으면 1번만 산다(strategy = {@value #STRATEGY_ANY}).
 *       그 코인을 보유 중이거나(OPEN) 조기 매도 후 진입 6시간이 안 됐으면(WATCH) 어떤 조건으로도 다시 사지 않는다.
 *       맞은 조건은 전부 matched_conditions(예: "6h-3,6h-5,3h-3")·match_count에, 시장 약세 여부는 market_weak에 기록한다.
 *       이전(10/16~10/27)에는 조건마다 독립 포지션이라 같은 코인·같은 시각에 2~4건이 똑같이 기록됐다</li>
 *   <li>진입 제외 (10/21): 최근 {@value #EXCLUDE_HOURS}시간 안에 "6시간보유"·"비상손절"로 손실 마감한 코인 중
 *       한 번도 양전 못 했거나(max_rate ≤ 0) 고점을 먼저 찍고 빠진(max_at < min_at) 코인은 모든 조건에서 새로 사지 않는다.
 *       근거: 2주 백테스트에서 이런 코인의 다음 30시간 장타 평균 -0.11~-0.38% (그 외 +1.44%)</li>
 *   <li>거래 활발 재확인 (10/21): 감시 목록 코인은 매시간 다시 확인하지 않고 아래 두 시점에만 1번 확인한다.
 *       직전 3시간 거래대금이 24h 평소 속도의 50% 미만이면 감시 목록에서 뺀다(이미 보유 중인 포지션은 그대로 관리).
 *       <ol>
 *         <li>보유 코인의 장타가 손실(pnl_rate &lt; 0)로 끝났을 때</li>
 *         <li>보유하지 않은 코인에 신규 진입하기 직전 — 탈락하면 진입하지 않는다</li>
 *       </ol></li>
 *   <li>진입 조건: 6h-3(직전 6시간 -3% 이하) · 3h-3(직전 3시간 -3% 이하) · 6h-5(직전 6시간 -5% 이하, 6h-3에 포함) ·
 *       6h-3M(6h-3 + 시장 약세, 6h-3에 포함). 실제 진입은 사실상 "6h-3 또는 3h-3"이고 나머지는 기록용 표시다</li>
 *   <li>6h-3M / market_weak (10/27 추가): "시장 전체가 약할 때"인지 —
 *       시장 약세 = 감시 목록 코인 6시간 변화율 중앙값 ≤ {@value #MARKET_WEAK_MED_CHG6H_TEXT}%
 *       또는 최근 {@value #MARKET_CROWD_MINUTES}분 안에 장타 신호·진입이 있었던 코인 {@value #MARKET_CROWD_MIN}개 이상.
 *       근거: 10/23~27 market_state 분석에서 시장은 멀쩡한데 그 코인만 빠진 진입이 손실 대부분(-1.39%/건),
 *       시장이 같이 빠진 진입은 플러스. 10/28~ 진입 필터로는 쓰지 않고 market_weak로 기록만 해서 검증한다</li>
 *   <li>진입: trading.long-order-amount원(기본 6,000) 시장가 매수. 손절은 -7% 비상손절 하나뿐(최저치는 기록)</li>
 *   <li>모의/실전 (10/29~): trading.paper-mode 하나로 나눈다.
 *       true(모의, 기본)면 주문 없이 매도호가 체결 가정으로 기록만 한다(order_mode=VIRTUAL, 잔고 제한 없음 — 손익은 realized_pnl).
 *       false(실전)면 진입·청산 때 업비트 실계좌로 시장가 주문을 내고 실제 체결가·수량으로 기록한다(order_mode=REAL).
 *       주문 금액(trading.long-order-amount)과 동시 보유 상한(trading.long-max-hold-coins)은 두 모드에 똑같이 적용된다.
 *       <ul>
 *         <li>매수 전 KRW 잔고(주문금액 + 수수료)를 확인하고, 모자라면 그 코인은 건너뛴다</li>
 *         <li>매도는 그 포지션이 산 수량(volume)만큼, 계좌 잔고가 그보다 적으면 잔고만큼 판다.
 *             매도 주문이 실패하면 OPEN으로 두고 다음 3분 확인 때 다시 시도한다</li>
 *         <li>실전으로 산 포지션(REAL)을 모의 모드로 바꾼 뒤 청산하게 되면 주문 없이 기록만 닫는다(로그 ERROR) —
 *             모의 모드에서는 실계좌를 건드리지 않으므로 그 코인은 직접 팔아야 한다.
 *             반대로 모의로 연 포지션(VIRTUAL)은 실전 모드에서도 주문 없이 기록만 닫는다</li>
 *       </ul></li>
 *   <li>청산 (10/19~, 3분 확인 · 진입 매도호가 대비 매수호가 %, 수수료 전):
 *       <ol>
 *         <li>{@value #EMERGENCY_STOP_TEXT}% 이하 → 즉시 매도 (비상손절-7%, 10/22 추가)
 *             — 반등 없이 계속 흘러내리는 코인의 -10~-20%대 손실을 막는 보험. 10/16~22 371건 기준
 *             -7% 이하를 찍고 이익으로 끝난 거래는 0건, 걸리는 건 17건(그중 11건은 더 크게 잃음)</li>
 *         <li>+{@value #TAKE_PROFIT_TEXT}% 이상 → 즉시 매도 (익절+2%)</li>
 *         <li>보유 중 -0.5% 이하까지 빠진 적이 있고 지금 +0.5% 이상 → 즉시 매도 (손실회복+0.5%)</li>
 *         <li>보유 중 +1.5% 이상 찍은 적이 있고 지금 그 최고치보다 0.6%p 이상 내려왔으면 → 즉시 매도 (고점대비-0.6%, 10/19 추가)
 *             — 10/27: "+0.3% 이하로 내려오면"에서 변경. 저가 코인은 호가 한 칸이 0.3~0.6%라 +0.3%를 건너뛰고
 *             0.00%(수수료 빼고 -0.1%)에 팔리는 일이 대부분이었다. 10/21~27 경로 439건 기준 개선 17 / 악화 9(그중 익절 2% 놓침 8)
 *             — 꽤 올랐다가 이익을 다 돌려주고 손실로 끝나는 경우를 막는 안전장치</li>
 *         <li>모두 아니면 진입 6시간 후 매도 (6시간보유)</li>
 *       </ol>
 *       근거: 9/30~10/14 백테스트에서 6h-3 건당 +0.96% → +1.14%, 상위 5건 제외 -0.27% → +0.37%.
 *       10/16~19 하락장에서는 6시간 보유 -0.96% → 약 -0.2~-0.7%</li>
 *   <li>조기 매도해도 진입 6시간이 될 때까지 같은 조건·코인으로 다시 사지 않는다(status=WATCH) — 백테스트와 같은 구조.
 *       6시간 시점의 매수호가를 hold6h_* 로 남겨 "6시간 들고 있었다면"과 비교한다</li>
 *   <li>기록은 long_trade 테이블. 10/29 단타 코드 제거 후 계좌 코인을 자동으로 파는 곳은 이 클래스(자기가 산 포지션)뿐이다</li>
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
    private final MarketStateService marketStateService;

    /** 조건: code, 직전 몇 시간 변화율을 볼지, 그 변화율이 이 값 이하이면 매수 */
    public record Strategy(String code, int lookbackHours, BigDecimal dropPct) {
    }

    /** 하락 조건 — 하나라도 맞으면 진입 (10/28~ 코인당 1포지션, 맞은 조건은 전부 기록) */
    public static final List<Strategy> STRATEGIES = List.of(
            new Strategy("6h-3", 6, new BigDecimal("-3")),
            new Strategy("3h-3", 3, new BigDecimal("-3")),
            new Strategy("6h-5", 6, new BigDecimal("-5")));
    /** 6h-3이 맞고 시장도 약세면 matched_conditions에 함께 기록하는 표시 (10/27~) */
    static final String MARKET_TAG = "6h-3M";
    /** 10/28~ 코인당 1포지션 진입의 strategy 값 (이전 조건별 독립 포지션과 구분) */
    static final String STRATEGY_ANY = "ANY";

    /** 6h-3M 시장 약세 조건 ① 감시 목록 6시간 변화율 중앙값(%) 이하 */
    static final BigDecimal MARKET_WEAK_MED_CHG6H = new BigDecimal("-0.43");
    static final String MARKET_WEAK_MED_CHG6H_TEXT = "-0.43";
    /** 6h-3M 시장 약세 조건 ② 최근 이 시간(분) 안에 */
    static final int MARKET_CROWD_MINUTES = 30;
    /** 6h-3M 시장 약세 조건 ② 장타 신호·진입이 있었던 코인이 이 개수 이상 */
    static final int MARKET_CROWD_MIN = 3;

    /** 진입 제외 기간(시간) — 6시간보유 손실(양전 못 함 / 고점 후 하락) 코인 */
    static final int EXCLUDE_HOURS = 24;
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
    /** ③ 지금 그 최고치보다 이 값(%p) 이상 내려오면 즉시 매도 (10/27: +0.3% 이하 → 고점 대비 -0.6%p) */
    static final BigDecimal TRAIL_DROP_PCT = new BigDecimal("0.6");
    static final String TRAIL_REASON = "고점대비-0.6%";
    /** ⓪ 이 수익률(%) 이하면 즉시 매도 — 비상손절 (10/22) */
    static final BigDecimal EMERGENCY_STOP_PCT = new BigDecimal("-7");
    static final String EMERGENCY_STOP_TEXT = "-7";
    static final String EMERGENCY_STOP_REASON = "비상손절" + EMERGENCY_STOP_TEXT + "%";
    /** 이 스프레드(%) 이상이면 진입하지 않음 */
    private static final BigDecimal MAX_SPREAD_PCT = new BigDecimal("0.4");
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0005");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** 슬로우 루프 지표의 호가를 그대로 써도 되는 최대 경과(초) */
    private static final long SIGNAL_FRESH_SECONDS = 240;

    /** 실제 주문 체결 확인 — 최대 시도 횟수와 간격(ms). 시장가라 보통 1초 안에 끝난다 */
    private static final int FILL_CHECK_TRIES = 5;
    private static final long FILL_CHECK_INTERVAL_MS = 1000;

    /** order_mode — VIRTUAL(모의: 주문 없이 기록만) / REAL(실전: 업비트 실계좌 주문) */
    static final String VIRTUAL = "VIRTUAL";
    static final String REAL = "REAL";

    /** 1회 매수 금액(원) — 모의·실전 공통 (모의는 이 금액으로 샀다고 보고 손익 계산). 업비트 최소 주문 5,000원 — -7% 손절 뒤에도 매도가 5,000원 이상이도록 6,000원 이상 권장 */
    @Value("${trading.long-order-amount:6000}")
    private BigDecimal orderAmount;
    /** 동시에 보유하는 코인 수 상한 (OPEN 기준) */
    @Value("${trading.long-max-hold-coins:30}")
    private int maxHoldCoins;

    private static final String OPEN = "OPEN";
    private static final String WATCH = "WATCH";
    private static final String CLOSED = "CLOSED";

    public void run(Map<String, CoinSignalDto> signalMap) {
        LocalDateTime now = LocalDateTime.now();
        // 0. 시장 흐름 기록 (10/23) — checked_at = now 라서 이번에 진입하는 장타의 entry_at과 같다. 실패해도 매매는 그대로
        marketStateService.record(now, signalMap);
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
                if (rate.compareTo(EMERGENCY_STOP_PCT) <= 0) {
                    reason = EMERGENCY_STOP_REASON;
                } else if (rate.compareTo(TAKE_PROFIT_PCT) >= 0) {
                    reason = "익절+" + TAKE_PROFIT_TEXT + "%";
                } else if (p.getMinRate().compareTo(RECOVERY_LOSS_PCT) <= 0 && rate.compareTo(RECOVERY_EXIT_PCT) >= 0) {
                    reason = "손실회복+0.5%";
                } else if (p.getMaxRate().compareTo(TRAIL_PEAK_PCT) >= 0
                        && rate.compareTo(p.getMaxRate().subtract(TRAIL_DROP_PCT)) <= 0) {
                    reason = TRAIL_REASON;
                } else if (heldSec >= HOLD_MINUTES * 60) {
                    reason = "6시간보유";
                }
                if (reason != null && close(p, bid, heldSec, now, reason)) {
                    if (p.getPnlRate() != null && p.getPnlRate().signum() < 0) lossClosed.add(p.getMarket());
                    // 6시간을 채우고 판 건은 바로 재진입 가능, 조기 매도는 WATCH로 6시간까지 재진입 금지
                    if ("6시간보유".equals(reason)) stillOpen.remove(key(p.getStrategy(), p.getMarket()));
                } else {
                    longTradeRepository.save(p); // 청산 사유 없음, 또는 매도 주문 실패 → OPEN 유지, 다음 확인 때 다시
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

        // 2. 신규 진입 — 코인당 1포지션 (10/28~). 하락 조건 중 하나라도 맞으면 1번만 사고, 맞은 조건은 전부 기록
        Set<String> heldCoins = new HashSet<>();
        for (LongTrade p : longTradeRepository.findByStatus(OPEN)) heldCoins.add(p.getMarket());
        // 보유 중이거나 조기 매도 후 진입 6시간이 안 된 코인 — 조건과 상관없이 새로 사지 않음 (이전 조건별 포지션 포함)
        Set<String> blockedCoins = new HashSet<>(heldCoins);
        for (String k : stillOpen) blockedCoins.add(k.substring(k.indexOf('|') + 1));
        Set<String> excluded = new HashSet<>(longTradeRepository.findExcludedMarkets(now.minusHours(EXCLUDE_HOURS)));
        record Candidate(String coin, List<String> matched, BigDecimal chg, CoinSignalDto s) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (String coin : codeRepository.findAllCoinCode()) {
            if (excluded.contains(coin)) continue; // 10/21: ETH/SOL/XRP도 다른 코인과 같은 기준으로 진입
            if (blockedCoins.contains(coin)) continue;
            CoinSignalDto s = signalMap.get(coin);
            if (s == null || s.getPrice() == null) continue;
            if (s.getSpreadPct() == null || s.getSpreadPct().compareTo(MAX_SPREAD_PCT) >= 0) continue;
            List<String> matched = new ArrayList<>();
            BigDecimal deepest = null; // 맞은 조건의 변화율 중 가장 많이 빠진 값 — 정렬·trigger_chg용
            for (Strategy st : STRATEGIES) {
                BigDecimal chg = st.lookbackHours() == 6 ? s.getChg6h() : s.getChg3h();
                if (chg == null || chg.compareTo(st.dropPct()) > 0) continue;
                matched.add(st.code());
                if (deepest == null || chg.compareTo(deepest) < 0) deepest = chg;
            }
            if (!matched.isEmpty()) candidates.add(new Candidate(coin, matched, deepest, s));
        }
        if (candidates.isEmpty()) return;

        // 시장 약세 판단 (10/27~, 기록용) — 최근 30분 진입 코인 + 이번 신호 코인, 감시 목록 6h 중앙값
        Set<String> crowd = new HashSet<>(longTradeRepository.findMarketsEnteredSince(now.minusMinutes(MARKET_CROWD_MINUTES)));
        for (Candidate c : candidates) crowd.add(c.coin());
        BigDecimal medChg6h = MarketStateService.medianChg6h(signalMap);
        boolean marketWeak = (medChg6h != null && medChg6h.compareTo(MARKET_WEAK_MED_CHG6H) <= 0)
                || crowd.size() >= MARKET_CROWD_MIN;
        log.info("[장타] 진입 신호 {}개 — 시장 {} (6h 중앙값:{}% 최근{}분 신호 코인:{}개)", candidates.size(),
                marketWeak ? "약세" : "약세 아님", r(medChg6h, 2), MARKET_CROWD_MINUTES, crowd.size());

        candidates.sort(Comparator.comparing(Candidate::chg));
        int skippedByCap = 0;
        Set<String> inactiveCoins = new HashSet<>();
        BigDecimal krwLeft = null; // 실제 주문 시 남은 KRW (처음 필요할 때 1번 조회)
        BigDecimal need = orderAmount.multiply(BigDecimal.ONE.add(FEE_RATE));
        for (Candidate c : candidates) {
            if (heldCoins.size() >= maxHoldCoins) {
                skippedByCap++;
                continue;
            }
            // 신규 진입 직전 — 거래 활발 1번 확인, 미달이면 진입 안 하고 감시 목록에서 제외
            if (inactiveCoins.contains(c.coin())) continue;
            String inactive = coinListService.checkInactive(c.coin());
            if (inactive != null) {
                inactiveCoins.add(c.coin());
                coinListService.removeFromList(c.coin(), "장타 진입 직전 " + inactive);
                continue;
            }
            List<String> matched = new ArrayList<>(c.matched());
            if (marketWeak && matched.contains("6h-3")) matched.add(MARKET_TAG);
            // 실전(paper-mode=false)만 실제 주문 — 잔고 확인 후 시장가 매수. 모의는 주문 없이 기록만
            Fill fill = null;
            if (!exchangeClient.isPaperMode()) {
                try {
                    if (krwLeft == null) krwLeft = exchangeClient.availableKrwBalance();
                    if (krwLeft.compareTo(need) < 0) {
                        log.warn("{} [장타] 매수 건너뜀 — KRW 잔고 부족(가용:{}원 필요:{}원)",
                                c.coin(), krwLeft.setScale(0, RoundingMode.DOWN), need.setScale(0, RoundingMode.UP));
                        continue;
                    }
                    fill = buyOrder(c.coin(), orderAmount);
                    krwLeft = krwLeft.subtract(need);
                } catch (Exception e) {
                    log.warn("{} [장타] 매수 주문 실패 — 진입 안 함: {}", c.coin(), e.getMessage());
                    continue;
                }
            }
            try {
                open(c.coin(), matched, marketWeak, c.chg(), c.s(), now, fill);
                heldCoins.add(c.coin());
            } catch (Exception e) {
                if (fill != null) {
                    log.error("{} [장타] 매수 체결됐는데 기록 실패 — 계좌 확인 필요 (주문:{} 수량:{}): {}", c.coin(),
                            fill.uuid(), fill.volume().toPlainString(), e.getMessage());
                } else {
                    log.warn("{} [장타 {}] 진입 기록 실패: {}", c.coin(), String.join(",", matched), e.getMessage());
                }
            }
        }
        if (skippedByCap > 0) {
            log.info("[장타] 동시 보유 코인 {}개 상한으로 진입 {}건 보류", maxHoldCoins, skippedByCap);
        }
    }

    private void open(String coin, List<String> matched, boolean marketWeak, BigDecimal chg, CoinSignalDto s,
                      LocalDateTime now, Fill fill) {
        // 실제 주문이면 체결 평균가, 아니면 그 순간 매도호가에 체결됐다고 가정
        BigDecimal ask = fill != null ? fill.price() : s.getPrice().getAskPrice();
        String conditions = String.join(",", matched);
        LongTrade saved = longTradeRepository.save(LongTrade.builder()
                .strategy(STRATEGY_ANY)
                .matchedConditions(conditions)
                .matchCount(matched.size())
                .marketWeak(marketWeak)
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
                .orderAmount(orderAmount)
                .orderMode(fill != null ? currentOrderMode() : VIRTUAL)
                .volume(fill != null ? fill.volume() : null)
                .buyUuid(fill != null ? fill.uuid() : null)
                .updatedAt(now)
                .build());
        BigDecimal bid = s.getPrice().getBidPrice();
        BigDecimal rate0 = pct(ask, bid);
        saveTick(saved, now, 0, "HOLD", bid, rate0, rate0, rate0, s);
        log.info("{} [장타 매수 {}{}{}] 체결가:{} 6h:{}% 3h:{}%", coin, conditions, marketWeak ? " 시장약세" : "",
                fill != null ? " " + saved.getOrderMode() + "주문" : "",
                ask.stripTrailingZeros().toPlainString(), r(s.getChg6h(), 2), r(s.getChg3h(), 2));
    }

    /**
     * 청산 기록 (+ 그 포지션을 실제 주문으로 샀으면 매도 주문).
     *
     * @return true면 청산 완료(WATCH 또는 CLOSED로 저장), false면 매도 주문 실패로 OPEN 유지
     */
    private boolean close(LongTrade p, BigDecimal bid, long heldSec, LocalDateTime now, String reason) {
        Fill sell = null;
        String mode = p.getOrderMode();
        if (mode != null && !VIRTUAL.equals(mode)) {
            if (!mode.equals(currentOrderMode())) {
                log.error("{} [장타] {}로 산 포지션인데 지금은 {} 모드 — 매도 주문 없이 기록만 청산 (계좌 확인 필요)",
                        p.getMarket(), mode, currentOrderMode());
            } else {
                try {
                    BigDecimal vol = sellableVolume(p);
                    if (vol.signum() <= 0) {
                        log.error("{} [장타] 계좌에 보유 수량이 없어 매도 주문 없이 기록만 청산 (기록 수량:{})",
                                p.getMarket(), p.getVolume());
                    } else {
                        sell = sellOrder(p.getMarket(), vol);
                    }
                } catch (Exception e) {
                    log.error("{} [장타] 매도 주문 실패 ({}) — OPEN 유지, 다음 확인 때 다시 시도: {}",
                            p.getMarket(), reason, e.getMessage());
                    return false;
                }
            }
        }

        BigDecimal cost;
        BigDecimal proceeds;
        BigDecimal exitPrice;
        if (sell != null) {
            // 실제 체결 기준 — 산 금액(체결가×수량 + 수수료) vs 판 금액(체결가×수량 - 수수료)
            cost = p.getEntryPrice().multiply(p.getVolume()).multiply(BigDecimal.ONE.add(FEE_RATE));
            proceeds = sell.price().multiply(sell.volume()).multiply(BigDecimal.ONE.subtract(FEE_RATE));
            exitPrice = sell.price();
            p.setSellUuid(sell.uuid());
        } else {
            // 가상 — 주문금액만큼 진입가에 샀다고 보고 지금 매수호가에 판 결과
            BigDecimal amount = p.getOrderAmount() != null ? p.getOrderAmount() : orderAmount;
            cost = amount.multiply(BigDecimal.ONE.add(FEE_RATE));
            BigDecimal qty = amount.divide(p.getEntryPrice(), 12, RoundingMode.HALF_UP);
            proceeds = qty.multiply(bid).multiply(BigDecimal.ONE.subtract(FEE_RATE));
            exitPrice = bid;
        }
        BigDecimal pnl = proceeds.subtract(cost);
        boolean held6h = "6시간보유".equals(reason);
        p.setStatus(held6h ? CLOSED : WATCH);
        p.setExitAt(now);
        p.setExitPrice(exitPrice);
        p.setExitReason(reason);
        p.setHoldMinutes(BigDecimal.valueOf(heldSec).divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP));
        p.setRealizedPnl(pnl.setScale(0, RoundingMode.HALF_UP));
        p.setPnlRate(pnl.divide(cost, 10, RoundingMode.HALF_UP).multiply(HUNDRED).setScale(3, RoundingMode.HALF_UP));
        if (held6h) { // 6시간 보유로 판 건은 그림자 = 실제
            p.setHold6hAt(now);
            p.setHold6hPrice(exitPrice);
            p.setHold6hPnlRate(p.getPnlRate());
        }
        longTradeRepository.save(p);
        log.info("{} [장타 매도 {} {}{}] 체결가:{} 손익:{}원({}%)", p.getMarket(), p.getStrategy(), reason,
                sell != null ? " " + mode + "주문" : "",
                exitPrice.stripTrailingZeros().toPlainString(), p.getRealizedPnl(), p.getPnlRate());
        return true;
    }

    // ══════════════════════════════════════════════════════════════════
    //  실제 주문 (10/29) — 실전 모드(trading.paper-mode=false)일 때만 호출된다
    // ══════════════════════════════════════════════════════════════════

    /** 주문 체결 결과 — 평균 체결가, 체결 수량, 주문 uuid */
    private record Fill(String uuid, BigDecimal price, BigDecimal volume) {
    }

    private String currentOrderMode() {
        return exchangeClient.isPaperMode() ? VIRTUAL : REAL;
    }

    /** 원화 금액 시장가 매수 → 체결 확인 */
    private Fill buyOrder(String market, BigDecimal krwAmount) throws InterruptedException {
        OrdersResponse res = exchangeClient.orderCoin(market, "bid", krwAmount.toPlainString());
        Fill fill = awaitFill(res.getUuid());
        log.info("{} [장타] {} 매수 체결 — 금액:{}원 체결가:{} 수량:{}", market, currentOrderMode(),
                krwAmount.toPlainString(), fill.price().stripTrailingZeros().toPlainString(), fill.volume().toPlainString());
        return fill;
    }

    /** 수량 시장가 매도 → 체결 확인 */
    private Fill sellOrder(String market, BigDecimal volume) throws InterruptedException {
        OrdersResponse res = exchangeClient.orderCoin(market, "ask", volume.stripTrailingZeros().toPlainString());
        return awaitFill(res.getUuid());
    }

    /**
     * 주문 조회로 체결 확인. 시장가 매수는 남은 금액이 취소되며 끝나(state=cancel) 체결 수량이 있으면 완료로 본다.
     * 체결 수량이 끝내 0이면 예외.
     */
    private Fill awaitFill(String uuid) throws InterruptedException {
        if (uuid == null) throw new IllegalStateException("주문 응답에 uuid 없음");
        OrderResponse last = null;
        for (int i = 0; i < FILL_CHECK_TRIES; i++) {
            Thread.sleep(FILL_CHECK_INTERVAL_MS);
            last = exchangeClient.checkCoin(uuid);
            BigDecimal vol = last == null || last.getExecutedVolume() == null ? BigDecimal.ZERO : new BigDecimal(last.getExecutedVolume());
            if (vol.signum() > 0 && !"wait".equals(last.getState())) {
                return new Fill(uuid, avgFillPrice(last, vol), vol);
            }
        }
        throw new IllegalStateException("체결 확인 실패 (주문:" + uuid + " 상태:" + (last == null ? "조회 없음" : last.getState()) + ")");
    }

    /** 체결 내역의 가중평균 체결가 = Σ(체결가×수량) / Σ수량 */
    private static BigDecimal avgFillPrice(OrderResponse o, BigDecimal executedVol) {
        if (o.getTrades() == null || o.getTrades().isEmpty()) {
            throw new IllegalStateException("체결 내역(trades) 없음 — 주문:" + o.getUuid());
        }
        BigDecimal funds = BigDecimal.ZERO;
        BigDecimal vol = BigDecimal.ZERO;
        for (OrderResponse.Traders t : o.getTrades()) {
            BigDecimal v = new BigDecimal(t.getVolume());
            funds = funds.add(new BigDecimal(t.getPrice()).multiply(v));
            vol = vol.add(v);
        }
        if (vol.signum() == 0) vol = executedVol;
        return funds.divide(vol, 10, RoundingMode.HALF_UP);
    }

    /** 이 포지션이 산 수량과 계좌 잔고 중 작은 쪽 (수동 매도 등으로 잔고가 줄었을 수 있음) */
    private BigDecimal sellableVolume(LongTrade p) {
        String coin = p.getMarket().replace("KRW-", "");
        BigDecimal balance = BigDecimal.ZERO;
        for (CoinAccount a : exchangeClient.checkCoinAccount()) {
            if (coin.equals(a.getCoinName()) && "KRW".equals(a.getCoinType()) && a.getBalance() != null) {
                balance = a.getBalance();
            }
        }
        BigDecimal mine = p.getVolume() == null ? BigDecimal.ZERO : p.getVolume();
        return mine.min(balance);
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
