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
    // (8/25 거래빈도 확대: 하루 80~100건 목표에 맞춰 전 쿨다운을 대폭 단축)
    /**
     * 손절 직후 최소 대기 시간 (이후 승률 기반 쿨다운 적용)
     */
    private static final int RE_ENTRY_COOLDOWN_MINUTES = 2;

    // ─── 익절 후 재진입 가격 앵커 설정 ──────────────────────────────────
    /**
     * 익절 후 재진입: 앵커가(평균매수가) 초과 시 허용되는 최대 프리미엄 (0.5%)
     */
    private static final BigDecimal PROFIT_REENTRY_MAX_PREMIUM = new BigDecimal("0.005");
    /**
     * 익절 후 재진입: 앵커가 초과 구간 진입 허용 최소 RSI — 강한 상승 모멘텀 확인
     */
    private static final BigDecimal PROFIT_REENTRY_STRONG_RSI = new BigDecimal("54");
    /**
     * 익절 후 재진입: 앵커가 초과 구간 진입 허용 최소 RSI 상승폭
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

            // ── 단기 국면 필터: SHORT_BEAR만 차단 (SHORT_BULL/SIDEWAYS 허용) ──────
            // (8/25 거래빈도 확대: SHORT_BULL 전용 → BEAR만 차단으로 완화.
            //  8/15-24 로그에서 "단기 국면 차단"이 전체 진입 시도의 절반 가까이를 막아
            //  거래빈도 저하의 가장 큰 원인이었음 — 하락 추세만 걸러내고 나머지는 하위 필터
            //  (RSI/EMA/BB)가 품질을 담당하도록 역할 재분배)
            if (signal.getShortPhase() == MarketPhase.BEAR) {
                log.info("{} 단기 국면 차단 [단기:BEAR — 하락 추세 진입 불가]", coin);
                continue;
            }

            // ── 장기 국면 필터: 60분봉 BEAR만 차단 (SIDEWAYS 허용) ──────────────
            // 기존: BULL 전용 → 거래가 너무 적음 (Aug 데이터: 하루 0~1회)
            // 변경: BEAR만 차단, SIDEWAYS에서도 단기 BULL이면 진입 허용
            // SIDEWAYS 진입 시 익절 임계는 SIDEWAYS 기준으로 자동 적용됨 (PROFIT_THRESHOLD_SIDEWAYS)
            if (signal.getPhase() == MarketPhase.BEAR) {
                log.info("{} 장기 국면 차단 [장기:BEAR — 하락 추세 진입 불가]",
                        coin);
                continue;
            }

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

            // ── 손절 후 재진입 판단 (승률 기반 동적 쿨다운) ────────────────
            if (lastTradeOpt.isPresent() && lastTradeOpt.get().getLastDamagedAt() != null) {
                LocalDateTime lastDamagedAt = lastTradeOpt.get().getLastDamagedAt();
                int dropCount = Optional.ofNullable(lastTradeOpt.get().getDropCount()).orElse(0);
                int profitCount = Optional.ofNullable(lastTradeOpt.get().getProfitCount()).orElse(0);
                int cooldownMinutes = calcCooldownMinutes(dropCount, profitCount);

                if (lastDamagedAt.isAfter(LocalDateTime.now().minusMinutes(cooldownMinutes))) {
                    int total = dropCount + profitCount;
                    String winRateStr = (total > 0)
                            ? String.format("%.0f%%", (double) profitCount / total * 100) : "-";
                    log.info("{} 손절 후 쿨다운 중 (승률:{}, {}분 대기) - 재진입 차단",
                            coin, winRateStr, cooldownMinutes);
                    continue;
                }
            }

            // ── 익절 후 재진입 가격 앵커 체크 ──────────────────────────────
            // 익절로 판매한 코인 재진입 시: 앵커가(평균매수가) 이하로 복귀해야 진입 허용
            // 앵커가 + 0.5% 이내는 RSI ≥ 54 AND RSI 상승 ≥ 3pt 일 때만 예외 허용
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
                } else if (currentBidPrice.compareTo(anchorCeil) <= 0) {
                    // 앵커가 초과이나 허용 폭(+0.5%) 이내: 강한 지표 확인 시만 예외 허용
                    BigDecimal rsiRise = prevRsi != null ? rsi.subtract(prevRsi) : BigDecimal.ZERO;
                    boolean strongRsi = rsi.compareTo(PROFIT_REENTRY_STRONG_RSI) >= 0;
                    boolean strongRise = rsiRise.compareTo(PROFIT_REENTRY_STRONG_RISE) >= 0;
                    if (!strongRsi || !strongRise) {
                        log.info("{} 익절 후 재진입 차단 — 기준가({}) 초과, 강한 지표 미달 (RSI:{} 상승:{}pt / 필요 RSI≥{} 상승≥{}pt)",
                                coin,
                                anchorPrice.setScale(0, RoundingMode.HALF_UP),
                                rsi.setScale(1, RoundingMode.HALF_UP),
                                rsiRise.setScale(1, RoundingMode.HALF_UP),
                                PROFIT_REENTRY_STRONG_RSI, PROFIT_REENTRY_STRONG_RISE);
                        continue;
                    }
                    BigDecimal premiumPct = currentBidPrice.subtract(anchorPrice)
                            .divide(anchorPrice, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
                    log.info("{} 익절 후 재진입 예외 허용 — 기준가({}) +{}% 초과이나 강한 지표 확인 (RSI:{} 상승:{}pt)",
                            coin,
                            anchorPrice.setScale(0, RoundingMode.HALF_UP),
                            premiumPct,
                            rsi.setScale(1, RoundingMode.HALF_UP),
                            rsiRise.setScale(1, RoundingMode.HALF_UP));
                } else {
                    // 앵커가 +0.5% 초과: 완전 차단
                    log.info("{} 익절 후 재진입 차단 — 현재가({}) > 기준가({}) +{}% 한도 초과",
                            coin,
                            currentBidPrice.setScale(0, RoundingMode.HALF_UP),
                            anchorPrice.setScale(0, RoundingMode.HALF_UP),
                            PROFIT_REENTRY_MAX_PREMIUM.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP));
                    continue;
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
            tradeHistoryRepository.save(buyHistory(coin, orderAmount, signal));
            // 재매수 성공 → DB 앵커 해제 (profitAnchorPrice = null)
            lastTradeOpt.ifPresent(lt -> lastTradeRepository.save(lt.toBuilder().profitAnchorPrice(null).build()));
            exchangeClient.askSuccessMessage(response);
        }
    }

    /**
     * 승률(dropCount:profitCount 비율) 기반 동적 쿨다운 계산
     * (8/25 거래빈도 확대: 하루 80~100건 목표에 맞춰 전 구간 단축)
     * <pre>
     *   dropCount < 2         → 샘플 부족, 기본 쿨다운 2분
     *   승률 >= 50%           → 2분   (정상 성과)
     *   승률 30% 이상 50% 미만 → 8분   (성과 저하 경고, 기존 15분 → 단축)
     *   승률 30% 미만          → 15분  (기존 30분 → 단축)
     * </pre>
     */
    private int calcCooldownMinutes(int dropCount, int profitCount) {
        if (dropCount < 2) return RE_ENTRY_COOLDOWN_MINUTES; // 샘플 부족 → 기본 2분

        double winRate = (double) profitCount / (profitCount + dropCount);
        if (winRate >= 0.5) return RE_ENTRY_COOLDOWN_MINUTES; // 2분
        if (winRate >= 0.3) return 8;                          // 8분 (기존 15분)
        return 15;                                             // 15분 (기존 30분)
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
