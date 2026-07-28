package com.finntech.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
// Spring Boot 4는 Jackson 3을 쓴다 — 패키지가 com.fasterxml.jackson이 아니라 tools.jackson이다.
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 통장 비교/추천 (정보성) — 실 적금 금리를 불러와 <b>가입 자격이 제한된 상품을 제외</b>하고 금리순으로 준다.
 *
 * <p><b>규제(마스터 §5-5, 원칙 5 개정).</b> 금융위·금감원 유권해석(2022.6.15)상 <b>단순 정보제공·판매목적 아님·
 * 무제휴·가입 편의 없음</b>이면 금소법 '중개업'이 아니다. 그래서 가입 버튼·제휴 링크 없이 정보만 표시한다.
 *
 * <p><b>출처 = 금감원 금융상품통합비교공시 오픈API</b> (2026-07-24 전환. 이전 네이버페이 비공식 API 대체).
 * 공식 API라 차단 위험이 없고 {@code rsrv_type}(적립방식)·{@code join_deny}(가입제한) 같은 필드가 더 온다.
 * 호출·파싱에 함정이 셋 있어 전부 방어한다.
 * <ol>
 *   <li><b>https 강제</b> — http로 부르면 307로 튕긴다.</li>
 *   <li><b>User-Agent 필수</b> — 없으면 WAF가 상태코드도 없이 연결을 끊는다(curl 52 Empty reply).</li>
 *   <li><b>비표준 JSON</b> — {@code spcl_cnd}(우대조건) 등에 이스케이프 안 된 생 개행이 들어온다.
 *       표준 파서는 통째로 거부하므로 {@link JsonReadFeature#ALLOW_UNESCAPED_CONTROL_CHARS}를 켠 전용
 *       매퍼로 읽는다. 전역 ObjectMapper를 느슨하게 만들면 다른 입력까지 관대해지므로 여기서만 쓴다.</li>
 * </ol>
 *
 * <p><b>2단 응답 조인.</b> 응답이 {@code baseList}(상품 설명, 금리 없음) + {@code optionList}(기간·방식별 금리,
 * 상품명 없음)로 나뉜다. {@code (fin_co_no, fin_prdt_cd)}로 짝지어야 화면에 쓸 한 줄이 된다.
 * 상품 1개에 금리 줄이 여러 개(기간 1·3·6·12·24·36개월 × 정액/자유적립)라 <b>1:N</b>이다.
 *
 * <p><b>자유적립식만 쓰는 이유.</b> MOA의 '지킨 돈'은 한도 초과분만큼 깎여 <b>매달 금액이 달라진다</b>.
 * 매달 같은 금액을 넣어야 하는 정액적립식은 구조가 맞지 않는다. 실측상 손해도 없다(2026-07 은행 적금 기준
 * 기본금리 평균 자유 2.60% vs 정액 2.67%, 우대 포함은 자유 3.81% vs 정액 3.48%로 오히려 높고 상품 수도 2.6배).
 *
 * <p><b>필터·정렬.</b> {@code exclude-keywords}(간부·청년·장병·미소·청약)가 상품명에 있거나 {@code join_deny=3}
 * (일부 제한)이면 뺀 뒤 <b>기본금리 내림차순</b>(→최고금리→이름)으로 정렬한다. 결정론적이라 재현 가능(§4).
 * 다만 은행 신고가 부실해 아동·청년 전용 상품이 {@code join_deny=1}(제한없음)로 올라오는 경우가 있다.
 * 자연어({@code join_member}·{@code spcl_cnd})에 묻힌 자격은 후속 LLM 사전 라벨링 단계에서 구조화한다.
 *
 * <p>기간별로 <b>TTL 캐시</b>를 둬 매 요청마다 외부를 때리지 않고, 실패·키 미설정 시 <b>더미로 폴백</b>한다.
 * 순수 함수({@link #filterAndRank}·{@link #nearestPeriodBucket}·{@link #parseRate})만 단위 테스트한다.
 */
@Service
public class SavingsCompareService {

    /** 비표준 JSON(생 개행 포함) 전용 매퍼. 전역 매퍼를 오염시키지 않으려고 따로 둔다. */
    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .build();

    /** 금감원 적금 API가 실제로 데이터를 주는 예치기간(개월). 네이버에 없던 36개월이 포함된다. */
    static final int[] PERIOD_BUCKETS = {6, 12, 24, 36};

    /** 가입제한 코드 3 = 일부 제한. 일반 사용자가 담기 어려우므로 뺀다. (1=제한없음) */
    static final String JOIN_DENY_RESTRICTED = "3";

    private final boolean enabled;
    private final String auth;
    private final String path;
    private final String serviceName;
    private final String topFinGrpNo;
    private final String userAgent;
    private final String reserveType;
    private final int maxPages;
    private final int defaultLimit;
    private final int defaultPeriod;
    private final long cacheTtlMinutes;
    private final List<String> excludeKeywords;
    private final RestClient client;
    private final Clock clock;
    private final EligibilityLabelService eligibilityLabelService;

    // 기간(버킷)별 성공 조회를 TTL 동안 재사용한다.
    private final Map<Integer, List<Account>> cacheByPeriod = new ConcurrentHashMap<>();
    private final Map<Integer, Instant> cacheAtByPeriod = new ConcurrentHashMap<>();

    public SavingsCompareService(
            @Value("${finntech.savings-compare.enabled:true}") boolean enabled,
            @Value("${finntech.savings-compare.fss.auth:}") String auth,
            @Value("${finntech.savings-compare.fss.base-url:https://finlife.fss.or.kr}") String baseUrl,
            @Value("${finntech.savings-compare.fss.path:/finlifeapi}") String path,
            @Value("${finntech.savings-compare.fss.service-name:savingProductsSearch}") String serviceName,
            @Value("${finntech.savings-compare.fss.top-fin-grp-no:020000}") String topFinGrpNo,
            @Value("${finntech.savings-compare.fss.user-agent:Mozilla/5.0}") String userAgent,
            @Value("${finntech.savings-compare.fss.reserve-type:자유적립식}") String reserveType,
            @Value("${finntech.savings-compare.default-period:12}") int defaultPeriod,
            @Value("${finntech.savings-compare.max-pages:8}") int maxPages,
            @Value("${finntech.savings-compare.default-limit:8}") int defaultLimit,
            @Value("${finntech.savings-compare.cache-ttl-minutes:30}") long cacheTtlMinutes,
            @Value("${finntech.savings-compare.exclude-keywords:간부,청년,장병,미소,청약}") List<String> excludeKeywords,
            EligibilityLabelService eligibilityLabelService,
            Clock clock) {
        this.eligibilityLabelService = eligibilityLabelService;
        this.enabled = enabled;
        this.auth = auth == null ? "" : auth.trim();
        this.path = path;
        this.serviceName = serviceName;
        this.topFinGrpNo = topFinGrpNo;
        this.userAgent = userAgent;
        this.reserveType = reserveType;
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
        return compare(limit, null);
    }

    /**
     * 나이 자격까지 맞춰 거른 통장 비교.
     *
     * @param birthYear 사용자 출생연도. null이면(마이데이터 미연동) 나이 조건은 따지지 않고
     *                  특수 신분 조건만 걸러 정보로 보여준다 — 판매가 아니라 비교이므로 감추지 않는다.
     */
    public CompareResult compare(Integer limit, Integer birthYear) {
        int lim = (limit == null || limit <= 0) ? defaultLimit : limit;
        boolean[] live = {false};
        List<Account> ranked = eligibleOnly(rankedForPeriod(defaultPeriod, live), birthYear);
        List<Account> top = ranked.size() > lim ? new ArrayList<>(ranked.subList(0, lim)) : ranked;
        String note = live[0] ? null
                : "실시간 조회가 어려워 예시 데이터를 보여드려요. 실제 금리·가입은 각 금융사에서 확인하세요.";
        return new CompareResult(top, live[0], ranked.size(), note);
    }

    /**
     * 가입 자격에 맞는 상품만 남긴다. 자격 문구가 없는 항목(더미 폴백)은 판정할 근거가 없으므로 통과시킨다
     * — 여기서 거르면 외부 조회 실패 시 화면이 통째로 비어버린다.
     */
    private List<Account> eligibleOnly(List<Account> accounts, Integer birthYear) {
        Map<String, String> byKey = new LinkedHashMap<>();
        for (Account a : accounts) {
            if (!a.prdtKey().isBlank() && !a.joinMember().isBlank()) byKey.put(a.prdtKey(), a.joinMember());
        }
        if (byKey.isEmpty()) return accounts;

        Map<String, EligibilityLabelService.Eligibility> labels = eligibilityLabelService.labelAll(byKey);
        Integer age = ageOf(birthYear);
        return accounts.stream()
                .filter(a -> !byKey.containsKey(a.prdtKey())
                        || EligibilityLabelService.eligible(labels.get(a.prdtKey()), age,
                                JOIN_DENY_RESTRICTED.equals(a.joinDeny())))
                .toList();
    }

    /**
     * 출생연도 → 만 나이 근사. 생일 경과 여부는 월·일을 저장하지 않아 알 수 없으므로 <b>연도 차</b>로
     * 계산한다(실제 만 나이보다 최대 1살 많게 나올 수 있다). {@code now()}를 직접 읽지 않고 주입된
     * Clock을 쓴다(§4 원칙 3 재현성).
     */
    private Integer ageOf(Integer birthYear) {
        if (birthYear == null || birthYear <= 0) return null;
        return LocalDate.now(clock).getYear() - birthYear;
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

    /**
     * 상품명 제외 키워드를 빼고 기본금리 내림차순(→최고금리→이름)으로 정렬. 순수·결정론.
     *
     * <p><b>{@code join_deny}로 여기서 거르지 않는다.</b> 실측(2026-07-24)상 이 코드는 양방향으로
     * 틀린다 — `마이키즈 적금`(만 17세 미만)이 1(제한없음)로, `WELCOME 잔돈모아올림적금`(가입대상
     * "제한없음")이 3(일부 제한)으로 신고돼 있다. 3을 그대로 자르면 `NH1934월복리적금`(만19~34세)처럼
     * <b>해당 나이 사용자에게 딱 맞는 상품</b>까지 잃는다. 자격 판정은 {@link EligibilityLabelService}가
     * 자연어를 읽어 하고, 이 코드는 거기서 힌트로만 쓴다.
     */
    static List<Account> filterAndRank(List<Account> all, List<String> excludeKeywords) {
        return all.stream()
                .filter(a -> a.name() != null
                        && excludeKeywords.stream().noneMatch(k -> a.name().contains(k)))
                .sorted(Comparator.comparingDouble(Account::baseRate).reversed()
                        .thenComparing(Comparator.comparingDouble(Account::primeRate).reversed())
                        .thenComparing(Account::name))
                .toList();
    }

    /** 개월수를 적금 API가 지원하는 가까운 버킷(6·12·24·36)으로 매핑. 0 이하면 기본 12. 순수. */
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
        // 인증키가 없으면 외부를 때리지 않는다 — 어차피 err_cd가 떨어지고 더미로 갈 길이다.
        if (!enabled || auth.isEmpty()) return null;
        Instant now = clock.instant();
        List<Account> c = cacheByPeriod.get(bucket);
        Instant at = cacheAtByPeriod.get(bucket);
        if (c != null && at != null && Duration.between(at, now).toMinutes() < cacheTtlMinutes) {
            return c;
        }
        try {
            List<Account> fetched = fetchAll(bucket);
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

    /** pageNo를 넘겨가며 전 페이지를 모으고, baseList와 optionList를 상품키로 조인한다. */
    private List<Account> fetchAll(int bucket) {
        List<Account> out = new ArrayList<>();
        for (int page = 1; page <= maxPages; page++) {
            Map<String, Object> result = fetchPage(page);
            if (result == null) break;

            // baseList = 상품 설명(금리 없음). 조인용으로 상품키에 색인해 둔다.
            Map<String, Map<String, Object>> baseByKey = new HashMap<>();
            for (Map<String, Object> b : rows(result.get("baseList"))) {
                baseByKey.put(productKey(b), b);
            }

            // optionList = 기간·방식별 금리(상품명 없음). 원하는 기간·적립방식만 남겨 base와 짝짓는다.
            for (Map<String, Object> o : rows(result.get("optionList"))) {
                if (!reserveType.equals(str(o.get("rsrv_type_nm")))) continue;
                if (bucket != intOf(o.get("save_trm"), -1)) continue;
                Map<String, Object> b = baseByKey.get(productKey(o));
                if (b == null) continue;   // 짝이 없으면 화면에 쓸 이름이 없다.
                out.add(new Account(
                        // 회사·상품명에도 생 개행이 섞여 온다("Sh해양플라스틱Zero!적금\n(자유적립식)").
                        // 한 줄로 눌러야 목록에서 깨지지 않는다. 우대조건은 줄바꿈이 의미라 그대로 둔다.
                        oneLine(b.get("kor_co_nm")), oneLine(b.get("fin_prdt_nm")),
                        parseRate(o.get("intr_rate")), parseRate(o.get("intr_rate2")),
                        bucket, str(o.get("rsrv_type_nm")),
                        str(b.get("join_deny")), str(b.get("join_member")),
                        str(b.get("spcl_cnd")), productKey(b)));
            }

            if (page >= intOf(result.get("max_page_no"), 1)) break;
        }
        return out;
    }

    /** 한 페이지 조회 → 비표준 JSON을 관대 모드로 파싱 → err_cd 정상일 때만 result를 준다. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchPage(int pageNo) {
        String raw = client.get()
                .uri(b -> b.path(path + "/" + serviceName + ".json")
                        .queryParam("auth", auth)
                        .queryParam("topFinGrpNo", topFinGrpNo)
                        .queryParam("pageNo", pageNo)
                        .build())
                // WAF가 기본 UA를 막는다. 없으면 상태코드도 없이 연결이 끊긴다.
                .header("User-Agent", userAgent)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve().body(String.class);
        if (raw == null || raw.isBlank()) return null;

        Map<String, Object> root = LENIENT.readValue(raw, Map.class);
        if (!(root.get("result") instanceof Map)) return null;
        Map<String, Object> result = (Map<String, Object>) root.get("result");
        // 000 = 정상. 키 만료·한도 초과 등은 여기서 걸러 폴백으로 보낸다.
        if (!"000".equals(str(result.get("err_cd")))) return null;
        return result;
    }

    /** baseList·optionList를 Map 리스트로 안전하게 꺼낸다. 없으면 빈 리스트. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object e : list) {
            if (e instanceof Map) out.add((Map<String, Object>) e);
        }
        return out;
    }

    /** baseList와 optionList를 잇는 상품 식별키 = 금융회사코드 + 상품코드. */
    private static String productKey(Map<String, Object> row) {
        return str(row.get("fin_co_no")) + ":" + str(row.get("fin_prdt_cd"));
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    /** 개행·탭·연속 공백을 공백 하나로 눌러 한 줄로 만든다. 목록에 그대로 찍히는 값에만 쓴다. 순수. */
    static String oneLine(Object v) {
        return str(v).replaceAll("\\s+", " ").trim();
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

    /**
     * 화면에 쓰는 통장 한 줄. 앞 4개는 프론트 {@code AccountView} 계약이라 순서·이름을 바꾸지 않는다.
     * 뒤쪽은 금감원 API에서만 오는 값으로, {@code joinMember}·{@code spclCnd}는 후속 LLM 자격 라벨링의
     * 입력이고 {@code prdtKey}는 그 라벨을 상품에 붙일 때 쓰는 키다.
     */
    public record Account(String company, String name, double baseRate, double primeRate,
                          int saveTrm, String reserveType, String joinDeny, String joinMember,
                          String spclCnd, String prdtKey) {

        /** 더미·테스트용 간편 생성자 — 금감원 전용 필드는 비운다. */
        public Account(String company, String name, double baseRate, double primeRate) {
            this(company, name, baseRate, primeRate, 0, "", "", "", "", "");
        }
    }

    /** live=false면 더미 폴백(note에 안내). totalConsidered=제외 후 남은 전체 수. */
    public record CompareResult(List<Account> accounts, boolean live, int totalConsidered, String note) {}
}
