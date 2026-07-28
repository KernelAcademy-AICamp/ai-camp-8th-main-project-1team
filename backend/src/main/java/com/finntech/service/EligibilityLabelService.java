package com.finntech.service;

import com.finntech.audit.Hashing;
import com.finntech.domain.ProductEligibility;
import com.finntech.repository.ProductEligibilityRepository;
import com.finntech.util.HttpClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 금융상품 가입 자격 라벨러 — 금감원 공시의 자연어 {@code join_member}를 {@code (minAge, maxAge,
 * specialStatus)}로 구조화한다. 상세 배경은 {@link ProductEligibility} 참고.
 *
 * <p><b>원칙 1과의 관계 (문서 §4 "판단은 코드가, 표현은 AI가").</b> 충돌하지 않는다.
 * <ul>
 *   <li>LLM에 보내는 것은 <b>금감원이 공개한 상품 공시 문구</b>뿐이다. 개인 소비 기록도, 사용자 정보도
 *       전송하지 않는다 — 처리방침 5번(외부 AI에 집계 수치만)을 자연히 충족한다.</li>
 *   <li>LLM은 <b>문장을 구조로 옮기기만</b> 한다. "이 사용자가 가입 가능한가"라는 판정은
 *       {@link #eligible}이라는 순수 함수가 한다. AI는 나이를 비교하지도, 상품을 고르지도 않는다.</li>
 * </ul>
 *
 * <p><b>폴백</b>: API 키가 없거나 호출이 실패하면 {@link #ruleParse} 규칙 파서로 떨어진다(D-02 포기 순서).
 * 규칙 파서는 결정론적이라 키 없이도 시연이 돌아가고, 단위 테스트가 가능하다.
 *
 * <p><b>보수적 실패</b>: 자격을 끝내 못 읽으면 특수 조건으로 간주해 <b>제외</b>한다. 가입할 수 없는
 * 상품을 권하는 쪽보다 몇 개 덜 보여주는 쪽이 안전하다(정보성 비교의 신뢰가 우선).
 */
@Service
public class EligibilityLabelService {

    /**
     * 나이로는 확인할 수 없는 신분·상황. 우리가 아는 건 출생연도뿐이라 이런 조건이 붙으면 제외한다.
     * <p>`보유한`(○○은행 통장 보유)·`1인 1계좌` 같은 <b>절차 조건은 넣지 않는다</b> — 계좌를 만들면
     * 누구나 충족할 수 있어 신분 제한이 아니다(사용자 결정 2026-07-24).
     */
    private static final Pattern SPECIAL = Pattern.compile(
            // 신분·직군
            "장병|군인|현역|병사|용사|공무원|교직원|조합원|임직원|재직자|직장인|근로자|근무|사원|"
            + "농업인|어업인|축산인|장애인|국가유공자|"
            // 소득·자산 요건
            + "기초생활|수급자|차상위|무주택|유주택|신용등급|"
            // 가족 관계
            + "자녀|부모|학부모|출산|임신|다자녀|한부모|"
            // 되돌릴 수 없는 개인 속성·이력 (저축은행에 실재: 12干支정기적금·생일축하정기적금·JT쩜피플러스)
            + "간지|干支|띠에|생일월|생일이|반려견|반려동물|첫거래|첫 거래|미거래|첫 고객|당행 첫");

    private static final Pattern RANGE   = Pattern.compile("만?\\s*(\\d{1,3})\\s*세?\\s*[~∼-]\\s*만?\\s*(\\d{1,3})\\s*세");
    private static final Pattern AGE_MIN = Pattern.compile("만\\s*(\\d{1,3})\\s*세\\s*이상");
    private static final Pattern AGE_LT  = Pattern.compile("만\\s*(\\d{1,3})\\s*세\\s*미만");
    private static final Pattern AGE_LE  = Pattern.compile("만\\s*(\\d{1,3})\\s*세\\s*이하");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 한 요청에서 허용하는 LLM 호출 수.
     *
     * <p>없을 때 무슨 일이 벌어지나: 라벨이 비어 있는 첫 요청(배포 직후·자격 문구가 대거 바뀐 날)에
     * 상품 수만큼 <b>순차로</b> LLM을 부른다. 금감원 적금은 페이지당 수십 개이고 최대 8페이지라
     * 수백 회가 되며, 그동안 {@code @Transactional}이 열려 있어 DB 커넥션 하나를 계속 붙잡는다.
     * 동시에 두세 명만 들어와도 커넥션 풀이 마르고, 그 시점부터는 통장 비교와 무관한 화면까지 죽는다.
     * 이 저장소는 같은 모양('첫 접근 동시요청')을 리포트 캐시에서 이미 한 번 겪었다.
     *
     * <p>그래서 한 요청은 정해진 만큼만 승급시키고 나머지는 규칙 파서로 답한 뒤 <b>저장하지 않는다</b>.
     * 다음 요청이 그다음 몫을 이어받아, 화면을 세워 두지 않고도 몇 번의 조회에 걸쳐 전부 채워진다.
     * 5회 × 최대 5초 = 최악 25초로 유계다.
     */
    private static final int MAX_LLM_CALLS_PER_REQUEST = 5;

    private final ProductEligibilityRepository repository;
    private final String apiKey;
    private final String model;
    private final RestClient restClient;
    private final Clock clock;

    public EligibilityLabelService(
            ProductEligibilityRepository repository,
            @Value("${finntech.gemini.api-key:}") String apiKey,
            @Value("${finntech.gemini.model:gemini-2.0-flash}") String model,
            @Value("${finntech.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl,
            Clock clock) {
        this.repository = repository;
        this.apiKey = apiKey;
        this.model = model;
        // LLM은 사용자 요청 안에서 불린다 — 금감원(8초)보다 짧게 잡는다. 늦으면 규칙 파서가 대신한다.
        this.restClient = RestClient.builder().baseUrl(baseUrl)
                .requestFactory(HttpClients.factory(Duration.ofSeconds(3), Duration.ofSeconds(5)))
                .build();
        this.clock = clock;
    }

    public boolean aiEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    // ======================================================================
    //  공개 API
    // ======================================================================

    /**
     * 상품들의 자격 라벨을 한 번에 얻는다. 저장된 라벨의 자격 문구 해시가 같으면 그대로 쓰고,
     * 새 상품이거나 문구가 바뀐 것만 새로 판정해 저장한다.
     *
     * @param products 상품키 → 자격 문구({@code join_member}) 원문
     * @return 상품키 → 자격 라벨
     */
    @Transactional
    public Map<String, Eligibility> labelAll(Map<String, String> products) {
        Map<String, Eligibility> out = new HashMap<>();
        if (products.isEmpty()) return out;

        Map<String, ProductEligibility> stored = new HashMap<>();
        for (ProductEligibility e : repository.findByPrdtKeyIn(List.copyOf(products.keySet()))) {
            stored.put(e.getPrdtKey(), e);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int llmBudget = MAX_LLM_CALLS_PER_REQUEST;
        for (Map.Entry<String, String> p : products.entrySet()) {
            String key = p.getKey();
            String joinMember = p.getValue();
            String hash = sha256(joinMember);
            ProductEligibility saved = stored.get(key);

            // 자격 문구가 그대로면 재판정하지 않는다. 금리만 바뀌는 대부분의 날에는 LLM 호출이 0회다.
            if (saved != null && hash.equals(saved.getJoinMemberHash())) {
                out.put(key, toRecord(saved));
                continue;
            }

            // 예산을 다 쓰면 이번 요청에서는 규칙 파서로 판정하고 저장하지 않는다.
            // 저장하지 않는 것이 핵심이다 — 규칙 결과를 저장해 버리면 해시가 맞아떨어져
            // **다음 요청에서도 영영 LLM으로 승급되지 않는다.**
            if (llmBudget <= 0) {
                out.put(key, ruleParse(joinMember));
                continue;
            }
            llmBudget--;

            Eligibility fresh = judge(joinMember);
            if (saved == null) {
                repository.save(new ProductEligibility(key, hash, fresh.minAge(), fresh.maxAge(),
                        fresh.specialStatus(), fresh.source(), now));
            } else {
                saved.relabel(hash, fresh.minAge(), fresh.maxAge(),
                        fresh.specialStatus(), fresh.source(), now);
                repository.save(saved);
            }
            out.put(key, fresh);
        }
        return out;
    }

    // ======================================================================
    //  순수 계산 (단위 테스트 진입점)
    // ======================================================================

    /** 은행 신고값을 못 믿는 경우가 없다고 보고 판정한다(테스트·단순 호출용). */
    static boolean eligible(Eligibility e, Integer age) {
        return eligible(e, age, false);
    }

    /**
     * 이 나이가 상품에 가입할 수 있는가. 순수·결정론.
     *
     * <p>{@code age}가 null이면(마이데이터 미연동으로 출생연도를 모름) 나이 조건은 따지지 않고
     * 특수 신분 조건만 걸러 <b>정보로는 보여준다</b>. 판매가 아니라 비교이므로 감추지 않는다.
     *
     * <p>{@code bankFlaggedRestricted}는 금감원 {@code join_deny=3}(일부 제한) 신고다. 이 값은 그대로
     * 믿을 수 없어서(제한 없는 상품도 3으로 올라온다) <b>차단이 아니라 안전장치로만</b> 쓴다 —
     * 은행은 제한이 있다는데 우리가 자격 문구에서 나이도 신분도 못 읽어냈다면, 읽지 못한 조건이
     * 있다는 뜻이므로 보수적으로 제외한다. 나이 조건을 읽어냈으면 그 판정을 믿는다.
     */
    static boolean eligible(Eligibility e, Integer age, boolean bankFlaggedRestricted) {
        if (e == null) return false;                      // 라벨이 없으면 보수적으로 제외
        if (e.specialStatus() != null) return false;      // 나이로 확인 못 하는 조건 → 제외
        boolean noAgeCondition = e.minAge() == null && e.maxAge() == null;
        if (bankFlaggedRestricted && noAgeCondition) return false;
        if (age == null) return true;
        if (e.minAge() != null && age < e.minAge()) return false;
        if (e.maxAge() != null && age > e.maxAge()) return false;
        return true;
    }

    /**
     * 규칙 기반 자격 파서 (LLM 폴백). 순수·결정론.
     * <p>특수 신분을 <b>먼저</b> 본다. "만 19세미만 자녀 2명 이상을 둔 부모"처럼 나이 표현과 신분 조건이
     * 섞인 문구에서 나이만 읽으면 성인 부모를 잘못 걸러내기 때문이다.
     */
    static Eligibility ruleParse(String joinMember) {
        String s = joinMember == null ? "" : joinMember.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return new Eligibility(null, null, "가입대상 미상", "RULE");

        Matcher sp = SPECIAL.matcher(s);
        if (sp.find()) return new Eligibility(null, null, sp.group() + " 조건 필요", "RULE");

        Integer min = null, max = null;
        Matcher r = RANGE.matcher(s);
        if (r.find()) {
            min = Integer.parseInt(r.group(1));
            max = Integer.parseInt(r.group(2));
        } else {
            Matcher mi = AGE_MIN.matcher(s);
            if (mi.find()) min = Integer.parseInt(mi.group(1));
            Matcher lt = AGE_LT.matcher(s);
            Matcher le = AGE_LE.matcher(s);
            if (lt.find()) max = Integer.parseInt(lt.group(1)) - 1;   // "만 17세 미만" → 16세까지
            else if (le.find()) max = Integer.parseInt(le.group(1));  // "만 39세 이하" → 39세까지
        }
        return new Eligibility(min, max, null, "RULE");
    }

    // ======================================================================
    //  내부 — 판정·LLM
    // ======================================================================

    /** LLM으로 구조화하고, 키가 없거나 실패하면 규칙 파서로 떨어진다. */
    private Eligibility judge(String joinMember) {
        Eligibility fallback = ruleParse(joinMember);
        if (!aiEnabled()) return fallback;
        Eligibility ai = callGemini(joinMember);
        return ai == null ? fallback : ai;
    }

    private Eligibility callGemini(String joinMember) {
        String prompt = """
                아래는 금융감독원이 공시한 금융상품의 '가입대상' 문구입니다.
                이 문구만 보고 가입 자격을 JSON으로 옮기세요. 설명·마크다운 없이 JSON만 출력하세요.

                형식: {"minAge": 정수 또는 null, "maxAge": 정수 또는 null, "specialStatus": 문자열 또는 null}

                규칙:
                - minAge/maxAge는 만 나이이며 경계를 포함합니다. "만 17세 미만"이면 maxAge=16입니다.
                - 나이 제한이 없으면 null입니다. "실명의 개인", "제한없음"은 셋 다 null입니다.
                - 나이가 아닌 신분·상황이 필요하면 specialStatus에 그 사유를 10자 이내로 적으세요.
                  예: 군 장병, 공무원, 조합원, 기초생활수급자, 자녀 있는 부모, 농업인.
                - 특정 은행의 통장을 보유해야 하는 조건은 누구나 만들 수 있으므로 specialStatus가 아닙니다(null).
                - 개인사업자·임의단체 포함 여부는 자격 제한이 아닙니다(null).
                - specialStatus가 있으면 minAge/maxAge는 null로 두세요.

                가입대상: %s
                """.formatted(joinMember);
        try {
            Map<?, ?> response = restClient.post()
                    .uri("/v1beta/models/{model}:generateContent?key={key}", model, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))))
                    .retrieve()
                    .body(Map.class);
            String text = extractText(response);
            return text == null ? null : parseJson(text);
        } catch (Exception e) {
            // 시연 중 네트워크·쿼터 문제로 목록이 비면 안 된다. 조용히 규칙 파서로 떨어진다.
            return null;
        }
    }

    /** LLM이 코드펜스를 붙여 보내는 경우가 있어 JSON 본문만 잘라 읽는다. */
    private static Eligibility parseJson(String text) {
        int s = text.indexOf('{'), e = text.lastIndexOf('}');
        if (s < 0 || e <= s) return null;
        try {
            Map<?, ?> m = MAPPER.readValue(text.substring(s, e + 1), Map.class);
            Integer min = intOrNull(m.get("minAge"));
            Integer max = intOrNull(m.get("maxAge"));
            Object st = m.get("specialStatus");
            String special = (st == null || st.toString().isBlank() || "null".equals(st.toString()))
                    ? null : st.toString().trim();
            return new Eligibility(special == null ? min : null, special == null ? max : null, special, "AI");
        } catch (Exception ex) {
            return null;
        }
    }

    private static Integer intOrNull(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) return null;
        try {
            return Integer.valueOf(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String extractText(Map<?, ?> response) {
        if (response == null) return null;
        if (!(response.get("candidates") instanceof List<?> list) || list.isEmpty()) return null;
        if (!(list.get(0) instanceof Map<?, ?> cand)) return null;
        if (!(cand.get("content") instanceof Map<?, ?> cm)) return null;
        if (!(cm.get("parts") instanceof List<?> pl) || pl.isEmpty()) return null;
        if (!(pl.get(0) instanceof Map<?, ?> pm)) return null;
        Object text = pm.get("text");
        return text == null ? null : text.toString();
    }

    private static Eligibility toRecord(ProductEligibility e) {
        return new Eligibility(e.getMinAge(), e.getMaxAge(), e.getSpecialStatus(), e.getSource());
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Hashing.hex(md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** specialStatus가 null이면 나이만 맞으면 되는 범용 상품이다. source = AI | RULE. */
    public record Eligibility(Integer minAge, Integer maxAge, String specialStatus, String source) {}
}
