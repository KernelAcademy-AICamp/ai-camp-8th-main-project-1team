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

    /** 상호 자체가 PG·간편결제 이름인가 — 그러면 결제처를 말해 주지 않는다. */
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
        Map<String, String> out = new TreeMap<>();
        if (!aiEnabled() || merchantNames == null || merchantNames.isEmpty()) return out;

        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(merchantNames));
        int calls = 0;
        for (int i = 0; i < distinct.size() && calls < MAX_LLM_CALLS_PER_REQUEST; i += BATCH, calls++) {
            List<String> batch = distinct.subList(i, Math.min(i + BATCH, distinct.size()));
            Map<String, String> got = callGemini(batch);
            if (got != null) out.putAll(got);
        }
        return out;
    }

    private Map<String, String> callGemini(List<String> names) {
        // 중분류 축은 대조표가 준다 — 목록을 코드에 박으면 축이 바뀔 때 조용히 갈라진다(§4-4).
        String cats = String.join(", ", mapper.midCategories());
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            list.append(i + 1).append(". ").append(names.get(i)).append('\n');
        }

        String prompt = """
                아래는 한국 카드 명세서에 찍힌 가맹점명입니다. 각각이 어떤 소비인지 분류하세요.

                분류 축(이 중 하나만 쓰세요): %s

                규칙:
                - **명백한 것만** 분류하세요. 조금이라도 모르겠으면 그 번호는 빼세요.
                  틀린 분류보다 분류하지 않는 편이 낫습니다.
                - 지점명·차량번호·영문 표기가 붙어 있어도 브랜드로 판단하세요.
                  예: "GS25 강남역점" → 편의점/잡화, "NETFLIX.COM" → 취미/여가
                - 결제대행사·간편결제 상호(토스페이먼츠, 카카오페이, NHN KCP 등)는 무엇을 샀는지
                  알 수 없으므로 빼세요.
                - 뜻을 알 수 없는 상호, 사람 이름만 있는 것, 숫자뿐인 것은 빼세요.

                설명·마크다운 없이 JSON만 출력하세요.
                형식: {"1": "편의점/잡화", "3": "식비"}   (분류한 번호만 넣습니다)

                가맹점:
                %s
                """.formatted(cats, list);

        try {
            Map<?, ?> response = restClient.post()
                    .uri("/v1beta/models/{model}:generateContent?key={key}", model, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))))
                    .retrieve()
                    .body(Map.class);
            String text = extractText(response);
            return text == null ? null : parseJson(text, names);
        } catch (Exception e) {
            // 미분류로 남을 뿐 화면이 깨지지는 않는다 — 추정은 부가 정보다.
            return null;
        }
    }

    /** 번호 → 중분류 JSON 을 이름 → 중분류로 되돌린다. 축에 없는 값은 버린다. */
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
                String cat = entry.getValue().toString().trim();
                // 모델이 축 밖의 이름을 지어낼 수 있다. 대조표에 있는 것만 받는다.
                if (mapper.midCategories().contains(cat)) out.put(names.get(idx - 1), cat);
            }
            return out;
        } catch (Exception ex) {
            return null;
        }
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
