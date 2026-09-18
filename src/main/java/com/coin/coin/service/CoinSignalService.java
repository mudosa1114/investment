package com.coin.coin.service;

import com.coin.coin.common.MarketPhase;
import com.coin.coin.dto.CoinAccount;
import com.coin.coin.dto.CoinPrice;
import com.coin.coin.dto.CoinSignalDto;
import com.coin.coin.dto.response.CandleResponse;
import com.coin.coin.dto.response.OrdersResponse;
import com.coin.coin.entity.LastTrade;
import com.coin.coin.repository.CoinCodeRepository;
import com.coin.coin.repository.LastTradeRepository;
import com.coin.coin.repository.TradeHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

import static com.coin.coin.dto.TradeHistoryDto.buyHistory;

/**
 * 코인 지표 빌드 + 최초 매수(진입) 판단 — 필터 파이프라인과 확신도 기반 포지션 사이징을
 * 담당한다 (UpbitApi 역할분리, 9/4). 청산 판단은 {@link PositionExitService}가 담당.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoinSignalService {

    private final CoinCodeRepository codeRepository;
    private final LastTradeRepository lastTradeRepository;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final UpbitExchangeClient exchangeClient;
    private final TechnicalIndicatorService indicatorService;
    private final TradingStateStore stateStore;

    // ─── 매수 설정 ────────────────────────────────────────────────────
    private static final String MIN_ORDER_AMOUNT = "10000";              // 최초 매수 금액 (KRW) — 기본(중간 확신도) 금액
    /**
     * 확신도 기반 매수 금액 차등 (8/31 도입).
     * 8/27-30 로그(126건) 분석 결과, 진입 필터를 하나 더 좁히면 거래빈도가 다시 줄어드는데
     * (사용자 요청: 거래빈도는 절대 줄이면 안 됨) 그렇다고 지금처럼 모든 셋업에 동일 금액(1만원)을
     * 베팅하면 승률이 낮은 셋업의 손실이 그대로 계좌에 반영됨. 그래서 "거래는 다 하되(데이터 수집 유지),
     * 베팅 금액을 셋업 확신도에 따라 차등"하는 방식으로 절충함 — 거래 건수는 그대로 두고 자본 배분만 조정.
     * 데이터 근거(8/27-30, n=126):
     * RSI 40~45 진입: 33.3% 승률(15건) — 다른 구간(13~17%)보다 뚜렷이 높음 → 고확신
     * 장기 BULL 진입: 22.2% 승률(27건) — SIDEWAYS(15.2%, 99건)보다 높음 → 고확신
     * 단기BULL+장기SIDEWAYS 조합: 0.0% 승률(10건, 전량 손실) → 저확신(최소 배팅)
     * 단기SIDEWAYS+장기SIDEWAYS + RSI 55이상: 표본상 가장 방향성 없는 "타임아웃" 조합 → 저확신
     * <p>
     * [9/4 긴급 수정] ORDER_AMOUNT_MICRO_CONVICTION을 5000원(Upbit 시장가 매도 최소금액과 동일)으로
     * 설정했더니 8/31~9/3 실제 운영에서 심각한 버그 발생: 5000원어치로 산 코인이 -1.x%만 하락해도
     * "평가금액(수량×현재가) < 5000원"이 되어 Upbit가 전체 시장가 매도 주문 자체를 거부함
     * (400 Bad Request, error:"under_min_total_market_ask"). 강제손절/시간강제매도/트레일링 등
     * 모든 매도 경로가 이 방식(전량 시장가 매도)을 쓰므로, 한 번 이 상태에 빠지면 가격이 최초
     * 매수가 부근으로 회복할 때까지 매도 자체가 불가능 — 그동안 30초 주기 fastPriceCheck가
     * 계속 실패하며 ERROR 로그를 무한 반복 생성함(9/3 하루에만 1,121회 발생, KRW-INJ 포지션이
     * 02:37~09:55(7시간 이상) 동안 매도 불가 상태로 묶여 코인 슬롯 하나를 그동안 낭비함).
     * → 저확신(단기BULL+장기SIDE) 등급을 ORDER_AMOUNT_LOW_CONVICTION(7000원)과 통합.
     * 7000원은 -1.2% 하드스탑을 맞아도 평가금액이 약 6,916원으로 5,000원 최소금액에서
     * 충분한 여유(약 38%)가 있어 동일한 문제가 재현되지 않음.
     */
    private static final String ORDER_AMOUNT_HIGH_CONVICTION = "13000";
    private static final String ORDER_AMOUNT_LOW_CONVICTION = "7000";
    /**
     * 고확신 판단: RSI가 이 값 미만이면 회복 초입 구간으로 판단
     */
    private static final BigDecimal HIGH_CONVICTION_RSI_MAX = BigDecimal.valueOf(48);
    /**
     * 저확신 판단: 단기·장기 모두 SIDEWAYS일 때, RSI가 이 값 이상이면 방향성 약한 구간으로 판단
     */
    private static final BigDecimal LOW_CONVICTION_SIDEWAYS_RSI_MIN = BigDecimal.valueOf(55);
    /**
     * 매수 허용 RSI 하한
     * (8/25 거래빈도 확대: 43 → 40 — 하루 80~100건 목표를 위해 진입 구간 확장)
     */
    private static final BigDecimal RSI_BUY_MIN = BigDecimal.valueOf(40);
    /**
     * 매수 허용 RSI 상한 — 과열 진입 방지
     * (8/25 거래빈도 확대: 55 → 65 — 품질 우선으로 55까지 줄였던 것을 빈도 목표에 맞춰 재확장.
     * 품질 저하는 RSI모멘텀손절 오발동 방지 로직(entryRsiMap/최소보유시간)이 어느 정도 보완)
     */
    private static final BigDecimal RSI_BUY_MAX = BigDecimal.valueOf(65);
    /**
     * BB 위치 진입 차단 기준: (현재가 - BB하단) / (BB상단 - BB하단) ≥ 이 값이면 고점 진입으로 판단해 차단
     * (8/25 거래빈도 확대: 0.70 → 0.90 — 진입 가능 구간을 BB 상단 근처까지 확장)
     */
    private static final BigDecimal BB_ENTRY_MAX_PCT = new BigDecimal("0.90");
    /**
     * RSI 상승 최소폭: 직전 슬로우 루프 대비 RSI 상승폭이 이 값 미만이면 진입 차단 (↑0.1 같은 노이즈 필터링)
     * (8/25 거래빈도 확대: 2.0 → 0.3 — 3분마다 2.0pt 상승을 요구하는 조건이 진입 기회를 크게 제한했음)
     */
    private static final BigDecimal RSI_RISE_MIN = new BigDecimal("0.3");

    // ─── 재진입 쿨다운 설정 ───────────────────────────────────────────
    // 9/7: 승률 기반 차등 쿨다운(calcCooldownMinutes) 폐지. 손절한 코인이라도 지표가
    // 회복을 보이면 이전 손절가와 무관하게 재매수해야 한다는 방침에 따라, 재진입 가부는
    // 전적으로 아래 최초매수 필터(RSI 40~65+상승, EMA9>EMA20 구조, BB<90%)가 판단한다.
    // 이 상수는 슬로우 루프 한 사이클(3분) 내 캐시 미갱신 상태에서 곧바로 되사는 것만
    // 막는 최소 안전장치로 축소.
    /**
     * 손절 직후 최소 대기 시간 — 캐시 미갱신 상태에서의 즉시 재매수만 방지
     */
    private static final int RE_ENTRY_COOLDOWN_MINUTES = 2;

    // ─── 익절 후 재진입 가격 앵커 설정 ──────────────────────────────────
    /**
     * 익절 후 재진입 앵커가(평균매수가) 초과 폭 — 로그 표기용으로만 사용 (9/17: 강한 지표
     * 예외 조건의 적용 범위 제한을 폐지하면서, 이 프리미엄 자체는 더 이상 "예외를 고려할지
     * 말지"를 가르는 게이트가 아니게 됨. 아래 참고).
     */
    private static final BigDecimal PROFIT_REENTRY_MAX_PREMIUM = new BigDecimal("0.005");
    /**
     * 익절 후 재진입: 앵커가 초과 시 즉시 재진입 허용 최소 RSI — 강한 상승 모멘텀 확인.
     *
     * <p>9/17: 이 예외 조건의 적용 범위를 "앵커가+0.5% 이내"라는 제한에서 풀었다. 종전엔
     * 앵커를 +0.5% 넘게 초과하면 RSI가 아무리 강해도 무조건 차단하고 PROFIT_ANCHOR_MAX_HOURS
     * (4시간)이 지나야만 재진입이 풀렸는데, 빠르게 치고 올라가는 코인일수록 그 4시간 동안
     * 놓치는 기회비용이 컸다(예: 10,050원 익절 후 10,100→10,200→10,300원으로 계속 오르는
     * 코인은 지표가 강하게 확인돼도 최대 4시간 동안 재진입 자체가 불가능했음). 이제는 앵커를
     * 얼마나 초과했든 RSI≥54 & 직전 대비 상승≥3pt(PROFIT_REENTRY_STRONG_RISE)가 확인되면
     * 즉시 재진입을 허용하고, 지표가 이 정도로 강하지 않은 애매한 반등에서만 기존처럼 차단
     * (그리고 4시간 자동해제를 기다림) — 진짜 급등은 놓치지 않으면서, 이 앵커를 원래 만든
     * 목적(애매한 반등에 성급하게 되사는 것 방지)은 그대로 유지한다.
     */
    private static final BigDecimal PROFIT_REENTRY_STRONG_RSI = new BigDecimal("54");
    /**
     * 익절 후 재진입: 앵커가 초과 시 즉시 재진입 허용 최소 RSI 상승폭 (위 설명 참고)
     */
    private static final BigDecimal PROFIT_REENTRY_STRONG_RISE = new BigDecimal("3.0");
    /**
     * 익절 후 재진입 앵커 만료 시간(시간) — 이 시간을 넘으면 앵커를 무시하고 정상 진입 허용.
     * 8/25 13:06 재시작 이후 로그 재분석 결과 실제로 걸린 버그: 앵커에 만료가 없어
     * ETH/SOL/XRP/DOGE 등 상승장에서 가격이 오래전 앵커보다 +8~20% 높아진 코인들이
     * "익절 후 재진입 차단"에 전부 무기한 걸려 있었음 — 그 결과 매수 가능한 코인이
     * 사실상 XLM 1종으로 좁아져 (a) 상승장 수익 기회를 전부 놓치고 (b) 거래량도 XLM
     * 하나에 갇혀 낮게 유지됨. 이 앵커는 "익절 직후 바로 되사는 것"만 막으면 충분하므로
     * 몇 시간 지나면 자동 해제되도록 함.
     */
    private static final int PROFIT_ANCHOR_MAX_HOURS = 4;

    // ══════════════════════════════════════════════════════════════════
    //  지표 Map 빌드 (캔들 조회 최소화)
    // ══════════════════════════════════════════════════════════════════
    public Map<String, CoinSignalDto> buildSignalMap(Set<String> holdCoinSet) {
        Map<String, CoinSignalDto> map = new HashMap<>();

        // coin_code 목록 + 현재 보유 코인의 합집합을 대상으로 지표 빌드
        // → coin_code에서 제거된 코인을 보유 중이어도 익절/손절 판단이 정상 작동
        Set<String> targetCoins = new HashSet<>(codeRepository.findAllCoinCode());
        holdCoinSet.stream()
                .filter(c -> !c.equals("KRW-KRW"))
                .forEach(targetCoins::add);

        for (String coin : targetCoins) {
            try {
                List<CandleResponse> shortCandles = exchangeClient.candleResponses(coin, 3, 22);
                List<CandleResponse> phaseCandles = exchangeClient.candleResponses(coin, 60, 50);
                List<CandleResponse> emaCandles = exchangeClient.candleResponses(coin, 15, 30);
                if (exchangeClient.isInvalid(shortCandles, 15)
                        || exchangeClient.isInvalid(phaseCandles, 40)
                        || exchangeClient.isInvalid(emaCandles, 20)) {
                    log.warn("{} 캔들 부족 - 지표 계산 스킵", coin);
                    continue;
                }

                BigDecimal rsi = indicatorService.calculateRsi(shortCandles);
                MarketPhase shortPhase = indicatorService.detectShortTermPhase(emaCandles); // 15분봉 → 단기 국면 (주 필터)
                MarketPhase phase = indicatorService.detectMarketPhase(phaseCandles);       // 60분봉 → 장기 국면 (보조 필터)
                Map<String, BigDecimal> ema = indicatorService.calculateEmaCross(emaCandles);
                Map<String, BigDecimal> bb = indicatorService.calculateBollingerBands(shortCandles);
                CoinPrice price = exchangeClient.checkCoinPrice(coin);

                // 9/14: BB·EMA 원값 스냅샷 로깅 — RSI/phase는 매 사이클(보유 무관) 로그가 있어
                // 이후 방향성 백테스트(RSI≥70 86.4% 음전환 등)가 가능했던 반면, BB 상단터치·데드크로스는
                // 실제 매도가 발동한 순간에만 로그(profitScoreBreakdown 등)에 남아 표본이 매도건수로
                // 제한됨. 보유 여부와 무관하게 감시 대상 전체를 매 3분 사이클마다 남겨, RSI 검증 때와
                // 동일한 방식(지표상태 시점 → 이후 N분 수익률)으로 BB·데드크로스의 실제 예측력을
                // 검증하기 위한 원값 로그. 점수 계산(profitSellScore 등)에는 영향 없음 — 순수 기록용.
                boolean deadCross = !indicatorService.isGoldenCross(ema);

                // ── 관찰용 신규 지표(9/18) — 매매 판단에는 전혀 사용하지 않는다. 실거래 기준
                // (RSI 매수구간/EMA구조/장기phase=SIDEWAYS)은 그대로 두고, "이 코인이 앞으로
                // 오를지/내릴지"를 예측할 만한 다른 후보 지표 4개(거래량배율/ATR/MACD/오더북
                // 매수잔량비율)를 미리 측정만 해서 지표스냅샷 로그에 함께 남긴다 — 1주일가량
                // 쌓이면 기존 RSI/BB/phase 검증 때와 동일한 방식(지표상태 시점 → 이후 N분
                // 수익률)으로 역산검증해 쓸만한지 판단할 예정. 계산에 실패해도 기존 신호
                // 빌드(rsi/phase/ema/bb, 실거래 판단에 쓰이는 값들) 자체는 절대 막지 않도록
                // 별도 try/catch로 격리한다.
                String volumeRatioLog = "N/A";
                String atrLog = "N/A";
                String macdLog = "N/A";
                String obImbalanceLog = "N/A";
                try {
                    BigDecimal volumeRatio = indicatorService.calculateVolumeRatio(shortCandles);
                    BigDecimal atr = indicatorService.calculateAtr(shortCandles);
                    volumeRatioLog = volumeRatio.setScale(2, RoundingMode.HALF_UP).toPlainString();
                    atrLog = atr.setScale(4, RoundingMode.HALF_UP).toPlainString();

                    List<CandleResponse> macdCandles = exchangeClient.candleResponses(coin, 15, 60);
                    if (!exchangeClient.isInvalid(macdCandles, 40)) {
                        Map<String, BigDecimal> macd = indicatorService.calculateMacd(macdCandles);
                        macdLog = String.format("%s/%s/%s",
                                macd.get("macd").setScale(2, RoundingMode.HALF_UP),
                                macd.get("signal").setScale(2, RoundingMode.HALF_UP),
                                macd.get("histogram").setScale(2, RoundingMode.HALF_UP));
                    }

                    BigDecimal obImbalance = exchangeClient.orderBookImbalance(coin);
                    obImbalanceLog = obImbalance.setScale(3, RoundingMode.HALF_UP).toPlainString();
                } catch (Exception e) {
                    log.warn("{} 관찰용 신규지표 계산 실패(무시하고 계속): {}", coin, e.getMessage());
                }

                log.info("{} 지표스냅샷 RSI:{} BB상단:{} BB중간:{} BB하단:{} EMA5:{} EMA20:{} 데드크로스:{} 가격:{} 단기:{} 장기:{} 거래량배율:{} ATR:{} MACD/시그널/히스토:{} 오더북매수비율:{}",
                        coin, rsi.setScale(2, RoundingMode.HALF_UP),
                        bb.get("upper"), bb.get("middle"), bb.get("lower"),
                        ema.get("ema5"), ema.get("ema20"), deadCross,
                        price.getBidPrice(), shortPhase, phase,
                        volumeRatioLog, atrLog, macdLog, obImbalanceLog);

                map.put(coin, CoinSignalDto.builder()
                        .rsi(rsi)
                        .shortPhase(shortPhase)
                        .phase(phase)
                        .ema(ema)
                        .bb(bb)
                        .price(price)
                        .build());

            } catch (Exception e) {
                log.warn("{} 지표 빌드 실패: {}", coin, e.getMessage());
            }
        }
        return map;
    }

    // ══════════════════════════════════════════════════════════════════
    //  최초 매수 — SHORT_BULL 전용, RSI 45~65 구간만 진입
    // ══════════════════════════════════════════════════════════════════
    public void firstPurchaseCoin(Set<String> holdCoinSet,
                                  Map<String, CoinSignalDto> signalMap,
                                  List<CoinAccount> accountList) {

        for (String coin : codeRepository.findAllCoinCode()) {
            if (holdCoinSet.contains(coin)) continue;

            // ── 이월잔고 방어 ─────────────────────────────────────────────
            // holdCoinSet은 일정 기준 이상 잔고만 포함 — API 지연·잔고 미반영 시
            // 소량 잔고가 누락될 수 있음. accountList를 직접 스캔해 이중 확인.
            // 잔고가 조금이라도 있으면 이월 포지션으로 간주하고 매수 스킵
            String currency = coin.replace("KRW-", "");
            boolean hasResidualBalance = accountList.stream()
                    .anyMatch(acc -> currency.equals(acc.getCoinName())
                            && acc.getBalance().compareTo(BigDecimal.ZERO) > 0);
            if (hasResidualBalance) {
                log.warn("{} 이월잔고 감지 — holdCoinSet 미반영 소량 잔고 존재, 매수 스킵", coin);
                continue;
            }

            // ── 수동 당일 차단 코인 ─────────────────────────────────────────
            if (stateStore.dailyBlacklistSet.contains(coin)) {
                log.info("{} 당일 차단 코인 - 매수 불가", coin);
                continue;
            }

            // ── 임시 시간 차단 (연속 손절 3회→20분 / 5회 이상→1h) ────────────
            LocalDateTime banUntil = stateStore.temporaryBanUntilMap.get(coin);
            if (banUntil != null) {
                if (LocalDateTime.now().isBefore(banUntil)) {
                    long remainMin = java.time.Duration.between(LocalDateTime.now(), banUntil).toMinutes();
                    log.info("{} 임시차단 중 - 잔여 {}분", coin, remainMin + 1);
                    continue;
                } else {
                    stateStore.temporaryBanUntilMap.remove(coin); // 만료 → 자동 해제
                }
            }

            CoinSignalDto signal = signalMap.get(coin);
            if (signal == null) continue;

            // ── 국면 필터 제거 (9/9) ────────────────────────────────────────
            // 로그 실측 백테스트(8/15-9/6, n=3,568) 결과 BULL/BEAR 국면이 이후 수익률에 예측력이
            // 없거나 오히려 역전됨을 확인(30분후 기준 BULL -0.134%p, BEAR +0.139%p) — 국면만으로
            // 진입을 막을 근거가 없다고 판단해 단기/장기 BEAR 차단 필터를 모두 제거. 진입 품질은
            // 검증된 RSI와 아래 EMA/BB 구조 필터가 전담한다. phase는 사이징(determineOrderAmount)과
            // 로그 표기에는 계속 사용한다.

            // ── EMA 구조 필터: 가격 ≥ EMA9×0.997 AND EMA9 ≥ EMA20×0.999 ──────
            // EMA9 > EMA20 : 단기 추세가 중기 추세 위 (구조 유지)
            // 가격 > EMA9  : 현재가가 단기 추세선 위로 복귀 (조정 이후 회복 확인)
            // EMA5 > EMA20 골든크로스보다 안정적 — EMA5(75분)는 노이즈 과민, EMA9(135분)은 완충
            // (8/25 거래빈도 확대: 엄격한 부등호 대신 0.1~0.3% 버퍼 허용 — 교차 직전/직후 진입 기회 확보)
            {
                BigDecimal ema9 = signal.getEma().get("ema9");
                BigDecimal ema20 = signal.getEma().get("ema20");
                BigDecimal bidPrice = signal.getPrice().getBidPrice();
                BigDecimal ema20Buffer = ema20.multiply(new BigDecimal("0.999"));
                BigDecimal ema9Buffer = ema9.multiply(new BigDecimal("0.997"));
                if (ema9.compareTo(ema20Buffer) <= 0) {
                    log.info("{} EMA 구조 미달 [EMA9:{} ≤ EMA20:{}] - 진입 차단",
                            coin,
                            ema9.setScale(2, RoundingMode.HALF_UP),
                            ema20.setScale(2, RoundingMode.HALF_UP));
                    continue;
                }
                if (bidPrice.compareTo(ema9Buffer) <= 0) {
                    log.info("{} 가격 EMA9 미달 [가격:{} ≤ EMA9:{}] - 조정 미완료",
                            coin,
                            bidPrice.setScale(2, RoundingMode.HALF_UP),
                            ema9.setScale(2, RoundingMode.HALF_UP));
                    continue;
                }
            }

            // ── 장기 phase 필터: SIDEWAYS만 진입 허용 (9/18 역산검증 기반 신규) ──────
            // 9/14~9/18 5일치 로그(지표스냅샷, 3분 간격)로 역산검증한 결과: 코인 자체의
            // 장기(60분봉 EMA9/20) phase가 BULL일 때는 순방향 예측력이 오히려 베이스라인보다
            // 낮았다(60분 호라이즌 기준 -6.4%p — EMA 구조는 후행지표라 "이미 오른 뒤"에 BULL로
            // 잡히는 경향 때문으로 추정). BEAR도 호라이즌별로 부호가 뒤집혀 일관성이 없었던
            // 반면, SIDEWAYS만 3/15/60분 전 구간에서 일관되게(+0.7~+2.6%p) 베이스라인 대비
            // 양의 리프트를 보였다. 같은 데이터로 "+0.3%익절/-1.1%손절" 규칙을 실제 시뮬레이션
            // 했을 때도 SIDEWAYS 진입만 유일하게 뚜렷한 플러스 기대값(+0.0239%/건, 승률
            // 80.2%)을 보였고 BULL/BEAR는 수수료 감안 시 전부 마이너스였다.
            // (9/9에 "국면 자체는 예측력 없다"며 BULL/BEAR 차단 필터를 제거한 적이 있는데,
            // 그건 일별/거래단위 백테스트 결론이었고 이번엔 3분 단위로 더 촘촘히 검증해
            // SIDEWAYS를 적극 요구하는 반대 방향 결론이 나온 것 — 표본이 5일치라 계속
            // 지켜보며 재검증이 필요하다.)
            if (signal.getPhase() != MarketPhase.SIDEWAYS) {
                log.info("{} 장기 phase 조건 미충족 [현재:{}, 필요:SIDEWAYS] - 진입 차단",
                        coin, signal.getPhase());
                continue;
            }

            // ── RSI 매수 구간 필터: RSI_BUY_MIN 이상 RSI_BUY_MAX 미만 ──────────
            // 과열(RSI_BUY_MAX 이상) 및 하락 모멘텀(RSI_BUY_MIN 미만) 모두 차단
            BigDecimal rsi = signal.getRsi();
            if (rsi.compareTo(RSI_BUY_MIN) < 0 || rsi.compareTo(RSI_BUY_MAX) >= 0) {
                log.info("{} RSI 매수 구간 이탈({}) - 보류 [허용: {}~{}]",
                        coin, rsi.setScale(1, RoundingMode.HALF_UP), RSI_BUY_MIN, RSI_BUY_MAX);
                continue;
            }

            // ── RSI 상승 방향 + 최소 상승폭 필터 ──────────────────────────
            // 직전 슬로우 루프 대비 RSI가 2.0pt 이상 상승해야 진입
            // ↑0.1, ↑0.2 같은 노이즈 수준 상승은 조정 완료로 보지 않음
            BigDecimal prevRsi = stateStore.prevRsiMap.get(coin);
            if (prevRsi != null) {
                BigDecimal rsiRise = rsi.subtract(prevRsi);
                if (rsiRise.compareTo(RSI_RISE_MIN) < 0) {
                    log.info("{} RSI 상승폭 미달 진입 차단 (직전:{} → 현재:{}, 상승폭:{}pt < {}pt 기준)",
                            coin,
                            prevRsi.setScale(1, RoundingMode.HALF_UP),
                            rsi.setScale(1, RoundingMode.HALF_UP),
                            rsiRise.setScale(1, RoundingMode.HALF_UP),
                            RSI_RISE_MIN);
                    continue;
                }
            } else {
                // prevRsi 없음 = 당일 첫 진입 or 자정 리셋 직후 → 추세 방향 불명
                // 방향 정보 없이 RSI_BUY_MIN~MAX 어디서든 진입 가능하므로 안전 마진으로 밴드 중간값 요구
                // (8/25 거래빈도 확대에 맞춰 52 → 45로 하향 — 넓어진 밴드(40~65)의 중간값 수준)
                BigDecimal RSI_NO_HISTORY_MIN = new BigDecimal("45");
                if (rsi.compareTo(RSI_NO_HISTORY_MIN) < 0) {
                    log.info("{} RSI 방향 이력 없음 + RSI 낮음({}) → 진입 보류 (이력 없을 때 최소 {})",
                            coin, rsi.setScale(1, RoundingMode.HALF_UP), RSI_NO_HISTORY_MIN);
                    continue;
                }
            }

            // ── BB 위치 진입 필터 (70% 이상 → 고점 진입 차단) ────────────────
            {
                BigDecimal upper = signal.getBb().get("upper");
                BigDecimal lower = signal.getBb().get("lower");
                BigDecimal bidPrice = signal.getPrice().getBidPrice();
                BigDecimal bbRange = upper.subtract(lower);
                if (bbRange.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal bbPct = bidPrice.subtract(lower)
                            .divide(bbRange, 4, RoundingMode.HALF_UP);
                    if (bbPct.compareTo(BB_ENTRY_MAX_PCT) >= 0) {
                        log.info("{} BB 위치 차단 (BB위치: {}%, 상단70% 초과) - 고점 진입 위험",
                                coin, bbPct.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP));
                        continue;
                    }
                }
            }

            // ── 이전 거래 이력 조회 ─────────────────────────────────────────
            Optional<LastTrade> lastTradeOpt = lastTradeRepository.findByMarket(coin);

            // ── 익절 후 차등 쿨다운 (정상:3분 / 과열:10분 / 급등:15분) ────────
            LocalDateTime profitCoolUntil = stateStore.profitCooldownUntilMap.get(coin);
            if (profitCoolUntil != null) {
                if (LocalDateTime.now().isBefore(profitCoolUntil)) {
                    long remainMin = java.time.Duration.between(LocalDateTime.now(), profitCoolUntil).toMinutes();
                    log.info("{} 익절 쿨다운 중 (잔여 {}분) - 재진입 차단", coin, remainMin + 1);
                    continue;
                } else {
                    stateStore.profitCooldownUntilMap.remove(coin); // 만료 → 자동 해제
                }
            }

            // ── 손절 후 재진입 최소 대기 (9/7: 승률 기반 차등 쿨다운 폐지) ──────
            // 재진입 가부는 아래 최초매수 필터가 지표로 직접 판단 — 여기서는 캐시
            // 미갱신 상태에서 즉시 재매수하는 것만 막는다(RE_ENTRY_COOLDOWN_MINUTES).
            // 손절가보다 낮은 가격이어도 지표가 회복을 보이면 재매수를 막지 않는다.
            if (lastTradeOpt.isPresent() && lastTradeOpt.get().getLastDamagedAt() != null) {
                LocalDateTime lastDamagedAt = lastTradeOpt.get().getLastDamagedAt();
                if (lastDamagedAt.isAfter(LocalDateTime.now().minusMinutes(RE_ENTRY_COOLDOWN_MINUTES))) {
                    log.info("{} 손절 직후 최소대기 중 ({}분) - 재진입 차단", coin, RE_ENTRY_COOLDOWN_MINUTES);
                    continue;
                }
            }

            // ── 익절 후 재진입 가격 앵커 체크 ──────────────────────────────
            // 익절로 판매한 코인 재진입 시: 앵커가(평균매수가) 이하로 복귀해야 진입 허용.
            // 앵커가 초과 시에는 얼마나 초과했든 RSI≥54 & 상승≥3pt(강한 지표)면 즉시 재진입
            // 허용, 그렇지 않으면 차단 — 9/17: 종전엔 이 예외가 "+0.5% 이내"에서만 적용돼서
            // 빠르게 치고 올라가는 코인은 지표가 강해도 PROFIT_ANCHOR_MAX_HOURS(4시간)이 지날
            // 때까지 재진입이 막혀있었음(위 PROFIT_REENTRY_STRONG_RSI 설명 참고).
            // DB 기반 관리 — 앱 재시작 후에도 앵커 유지됨
            BigDecimal anchorPrice = lastTradeOpt.map(LastTrade::getProfitAnchorPrice).orElse(null);

            // ── 앵커 만료 체크 (PROFIT_ANCHOR_MAX_HOURS 경과 시 자동 해제) ──────
            // 만료 없이는 상승장에서 가격이 앵커보다 영구히 높게 유지되는 코인이
            // "익절 후 재진입 차단"에 무기한 걸려 매수 자체가 불가능해짐 (8/25-26 로그에서
            // ETH/SOL/XRP/DOGE/ADA/LINK가 전부 이 상태로 갇혀 XLM 1종만 거래되는 문제 확인)
            if (anchorPrice != null) {
                LocalDateTime tradedAt = lastTradeOpt.map(LastTrade::getTradedAt).orElse(null);
                if (tradedAt != null
                        && java.time.Duration.between(tradedAt, LocalDateTime.now()).toHours() >= PROFIT_ANCHOR_MAX_HOURS) {
                    log.info("{} 익절 후 재진입 앵커 만료 ({}시간 경과, 기준가:{}) — 해제하고 정상 진입 허용",
                            coin, PROFIT_ANCHOR_MAX_HOURS, anchorPrice.setScale(0, RoundingMode.HALF_UP));
                    lastTradeOpt.ifPresent(lt -> lastTradeRepository.save(lt.toBuilder().profitAnchorPrice(null).build()));
                    anchorPrice = null;
                }
            }

            if (anchorPrice != null) {
                BigDecimal currentBidPrice = signal.getPrice().getBidPrice();
                BigDecimal anchorCeil = anchorPrice.multiply(BigDecimal.ONE.add(PROFIT_REENTRY_MAX_PREMIUM));

                if (currentBidPrice.compareTo(anchorPrice) <= 0) {
                    // 앵커가 이하 복귀: 일반 진입 조건으로 허용
                    log.info("{} 익절 후 재진입 허용 — 현재가({}) ≤ 기준가({}) 복귀",
                            coin,
                            currentBidPrice.setScale(0, RoundingMode.HALF_UP),
                            anchorPrice.setScale(0, RoundingMode.HALF_UP));
                } else {
                    // 앵커가 초과 (얼마나 초과했든 무관, 9/17: +0.5% 제한 폐지) — 강한 지표
                    // (RSI≥54 & 상승≥3pt) 확인 시에만 즉시 재진입 허용, 아니면 차단
                    //
                    // prevRsi 이력이 없는 경우(직전 슬로우 루프 캐시에 이 코인이 없었음 —
                    // 신규 추적 코인/재시작 직후 등) rsiRise를 0으로 취급하면 "상승 없음"과
                    // "측정 불가"가 로그상 구분되지 않아, RSI 레벨 자체는 충분히 강한데도
                    // 상승폭 미달로 오판되어 차단되는 문제가 있었다 (9/17, KRW-TRUMP
                    // RSI:58.8인데도 "상승:0.0pt"로 차단된 사례 — 실제로는 이력 없음이었음).
                    // 매수필터(위 296행 부근)의 기존 처리 방식과 동일하게, 이력이 없으면
                    // 상승폭 대신 RSI 레벨만으로 "강한 지표"를 판정한다.
                    boolean strongRsi = rsi.compareTo(PROFIT_REENTRY_STRONG_RSI) >= 0;
                    BigDecimal premiumPct = currentBidPrice.subtract(anchorPrice)
                            .divide(anchorPrice, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);

                    boolean strongConfirmed;
                    String rsiRiseLog;
                    String requirementLog;
                    if (prevRsi != null) {
                        BigDecimal rsiRise = rsi.subtract(prevRsi);
                        boolean strongRise = rsiRise.compareTo(PROFIT_REENTRY_STRONG_RISE) >= 0;
                        strongConfirmed = strongRsi && strongRise;
                        rsiRiseLog = rsiRise.setScale(1, RoundingMode.HALF_UP) + "pt";
                        requirementLog = "RSI≥" + PROFIT_REENTRY_STRONG_RSI + " 상승≥" + PROFIT_REENTRY_STRONG_RISE + "pt";
                    } else {
                        // 이력 없음 — 상승폭은 판단 불가, RSI 레벨만으로 "강한 지표" 판정
                        strongConfirmed = strongRsi;
                        rsiRiseLog = "이력없음";
                        requirementLog = "RSI≥" + PROFIT_REENTRY_STRONG_RSI + "(이력없음—레벨만 적용)";
                    }

                    if (!strongConfirmed) {
                        log.info("{} 익절 후 재진입 차단 — 기준가({}) +{}% 초과, 강한 지표 미달 (RSI:{} 상승:{} / 필요 {})",
                                coin,
                                anchorPrice.setScale(0, RoundingMode.HALF_UP),
                                premiumPct,
                                rsi.setScale(1, RoundingMode.HALF_UP),
                                rsiRiseLog,
                                requirementLog);
                        continue;
                    }
                    boolean beyondOldPremiumBand = currentBidPrice.compareTo(anchorCeil) > 0;
                    log.info("{} 익절 후 재진입 예외 허용 — 기준가({}) +{}% 초과이나 강한 지표 확인 (RSI:{} 상승:{}){}",
                            coin,
                            anchorPrice.setScale(0, RoundingMode.HALF_UP),
                            premiumPct,
                            rsi.setScale(1, RoundingMode.HALF_UP),
                            rsiRiseLog,
                            beyondOldPremiumBand ? " [+0.5% 밖 — 9/17 확장 적용]" : "");
                }
            }

            BigDecimal prevRsiLog = stateStore.prevRsiMap.get(coin);
            // 확신도 기반 포지션 사이징: 거래 횟수는 그대로 유지하면서(필터링 아님)
            // 통계적으로 승률이 높은/낮은 셋업에 따라 주문 금액만 차등 적용
            ConvictionOrder convictionOrder = determineOrderAmount(rsi, signal.getShortPhase(), signal.getPhase());
            String orderAmount = convictionOrder.amount();
            log.info("{} 최초매수 RSI:{}{} [단기:{} 장기:{} EMA9>{} BB:{} 앵커:{}] 확신도:{} 금액:{}원",
                    coin,
                    rsi.setScale(1, RoundingMode.HALF_UP),
                    prevRsiLog != null
                            ? String.format("(↑%.1f)", rsi.subtract(prevRsiLog).doubleValue())
                            : "",
                    signal.getShortPhase(), signal.getPhase(),
                    signal.getEma().get("ema20").setScale(0, RoundingMode.HALF_UP),
                    indicatorService.bbPosition(signal),
                    anchorPrice != null ? anchorPrice.setScale(0, RoundingMode.HALF_UP) + "원" : "없음",
                    convictionOrder.tier(), orderAmount);
            OrdersResponse response = exchangeClient.orderCoin(coin, "bid", orderAmount);
            stateStore.positionEntryTimeMap.put(coin, LocalDateTime.now()); // 시간 손절용 진입 시각 기록
            stateStore.entryRsiMap.put(coin, rsi); // RSI 모멘텀손절 오발동 방지용 진입 시점 RSI 기록
            stateStore.rsiTroughMap.put(coin, rsi); // 관망구간 추가매수 판단용 RSI 저점 초기화 (9/9)
            stateStore.dcaCountMap.remove(coin);
            stateStore.lastDcaAtMap.remove(coin);
            tradeHistoryRepository.save(buyHistory(coin, orderAmount, signal));
            // 재매수 성공 → DB 앵커 해제 (profitAnchorPrice = null)
            lastTradeOpt.ifPresent(lt -> lastTradeRepository.save(lt.toBuilder().profitAnchorPrice(null).build()));
            exchangeClient.askSuccessMessage(response);
        }
    }

    /**
     * 확신도 기반 매수 금액 산정 — {market, amount} 쌍 반환 (조회 시점의 RSI/국면 조합 기준).
     * 8/27-30 로그 분석(n=126) 근거는 ORDER_AMOUNT_* 상수 주석 참고.
     * 거래 자체는 그대로 진행(거래빈도 유지)하되 셋업 확신도에 따라 베팅 금액만 차등한다.
     */
    private record ConvictionOrder(String amount, String tier) {
    }

    private ConvictionOrder determineOrderAmount(BigDecimal rsi, MarketPhase shortPhase, MarketPhase longPhase) {
        boolean bothSideways = shortPhase == MarketPhase.SIDEWAYS && longPhase == MarketPhase.SIDEWAYS;

        // 저확신 ① 단기BULL+장기SIDEWAYS 조합 — 8/27-30 로그 10건 전량 손실(0%)
        // (9/4: 별도 "초저확신" 5000원 등급은 Upbit 매도 최소금액 버그로 제거, 저확신 금액으로 통합)
        if (shortPhase == MarketPhase.BULL && longPhase == MarketPhase.SIDEWAYS) {
            return new ConvictionOrder(ORDER_AMOUNT_LOW_CONVICTION, "저확신(단기BULL+장기SIDE)");
        }
        // 저확신 ② 단기+장기 모두 SIDEWAYS + RSI 상단권 — 표본 중 가장 방향성 약한 조합
        if (bothSideways && rsi.compareTo(LOW_CONVICTION_SIDEWAYS_RSI_MIN) >= 0) {
            return new ConvictionOrder(ORDER_AMOUNT_LOW_CONVICTION, "저확신(SIDE/SIDE+RSI고)");
        }
        // 고확신 ① RSI 회복 초입 (40~48 구간, 실측 승률 33%)
        if (rsi.compareTo(HIGH_CONVICTION_RSI_MAX) < 0) {
            return new ConvictionOrder(ORDER_AMOUNT_HIGH_CONVICTION, "고확신(RSI낮음)");
        }
        // 고확신 ② 장기 BULL 확인 (실측 승률 22% vs SIDEWAYS 15%)
        if (longPhase == MarketPhase.BULL) {
            return new ConvictionOrder(ORDER_AMOUNT_HIGH_CONVICTION, "고확신(장기BULL)");
        }
        return new ConvictionOrder(MIN_ORDER_AMOUNT, "기본");
    }
}
