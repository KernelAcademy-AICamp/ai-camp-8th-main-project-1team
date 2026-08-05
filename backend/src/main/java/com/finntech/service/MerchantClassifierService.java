package com.finntech.service;

import com.finntech.config.GeminiModels;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.util.HttpClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 미분류 결제를 <b>가맹점명만 보고</b> 중분류로 추정한다 — 실데이터에는 업종코드가 없기 때문이다.
 *
 * <p><b>추정은 판정에 쓰지 않는다.</b> 결과는 {@code user_payment.category2_llm} 에만 담기고
 * {@code category2} 는 건드리지 않는다. {@code WasteScoringService} 가 {@code category2} 를 직접
 * 읽어 낭비를 판정하므로, 덮는 순간 <i>"판단은 설명가능한 모델이, 표현은 AI가"</i>(마스터 §4-1)가
 * 깨진다. 화면에는 "AI 추정" 배지로 보이고, <b>사람이 확인해야</b> 확정 분류가 된다
 * ({@link MerchantCategoryService#confirm}).
 *
 * <p>브랜드 일반화가 이쪽의 몫이다 — {@code GS25 역삼점} 을 처음 봐도 편의점인 줄 아는 것.
 * 사전({@link MerchantCategoryService})은 "같은 점포를 두 번 묻지 않는" 일을 맡는다.
 */
@Service
public class MerchantClassifierService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 한 번에 물어보는 가맹점 수. 가맹점명은 짧아 한 요청에 여럿을 담을 수 있고,
     * 건당 호출하면 명세서 한 장에 수백 번을 부르게 된다.
     */
    private static final int BATCH = 40;

    /**
     * 한 요청에서 LLM 을 부를 수 있는 최대 횟수. {@code EligibilityLabelService} 와 같은 장치다 —
     * 사용자 요청 안에서 불리므로 최악 지연이 유계여야 한다. 남는 것은 다음 요청에서 처리된다.
     */
    private static final int MAX_LLM_CALLS_PER_REQUEST = 5;

    /**
     * <b>다시 물을 때</b>의 묶음 크기. 첫 판에서 못 잡은 것을 작게 나눠 한 번 더 묻는다.
     *
     * <p>모델은 알고 있는데 <b>큰 묶음에서 흘린다.</b> 2026-08-05 실측 — 넷플릭스를 단독으로
     * 물으면 곧바로 맞히는데(`영상물 제공 서비스업…`), 40개에 섞으면 답을 빼먹었다.
     * 묶음이 작으면 그 일이 줄어든다.
     */
    private static final int RETRY_BATCH = 5;

    private final IndustryCategoryMapper mapper;
    private final String apiKey;
    private final String model;
    private final RestClient restClient;

    public MerchantClassifierService(
            IndustryCategoryMapper mapper,
            @Value("${finntech.gemini.api-key:}") String apiKey,
            @Value("${finntech.gemini.model:}") String model,
            @Value("${finntech.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl) {
        this.mapper = mapper;
        this.apiKey = apiKey;
        this.model = GeminiModels.orDefault(model);
        this.restClient = RestClient.builder().baseUrl(baseUrl)
                .requestFactory(HttpClients.factory(Duration.ofSeconds(3), Duration.ofSeconds(8)))
                .build();
    }

    public boolean aiEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 물어볼 가치가 있는 가맹점인가.
     *
     * <p><b>PG 는 물어봐도 소용없다.</b> 가맹점명이 PG 상호면 무엇을 샀는지 알 수 없다.
     * 걸러내지 않으면 미분류의 상당수를 차지하는 간편결제 건을 통째로 LLM 에 보내게 된다.
     * 다만 <b>가맹점명이 실제 가게면</b>({@code 삼성물산리조트(주)에버랜드}) 사업자번호가 PG 라도
     * 그 이름으로 물어본다 — 이름이 답을 들고 있기 때문이다.
     */
    public boolean worthAsking(String merchantName, String businessNumber) {
        if (merchantName == null || merchantName.isBlank()) return false;
        String n = merchantName.trim();
        if (n.length() < 2) return false;
        // businessNumber 는 **일부러 보지 않는다.** 인자로 받는 것은 그 사실을 못박기 위해서다 —
        // 여기에 `isPaymentAgency(businessNumber)` 를 넣고 싶어지는데, 넣으면 PG 를 거친 결제가
        // 통째로 빠진다. 그런데 그것들이야말로 이름에 답이 있는 결제다
        // (KG모빌리언스 번호 + `삼성물산리조트(주)에버랜드`). 번호가 PG 라는 것은
        // "업종코드를 믿지 말라"는 뜻이지 "이름도 쓸모없다"는 뜻이 아니다.
        return !isAgencyName(n);
    }

    /**
     * 상호 자체가 결제대행사 이름인가 — <b>그러면 무엇을 샀는지 원리적으로 알 수 없다.</b>
     *
     * <p>판정은 <b>번호가 아니라 이름</b>으로 한다. {@code Apple} 은 카카오페이 번호로 들어와도
     * 이름이 무엇을 샀는지 말해 주고, 반대로 상호가 {@code 네이버페이} 면 카드사가 준 정보가
     * 그것뿐이라 앱이 알 길이 없다. 이 둘을 똑같이 '카테고리없음'에 두면 사용자는
     * <i>"앱이 못 하는 것"</i>과 <i>"내가 알려주면 되는 것"</i>을 구분할 수 없다.
     *
     * <p>이 값은 <b>표시에만</b> 쓴다 — 카테고리 축을 늘리지 않는다(마스터 §4 원칙 4).
     */
    public boolean isPaymentAgencyMerchant(String merchantName) {
        return merchantName != null && !merchantName.isBlank() && isAgencyName(merchantName.trim());
    }

    private boolean isAgencyName(String name) {
        String n = name.replaceAll("[\\s()（）주\\-_.]", "").toUpperCase();
        for (String pg : mapper.paymentAgencyNames()) {
            String p = pg.replaceAll("[\\s()（）주\\-_.]", "").toUpperCase();
            if (!p.isEmpty() && n.contains(p)) return true;
        }
        return false;
    }

    /**
     * 가맹점명들을 중분류로 추정한다. <b>명백하지 않으면 그 이름은 결과에 없다</b> —
     * 억지로 붙이면 "AI 추정"이 오히려 방해가 된다.
     *
     * @return 가맹점명 → 중분류. 정렬 고정(§4-3 재현성).
     */
    public Map<String, String> classify(List<String> merchantNames) {
        return classify(merchantNames, java.util.Set.of());
    }

    /**
     * 가맹점명들을 중분류로 추정한다 — <b>두 판으로 나눈다.</b>
     *
     * <p>첫 판은 40개씩 묶어 훑는다. 그런데 모델은 <b>알고 있는데도 큰 묶음에서 흘린다</b>
     * (2026-08-05 실측: 넷플릭스를 단독으로 물으면 맞히는데 40개에 섞으면 빼먹었다).
     * 그래서 <b>못 잡은 것 중 중요한 것만</b> 작게 나눠 한 번 더 묻는다.
     *
     * <p>무엇이 중요한가는 여기서 정하지 않는다 — 결제 건수·금액을 아는 것은 부르는 쪽이다.
     * 1건 200원짜리 카드 수수료를 다시 묻는 데 호출을 쓰지 않기 위한 구분이다.
     *
     * @param important 못 잡았을 때 <b>다시 물을 값어치가 있는</b> 가맹점명
     * @return 가맹점명 → 중분류. 정렬 고정(§4-3 재현성).
     */
    public Map<String, String> classify(List<String> merchantNames, java.util.Set<String> important) {
        Map<String, String> out = new TreeMap<>();
        if (!aiEnabled() || merchantNames == null || merchantNames.isEmpty()) return out;

        // 법인격 표기만 다른 같은 가맹점은 **한 번만 묻는다.** '(주)우아한형제들'과 '우아한형제들'을
        // 따로 물으면 호출이 두 배로 들 뿐 아니라, 한쪽만 답을 받아 같은 가게가 갈린다 —
        // 2026-08-05 실측에서 실제로 갈렸다(하나는 쇼핑, 하나는 미분류). 이 명세서에서 이렇게
        // 갈린 곳이 4곳 54만원이었고, 그중 배달앱 한 곳이 26만원이다.
        Map<String, List<String>> variants = new LinkedHashMap<>();
        for (String n : merchantNames) {
            if (n == null || n.isBlank()) continue;
            variants.computeIfAbsent(corporateFormStripped(n), k -> new ArrayList<>()).add(n);
        }
        List<String> distinct = variants.values().stream().map(v -> v.get(0)).toList();

        Map<String, String> byRepresentative = new TreeMap<>();
        int calls = 0;
        for (int i = 0; i < distinct.size() && calls < MAX_LLM_CALLS_PER_REQUEST; i += BATCH, calls++) {
            Map<String, String> got = callGemini(distinct.subList(i, Math.min(i + BATCH, distinct.size())));
            if (got != null) byRepresentative.putAll(got);
        }

        // 두 번째 판 — 못 잡은 것 중 중요한 것만, 작게.
        // 대표 하나가 중요하지 않아도 **표기가 다른 형제 중 하나라도** 중요하면 다시 묻는다.
        List<String> retry = new ArrayList<>();
        for (var e : variants.entrySet()) {
            String representative = e.getValue().get(0);
            if (!byRepresentative.containsKey(representative)
                    && e.getValue().stream().anyMatch(important::contains)) {
                retry.add(representative);
            }
        }
        for (int i = 0; i < retry.size() && calls < MAX_LLM_CALLS_PER_REQUEST; i += RETRY_BATCH, calls++) {
            Map<String, String> got = callGemini(retry.subList(i, Math.min(i + RETRY_BATCH, retry.size())));
            if (got != null) byRepresentative.putAll(got);
        }

        // 대표가 받은 답을 **표기가 다른 형제 전부**에 돌려준다.
        for (List<String> group : variants.values()) {
            String answer = byRepresentative.get(group.get(0));
            if (answer != null) group.forEach(n -> out.put(n, answer));
        }
        return out;
    }

    /**
     * 법인격 표기를 걷어낸 비교용 이름 — {@code (주)우아한형제들}·{@code 주식회사 우아한형제들}·
     * {@code 우아한형제들}이 <b>같은 가맹점</b>임을 알아보기 위한 것이다.
     *
     * <p>사전의 키는 여전히 <b>풀네임</b>이다. 여기서 지우는 것은 "누구에게 물을지"를 정할 때뿐이라,
     * 지점명({@code GS25 강남역점})처럼 실제로 다른 점포를 뭉뚱그릴 위험이 없다.
     */
    static String corporateFormStripped(String name) {
        return name.replaceAll("\\(\\s*주\\s*\\)|\\(\\s*유\\s*\\)|㈜|㈠|주식회사|유한회사|합자회사", "")
                .replaceAll("\\s+", "")
                .toUpperCase();
    }

    private Map<String, String> callGemini(List<String> names) {
        // **중분류가 아니라 업종을 묻는다.** 모델이 우리 축을 직접 고르면 축 배정까지 AI 가 하는
        // 셈이라 원칙 1 과 어긋나고, 표를 고쳐도 모델의 옛 답은 안 따라온다. 업종으로 받으면
        // 모델은 "이 가게가 무엇을 파는가"라는 사실만 말하고 축은 우리 표가 정한다.
        //
        // 2026-08-05 전수 대조(실데이터 86종): 중분류를 직접 묻는 방식 36종 → 업종을 묻는 방식
        // **55종**. 둘 다 답한 6종 중 4종에서 업종 쪽이 우리 표와 일치했다
        // (올리브영 쇼핑→미용, 교보문고 쇼핑→취미/여가).
        //
        // 처음엔 오히려 21종으로 **떨어졌다.** "명백한 것만, 틀리느니 답하지 마라"를 377개
        // 목록과 함께 주니 모델이 과하게 보수적이 됐다. **"가장 가까운 업종을 고르라"**로
        // 바꾸자 55종이 됐다 — 규칙 한 줄이 커버리지를 두 배 넘게 갈랐다.
        StringBuilder catalog = new StringBuilder();
        mapper.industryNamesByMid().forEach((mid, list) ->
                catalog.append('[').append(mid).append("] ").append(String.join(" · ", list)).append('\n'));

        StringBuilder list = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            list.append(i + 1).append(". ").append(names.get(i)).append('\n');
        }

        String prompt = """
                아래는 한국 카드 명세서에 찍힌 가맹점명입니다. 각 가맹점이 어느 업종인지 고르세요.

                업종 목록입니다. 대괄호는 그 업종이 속한 소비 분류이고, 답에는 업종 이름만 쓰세요.

                %s
                - 가맹점이 무엇을 파는지 알겠다면 **목록에서 가장 가까운 업종**을 고르세요.
                  딱 맞는 것이 없어도 가장 가까운 것을 고르면 됩니다.
                - **해외 가맹점도 마찬가지입니다.** 영문·로마자 상호라도 무엇을 파는 곳인지
                  알겠다면 고르세요(예: 공항 면세점, 해외 호텔, 해외 항공사).
                - 결제대행사 상호(토스페이먼츠, 나이스페이먼츠, KG이니시스, 네이버페이,
                  카카오페이 등)는 **여러 가게의 결제를 대신 처리하는 회사**라 무엇을 샀는지
                  알 수 없으므로 빼세요.
                - 다만 **한 브랜드의 자체 결제 수단**은 그 브랜드로 판단하세요 — 이름에 '페이'가
                  붙었다고 빼면 안 됩니다. '컬리페이'는 마켓컬리에서 산 것이고,
                  '무신사페이먼츠'는 무신사에서 산 것입니다.
                - 뜻을 알 수 없는 상호, 사람 이름만 있는 것, 숫자뿐인 것은 빼세요.
                - 목록에 있는 이름을 **글자 그대로** 쓰세요.

                설명·마크다운 없이 JSON만 출력하세요.
                형식: {"1": "체인화 편의점", "3": "한식 일반 음식점업"}

                가맹점:
                %s
                """.formatted(catalog, list);

        try {
            Map<?, ?> response = restClient.post()
                    .uri("/v1beta/models/{model}:generateContent?key={key}", model, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    // temperature 0 — **같은 명세서를 두 번 넣으면 같은 답이 나와야 한다.**
                    // 기본 표집 온도로 두면 실행마다 39·55·59·61종으로 흔들렸다(2026-08-05 실측).
                    // 재현성은 이 저장소의 설계 원칙이다(§4-3).
                    .body(Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                                 "generationConfig", Map.of("temperature", 0)))
                    .retrieve()
                    .body(Map.class);
            String text = extractText(response);
            return text == null ? null : parseJson(text, names);
        } catch (Exception e) {
            // 미분류로 남을 뿐 화면이 깨지지는 않는다 — 추정은 부가 정보다.
            return null;
        }
    }

    /**
     * 번호 → <b>업종 이름</b> JSON 을 가맹점명 → 중분류로 되돌린다.
     *
     * <p>모델은 업종만 답하고 축 배정은 우리 표가 한다. 못 옮기는 답은 버린다 —
     * 지어낸 이름이 들어오지 못한다.
     */
    Map<String, String> parseJson(String text, List<String> names) {
        int s = text.indexOf('{'), e = text.lastIndexOf('}');
        if (s < 0 || e <= s) return null;
        Map<String, String> out = new TreeMap<>();
        try {
            Map<?, ?> m = MAPPER.readValue(text.substring(s, e + 1), Map.class);
            for (var entry : m.entrySet()) {
                int idx;
                try {
                    idx = Integer.parseInt(entry.getKey().toString().trim());
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (idx < 1 || idx > names.size() || entry.getValue() == null) continue;
                String mid = toMid(entry.getValue().toString());
                if (mid != null) out.put(names.get(idx - 1), mid);
            }
            return out;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 모델이 답한 <b>업종 이름</b>을 우리 중분류로 옮긴다. 못 옮기면 {@code null} — 버린다.
     *
     * <p>세 단계로 본다.
     * <ol>
     *   <li>대조표에 <b>정확히</b> 있는 업종 이름</li>
     *   <li>중분류를 곧장 답한 경우(모델이 대괄호 안의 것을 그대로 쓰기도 한다)</li>
     *   <li><b>근사 일치</b> — 목록 밖 답의 대부분이 "거의 맞는" 이름이다. 2026-08-05 실측에서
     *       6건이 버려졌는데 {@code 기타 상품 전문 소매업}(우리 것은 {@code 그 외 기타 분류
     *       안된 상품 전문 소매업}), {@code 화장품, 비nu 및 방향제 소매업}(오타) 처럼 회수
     *       가능한 것이었다. 공백·쉼표를 지우고 <b>한쪽이 다른 쪽을 품으면</b> 같은 것으로 본다.</li>
     * </ol>
     *
     * <p>근사 일치는 <b>후보가 하나일 때만</b> 받는다. 여럿이면 어느 중분류인지 알 수 없어
     * 넘겨짚는 셈이 된다 — 모르는 것을 아는 척하지 않는다.
     */
    String toMid(String answer) {
        if (answer == null || answer.isBlank()) return null;
        String a = answer.trim();

        String exact = mapper.midOfIndustryName(a);
        if (!IndustryCategoryMapper.UNCLASSIFIED.equals(exact)) return exact;
        if (mapper.midCategories().contains(a)) return a;          // 중분류를 곧장 답한 경우

        String key = squash(a);
        if (key.isEmpty()) return null;
        String hit = null;
        for (var e : mapper.industryNamesByMid().entrySet()) {
            for (String name : e.getValue()) {
                String n = squash(name);
                if (!n.contains(key) && !key.contains(n)) continue;
                if (hit != null && !hit.equals(e.getKey())) return null;   // 갈리면 버린다
                hit = e.getKey();
            }
        }
        return hit;
    }

    /** 비교용 — 공백·쉼표·괄호를 지운다. 표기 차이로 같은 업종이 남이 되지 않게. */
    private static String squash(String s) {
        return s == null ? "" : s.replaceAll("[\\s,()（）·]", "");
    }

    @SuppressWarnings("unchecked")
    private static String extractText(Map<?, ?> response) {
        if (response == null) return null;
        try {
            var candidates = (List<Map<String, Object>>) response.get("candidates");
            if (candidates == null || candidates.isEmpty()) return null;
            var content = (Map<String, Object>) candidates.get(0).get("content");
            var parts = (List<Map<String, Object>>) content.get("parts");
            if (parts == null || parts.isEmpty()) return null;
            Object t = parts.get(0).get("text");
            return t == null ? null : t.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
