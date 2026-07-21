package com.finntech.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 통장 비교/추천 (정보성) — 실 예·적금 금리를 불러와 <b>가입 자격이 제한된 상품을 제외</b>하고 금리순으로 준다.
 *
 * <p><b>규제(마스터 §5-5, 원칙 5 개정).</b> 금융위·금감원 유권해석(2022.6.15)상 <b>단순 정보제공·판매목적 아님·
 * 무제휴·가입 편의 없음</b>이면 금소법 '중개업'이 아니다. 그래서 가입 버튼·제휴 링크 없이 정보만 표시한다.
 *
 * <p><b>기간별 조회.</b> 적금은 예치기간에 따라 결과·금리가 달라진다(6·12·24개월 지원). 목표별 계획에서 나온
 * 개월수를 가까운 버킷으로 매핑({@link #nearestPeriodBucket})해 그 기간으로 검색한다. 기간별로 <b>TTL 캐시</b>를 둬
 * 매 요청마다 외부를 때리지 않고(재현성·부하 안전), 실패·차단 시 <b>더미로 폴백</b>한다(비공식 API라 취약).
 *
 * <p><b>필터·정렬.</b> 상품명에 {@code exclude-keywords}(간부·청년·장병·미소·청약=자격 제한)가 들어가면 제외하고,
 * <b>기본금리 내림차순</b>(→최고금리→이름)으로 정렬. 결정론적이라 재현 가능(§4). 순수 함수만 단위 테스트.
 */
@Service
public class SavingsCompareService {

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 적금 API가 실제로 데이터를 주는 예치기간(개월). 36은 응답이 없어 뺀다. */
    static final int[] PERIOD_BUCKETS = {6, 12, 24};

    private final boolean enabled;
    private final String path;
    private final String productTypeCode;
    private final String companyGroupCode;
    private final String sortType;
    private final String depositAmount;
    private final String referer;
    private final int maxPages;
    private final int defaultLimit;
    private final int defaultPeriod;
    private final long cacheTtlMinutes;
    private final List<String> excludeKeywords;
    private final RestClient client;
    private final Clock clock;

    // 기간(버킷)별 성공 조회를 TTL 동안 재사용한다.
    private final Map<Integer, List<Account>> cacheByPeriod = new ConcurrentHashMap<>();
    private final Map<Integer, Instant> cacheAtByPeriod = new ConcurrentHashMap<>();

    public SavingsCompareService(
            @Value("${finntech.savings-compare.enabled:true}") boolean enabled,
            @Value("${finntech.savings-compare.base-url:https://new-m.pay.naver.com}") String baseUrl,
            @Value("${finntech.savings-compare.path:/savings/api/v1/productList}") String path,
            @Value("${finntech.savings-compare.referer:https://new-m.pay.naver.com/savings/list/saving}") String referer,
            @Value("${finntech.savings-compare.product-type-code:1003}") String productTypeCode,
            @Value("${finntech.savings-compare.company-group-code:BA}") String companyGroupCode,
            @Value("${finntech.savings-compare.sort-type:INTEREST_RATE}") String sortType,
            @Value("${finntech.savings-compare.deposit-amount:30000}") String depositAmount,
            @Value("${finntech.savings-compare.default-period:6}") int defaultPeriod,
            @Value("${finntech.savings-compare.max-pages:8}") int maxPages,
            @Value("${finntech.savings-compare.default-limit:8}") int defaultLimit,
            @Value("${finntech.savings-compare.cache-ttl-minutes:30}") long cacheTtlMinutes,
            @Value("${finntech.savings-compare.exclude-keywords:간부,청년,장병,미소,청약}") List<String> excludeKeywords,
            Clock clock) {
        this.enabled = enabled;
        this.path = path;
        this.referer = referer;
        this.productTypeCode = productTypeCode;
        this.companyGroupCode = companyGroupCode;
        this.sortType = sortType;
        this.depositAmount = depositAmount;
        this.defaultPeriod = defaultPeriod;
        this.maxPages = maxPages;
        this.defaultLimit = defaultLimit;
        this.cacheTtlMinutes = cacheTtlMinutes;
        this.excludeKeywords = excludeKeywords == null ? List.of() : List.copyOf(excludeKeywords);
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.clock = clock;
    }

    // ======================================================================
    //  공개 API
    // ======================================================================

    /** 통장 비교 섹션용 — 기본 기간에서 자격 제한 제외 후 금리순 상위 {@code limit}개. 실패 시 더미. */
    public CompareResult compare(Integer limit) {
        int lim = (limit == null || limit <= 0) ? defaultLimit : limit;
        boolean[] live = {false};
        List<Account> ranked = rankedForPeriod(defaultPeriod, live);
        List<Account> top = ranked.size() > lim ? new ArrayList<>(ranked.subList(0, lim)) : ranked;
        String note = live[0] ? null
                : "실시간 조회가 어려워 예시 데이터를 보여드려요. 실제 금리·가입은 각 금융사에서 확인하세요.";
        return new CompareResult(top, live[0], ranked.size(), note);
    }

    /** 특정 개월수(가까운 버킷으로 매핑)로 자격 제한 제외 후 금리순 전체를 준다. 추천(목표별)에서 쓴다. */
    public List<Account> rankedForPeriod(int periodMonths, boolean[] liveOut) {
        int bucket = nearestPeriodBucket(periodMonths);
        List<Account> raw = cachedOrFetch(bucket);
        boolean live = raw != null && !raw.isEmpty();
        if (liveOut != null && liveOut.length > 0) liveOut[0] = live;
        return filterAndRank(live ? raw : dummy(), excludeKeywords);
    }

    // ======================================================================
    //  순수 계산 (단위 테스트 진입점)
    // ======================================================================

    /** 상품명에 제외 키워드가 들어가면 빼고, 기본금리 내림차순(→최고금리→이름)으로 정렬. 순수·결정론. */
    static List<Account> filterAndRank(List<Account> all, List<String> excludeKeywords) {
        return all.stream()
                .filter(a -> a.name() != null
                        && excludeKeywords.stream().noneMatch(k -> a.name().contains(k)))
                .sorted(Comparator.comparingDouble(Account::baseRate).reversed()
                        .thenComparing(Comparator.comparingDouble(Account::primeRate).reversed())
                        .thenComparing(Account::name))
                .toList();
    }

    /** 개월수를 적금 API가 지원하는 가까운 버킷(6·12·24)으로 매핑. 0 이하면 기본 12. 순수. */
    static int nearestPeriodBucket(int months) {
        if (months <= 0) return 12;
        int best = PERIOD_BUCKETS[0];
        for (int b : PERIOD_BUCKETS) {
            if (Math.abs(b - months) < Math.abs(best - months)) best = b;
        }
        return best;
    }

    /** "4.50" 같은 문자열 금리를 double로. 파싱 불가면 0.0. 순수. */
    static double parseRate(Object v) {
        if (v == null) return 0.0;
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    // ======================================================================
    //  내부 — 조회·캐시·폴백
    // ======================================================================

    private List<Account> cachedOrFetch(int bucket) {
        if (!enabled) return null;
        Instant now = clock.instant();
        List<Account> c = cacheByPeriod.get(bucket);
        Instant at = cacheAtByPeriod.get(bucket);
        if (c != null && at != null && Duration.between(at, now).toMinutes() < cacheTtlMinutes) {
            return c;
        }
        try {
            List<Account> fetched = fetchAll(String.valueOf(bucket));
            if (!fetched.isEmpty()) {
                cacheByPeriod.put(bucket, fetched);
                cacheAtByPeriod.put(bucket, now);
                return fetched;
            }
        } catch (Exception e) {
            // 차단·네트워크·스키마 변경 → 폴백(오래된 캐시가 있으면 그것, 없으면 더미).
        }
        return cacheByPeriod.get(bucket);
    }

    /** test.py와 같은 방식으로 offset을 넘겨가며 조건에 맞는 상품을 모은다. */
    @SuppressWarnings("unchecked")
    private List<Account> fetchAll(String period) {
        List<Account> out = new ArrayList<>();
        int offset = 0;
        for (int page = 0; page < maxPages; page++) {
            final int off = offset;
            Map<String, Object> body = client.get()
                    .uri(b -> b.path(path)
                            .queryParam("productTypeCode", productTypeCode)
                            .queryParam("companyGroupCode", companyGroupCode)
                            .queryParam("sortType", sortType)
                            .queryParam("depositPeriod", period)
                            .queryParam("depositAmount", depositAmount)
                            .queryParam("offset", off)
                            .build())
                    .header("User-Agent", UA)
                    .header("Referer", referer)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve().body(Map.class);
            if (body == null || !Boolean.TRUE.equals(body.get("isSuccess"))) break;
            Object resultObj = body.get("result");
            if (!(resultObj instanceof Map)) break;
            Map<String, Object> result = (Map<String, Object>) resultObj;
            Object productsObj = result.get("products");
            if (!(productsObj instanceof List<?> products) || products.isEmpty()) break;

            for (Object po : products) {
                if (!(po instanceof Map)) continue;
                Map<String, Object> p = (Map<String, Object>) po;
                out.add(new Account(
                        str(p.get("companyName")), str(p.get("name")),
                        parseRate(p.get("interestRate")), parseRate(p.get("primeInterestRate"))));
            }

            int size = intOf(result.get("size"), products.size());
            offset += size <= 0 ? products.size() : size;
            int total = intOf(result.get("totalCount"), -1);
            if (total >= 0 && out.size() >= total) break;
        }
        return out;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static int intOf(Object v, int dflt) {
        if (v instanceof Number n) return n.intValue();
        try {
            return v == null ? dflt : Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** 외부 조회가 막혔을 때 화면이 비지 않게 하는 현실적 예시(자격 제한 없는 통장, 금리순). */
    private static List<Account> dummy() {
        return List.of(
                new Account("우리은행", "Npay 우리 적금", 4.50, 4.50),
                new Account("카카오뱅크", "자유적금", 3.50, 3.70),
                new Account("케이뱅크", "코드K 자유적금", 3.50, 3.50),
                new Account("iM뱅크", "세븐적금", 3.30, 3.85),
                new Account("케이뱅크", "주거래우대 자유적금", 3.20, 3.80),
                new Account("신한은행", "쏠편한 선물하는 적금", 3.10, 3.10),
                new Account("KDB산업은행", "KDB 자유적금", 3.01, 3.01),
                new Account("NH농협은행", "NH매일드림적금", 3.00, 3.70));
    }

    // ======================================================================
    //  DTO
    // ======================================================================

    public record Account(String company, String name, double baseRate, double primeRate) {}

    /** live=false면 더미 폴백(note에 안내). totalConsidered=제외 후 남은 전체 수. */
    public record CompareResult(List<Account> accounts, boolean live, int totalConsidered, String note) {}
}
