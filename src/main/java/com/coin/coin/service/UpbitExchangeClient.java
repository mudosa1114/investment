package com.coin.coin.service;

import com.coin.coin.config.UpbitJwtGenerator;
import com.coin.coin.dto.CoinAccount;
import com.coin.coin.dto.CoinPrice;
import com.coin.coin.dto.TradeRequest;
import com.coin.coin.dto.UriBuilderDto;
import com.coin.coin.dto.response.AccountResponse;
import com.coin.coin.dto.response.CandleResponse;
import com.coin.coin.dto.response.OrderBookResponse;
import com.coin.coin.dto.response.OrderResponse;
import com.coin.coin.dto.response.OrdersResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.coin.coin.dto.CoinPrice.latestCoinPrice;

/**
 * Upbit REST API 순수 클라이언트 — 캔들/현재가/계좌/주문 조회 및 주문 실행만 담당한다.
 * 매매 판단(지표 해석, 진입/청산 로직)은 여기서 하지 않는다 (UpbitApi 역할분리, 9/4).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class UpbitExchangeClient {

    private final RestTemplate restTemplate;
    private final UriBuilderDto coinUriBuilder;
    private final UpbitJwtGenerator jwtGenerator;
    private final PaperTradingService paperTradingService;

    /**
     * 모의매매 모드 (10/6 추가). true면 계좌 조회·주문·주문조회를 PaperTradingService가 대신 처리하고
     * 업비트 실제 주문 API는 호출하지 않는다. 시세(캔들·호가) 조회는 그대로 실제 API를 쓴다.
     * 설정이 없으면 기본값 true — 실거래는 trading.paper-mode=false 를 명시해야만 동작한다.
     */
    @Value("${trading.paper-mode:true}")
    private boolean paperMode;

    public boolean isPaperMode() {
        return paperMode;
    }

    /** 모의매매 지정가 가정 주문 체결 확인 — 패스트 루프에서 호출 */
    public void checkPaperLimitOrders() {
        if (paperMode) {
            paperTradingService.checkPendingLimits(this::checkCoinPrice);
        }
    }

    public List<CandleResponse> candleResponses(String market, int unit, int period) {
        CandleResponse[] candles = restTemplate.getForObject(
                coinUriBuilder.upbitCandles(market, unit, period),
                CandleResponse[].class
        );
        return ObjectUtils.isEmpty(candles) ? null : Arrays.asList(candles);
    }

    public boolean isInvalid(List<CandleResponse> candles, int minSize) {
        return candles == null || candles.size() < minSize;
    }

    public CoinPrice checkCoinPrice(String market) {
        OrderBookResponse[] res = restTemplate.getForObject(
                coinUriBuilder.upbitOrderBook(market), OrderBookResponse[].class);
        return latestCoinPrice(Optional.ofNullable(res)
                .map(Arrays::asList).orElse(Collections.emptyList()));
    }

    public Map<String, BigDecimal> orderPrice(String market) {
        OrderBookResponse[] res = restTemplate.getForObject(
                coinUriBuilder.upbitOrderBook(market), OrderBookResponse[].class);
        List<OrderBookResponse> list = Optional.ofNullable(res)
                .map(Arrays::asList).orElse(Collections.emptyList());
        return Map.of(
                "askPrice", list.get(0).getOrderBookUnits().get(0).getAskPrice(),
                "bidPrice", list.get(0).getOrderBookUnits().get(0).getBidPrice()
        );
    }

    /**
     * 오더북 매수 잔량 비율 (관찰용 신규 지표, 9/18) — 매매 판단에는 사용하지 않는다.
     * Upbit 오더북 응답의 호가단위 전체(상위 15호가)에 대해 매수잔량합/(매수잔량합+매도잔량합)을
     * 계산한다. 0.5 초과면 매수 잔량 우위(수요 우위), 미만이면 매도 잔량 우위(공급 우위).
     * CoinSignalService의 지표스냅샷 로그 전용 — 기존 checkCoinPrice/orderPrice 호출과는
     * 별개의 오더북 조회를 1회 추가한다(기존 매매 판단 경로에는 영향 없음).
     */
    public BigDecimal orderBookImbalance(String market) {
        return bidRatio(orderBook(market));
    }

    /** 오더북 원본 조회 (10/7) — 호가(CoinPrice)와 매수잔량비율을 한 번의 호출로 같이 계산하기 위함 */
    public List<OrderBookResponse> orderBook(String market) {
        OrderBookResponse[] res = restTemplate.getForObject(
                coinUriBuilder.upbitOrderBook(market), OrderBookResponse[].class);
        return Optional.ofNullable(res).map(Arrays::asList).orElse(Collections.emptyList());
    }

    /** 오더북 상위 호가 전체의 매수잔량 / (매수잔량 + 매도잔량) */
    public static BigDecimal bidRatio(List<OrderBookResponse> list) {
        if (list == null || list.isEmpty() || ObjectUtils.isEmpty(list.get(0).getOrderBookUnits())) {
            return BigDecimal.valueOf(0.5);
        }
        BigDecimal totalBid = BigDecimal.ZERO;
        BigDecimal totalAsk = BigDecimal.ZERO;
        for (OrderBookResponse.OrderBookUnits unit : list.get(0).getOrderBookUnits()) {
            if (unit.getBidSize() != null) totalBid = totalBid.add(unit.getBidSize());
            if (unit.getAskSize() != null) totalAsk = totalAsk.add(unit.getAskSize());
        }
        BigDecimal total = totalBid.add(totalAsk);
        if (total.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.valueOf(0.5);
        }
        return totalBid.divide(total, 6, java.math.RoundingMode.HALF_UP);
    }

    public List<CoinAccount> checkCoinAccount() {
        if (paperMode) {
            return paperTradingService.accounts();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwtGenerator.upbitJwtToken());
        headers.set("accept", "application/json");

        ResponseEntity<AccountResponse[]> res = restTemplate.exchange(
                coinUriBuilder.upbitAccount(), HttpMethod.GET,
                new HttpEntity<>(headers), AccountResponse[].class);

        return Optional.ofNullable(res.getBody())
                .map(Arrays::asList).orElse(Collections.emptyList())
                .stream().map(CoinAccount::coinAccount).toList();
    }

    /**
     * 매수 가능한 KRW 현금 잔고 조회 (9/22 추가 — insufficient_funds_bid 방지용).
     * checkCoinAccount()가 반환하는 계좌 목록에서 KRW 현금 항목(coinType=coinName="KRW")만 추출.
     */
    public BigDecimal availableKrwBalance() {
        return checkCoinAccount().stream()
                .filter(a -> "KRW".equals(a.getCoinType()) && "KRW".equals(a.getCoinName()))
                .map(CoinAccount::getBalance)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    public OrderResponse checkCoin(String uuid) {
        if (paperMode) {
            return paperTradingService.order(uuid);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " +
                jwtGenerator.upbitJwtTokenWithQuery("uuid=" + uuid));
        headers.set("accept", "application/json");

        return restTemplate.exchange(
                coinUriBuilder.upbitOrder(uuid), HttpMethod.GET,
                new HttpEntity<>(headers), OrderResponse.class).getBody();
    }

    public OrdersResponse orderCoin(String market, String side, String value) {
        if (paperMode) {
            CoinPrice price = checkCoinPrice(market);
            return side.equals("bid")
                    ? paperTradingService.marketBuy(market, new BigDecimal(value), price)
                    : paperTradingService.marketSell(market, new BigDecimal(value), price);
        }
        try {
            TradeRequest req = (side.equals("bid"))
                    ? TradeRequest.builder().market(market).side(side)
                    .price(value).ordType("price").build()
                    : TradeRequest.builder().market(market).side(side)
                    .volume(value).ordType("market").build();

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + jwtGenerator.upbitOrderToken(req));
            headers.set("accept", "application/json");

            return restTemplate.exchange(
                    coinUriBuilder.upbitOrder(), HttpMethod.POST,
                    new HttpEntity<>(req, headers), OrdersResponse.class).getBody();

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    public void askSuccessMessage(OrdersResponse response) {
        log.info("{} 코인 최초 구매 성공", response.getMarket());
    }
}
