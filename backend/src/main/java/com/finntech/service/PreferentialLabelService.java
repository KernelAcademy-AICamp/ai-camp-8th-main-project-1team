package com.finntech.service;

import com.finntech.audit.Hashing;
import com.finntech.domain.ProductPreferential;
import com.finntech.repository.ProductPreferentialRepository;
import com.finntech.service.SavingsMatchInputs.IssuerScope;
import com.finntech.service.SavingsMatchInputs.PreferentialCondition;
import com.finntech.service.SavingsMatchInputs.RequiredCondition;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 저축상품 우대조건 라벨러 — 금감원 공시의 자연어 {@code spcl_cnd}를 조건 <b>종류의 목록</b>으로
 * 구조화한다. 상세 배경은 {@link ProductPreferential} 참고.
 *
 * <p><b>{@link EligibilityLabelService}와 같은 틀이다</b> — LLM은 공시 문구를 구조로 옮기기만 하고,
 * `충족했나`라는 판정은 {@link SavingsMatchService}의 순수 함수가 한다. 키가 없거나 호출이 실패하면
 * {@link #ruleParse} 규칙 파서로 떨어지고, 문구 해시로 캐시해 금리만 바뀌는 날에는 호출이 0회다.
 * 원칙 1과 충돌하지 않는 이유도 같다 — 밖으로 나가는 것은 <b>금감원이 공개한 상품 문구뿐</b>이고
 * 사용자 소비·식별 정보는 나가지 않는다.
 *
 * <p><b>실패 방향은 자격 라벨러와 반대다.</b> 자격은 못 읽으면 상품을 <b>제외</b>하지만(가입 못 할 상품을
 * 권하느니 덜 보여준다), 우대조건은 못 읽어도 <b>제외하지 않고</b> 기본금리 + `확인 불가`로 남긴다 —
 * 조건을 모른다고 그 상품에 가입할 수 없는 것은 아니다. 실측상 83%가 이쪽이라, 같은 규칙으로 묶으면
 * 목록이 통째로 사라진다(§4.5 M6).
 *
 * <p><b>가산폭(%p)은 뽑지 않는다.</b> 실측 검산 일치율이 4/25(16%)라 파싱해도 못 쓴다(§8.1 D2).
 */
@Service
public class PreferentialLabelService {

    /**
     * 우대조건이 <b>없다</b>고 적힌 문구. 파싱 실패와 구분해야 한다 — 빈 집합이면 채울 조건이 없으니
     * 곧바로 최고금리다(실측: 한국스탠다드차타드 `퍼스트가계적금`).
     */
    private static final Pattern NO_CONDITION =
            Pattern.compile("^\\s*(없음|없습니다|해당\\s*없음|해당사항\\s*없음|-|N/?A)\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * 조건 종류 사전 (규칙 파서용). `scripts/collect-savings/inspect_fss.py`의 사전과 같은 축이며,
     * 실측 빈도가 높은 것부터다. LLM이 살아 있으면 이 표는 폴백으로만 쓰인다.
     */
    private static final List<Map.Entry<PreferentialCondition, Pattern>> DICTIONARY = List.of(
            Map.entry(PreferentialCondition.AUTO_TRANSFER, Pattern.compile("자동이체|공과금")),
            Map.entry(PreferentialCondition.FIRST_TRADE, Pattern.compile("첫\\s*거래|신규|처음|미거래")),
            Map.entry(PreferentialCondition.MARKETING_CONSENT, Pattern.compile("마케팅|광고성|수신\\s*동의")),
            Map.entry(PreferentialCondition.SALARY_TRANSFER, Pattern.compile("급여|월급|연금\\s*이체")),
            Map.entry(PreferentialCondition.CARD_PERFORMANCE,
                    Pattern.compile("(신용|체크|카드).{0,12}(결제|이용|실적)|카드사?\\s*(신용|체크)")),
            Map.entry(PreferentialCondition.ONLINE_JOIN, Pattern.compile("비대면|스마트폰|모바일|인터넷뱅킹|앱")),
            Map.entry(PreferentialCondition.RATE_COUPON, Pattern.compile("쿠폰")),
            Map.entry(PreferentialCondition.EVENT, Pattern.compile("이벤트|추첨|응모")),
            Map.entry(PreferentialCondition.MAIN_BANK,
                    Pattern.compile("주거래|(예금|적금|청약|통장).{0,4}보유|평잔|총수신")));

    /** 조건이 특정 금융사의 거래를 요구하는 표시. 실측 28%(16/58). */
    private static final Pattern SAME_BANK =
            Pattern.compile("당행|본\\s*은행|해당\\s*은행|입출(금|식)\\s*계좌|주거래");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 한 요청에서 허용하는 LLM 호출 수. {@link EligibilityLabelService}와 같은 이유·같은 값이다 —
     * 라벨이 비어 있는 첫 요청이 상품 수만큼 순차 호출을 내면 트랜잭션이 열린 채 커넥션을 붙잡는다.
     * 예산을 넘긴 상품은 규칙 파서로 답하고 <b>저장하지 않는다</b>(저장하면 해시가 맞아떨어져 영영
     * LLM으로 승급되지 않는다).
     *
     * <p>자격 라벨러와 <b>예산을 나눠 쓰지는 않는다.</b> 한 화면에서 둘 다 비어 있으면 최악 10회가
     * 되는데, 두 라벨은 서로 다른 문구를 보고 서로 다른 날 바뀌므로 한쪽 예산에 묶으면 다른 쪽이
     * 영영 못 채워진다.
     */
    private static final int MAX_LLM_CALLS_PER_REQUEST = 5;

    private final ProductPreferentialRepository repository;
    private final String apiKey;
    private final String model;
    private final RestClient restClient;
    /** 무료가 먼저인 문. 유료는 무료 사슬 다섯이 다 죽었을 때만 쓴다. */
    private final ModelGateway gateway;
    private final Clock clock;

    public PreferentialLabelService(
            ProductPreferentialRepository repository,
            @Value("${finntech.gemini.api-key:}") String apiKey,
            @Value("${finntech.gemini.model:}") String model,
            @Value("${finntech.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl,
            Clock clock,
            ModelGateway gateway) {
        this.gateway = gateway;
        this.repository = repository;
        this.apiKey = apiKey;
        this.model = com.finntech.config.GeminiModels.orDefault(model);
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
     * 상품들의 우대조건 라벨을 한 번에 얻는다. 문구 해시가 같으면 저장된 것을 그대로 쓰고, 새 상품이거나
     * 문구가 바뀐 것만 새로 라벨링해 저장한다.
     *
     * @param products 상품키 → 우대조건 문구({@code spcl_cnd}) 원문
     * @return 상품키 → 요구 조건 목록. <b>빈 목록은 "요구 조건 없음"</b>이고, 키가 아예 없으면
     *         <b>"아직 라벨링 안 됨"</b>이다 — 호출부가 그 둘을 구분한다(M6).
     */
    @Transactional
    public Map<String, List<RequiredCondition>> labelAll(Map<String, String> products) {
        Map<String, List<RequiredCondition>> out = new HashMap<>();
        if (products == null || products.isEmpty()) return out;

        Map<String, ProductPreferential> stored = new HashMap<>();
        for (ProductPreferential p : repository.findByPrdtKeyIn(List.copyOf(products.keySet()))) {
            stored.put(p.getPrdtKey(), p);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int llmBudget = MAX_LLM_CALLS_PER_REQUEST;
        for (Map.Entry<String, String> p : products.entrySet()) {
            String key = p.getKey();
            String raw = p.getValue();
            String hash = sha256(raw);
            ProductPreferential saved = stored.get(key);

            if (saved != null && hash.equals(saved.getSpclCndHash())) {
                out.put(key, decode(saved.getConditions()));
                continue;
            }
            if (llmBudget <= 0) {
                out.put(key, ruleParse(raw));      // 저장하지 않는다 — 다음 요청이 이어받는다
                continue;
            }
            llmBudget--;

            Labeled fresh = judge(raw);
            String encoded = encode(fresh.conditions());
            if (saved == null) {
                repository.save(new ProductPreferential(key, hash, encoded, fresh.source(), now));
            } else {
                saved.relabel(hash, encoded, fresh.source(), now);
                repository.save(saved);
            }
            out.put(key, fresh.conditions());
        }
        return out;
    }

    // ======================================================================
    //  순수 계산 (단위 테스트 진입점)
    // ======================================================================

    /**
     * 규칙 기반 우대조건 파서 (LLM 폴백). 순수·결정론.
     *
     * <p><b>당행 판정은 문구 전체를 보고 붙인다.</b> 조건별로 어디까지가 당행 한정인지는 자연어로만
     * 알 수 있어 규칙으로는 못 가른다. 문구에 `당행`·`주거래`·`입출식 계좌`가 있거나 은행 이름이 박혀
     * 있으면 <b>판정 가능한 조건들에</b> OWN을 붙인다 — 좁혀서 보는 쪽이 더 엄격해 <b>덜 준다고 말하는
     * 방향</b>이고, 그게 이 저장소가 택한 안전한 실패 방향이다.
     *
     * <p>사전에 하나도 안 걸리면 {@link PreferentialCondition#OTHER} 하나를 남긴다 — 문구는 있는데
     * 무엇을 요구하는지 못 읽었다는 뜻이고, 이게 <b>빈 목록(요구 조건 없음)과 다르다.</b>
     */
    static List<RequiredCondition> ruleParse(String spclCnd) {
        String s = spclCnd == null ? "" : spclCnd.replaceAll("\\s+", " ").trim();
        if (s.isEmpty() || NO_CONDITION.matcher(s).matches()) return List.of();

        boolean ownScope = SAME_BANK.matcher(s).find();
        Set<PreferentialCondition> found = new LinkedHashSet<>();
        for (Map.Entry<PreferentialCondition, Pattern> e : DICTIONARY) {
            if (e.getValue().matcher(s).find()) found.add(e.getKey());
        }
        if (found.isEmpty()) found.add(PreferentialCondition.OTHER);

        List<RequiredCondition> out = new ArrayList<>();
        for (PreferentialCondition c : found) {
            // OWN은 판정 가능한 축에만 의미가 있다. 판정 못 하는 조건에 붙여도 결과가 같아 붙이지 않는다.
            out.add(new RequiredCondition(c, ownScope && c.judgeable() ? IssuerScope.OWN : IssuerScope.ANY));
        }
        return sorted(out);
    }

    /** 저장 형식({@code CARD_PERFORMANCE@OWN,...})으로. 빈 목록은 빈 문자열이다. 순수. */
    static String encode(List<RequiredCondition> conditions) {
        if (conditions == null || conditions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (RequiredCondition c : sorted(conditions)) {
            if (!sb.isEmpty()) sb.append(',');
            sb.append(c.type().name()).append('@').append(c.scope().name());
        }
        return sb.toString();
    }

    /**
     * 저장 형식에서 되읽는다. 빈 문자열은 <b>빈 목록</b>(요구 조건 없음)이다. 순수.
     * <p>enum 이름이 바뀌거나 옛 값이 남아 못 읽는 토막은 {@link PreferentialCondition#OTHER}로 떨어뜨린다 —
     * 라벨 한 칸 때문에 화면이 죽는 쪽보다 `확인 못한 조건`으로 세는 쪽이 낫다.
     */
    static List<RequiredCondition> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return List.of();
        List<RequiredCondition> out = new ArrayList<>();
        for (String token : encoded.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            int at = t.indexOf('@');
            String type = at < 0 ? t : t.substring(0, at);
            String scope = at < 0 ? IssuerScope.ANY.name() : t.substring(at + 1);
            out.add(new RequiredCondition(parseType(type), parseScope(scope)));
        }
        return sorted(out);
    }

    /** 화면·정렬이 흔들리지 않게 조건 순서를 고정한다(설계원칙 3 재현성). */
    private static List<RequiredCondition> sorted(List<RequiredCondition> conditions) {
        return conditions.stream()
                .distinct()
                .sorted(Comparator.comparing((RequiredCondition c) -> c.type().ordinal())
                        .thenComparing(c -> c.scope().ordinal()))
                .toList();
    }

    private static PreferentialCondition parseType(String s) {
        try {
            return PreferentialCondition.valueOf(s);
        } catch (IllegalArgumentException e) {
            return PreferentialCondition.OTHER;
        }
    }

    private static IssuerScope parseScope(String s) {
        try {
            return IssuerScope.valueOf(s);
        } catch (IllegalArgumentException e) {
            return IssuerScope.ANY;
        }
    }

    // ======================================================================
    //  내부 — 판정·LLM
    // ======================================================================

    private record Labeled(List<RequiredCondition> conditions, String source) {}

    private Labeled judge(String spclCnd) {
        List<RequiredCondition> fallback = ruleParse(spclCnd);
        if (!aiEnabled()) return new Labeled(fallback, "RULE");
        List<RequiredCondition> ai = callGemini(spclCnd);
        return ai == null ? new Labeled(fallback, "RULE") : new Labeled(ai, "AI");
    }

    private List<RequiredCondition> callGemini(String spclCnd) {
        String prompt = """
                아래는 금융감독원이 공시한 예·적금 상품의 '우대조건' 문구입니다.
                이 문구만 보고 어떤 조건을 요구하는지 JSON으로 옮기세요. 설명·마크다운 없이 JSON만 출력하세요.

                형식: {"conditions": [{"code": "코드", "scope": "OWN" 또는 "ANY"}]}

                code 는 다음 중에서만 고릅니다:
                - CARD_PERFORMANCE  카드 사용 실적
                - SALARY_TRANSFER   급여·연금 이체
                - AUTO_TRANSFER     자동이체·공과금 이체
                - FIRST_TRADE       첫거래·신규 가입·미거래 고객
                - MARKETING_CONSENT 마케팅 수신 동의
                - ONLINE_JOIN       비대면·모바일·인터넷뱅킹 가입
                - RATE_COUPON       금리쿠폰
                - EVENT             이벤트·추첨
                - MAIN_BANK         주거래 실적·예적금 보유·평균잔액
                - OTHER             위 어디에도 안 맞는 조건

                규칙:
                - 우대조건이 없으면(`없음`, `해당없음`, 빈 문구) conditions 는 빈 배열입니다.
                - scope 는 그 조건이 **이 상품을 파는 금융사의** 거래를 요구하면 OWN, 아무 데나 되면 ANY 입니다.
                  `당행`, `우리은행 계좌`, `○○카드사 결제`처럼 금융사를 지정하면 OWN 입니다.
                - 가산 금리(%p)나 금액 조건은 옮기지 마세요. 어떤 종류의 조건인지만 남깁니다.
                - 같은 종류가 여러 번 나와도 한 번만 적습니다.

                우대조건: %s
                """.formatted(spclCnd);
        // **무료가 먼저, 유료는 비상용**(2026-08-21 사용자 결정).
        //
        // 이 자리는 화면 요청 안이라 오래 못 기다린다. 관문이 큐에 올리고 짧게(3초) 기다린 뒤,
        // 못 받으면 비어서 돌아온다 — 그때는 아래 유료로 가고, 그것도 실패하면 부르는 쪽의
        // **규칙 파서**가 답한다. 세 층 다 있으니 화면이 비는 일은 없다.
        var free = gateway.askNow(com.finntech.freechannel.Lane.USER_NOW,
                "preferential:" + Integer.toHexString(prompt.hashCode()), prompt);
        if (free.isPresent()) {
            List<RequiredCondition> parsedFree = parseJson(free.get());
            if (parsedFree != null) return parsedFree;
        }
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
    static List<RequiredCondition> parseJson(String text) {
        int s = text.indexOf('{'), e = text.lastIndexOf('}');
        if (s < 0 || e <= s) return null;
        try {
            Map<?, ?> m = MAPPER.readValue(text.substring(s, e + 1), Map.class);
            if (!(m.get("conditions") instanceof List<?> list)) return null;
            List<RequiredCondition> out = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> row)) continue;
                Object code = row.get("code");
                if (code == null) continue;
                out.add(new RequiredCondition(parseType(code.toString().trim()),
                        parseScope(String.valueOf(row.get("scope")).trim())));
            }
            return sorted(out);
        } catch (Exception ex) {
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

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Hashing.hex(md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
