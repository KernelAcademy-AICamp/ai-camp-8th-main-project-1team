package com.finntech.service;

import com.finntech.engine.AnalysisResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 판단은 코드가, 표현은 AI가 한다 (문서 §4 원칙 1).
 *
 * <p><b>AI는 숫자를 만들지도, 바꾸지도 않는다.</b> 이미 계산된 값을 문장으로 옮길 뿐이다.
 * 프레이밍 주의(D-06): 이것을 독창적 설계로 주장하면 안 된다 — 금융권이 설명가능성 규제 때문에
 * 룰 엔진을 쓰는 이유를 이해하고 같은 구조를 택한 것이다.
 *
 * <p><b>고정 템플릿 폴백</b>: 포기 순서 1번이 "LLM 문장 생성 → 고정 템플릿"이다(D-02).
 * API 키가 없거나 호출이 실패하면 조용히 템플릿으로 떨어져 <b>시연이 죽지 않게</b> 한다.
 *
 * <p><b>개인정보 처리방침 5번 준수</b>: 외부 AI에는 개인을 식별할 수 없는 <b>집계 수치만</b>
 * 전달하며 개별 소비 기록 원문은 전송하지 않는다 (문서 §5-3). 원칙 1을 지키면 자연히 충족된다.
 */
@Service
public class NarrativeService {

    private final String apiKey;
    private final String model;
    private final RestClient restClient;

    public NarrativeService(
            @Value("${finntech.gemini.api-key:}") String apiKey,
            @Value("${finntech.gemini.model:gemini-2.0-flash}") String model,
            @Value("${finntech.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public boolean aiEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 리포트 요약 문장. 실패 시 템플릿. */
    public Narrative summarizeReport(ReportService.ReportBody body, AnalysisResult analysis) {
        String template = reportTemplate(body);
        if (!aiEnabled()) return new Narrative(template, "TEMPLATE");

        String prompt = """
                아래는 이미 계산이 끝난 소비 분석 집계치입니다.
                숫자를 새로 만들거나 바꾸지 말고, 주어진 숫자만 사용해 2~3문장으로 요약하세요.
                과장 없이 사실만 전달하고, 금융상품 가입을 권유하지 마세요.

                총지출: %s원
                과한 소비 카테고리: %s
                잘한 소비 카테고리: %s
                """.formatted(
                body.totalSpend().toPlainString(),
                body.negative().stream().map(l -> l.displayName() + " " + l.spendPercent() + "%").toList(),
                body.positive().stream().map(l -> l.displayName() + " " + l.spendPercent() + "%").toList());

        return callGemini(prompt, template);
    }

    /** FDS 경고 문장. */
    public Narrative explainAlert(AlertService.Detection detection) {
        String template = detection.explanation();
        if (!aiEnabled()) return new Narrative(template, "TEMPLATE");

        String prompt = """
                아래는 이상소비 탐지 엔진이 이미 판정한 결과입니다.
                판정을 뒤집거나 점수를 바꾸지 말고, 사용자가 이해하기 쉽게 1~2문장으로 다시 쓰세요.
                겁주지 말고 확인을 권하는 어조로 쓰세요.

                판정 근거: %s
                """.formatted(template);

        return callGemini(prompt, template);
    }

    private Narrative callGemini(String prompt, String fallback) {
        try {
            Map<?, ?> response = restClient.post()
                    .uri("/v1beta/models/{model}:generateContent?key={key}", model, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))))
                    .retrieve()
                    .body(Map.class);

            String text = extractText(response);
            if (text == null || text.isBlank()) return new Narrative(fallback, "TEMPLATE_FALLBACK");
            return new Narrative(text.trim(), "AI");
        } catch (Exception e) {
            // 시연 중 네트워크·쿼터 문제로 화면이 비면 안 된다. 조용히 템플릿으로 떨어진다.
            return new Narrative(fallback, "TEMPLATE_FALLBACK");
        }
    }

    private String extractText(Map<?, ?> response) {
        if (response == null) return null;
        Object candidates = response.get("candidates");
        if (!(candidates instanceof List<?> list) || list.isEmpty()) return null;
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> cand)) return null;
        Object content = cand.get("content");
        if (!(content instanceof Map<?, ?> cm)) return null;
        Object parts = cm.get("parts");
        if (!(parts instanceof List<?> pl) || pl.isEmpty()) return null;
        Object p0 = pl.get(0);
        if (!(p0 instanceof Map<?, ?> pm)) return null;
        Object text = pm.get("text");
        return text == null ? null : text.toString();
    }

    private String reportTemplate(ReportService.ReportBody body) {
        String total = money(body.totalSpend());
        if (body.negative().isEmpty()) {
            return "이번 기간 총 " + total
                    + "원을 쓰셨고, 특정 카테고리에 쏠린 지출은 없었습니다. 지금 흐름을 유지해 보세요.";
        }
        ReportService.Line top = body.negative().get(0);
        return "이번 기간 총 " + total + "원을 쓰셨습니다. "
                + top.displayName() + " 지출이 전체의 " + top.spendPercent()
                + "%로 가장 큰 비중을 차지했습니다. 이 항목부터 줄여보는 건 어떨까요?";
    }

    /** 소수점과 자릿수 구분 없이 그대로 내보내면 "13310544.00원"처럼 읽히지 않는다. */
    private static String money(java.math.BigDecimal v) {
        return String.format("%,d", v.setScale(0, java.math.RoundingMode.HALF_UP).longValue());
    }

    /** source: AI | TEMPLATE | TEMPLATE_FALLBACK — 화면에 표시해 무엇이 생성했는지 밝힌다. */
    public record Narrative(String text, String source) {}
}
