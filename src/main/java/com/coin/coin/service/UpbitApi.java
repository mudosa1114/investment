package com.coin.coin.service;

import com.coin.coin.dto.CoinPrice;
import com.coin.coin.dto.UriBuilderDto;
import com.coin.coin.dto.OrderBookResponse;
import com.coin.coin.entity.Trade;
import com.coin.coin.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static com.coin.coin.dto.CoinPrice.latestCoinPrice;

@Service
@Slf4j
@RequiredArgsConstructor
public class UpbitApi {

    private final TradeRepository tradeRepository;
    private final UriBuilderDto coinUriBuilder;

    public String tradeCoin() {
        BigDecimal transactionFee = new BigDecimal(("0.0005"));

        Trade krwSol = tradeRepository.findByMarket("KRW-SOL");
        if (ObjectUtils.isEmpty(krwSol)) {
            // sol 코인 구매로직
            return "purchase success";
        }

        BigDecimal orderPrice = checkCoinPrice("KRW-SOL").getAskPrice();
        BigDecimal sellPrice = checkCoinPrice("KRW-SOL").getBidPrice();

        int purchasePrice = krwSol.getPrice();
        int expSellPrice = sellPrice.multiply(krwSol.getVolume()).multiply(BigDecimal.ONE.subtract(transactionFee)).intValue();

        if (purchasePrice < expSellPrice) {
            // sol 코인 판매로직
            return "sell success";
        }

        return "hold purchase";
    }

    public CoinPrice checkCoinPrice(String market) {
        OrderBookResponse[] responses = new RestTemplate().getForObject(coinUriBuilder.upbitUri(market), OrderBookResponse[].class);

        List<OrderBookResponse> solCoinList = Optional.ofNullable(responses)
                .map(Arrays::asList)
                .orElse(Collections.emptyList());

        return latestCoinPrice(solCoinList);
    }
}
