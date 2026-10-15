package com.coin.coin.service;

import com.coin.coin.dto.UriBuilderDto;
import com.coin.coin.dto.response.CandleResponse;
import com.coin.coin.dto.response.CoinTickerResponse;
import com.coin.coin.dto.response.MarketResponse;
import com.coin.coin.entity.CoinCode;
import com.coin.coin.repository.CoinCodeRepository;
import com.coin.coin.repository.LastTradeRepository;
import com.coin.coin.repository.TradeHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 감시 코인 목록 관리 — 동적(유동) 최대 {@value #DYNAMIC_SLOTS}개만 (10/16: 고정 메이저 ETH/SOL/XRP 제외).
 *
 * <p>10/16: 고정 코인은 사실상 무조건 매매였고 변동폭이 작아 손실만 쌓여 거래 대상에서 뺐다. 유동 후보에서도 제외한다.
 * 이미 보유 중인 고정 코인은 목록에서 빠져도 청산 규칙대로 정리된다.
 *
 * <p>10/15 개편: 매수 쪽에 진입 조건(스프레드·오더북·15분 RSI·3분 BB)이 생겼으므로, 목록 선정은
 * 지표를 전부 버리고 "거래가 충분하고 지금도 활발한가"만 본다. 목록은 매번 새로 뽑지 않고 유지하면서
 * 매일 00시에 성과가 나쁜 코인만 교체한다.
 * <ul>
 *   <li>선정 조건 ①: 24시간 거래대금 ≥ {@link #MIN_TRADE_VALUE_24H} (50억원)</li>
 *   <li>선정 조건 ②: 직전 3시간(완성된 60분봉 3개) 거래대금 ≥ 24시간 시간당 평균 × 3 × {@link #ACTIVE_RATIO}
 *       — "평소 속도의 절반 이상으로 지금도 거래되고 있다". 몇 시간 전 한 번 튀고 식은 코인을 거른다.</li>
 *   <li>기술적 필수 조건(지표 아님): 60분봉 40개 이상(= 지표 계산 가능, 상장 직후 제외),
 *       유의종목·상장폐지 제외, 스테이블코인·BTC 제외</li>
 *   <li>빈자리는 조건을 만족하는 코인 중 24시간 거래대금 순으로 채운다.</li>
 * </ul>
 * 일정:
 * <ul>
 *   <li>00:00 일일 교체 — ① 유의종목·상폐·24h 거래대금 미달 코인 제거 ② 전날(00~24시) 실매매 실현손익
 *       합계(원)가 마이너스인 코인 중 손실 금액 상위 {@link #DAILY_ROTATE_OUT}개 제거 ③ 빈자리 채움.
 *       오늘 제거된 코인은 다음 00시까지 다시 들어오지 않는다(메모리 보관 — 앱 재시작 시 초기화).</li>
 *   <li>06·12·18시, 앱 시작 시, /api/upbit/code 호출 시 — 유의종목·상폐 제거 + 빈자리 채움만 (성과 교체 없음)</li>
 * </ul>
 * 목록에서 빠져도 보유 중인 포지션은 그대로 청산 규칙을 따른다(buildSignalMap이 보유 코인을 함께 계산).
 *
 * <p>이전(9/7~10/14): 매 6시간 전체 재선정, 24h/1h 변동률·BB폭·최소가·장기 EMA20·손절 과다 필터 + 승률 점수.
 * 로그상 필터에 대부분 걸려 동적 코인이 중앙값 6개 수준이었다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoinListService {

    private final CoinCodeRepository codeRepository;
    private final LastTradeRepository lastTradeRepository;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final RestTemplate restTemplate;
    private final UriBuilderDto coinUriBuilder;
    private final UpbitExchangeClient exchangeClient;

    /** 옛 고정 메이저 — 10/16부터 목록·후보 모두에서 제외 (CoinSignalService.MAJOR_COINS와 동일) */
    private static final List<String> MAJORS = List.of("KRW-ETH", "KRW-SOL", "KRW-XRP");
    /** 동적(유동) 코인 최대 개수 (10/15: 17 → 20) */
    private static final int DYNAMIC_SLOTS = 20;
    /** 24시간 최소 거래대금 (원) — 10/15: 100억 → 50억. 100억 이상(메이저 제외)은 최근 22~27개뿐이라 20개 + 교체 여유가 부족 */
    private static final BigDecimal MIN_TRADE_VALUE_24H = new BigDecimal("5000000000");
    /** 직전 3시간 거래대금이 24시간 시간당 평균 속도의 이 비율 이상이어야 "활발" */
    private static final BigDecimal ACTIVE_RATIO = new BigDecimal("0.5");
    /** 활발 판단에 쓰는 완성 60분봉 개수 */
    private static final int ACTIVE_HOURS = 3;
    /** 지표 계산에 필요한 최소 60분봉 개수 (CoinSignalService.buildSignal 기준) */
    private static final int MIN_60M_CANDLES = 40;
    /** 매일 00시 손실 금액 기준으로 빼는 코인 수 */
    private static final int DAILY_ROTATE_OUT = 2;
    /** 선정 대상에서 제외할 마켓 (스테이블코인·BTC) */
    private static final Set<String> COIN_EXCLUSIONS = Set.of("KRW-USDT", "KRW-USDC", "KRW-DAI", "KRW-BTC");
    /** 후보 캔들 조회 간격 (ms) — 업비트 시세 API 초당 요청 제한 여유 */
    private static final long CANDLE_CALL_INTERVAL_MS = 120;

    /** 오늘 00시 교체에서 빠진 코인 → 그 날짜. 다음 날 00시 전까지 재편입 금지 */
    private final Map<String, LocalDate> rotatedOut = new HashMap<>();

    /** 매일 00:00 — 성과 교체 + 빈자리 채움 */
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    @Transactional
    public synchronized void dailyRotate() {
        update(true);
    }

    /** 06·12·18시 — 빈자리 채움만 */
    @Scheduled(cron = "0 0 6,12,18 * * *", zone = "Asia/Seoul")
    @Transactional
    public synchronized void refreshCoinList() {
        update(false);
    }

    /** 앱 시작 시 빈자리 채움 (재시작 직후 목록이 비어 있거나 모자란 경우) */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public synchronized void fillOnStartup() {
        update(false);
    }

    private void update(boolean daily) {
        String mode = daily ? "일일 교체" : "빈자리 채움";
        log.info("=== 코인 목록 갱신 시작 ({}) ===", mode);
        try {
            LocalDate today = LocalDate.now();
            rotatedOut.values().removeIf(d -> d.isBefore(today));

            List<String> prev = codeRepository.findAllCoinCode();
            List<String> dynamic = new ArrayList<>(prev.stream().filter(m -> !MAJORS.contains(m)).toList());

            // ── 1. 마켓 목록 (유의종목 포함 상세) ─────────────────────────────
            MarketResponse[] markets = restTemplate.getForObject(coinUriBuilder.upbitMarkets(), MarketResponse[].class);
            if (ObjectUtils.isEmpty(markets)) {
                log.warn("마켓 목록 조회 실패 - 갱신 중단");
                return;
            }
            Set<String> listed = new HashSet<>();
            Set<String> warned = new HashSet<>();
            for (MarketResponse m : markets) {
                if (m.getMarket() == null || !m.getMarket().startsWith("KRW-")) continue;
                listed.add(m.getMarket());
                if (m.isWarning()) warned.add(m.getMarket());
            }

            // ── 2. 24시간 거래대금 (티커 50개씩) ──────────────────────────────
            List<String> krw = listed.stream().sorted().toList();
            Map<String, BigDecimal> value24h = new HashMap<>();
            for (int i = 0; i < krw.size(); i += 50) {
                List<String> batch = krw.subList(i, Math.min(i + 50, krw.size()));
                CoinTickerResponse[] res = restTemplate.getForObject(
                        coinUriBuilder.upbitTicker(String.join(",", batch)), CoinTickerResponse[].class);
                if (res == null) continue;
                for (CoinTickerResponse t : res) {
                    if (t.getAccTradePrice24h() != null) value24h.put(t.getMarket(), t.getAccTradePrice24h());
                }
            }
            if (value24h.isEmpty()) {
                log.warn("티커 조회 결과 없음 - 갱신 중단");
                return;
            }

            // ── 3. 자격 상실 제거 — 상폐·유의종목은 항상, 거래대금 미달은 00시에만 ─────────
            List<String> removed = new ArrayList<>();
            for (String m : new ArrayList<>(dynamic)) {
                String why = null;
                if (!listed.contains(m)) why = "상장폐지/마켓 없음";
                else if (warned.contains(m)) why = "유의종목";
                else if (daily && value24h.containsKey(m) && value24h.get(m).compareTo(MIN_TRADE_VALUE_24H) < 0)
                    why = "24h 거래대금 " + eok(value24h.get(m)) + " < " + eok(MIN_TRADE_VALUE_24H);
                if (why != null) {
                    dynamic.remove(m);
                    removed.add(m);
                    log.info("{} 동적 목록 제외 — {}", m, why);
                }
            }

            // ── 4. 00시: 전날 실매매 손실 금액 상위 제거 ─────────────────────
            if (daily) {
                LocalDateTime end = today.atStartOfDay();
                LocalDateTime start = end.minusDays(1);
                Map<String, BigDecimal> pnl = new HashMap<>();
                for (Object[] r : tradeHistoryRepository.sumRealizedPnlByMarket(start, end)) {
                    pnl.put((String) r[0], new BigDecimal(r[1].toString()));
                }
                List<String> losers = dynamic.stream()
                        .filter(m -> pnl.containsKey(m) && pnl.get(m).signum() < 0)
                        .sorted(Comparator.comparing(pnl::get))
                        .limit(DAILY_ROTATE_OUT)
                        .toList();
                for (String m : losers) {
                    dynamic.remove(m);
                    removed.add(m);
                    rotatedOut.put(m, today);
                    log.info("{} 동적 목록 제외 — 전날 실현손익 {}원 (손실 상위 {}개 교체)",
                            m, pnl.get(m).setScale(0, RoundingMode.HALF_UP), DAILY_ROTATE_OUT);
                }
                if (losers.isEmpty()) log.info("전날 실현손실 코인 없음 — 성과 교체 없음");
            }

            // ── 5. 빈자리 채움 — 거래대금 순으로 조건 확인 ───────────────────
            List<Map.Entry<String, BigDecimal>> candidates = value24h.entrySet().stream()
                    .filter(e -> !MAJORS.contains(e.getKey()) && !COIN_EXCLUSIONS.contains(e.getKey()))
                    .filter(e -> !warned.contains(e.getKey()))
                    .filter(e -> !dynamic.contains(e.getKey()) && !rotatedOut.containsKey(e.getKey()))
                    .filter(e -> e.getValue().compareTo(MIN_TRADE_VALUE_24H) >= 0)
                    .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                    .toList();
            log.info("동적 후보 {}개 (24h 거래대금 ≥ {}, 현재 목록·오늘 교체 제외), 빈자리 {}개",
                    candidates.size(), eok(MIN_TRADE_VALUE_24H), Math.max(0, DYNAMIC_SLOTS - dynamic.size()));

            List<String> added = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> e : candidates) {
                if (dynamic.size() >= DYNAMIC_SLOTS) break;
                String m = e.getKey();
                try {
                    Thread.sleep(CANDLE_CALL_INTERVAL_MS);
                    List<CandleResponse> c60 = exchangeClient.candleResponses(m, 60, 50);
                    if (exchangeClient.isInvalid(c60, MIN_60M_CANDLES)) {
                        log.info("{} 동적 후보 제외 — 60분봉 {}개 < {} (상장 초기, 지표 계산 불가)",
                                m, c60 == null ? 0 : c60.size(), MIN_60M_CANDLES);
                        continue;
                    }
                    // index 0 = 진행 중인 봉 → 1..ACTIVE_HOURS 완성 봉 합계
                    BigDecimal recent = BigDecimal.ZERO;
                    for (int i = 1; i <= ACTIVE_HOURS; i++) {
                        BigDecimal v = c60.get(i).getCandleAccTradePrice();
                        if (v != null) recent = recent.add(v);
                    }
                    BigDecimal need = e.getValue().multiply(BigDecimal.valueOf(ACTIVE_HOURS))
                            .divide(BigDecimal.valueOf(24), 0, RoundingMode.HALF_UP).multiply(ACTIVE_RATIO);
                    if (recent.compareTo(need) < 0) {
                        log.info("{} 동적 후보 제외 — 직전 {}시간 거래대금 {} < 기준 {} (24h {}의 평소 속도 {}%)",
                                m, ACTIVE_HOURS, eok(recent), eok(need), eok(e.getValue()),
                                ACTIVE_RATIO.movePointRight(2).stripTrailingZeros().toPlainString());
                        continue;
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ex) {
                    log.info("{} 동적 후보 제외 — 캔들 조회 실패: {}", m, ex.getMessage());
                    continue;
                }
                dynamic.add(m);
                added.add(m);
            }

            // ── 6. 저장 ────────────────────────────────────────────────────
            Set<String> finalList = new LinkedHashSet<>(); // 10/16: 고정 메이저 없음
            finalList.addAll(dynamic);
            codeRepository.deleteAllInBatch();
            codeRepository.saveAll(finalList.stream().map(m -> CoinCode.builder().coinCode(m).build()).toList());

            List<String> newlyAdded = added.stream().filter(m -> !prev.contains(m)).toList();
            if (!newlyAdded.isEmpty()) lastTradeRepository.resetCountsByMarkets(newlyAdded);

            if (dynamic.size() < DYNAMIC_SLOTS) {
                log.warn("동적 코인 {}개 — 최대 {}개를 채우지 못함 (조건 만족 후보 부족)", dynamic.size(), DYNAMIC_SLOTS);
            }
            log.info("=== 코인 목록 갱신 완료 ({}) [동적({}개):{} 제외:{} 신규:{}] ===",
                    mode, dynamic.size(), dynamic, removed, added);
        } catch (Exception e) {
            log.error("코인 목록 갱신 중 오류 발생: {}", e.getMessage(), e);
        }
    }

    /** 원 → "N억" 표기 (로그용) */
    private static String eok(BigDecimal won) {
        if (won == null) return "N/A";
        return won.divide(BigDecimal.valueOf(100_000_000L), 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "억";
    }
}
