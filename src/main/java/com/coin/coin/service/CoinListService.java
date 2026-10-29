package com.coin.coin.service;

import com.coin.coin.dto.UriBuilderDto;
import com.coin.coin.dto.response.CandleResponse;
import com.coin.coin.dto.response.CoinTickerResponse;
import com.coin.coin.dto.response.MarketResponse;
import com.coin.coin.entity.CoinCode;
import com.coin.coin.repository.CoinCodeRepository;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 감시 코인 목록 관리 — 동적(유동) 최대 {@value #DYNAMIC_SLOTS}개만 (10/21~: ETH/SOL/XRP도 일반 후보로 포함, BTC·스테이블코인 제외).
 *
 * <p>10/21 장타 전환: 이 목록은 이제 장타의 "대상 코인(유니버스)"이다. 상위 20개 제한을 늘려 조건(거래대금·활발·유의종목·
 * 상장 직후 제외)을 만족하는 코인을 거래대금 순으로 최대 {@value #DYNAMIC_SLOTS}개까지 담고, 매시간 갱신한다.
 * 진입 여부는 LongTradeService가 판단. (10/29: 단타 코드 제거와 함께 단타 손실 기준 교체 로직도 삭제)
 * 이미 목록에 있는 코인의 거래 활발(조건 ②)은 매시간 재확인하지 않고, 장타 손실 마감 시·신규 진입 직전에만
 * LongTradeService가 {@link #checkInactive}로 1번 확인해 미달이면 {@link #removeFromList}로 뺀다.
 *
 * <p>10/16: 고정 코인(ETH/SOL/XRP)은 단타에서 변동폭이 작아 손실만 쌓여 거래 대상에서 뺐었다.
 * 10/21: 장타는 크게 빠졌을 때만 들어가고 메이저는 스프레드가 작아 비용이 유리하므로 다시 일반 후보로 넣는다
 * (거래대금 순·활발 조건을 다른 코인과 똑같이 적용).
 *
 * <p>10/15 개편: 매수 쪽에 진입 조건(스프레드·오더북·15분 RSI·3분 BB)이 생겼으므로, 목록 선정은
 * 지표를 전부 버리고 "거래가 충분하고 지금도 활발한가"만 본다. 목록은 매번 새로 뽑지 않고 유지하면서
 * 매시간 조건 미달 코인만 빼고 빈자리를 채운다.
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
 *   <li>매시 01분, /api/upbit/code 호출 시 — ① 유의종목·상폐·24h 거래대금 미달 코인 제거 ② 상한 초과분 정리 ③ 빈자리 채움</li>
 *   <li>앱 시작 시 — 유의종목·상폐 제거 + 빈자리 채움만</li>
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
    private final RestTemplate restTemplate;
    private final UriBuilderDto coinUriBuilder;
    private final UpbitExchangeClient exchangeClient;

    /** 동적(유동) 코인 최대 개수 (10/15: 17 → 20, 10/21: 20 → 60 → 40 — 장타 유니버스. 조건 만족 코인 중 거래대금 순으로 채움) */
    private static final int DYNAMIC_SLOTS = 40;
    /** 24시간 최소 거래대금 (원) — 10/15: 100억 → 50억. 100억 이상(메이저 제외)은 최근 22~27개뿐이라 20개 + 교체 여유가 부족 */
    private static final BigDecimal MIN_TRADE_VALUE_24H = new BigDecimal("5000000000");
    /** 직전 3시간 거래대금이 24시간 시간당 평균 속도의 이 비율 이상이어야 "활발" */
    private static final BigDecimal ACTIVE_RATIO = new BigDecimal("0.5");
    /** 활발 판단에 쓰는 완성 60분봉 개수 */
    private static final int ACTIVE_HOURS = 3;
    /** 지표 계산에 필요한 최소 60분봉 개수 (CoinSignalService.buildSignal 기준) */
    private static final int MIN_60M_CANDLES = 40;
    /** 선정 대상에서 제외할 마켓 (스테이블코인·BTC) */
    private static final Set<String> COIN_EXCLUSIONS = Set.of("KRW-USDT", "KRW-USDC", "KRW-DAI", "KRW-BTC");
    /** 후보 캔들 조회 간격 (ms) — 업비트 시세 API 초당 요청 제한 여유 */
    private static final long CANDLE_CALL_INTERVAL_MS = 120;

    /** 매시 1분 — 조건 미달 코인 제거 + 조건 만족 코인 추가 (10/21: 하루 1번 교체 → 매시간 유니버스 갱신) */
    @Scheduled(cron = "0 1 * * * *", zone = "Asia/Seoul")
    @Transactional
    public synchronized void dailyRotate() {
        update(true);
    }

    /** /api/upbit/code 수동 갱신 */
    @Transactional
    public synchronized void refreshCoinList() {
        update(true);
    }

    /** 앱 시작 시 빈자리 채움 (재시작 직후 목록이 비어 있거나 모자란 경우) */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public synchronized void fillOnStartup() {
        update(false);
    }

    private void update(boolean daily) {
        String mode = daily ? "일일 교체" : "빈자리 채움";
        log.debug("=== 코인 목록 갱신 시작 ({}) ===", mode);
        try {
            List<String> prev = codeRepository.findAllCoinCode();
            List<String> dynamic = new ArrayList<>(prev);

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

            // ── 3-1. 상한 초과분 정리 (10/21: 60 → 40) — 거래대금 낮은 순으로 제외 ──────
            //        제외돼도 이미 보유 중인 장타는 LongTradeService가 시세를 직접 조회해 그대로 관리한다
            if (dynamic.size() > DYNAMIC_SLOTS) {
                List<String> over = dynamic.stream()
                        .sorted(Comparator.comparing((String m) -> value24h.getOrDefault(m, BigDecimal.ZERO)))
                        .limit(dynamic.size() - DYNAMIC_SLOTS)
                        .toList();
                for (String m : over) {
                    dynamic.remove(m);
                    removed.add(m);
                    log.info("{} 동적 목록 제외 — 최대 {}개 초과 (24h 거래대금 {} 하위)", m, DYNAMIC_SLOTS, eok(value24h.get(m)));
                }
            }

            // ── 5. 빈자리 채움 — 거래대금 순으로 조건 확인 ───────────────────
            List<Map.Entry<String, BigDecimal>> candidates = value24h.entrySet().stream()
                    .filter(e -> !COIN_EXCLUSIONS.contains(e.getKey()))
                    .filter(e -> !warned.contains(e.getKey()))
                    .filter(e -> !dynamic.contains(e.getKey()))
                    .filter(e -> e.getValue().compareTo(MIN_TRADE_VALUE_24H) >= 0)
                    .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                    .toList();
            log.debug("동적 후보 {}개 (24h 거래대금 ≥ {}, 현재 목록 제외), 빈자리 {}개",
                    candidates.size(), eok(MIN_TRADE_VALUE_24H), Math.max(0, DYNAMIC_SLOTS - dynamic.size()));

            List<String> added = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> e : candidates) {
                if (dynamic.size() >= DYNAMIC_SLOTS) break;
                String m = e.getKey();
                try {
                    Thread.sleep(CANDLE_CALL_INTERVAL_MS);
                    List<CandleResponse> c60 = exchangeClient.candleResponses(m, 60, 50);
                    if (exchangeClient.isInvalid(c60, MIN_60M_CANDLES)) {
                        log.debug("{} 동적 후보 제외 — 60분봉 {}개 < {} (상장 초기, 지표 계산 불가)",
                                m, c60 == null ? 0 : c60.size(), MIN_60M_CANDLES);
                        continue;
                    }
                    String inactive = inactiveReason(c60, e.getValue());
                    if (inactive != null) {
                        log.debug("{} 동적 후보 제외 — {}", m, inactive);
                        continue;
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ex) {
                    log.debug("{} 동적 후보 제외 — 캔들 조회 실패: {}", m, ex.getMessage());
                    continue;
                }
                dynamic.add(m);
                added.add(m);
            }

            // ── 6. 저장 ────────────────────────────────────────────────────
            Set<String> finalList = new LinkedHashSet<>();
            finalList.addAll(dynamic);
            codeRepository.deleteAllInBatch();
            codeRepository.saveAll(finalList.stream().map(m -> CoinCode.builder().coinCode(m).build()).toList());

            if (dynamic.size() < DYNAMIC_SLOTS) {
                log.warn("동적 코인 {}개 — 최대 {}개를 채우지 못함 (조건 만족 후보 부족)", dynamic.size(), DYNAMIC_SLOTS);
            }
            log.info("=== 코인 목록 갱신 완료 ({}) [동적({}개):{} 제외:{} 신규:{}] ===",
                    mode, dynamic.size(), dynamic, removed, added);
        } catch (Exception e) {
            log.error("코인 목록 갱신 중 오류 발생: {}", e.getMessage(), e);
        }
    }

    /**
     * 최근 3시간 거래 활발 단건 재확인 (10/21, 장타용) — 선정 조건 ②와 같은 기준.
     * 장타가 손실로 끝난 보유 코인, 신규 진입 직전의 미보유 코인에만 쓴다(매시 갱신 때는 기존 목록 코인을 재확인하지 않음).
     *
     * @return 활발하지 않으면 사유, 활발하면 null. 조회 실패 시에도 null(판단 보류 — 제외하지 않음)
     */
    public String checkInactive(String market) {
        try {
            CoinTickerResponse[] res = restTemplate.getForObject(coinUriBuilder.upbitTicker(market), CoinTickerResponse[].class);
            if (res == null || res.length == 0 || res[0].getAccTradePrice24h() == null) return null;
            List<CandleResponse> c60 = exchangeClient.candleResponses(market, 60, ACTIVE_HOURS + 1);
            if (c60 == null || c60.size() < ACTIVE_HOURS + 1) return null;
            return inactiveReason(c60, res[0].getAccTradePrice24h());
        } catch (Exception e) {
            log.debug("{} 거래 활발 재확인 실패 — 판단 보류: {}", market, e.getMessage());
            return null;
        }
    }

    /** 감시 목록에서 한 코인 제외 (10/21). 이미 보유 중인 장타는 LongTradeService가 시세를 직접 조회해 그대로 관리한다 */
    public synchronized void removeFromList(String market, String why) {
        if (!codeRepository.existsById(market)) return;
        codeRepository.deleteById(market);
        log.info("{} 동적 목록 제외 — {}", market, why);
    }

    /** 직전 {@value #ACTIVE_HOURS}시간(완성 60분봉) 거래대금이 24h 평소 속도의 {@link #ACTIVE_RATIO} 미만이면 사유, 아니면 null */
    private String inactiveReason(List<CandleResponse> c60, BigDecimal value24h) {
        // index 0 = 진행 중인 봉 → 1..ACTIVE_HOURS 완성 봉 합계
        BigDecimal recent = BigDecimal.ZERO;
        for (int i = 1; i <= ACTIVE_HOURS; i++) {
            BigDecimal v = c60.get(i).getCandleAccTradePrice();
            if (v != null) recent = recent.add(v);
        }
        BigDecimal need = value24h.multiply(BigDecimal.valueOf(ACTIVE_HOURS))
                .divide(BigDecimal.valueOf(24), 0, RoundingMode.HALF_UP).multiply(ACTIVE_RATIO);
        if (recent.compareTo(need) >= 0) return null;
        return String.format("직전 %d시간 거래대금 %s < 기준 %s (24h %s의 평소 속도 %s%%)",
                ACTIVE_HOURS, eok(recent), eok(need), eok(value24h),
                ACTIVE_RATIO.movePointRight(2).stripTrailingZeros().toPlainString());
    }

    /** 원 → "N억" 표기 (로그용) */
    private static String eok(BigDecimal won) {
        if (won == null) return "N/A";
        return won.divide(BigDecimal.valueOf(100_000_000L), 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "억";
    }
}
