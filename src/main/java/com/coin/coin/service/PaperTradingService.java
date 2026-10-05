package com.coin.coin.service;

import com.coin.coin.dto.CoinAccount;
import com.coin.coin.dto.CoinPrice;
import com.coin.coin.dto.response.OrderResponse;
import com.coin.coin.dto.response.OrdersResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 모의매매 계좌 (10/6 추가).
 *
 * <p>trading.paper-mode=true 이면 UpbitExchangeClient가 실제 주문/계좌 API 대신 이 클래스를 쓴다.
 * 시세(캔들·호가)는 실제 업비트 데이터를 그대로 쓰고, 체결만 가상으로 처리한다.
 * <ul>
 *   <li>시장가 매수: 그 순간 최우선 매도호가에 전량 체결, 수수료 0.05%는 KRW에서 별도 차감(업비트와 동일)</li>
 *   <li>시장가 매도: 최우선 매수호가에 전량 체결, 수수료 0.05% 차감 후 입금</li>
 *   <li>평균매수가: 수수료 제외 체결가 가중평균(업비트 avg_buy_price와 같은 방식)</li>
 * </ul>
 * 상태는 trading.paper-state-file(JSON)에 매 체결마다 저장해 재시작해도 이어진다.
 *
 * <p>지정가 비교: 시장가 매수가 체결될 때마다 "같은 순간 최우선 매수호가에 지정가를 걸었다면"을
 * 함께 등록하고, 패스트 루프(30초)마다 체결 여부를 확인해 "[지정가가정]" 로그를 남긴다.
 * 매수호가보다 낮게 거래되거나(매수호가 &lt; 지정가) 매도호가가 지정가까지 내려오면 체결로 본다.
 * 실제 포지션은 시장가 기준으로만 운영되고, 지정가 쪽은 기록만 한다.
 */
@Component
@Slf4j
public class PaperTradingService {

    private static final BigDecimal FEE_RATE = new BigDecimal("0.0005");
    private static final BigDecimal MIN_ORDER_KRW = new BigDecimal("5000");
    /** 지정가 가정 주문의 최대 대기 시간 — 이후 미체결 만료로 기록 */
    private static final long LIMIT_TIMEOUT_SECONDS = 600;

    @Value("${trading.paper-initial-krw:200000}")
    private BigDecimal initialKrw;
    @Value("${trading.paper-state-file:./paper-account.json}")
    private String stateFile;

    private final ObjectMapper mapper = new ObjectMapper();

    private BigDecimal krw = BigDecimal.ZERO;
    /** 코인(예: "ETH") → [보유수량, 평균매수가] */
    private final Map<String, BigDecimal[]> holdings = new LinkedHashMap<>();
    /** 가상 주문 uuid → 체결 결과 (checkCoin 응답용) */
    private final Map<String, OrderResponse> fills = new ConcurrentHashMap<>();
    /** 지정가 가정 주문 (미체결 대기) */
    private final List<LimitShadow> pendingLimits = new ArrayList<>();

    private record LimitShadow(String market, BigDecimal limitPrice, BigDecimal marketPrice, LocalDateTime createdAt) {}

    @PostConstruct
    public synchronized void load() {
        File f = new File(stateFile);
        if (f.exists()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = mapper.readValue(f, Map.class);
                krw = new BigDecimal(String.valueOf(m.get("krw")));
                @SuppressWarnings("unchecked")
                Map<String, Map<String, Object>> h = (Map<String, Map<String, Object>>) m.get("holdings");
                if (h != null) {
                    h.forEach((coin, v) -> holdings.put(coin, new BigDecimal[]{
                            new BigDecimal(String.valueOf(v.get("balance"))),
                            new BigDecimal(String.valueOf(v.get("avgBuyPrice")))}));
                }
                log.info("[모의매매] 계좌 복원 — KRW:{} 보유코인:{}개 ({})", krw.setScale(0, RoundingMode.DOWN), holdings.size(), f.getAbsolutePath());
                return;
            } catch (Exception e) {
                log.error("[모의매매] 계좌 파일 읽기 실패 — 초기 금액으로 새로 시작: {}", e.getMessage());
            }
        }
        krw = initialKrw;
        holdings.clear();
        save();
        log.info("[모의매매] 새 계좌 시작 — KRW:{} ({})", krw, f.getAbsolutePath());
    }

    private void save() {
        try {
            Map<String, Object> h = new LinkedHashMap<>();
            holdings.forEach((coin, v) -> {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("balance", v[0].toPlainString());
                e.put("avgBuyPrice", v[1].toPlainString());
                h.put(coin, e);
            });
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("krw", krw.toPlainString());
            m.put("holdings", h);
            m.put("savedAt", LocalDateTime.now().toString());
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(stateFile), m);
        } catch (Exception e) {
            log.error("[모의매매] 계좌 파일 저장 실패: {}", e.getMessage());
        }
    }

    /** 업비트 계좌 조회 응답과 같은 형태 (KRW 현금 + 보유 코인) */
    public synchronized List<CoinAccount> accounts() {
        List<CoinAccount> list = new ArrayList<>();
        list.add(CoinAccount.builder().coinName("KRW").coinType("KRW").balance(krw).avgBuyPrice(BigDecimal.ZERO).build());
        holdings.forEach((coin, v) -> list.add(CoinAccount.builder()
                .coinName(coin).coinType("KRW").balance(v[0]).avgBuyPrice(v[1]).build()));
        return list;
    }

    /** 시장가 매수 (원화 금액 지정) — price: 그 순간 실제 호가 */
    public synchronized OrdersResponse marketBuy(String market, BigDecimal krwAmount, CoinPrice price) {
        BigDecimal ask = price.getAskPrice();
        BigDecimal fee = krwAmount.multiply(FEE_RATE);
        if (krwAmount.compareTo(MIN_ORDER_KRW) < 0) {
            throw new IllegalStateException("[모의] 최소주문금액(5,000원) 미만: " + krwAmount);
        }
        if (krw.compareTo(krwAmount.add(fee)) < 0) {
            throw new IllegalStateException("[모의] 주문 가능한 금액(KRW)이 부족합니다. 가용:" + krw.setScale(0, RoundingMode.DOWN));
        }
        BigDecimal volume = krwAmount.divide(ask, 8, RoundingMode.DOWN);
        String coin = market.replace("KRW-", "");
        BigDecimal[] cur = holdings.getOrDefault(coin, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        BigDecimal newVol = cur[0].add(volume);
        BigDecimal newAvg = cur[0].multiply(cur[1]).add(volume.multiply(ask)).divide(newVol, 8, RoundingMode.HALF_UP);
        holdings.put(coin, new BigDecimal[]{newVol, newAvg});
        krw = krw.subtract(krwAmount).subtract(fee);
        String uuid = "paper-" + UUID.randomUUID();   // 매수 체결은 이후 조회하지 않으므로 fills에 저장하지 않음
        save();

        BigDecimal spreadPct = ask.subtract(price.getBidPrice()).divide(price.getBidPrice(), 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        log.info("[모의매매] 매수 체결 {} 금액:{}원 체결가(매도호가):{} 수량:{} 수수료:{} 잔여KRW:{}",
                market, krwAmount, ask.stripTrailingZeros().toPlainString(), volume.toPlainString(),
                fee.setScale(1, RoundingMode.HALF_UP), krw.setScale(0, RoundingMode.DOWN));
        pendingLimits.add(new LimitShadow(market, price.getBidPrice(), ask, LocalDateTime.now()));
        log.info("[지정가가정] 등록 {} 지정가(매수호가):{} 시장가(매도호가):{} 스프레드:{}%",
                market, price.getBidPrice().stripTrailingZeros().toPlainString(),
                ask.stripTrailingZeros().toPlainString(), spreadPct.setScale(3, RoundingMode.HALF_UP));
        return OrdersResponse.builder().market(market).uuid(uuid).side("bid").ordType("price")
                .price(krwAmount.toPlainString()).state("done").build();
    }

    /** 시장가 매도 (수량 지정) — price: 그 순간 실제 호가 */
    public synchronized OrdersResponse marketSell(String market, BigDecimal volume, CoinPrice price) {
        String coin = market.replace("KRW-", "");
        BigDecimal[] cur = holdings.get(coin);
        if (cur == null || cur[0].compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("[모의] 매도할 보유수량 없음: " + market);
        }
        BigDecimal vol = volume.min(cur[0]);
        BigDecimal bid = price.getBidPrice();
        BigDecimal proceeds = vol.multiply(bid);
        if (proceeds.compareTo(MIN_ORDER_KRW) < 0) {
            throw new IllegalStateException("[모의] 최소주문금액(5,000원) 미만 매도: " + proceeds.setScale(0, RoundingMode.DOWN));
        }
        BigDecimal fee = proceeds.multiply(FEE_RATE);
        krw = krw.add(proceeds).subtract(fee);
        BigDecimal remain = cur[0].subtract(vol);
        if (remain.compareTo(new BigDecimal("0.00000001")) <= 0) holdings.remove(coin);
        else holdings.put(coin, new BigDecimal[]{remain, cur[1]});
        String uuid = "paper-" + UUID.randomUUID();
        fills.put(uuid, fill(market, uuid, "ask", bid, vol, fee));
        save();
        log.info("[모의매매] 매도 체결 {} 수량:{} 체결가(매수호가):{} 금액:{}원 수수료:{} 잔여KRW:{}",
                market, vol.toPlainString(), bid.stripTrailingZeros().toPlainString(),
                proceeds.setScale(0, RoundingMode.HALF_UP), fee.setScale(1, RoundingMode.HALF_UP), krw.setScale(0, RoundingMode.DOWN));
        return OrdersResponse.builder().market(market).uuid(uuid).side("ask").ordType("market")
                .volume(vol.toPlainString()).state("done").build();
    }

    /** 가상 주문 조회 (업비트 주문 조회 응답과 같은 형태) */
    public OrderResponse order(String uuid) {
        return fills.remove(uuid);
    }

    private OrderResponse fill(String market, String uuid, String side, BigDecimal price, BigDecimal volume, BigDecimal fee) {
        OrderResponse.Traders t = new OrderResponse.Traders(market, uuid, price.toPlainString(), volume.toPlainString(),
                price.multiply(volume).toPlainString(), null, LocalDateTime.now().toString(), side);
        return OrderResponse.builder().market(market).uuid(uuid).side(side).state("done")
                .executedVolume(volume.toPlainString()).remainingVolume("0").paidFee(fee.toPlainString())
                .tradesCount("1").trades(List.of(t)).build();
    }

    /**
     * 지정가 가정 주문 체결 확인 — 패스트 루프(30초)에서 호출. priceFn: 실제 호가 조회.
     * 체결 조건: 현재 최우선 매수호가 &lt; 지정가(그 가격대를 뚫고 내려감) 또는 최우선 매도호가 ≤ 지정가.
     */
    public void checkPendingLimits(Function<String, CoinPrice> priceFn) {
        List<LimitShadow> snapshot;
        synchronized (this) {
            if (pendingLimits.isEmpty()) return;
            snapshot = new ArrayList<>(pendingLimits);
        }
        LocalDateTime now = LocalDateTime.now();
        for (LimitShadow s : snapshot) {
            long elapsed = Duration.between(s.createdAt(), now).getSeconds();
            String result = null;
            try {
                CoinPrice p = priceFn.apply(s.market());
                if (p.getBidPrice().compareTo(s.limitPrice()) < 0 || p.getAskPrice().compareTo(s.limitPrice()) <= 0) {
                    BigDecimal saved = s.marketPrice().subtract(s.limitPrice())
                            .divide(s.marketPrice(), 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
                    log.info("[지정가가정] 체결 {} 지정가:{} 시장가였다면:{} 절감:{}% 경과:{}초",
                            s.market(), s.limitPrice().stripTrailingZeros().toPlainString(),
                            s.marketPrice().stripTrailingZeros().toPlainString(), saved.setScale(3, RoundingMode.HALF_UP), elapsed);
                    result = "filled";
                }
            } catch (Exception e) {
                log.warn("[지정가가정] {} 호가 조회 실패: {}", s.market(), e.getMessage());
            }
            if (result == null && elapsed >= LIMIT_TIMEOUT_SECONDS) {
                log.info("[지정가가정] 미체결 만료 {} 지정가:{} 경과:{}초",
                        s.market(), s.limitPrice().stripTrailingZeros().toPlainString(), elapsed);
                result = "expired";
            }
            if (result != null) {
                synchronized (this) { pendingLimits.remove(s); }
            }
        }
    }
}
