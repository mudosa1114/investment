package com.coin.coin.service;

import com.coin.coin.common.MarketPhase;
import com.coin.coin.dto.UriBuilderDto;
import com.coin.coin.dto.response.CandleResponse;
import com.coin.coin.dto.response.CoinTickerResponse;
import com.coin.coin.dto.response.MarketResponse;
import com.coin.coin.entity.CoinCode;
import com.coin.coin.entity.LastTrade;
import com.coin.coin.repository.CoinCodeRepository;
import com.coin.coin.repository.LastTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 동적 코인 목록 갱신 — 매 6시간 하이브리드 선정(고정 메이저 + 동적 알트)
 * (UpbitApi 역할분리, 9/4).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoinListService {

    private final CoinCodeRepository codeRepository;
    private final LastTradeRepository lastTradeRepository;
    private final RestTemplate restTemplate;
    private final UriBuilderDto coinUriBuilder;
    private final UpbitExchangeClient exchangeClient;
    private final TechnicalIndicatorService indicatorService;

    // ─── 동적 코인 선정 설정 ──────────────────────────────────────────
    /** (8/25 거래빈도 확대: 8 → 14 — 고정3 + 동적11, DYNAMIC_COIN_WHITELIST 전체가 조건만 맞으면
     *   동시에 편입 가능하도록 확장. 동시 보유 가능한 코인이 많을수록 3분 슬로우 루프당 매수 기회가 늘어남) */
    private static final int MAX_COIN_SLOTS = 14;
    private static final int VOLUME_TOP_N   = 20;
    /** 24h 최소 거래대금 (KRW) — 이 미만 코인은 유동성 부족으로 제외 */
    private static final BigDecimal MIN_VOLUME_24H = new BigDecimal("10000000000"); // 100억원 (기존 200억 → 완화)
    /** 동적 코인 최소 현재가 (KRW) — 이 미만 극저가 코인 제외 (호가 스프레드 문제) */
    private static final BigDecimal COIN_MIN_PRICE = new BigDecimal("10"); // 10원
    /** 선정 대상에서 제외할 마켓 (스테이블코인·BTC) */
    private static final Set<String> COIN_EXCLUSIONS = Set.of(
            "KRW-USDT", "KRW-USDC", "KRW-DAI", "KRW-BTC"
    );
    /**
     * 동적 코인 화이트리스트 — 이 목록에 포함된 코인만 동적 선정 후보로 허용.
     *
     * <p>선정 기준:
     * <ul>
     *   <li>Upbit 장기 상장 (상장 1년 이상) — 신규 상장 소형 코인 배제</li>
     *   <li>시가총액 상위권 또는 Upbit 거래대금 꾸준 유지</li>
     *   <li>May27-31 로그 분석: AZTEC/POKT/RENDER/FF 등 0승 급락 코인 배제 효과</li>
     * </ul>
     */
    private static final Set<String> DYNAMIC_COIN_WHITELIST = Set.of(
            "KRW-ADA",  "KRW-LINK", "KRW-DOT",  "KRW-ATOM",
            "KRW-HBAR", "KRW-TRX",  "KRW-XLM",  "KRW-DOGE",
            "KRW-ETC",  "KRW-NEAR", "KRW-INJ"
    );

    // ─── 동적 선정 품질 필터 상수 ─────────────────────────────────────
    /** 24h 변동률 하한: 이 미만 폭락 코인 제외 (-8%) */
    private static final BigDecimal COIN_24H_CHANGE_MIN = new BigDecimal("-0.08");
    /** 24h 변동률 상한: 이 초과 급등 코인 제외 (+25%) — 되돌림 위험 */
    private static final BigDecimal COIN_24H_CHANGE_MAX = new BigDecimal("0.25");
    /** 1시간 변동률 하한: 이 미만 단기 급락 코인 제외 (-3%) */
    private static final BigDecimal COIN_1H_CHANGE_MIN  = new BigDecimal("-0.03");
    /** 1시간 변동률 상한: 이 초과 단기 급등 코인 제외 (+5%) — 단기 고점 진입 위험 */
    private static final BigDecimal COIN_1H_CHANGE_MAX  = new BigDecimal("0.05");
    /** BB 폭(%) 상한: (upper-lower)/middle 이 초과이면 변동성 극심 코인 제외 */
    private static final BigDecimal COIN_BB_WIDTH_MAX   = new BigDecimal("8");

    /**
     * 업비트 KRW 마켓 전체를 24h 거래대금 기준으로 정렬 후
     * 상위 {@value VOLUME_TOP_N}개 후보에서 수익 이력 점수를 반영해
     * 최적 {@value MAX_COIN_SLOTS}개를 coin_code 테이블에 저장한다.
     *
     * <p>점수 산정 기준:
     * <ul>
     *   <li>거래량 순위 점수: 20 - rank (1위=19점, 20위=0점)</li>
     *   <li>수익 이력 보정: (익절 수 - 손절 수) × 2</li>
     *   <li>제외 조건: 손절 5회 이상 && 익절 0회인 코인</li>
     * </ul>
     */
    /**
     * 매일 05:00 코인 목록 갱신 — 하이브리드 선정 (고정 메이저 + 동적 알트)
     *
     * <pre>
     * 고정 메이저 (3개): ETH, SOL, XRP — 유동성·안정성 보장, 항상 포함
     * 동적 알트   (최대 11개): DYNAMIC_COIN_WHITELIST 내 거래량 상위 후보 중 수익 이력 점수로 선정
     *                    (XLM 포함 — 과거 고정 메이저였으나 8/15-24 로그 승률 15%로 저하되어
     *                     동적 풀로 이동, 성과 기반 자동 배제 대상이 됨)
     *
     * 동적 점수 계산:
     *   기본점수 = VOLUME_TOP_N - 거래량 순위  (거래량 1위 → 높은 점수)
     *   이력보정 = (승률 - 0.5) × 20           (샘플 3건 이상일 때만 적용)
     *   제외조건 = 손절 5회 이상 && 익절 1회 이하
     *
     * 카운트 초기화:
     *   신규 진입 코인만 초기화 — 유지 코인의 이력은 보존하여 다음 갱신에 반영
     * </pre>
     */
    @Scheduled(cron = "0 0 0/6 * * *", zone = "Asia/Seoul")  // 00:00, 06:00, 12:00, 18:00
    @Transactional
    public void refreshCoinList() {
        log.info("=== 코인 목록 갱신 시작 (하이브리드: 고정3 + 동적최대11) ===");
        try {
            // ── 0. 이전 목록 스냅샷 (신규 진입 코인 판별용) ─────────────────
            Set<String> prevCoins = new HashSet<>(codeRepository.findAllCoinCode());

            // ── 1. 전체 KRW 마켓 조회 ────────────────────────────────────────
            MarketResponse[] markets = restTemplate.getForObject(
                    coinUriBuilder.upbitMarkets(), MarketResponse[].class);
            if (ObjectUtils.isEmpty(markets)) {
                log.warn("마켓 목록 조회 실패 - 갱신 중단");
                return;
            }

            // 고정 메이저 (BTC 제외 — 3분 단타 기준 변동폭 부족)
            // XLM 제외 (8/15-24 로그 재분석 결과 20건 거래 중 17건 손절, 승률 15% —
            //  가격대가 낮아(~250원) 1틱 변동폭이 커 RSI가 가격 정체 중에도 노이즈로 출렁이고,
            //  그 노이즈로 잦은 손절이 발생. 과거 May27-31 구간의 우량 판정은 최근 국면과 불일치.
            //  DYNAMIC_COIN_WHITELIST에는 남겨둬 거래량·승률이 회복되면 동적 선정으로 자동 재진입 가능,
            //  반대로 계속 부진하면 손절 5회↑&익절 1회↓ 필터로 자동 배제됨 — 고정 메이저처럼 영구 고정되지 않음)
            List<String> majors = List.of("KRW-ETH", "KRW-SOL", "KRW-XRP");

            List<String> krwMarkets = Arrays.stream(markets)
                    .map(MarketResponse::getMarket)
                    .filter(m -> m.startsWith("KRW-"))
                    .filter(m -> !COIN_EXCLUSIONS.contains(m))
                    .filter(m -> !majors.contains(m))         // 메이저는 동적 풀에서 제외
                    .filter(DYNAMIC_COIN_WHITELIST::contains) // 화이트리스트 코인만 허용
                    .toList();

            // ── 2. 티커(24h 거래대금) 일괄 조회 — 50개씩 배치 ───────────────
            List<CoinTickerResponse> allTickers = new ArrayList<>();
            for (int i = 0; i < krwMarkets.size(); i += 50) {
                List<String> batch = krwMarkets.subList(i, Math.min(i + 50, krwMarkets.size()));
                CoinTickerResponse[] batchResult = restTemplate.getForObject(
                        coinUriBuilder.upbitTicker(String.join(",", batch)),
                        CoinTickerResponse[].class);
                if (!ObjectUtils.isEmpty(batchResult)) {
                    allTickers.addAll(Arrays.asList(batchResult));
                }
            }

            if (allTickers.isEmpty()) {
                log.warn("티커 조회 결과 없음 - 갱신 중단");
                return;
            }

            // ── 3. 최소 거래대금 필터 + 거래량 상위 VOLUME_TOP_N개 추출 ──────
            List<CoinTickerResponse> topByVolume = allTickers.stream()
                    .filter(t -> t.getAccTradePrice24h() != null)
                    .filter(t -> t.getAccTradePrice24h().compareTo(MIN_VOLUME_24H) >= 0)
                    // 24h 변동률 필터: 폭락(-8% 미만) 및 급등(+25% 초과) 코인 제외
                    .filter(t -> {
                        BigDecimal cr = t.getSignedChangeRate();
                        if (cr == null) return true; // null이면 통과 (보수적 처리)
                        boolean ok = cr.compareTo(COIN_24H_CHANGE_MIN) >= 0
                                  && cr.compareTo(COIN_24H_CHANGE_MAX) <= 0;
                        if (!ok) log.info("{} 24h변동률 제외 ({}%) — 폭락/급등 구간",
                                t.getMarket(),
                                cr.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP));
                        return ok;
                    })
                    .sorted(Comparator.comparing(CoinTickerResponse::getAccTradePrice24h).reversed())
                    .limit(VOLUME_TOP_N)
                    .toList();

            // ── 4. 동적 점수 산정 ─────────────────────────────────────────────
            Map<String, Integer> scoreMap = new LinkedHashMap<>();
            for (int rank = 0; rank < topByVolume.size(); rank++) {
                String market = topByVolume.get(rank).getMarket();
                int score = VOLUME_TOP_N - rank;  // 거래량 순위 기본 점수 (1위 = 최고점)

                Optional<LastTrade> lt = lastTradeRepository.findByMarket(market);
                if (lt.isPresent()) {
                    int profitCnt = Optional.ofNullable(lt.get().getProfitCount()).orElse(0);
                    int dropCnt   = Optional.ofNullable(lt.get().getDropCount()).orElse(0);

                    // 손절 과다 코인 동적 풀 제외
                    if (dropCnt >= 5 && profitCnt <= 1) {
                        log.info("{} 동적 후보 제외 - 손절 과다 (손절:{}, 익절:{})", market, dropCnt, profitCnt);
                        continue;
                    }

                    // 승률 보정 — 샘플 3건 이상일 때만 적용 (소수 샘플 편향 방지)
                    int total = profitCnt + dropCnt;
                    if (total >= 3) {
                        double winRate = (double) profitCnt / total;
                        score += (int) ((winRate - 0.5) * 20);  // 50% 기준 ±10점
                    }
                }
                scoreMap.put(market, score);
            }

            // ── 5. 점수 상위 후보 중 캔들 충분한 코인 5개 동적 선정 ─────────
            // 상장 초기 코인(KRW-SOON 등)은 캔들 수 부족으로 지표 계산 불가
            // → 선정 단계에서 사전 차단하여 슬로우 루프 WARN 반복 방지
            int dynamicSlots = MAX_COIN_SLOTS - majors.size();  // 8 - 4 = 4
            List<String> dynamicSelected = new ArrayList<>();
            List<Map.Entry<String, Integer>> sortedCandidates = scoreMap.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .toList();

            for (Map.Entry<String, Integer> entry : sortedCandidates) {
                if (dynamicSelected.size() >= dynamicSlots) break;
                String market = entry.getKey();
                try {
                    List<CandleResponse> c3  = exchangeClient.candleResponses(market, 3, 22);
                    List<CandleResponse> c15 = exchangeClient.candleResponses(market, 15, 30);
                    List<CandleResponse> c60 = exchangeClient.candleResponses(market, 60, 50);
                    if (exchangeClient.isInvalid(c3, 15) || exchangeClient.isInvalid(c15, 20) || exchangeClient.isInvalid(c60, 40)) {
                        log.info("{} 동적 후보 제외 - 캔들 부족 (상장 초기 또는 거래 중단)", market);
                        continue;
                    }

                    // ── 품질 필터 ①: BB 폭 — 변동성 극심 코인 제외 ────────────────
                    Map<String, BigDecimal> bb = indicatorService.calculateBollingerBands(c3);
                    BigDecimal bbMiddle = bb.get("middle");
                    if (bbMiddle.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal bbWidth = bb.get("upper").subtract(bb.get("lower"))
                                .divide(bbMiddle, 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100));
                        if (bbWidth.compareTo(COIN_BB_WIDTH_MAX) > 0) {
                            log.info("{} 동적 후보 제외 - BB폭 과대 ({}%) — 변동성 극심",
                                    market, bbWidth.setScale(1, RoundingMode.HALF_UP));
                            continue;
                        }
                    }

                    // ── 품질 필터 ②: 최소 현재가 — 극저가 코인 제외 ──────────────
                    // 10원 미만 코인: 호가 단위(0.01원) 대비 변동폭이 너무 커
                    // 예) MBL 1.69원 → 1틱 = 0.59%, 목표 +0.8% 도달에 2틱 필요
                    BigDecimal curPrice  = c60.get(0).getTradePrice();
                    if (curPrice.compareTo(COIN_MIN_PRICE) < 0) {
                        log.info("{} 동적 후보 제외 - 현재가 {}원 < 최소{}원 (호가 스프레드 과대)",
                                market, curPrice, COIN_MIN_PRICE);
                        continue;
                    }

                    // ── 품질 필터 ③: 1시간 변동률 — 단기 급등락 코인 제외 ──────────
                    // c60: index 0 = 가장 최근 캔들, index 1 = 1시간 전 캔들
                    BigDecimal prevPrice = c60.get(1).getTradePrice();
                    if (prevPrice.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal change1h = curPrice.subtract(prevPrice)
                                .divide(prevPrice, 4, RoundingMode.HALF_UP);
                        if (change1h.compareTo(COIN_1H_CHANGE_MIN) < 0) {
                            log.info("{} 동적 후보 제외 - 1h 급락 ({}%) — 단기 하락 추세",
                                    market, change1h.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP));
                            continue;
                        }
                        if (change1h.compareTo(COIN_1H_CHANGE_MAX) > 0) {
                            log.info("{} 동적 후보 제외 - 1h 급등 ({}%) — 단기 고점 위험",
                                    market, change1h.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP));
                            continue;
                        }
                    }

                    // ── 품질 필터 ④: 장기 EMA20 기울기 — 하락 추세 코인 제외 ────────
                    // detectMarketPhase(c60): BULL = EMA20 기울기 양수, BEAR/SIDEWAYS = 제외
                    MarketPhase longPhase = indicatorService.detectMarketPhase(c60);
                    if (longPhase == MarketPhase.BEAR) {
                        log.info("{} 동적 후보 제외 - 장기 EMA20 하락 ({})", market, longPhase);
                        continue;
                    }

                } catch (Exception e) {
                    log.info("{} 동적 후보 제외 - 캔들 조회 실패: {}", market, e.getMessage());
                    continue;
                }
                dynamicSelected.add(market);
            }

            // ── 6. 최종 목록 = 고정 메이저 + 동적 알트 ─────────────────────
            Set<String> finalSelected = new LinkedHashSet<>();
            finalSelected.addAll(majors);
            finalSelected.addAll(dynamicSelected);

            if (finalSelected.size() < majors.size()) {
                log.warn("동적 선정 코인 없음 - 메이저만으로 유지");
            }

            // ── 7. coin_code 테이블 교체 ──────────────────────────────────────
            codeRepository.deleteAllInBatch();
            codeRepository.saveAll(finalSelected.stream()
                    .map(m -> CoinCode.builder().coinCode(m).build())
                    .toList());

            // ── 8. 신규 진입 코인만 카운트 초기화 ───────────────────────────
            //    유지 코인의 누적 이력은 보존 → 다음 갱신 점수에 반영
            List<String> newlyAdded = finalSelected.stream()
                    .filter(m -> !prevCoins.contains(m))
                    .toList();
            if (!newlyAdded.isEmpty()) {
                lastTradeRepository.resetCountsByMarkets(newlyAdded);
                log.info("신규 진입 코인 카운트 초기화: {}", newlyAdded);
            }

            log.info("=== 코인 목록 갱신 완료 [고정:{} 동적:{} 신규초기화:{}] ===",
                    majors, dynamicSelected, newlyAdded);

        } catch (Exception e) {
            log.error("코인 목록 갱신 중 오류 발생: {}", e.getMessage(), e);
        }
    }
}
