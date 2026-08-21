package com.finntech.service;

import com.finntech.engine.AnalysisResult;
import com.finntech.engine.CutCandidate;
import com.finntech.engine.UserProfile;
import com.finntech.util.Redact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    // **화면에 걸린 문장 셋은 이제 Gemini 를 안 부른다**(2026-08-08). 저장해 두고 무료 통로가
    // 하루 한 번 갱신하는 구조로 옮겼다 — 화면이 6~10초를 기다리지 않고, 값도 0 이다.
    // 그래서 이 클래스에 남은 일은 **무엇을 물을지와 못 받았을 때 무엇을 보일지**뿐이다.
    // `explainAlert` 만 예외다 — 그건 그 순간의 탐지에 붙는 말이라 저장해 둘 것이 아니다.

    private static final Logger log = LoggerFactory.getLogger(NarrativeService.class);

    private final String apiKey;
    private final String model;
    private final RestClient restClient;
    /** 무료가 먼저인 문. 유료는 무료 사슬 다섯이 다 죽었을 때만 쓴다. */
    private final ModelGateway gateway;

    public NarrativeService(
            @Value("${finntech.gemini.api-key:}") String apiKey,
            @Value("${finntech.gemini.model:}") String model,
            @Value("${finntech.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl,
            ModelGateway gateway) {
        this.gateway = gateway;
        this.apiKey = apiKey;
        this.model = com.finntech.config.GeminiModels.orDefault(model);
        // 타임아웃을 준다 — `RestClient.builder()` 는 정적 팩터리라 부트 자동구성이 안 붙고,
        // 이 저장소 classpath 에서는 JDK 팩터리로 떨어져 읽기 타임아웃이 무한이 된다
        // ({@link com.finntech.util.HttpClients} 가 같은 함정을 적어 두었다). 화면 요청 안에서
        // 도는 호출이라 무한이면 톰캣 스레드가 그대로 묶인다.
        this.restClient = RestClient.builder().baseUrl(baseUrl)
                .requestFactory(com.finntech.util.HttpClients.factory(
                        java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(8)))
                .build();
    }

    public boolean aiEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 리포트 요약 <b>재료</b> — 여기서 모델을 부르지 않는다.
     *
     * <p>부르는 일은 {@link NarrativeCacheService} 가 큐를 통해 한다. 이 클래스는 <b>무엇을
     * 물을지와 못 받았을 때 무엇을 보일지</b>만 안다 — 그 둘은 화면의 사정이라 여기 있는 것이 맞고,
     * 언제 얼마나 부를지는 통로의 사정이라 큐가 안다.
     */
    public NarrativeCacheService.Request reportRequest(Long userId, ReportService.ReportBody body,
                                                       AnalysisResult analysis) {
        String template = reportTemplate(body);
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

        return new NarrativeCacheService.Request(
                userId, com.finntech.domain.NarrativeCache.Kind.REPORT, "", prompt, template);
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

        return callModel(prompt, template);
    }

    /** 절약 후보(⑤) 권유 <b>재료</b>. 개별 결제가 아니라 category2 집계치만 담는다. */
    public NarrativeCacheService.Request cutCandidateRequest(Long userId, CutCandidate c) {
        String template = cutCandidateTemplate(c);
        String prompt = """
                아래는 이미 계산이 끝난 '줄일 수 있는 소비' 후보입니다.
                숫자를 새로 만들거나 바꾸지 말고, 주어진 숫자만 사용해 1~2문장으로 부드럽게 권유하세요.
                가입 권유나 강요 없이, 실천 아이디어를 한 가지 곁들이세요.

                카테고리: %s
                유형: %s (제거가능=전액 절감 / 최적화가능=과한 부분만 절감)
                최근 지출: %s원
                예상 절감액: %s원
                """.formatted(c.category2(), c.type(),
                String.format("%,d", c.monthlySpend()), String.format("%,d", c.estimatedSaving()));

        return new NarrativeCacheService.Request(
                // **카테고리가 키의 일부다.** 절약 후보는 카테고리마다 다른 문장이라, 이것이
                // 빠지면 카페 문장이 쇼핑 화면에 뜨고 서로 덮어쓴다.
                userId, com.finntech.domain.NarrativeCache.Kind.CUT_CANDIDATE, c.category2(),
                prompt, template);
    }

    /** 소비 프로필(④) 요약 <b>재료</b>. 이상소비지수와 성분별 기여점수 등 집계치만 담는다. */
    public NarrativeCacheService.Request profileRequest(Long userId, UserProfile p) {
        String template = profileTemplate(p);
        String prompt = """
                아래는 이미 계산이 끝난 소비 프로필 집계치입니다.
                점수를 바꾸지 말고, 주어진 숫자만 사용해 2~3문장으로 요약하세요.
                겁주지 말고, 어떤 요인이 큰지와 다음 한 걸음을 제안하세요.

                이상소비지수: %d/100
                성분별 기여점수: %s
                고정 반복결제 수: %d
                습관성(루틴) 소비 수: %d
                """.formatted(p.abnormalityIndex(), p.contributionPoints(), p.fixedCount(), p.routineCount());

        return new NarrativeCacheService.Request(
                userId, com.finntech.domain.NarrativeCache.Kind.PROFILE, "", prompt, template);
    }

    /**
     * <b>무료가 먼저, 유료는 비상용</b>(2026-08-21 사용자 결정).
     *
     * <p>화면에 걸린 문장 셋은 이미 저장해 두고 무료 통로가 갱신한다(2026-08-08). 여기 남은
     * 것은 그 밖의 자리라 <b>사용자가 기다리지 않는다</b> — 못 받으면 템플릿이 나간다.
     */
    private Narrative callModel(String prompt, String fallback) {
        var free = gateway.askNow(com.finntech.freechannel.Lane.USER_BACKGROUND,
                "narrative:" + Integer.toHexString(prompt.hashCode()), prompt);
        if (free.isPresent()) return new Narrative(free.get().trim(), "AI");
        return callGemini(prompt, fallback);
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
            if (text == null || text.isBlank()) {
                log.warn("소비 서술이 템플릿으로 폴백 — 응답에 문장이 없다(형식 변경 의심)");
                return new Narrative(fallback, "TEMPLATE_FALLBACK");
            }
            return new Narrative(text.trim(), "AI");
        } catch (Exception e) {
            // 시연 중 네트워크·쿼터 문제로 화면이 비면 안 된다. 템플릿으로 떨어뜨리되 **이유는 남긴다**.
            //
            // 예전에는 이 catch가 예외를 통째로 삼켰다. 그래서 운영에서 문장이 하나도 안 나오는데도
            // 로그가 비어 있었고, 원인(`gemini-2.0-flash` 할당량 0)을 찾으려고 컨테이너에서 API를
            // **손으로 재현**해야 했다(2026-08-04). 폴백이 시연을 살리는 것과 원인을 못 보는 것은
            // 다른 문제인데 하나로 묶여 있었다 — 지킴이 쪽은 이미 §8-V에서 같은 이유로 고쳤다.
            //
            // 키가 URI 질의문자열에 실리므로 반드시 가린다.
            log.warn("소비 서술이 템플릿으로 폴백 — {}", Redact.cause(e));
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
        // negative는 '줄이면 좋은 소비'만 모아 비중 내림차순으로 정렬한 목록이다. 그 1등을 두고
        // "전체에서 가장 큰 비중"이라고 말하면 거짓이 된다 — 실제로 쇼핑 0.4% 하나만 후보인 사용자에게
        // "쇼핑이 전체의 0.4%로 가장 큰 비중"이라는 문장이 나갔다. 무엇들 중의 1등인지 밝힌다.
        // '지출'로 받아 조사를 '이'로 고정한다(카테고리 이름의 받침 유무에 문장이 흔들리지 않게).
        ReportService.Line top = body.negative().get(0);
        return "이번 기간 총 " + total + "원을 쓰셨습니다. 줄이면 좋은 소비 중에서는 "
                + top.displayName() + " 지출(전체의 " + top.spendPercent()
                + "%)이 가장 컸어요. 이 항목부터 줄여보는 건 어떨까요?";
    }

    /** 절약 후보 폴백 문장 — 코드가 만든 근거({@code reason})를 그대로 쓴다(원칙 1). */
    private String cutCandidateTemplate(CutCandidate c) {
        return c.reason() + " 이 항목부터 조금씩 줄여보는 건 어떨까요?";
    }

    /** 프로필 폴백 문장 — 최대 기여 성분과 반복결제 수를 사실대로 전한다. */
    private String profileTemplate(UserProfile p) {
        String topFactor = p.contributionPoints().entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey).orElse("특이 요인 없음");
        return "이상소비지수는 " + p.abnormalityIndex() + "점입니다. 가장 큰 요인은 '" + topFactor
                + "'이고, 고정 반복결제 " + p.fixedCount() + "건·습관성 소비 " + p.routineCount() + "건이 확인됐어요.";
    }

    /** 소수점과 자릿수 구분 없이 그대로 내보내면 "13310544.00원"처럼 읽히지 않는다. */
    private static String money(java.math.BigDecimal v) {
        return String.format("%,d", v.setScale(0, java.math.RoundingMode.HALF_UP).longValue());
    }

    /** source: AI | TEMPLATE | TEMPLATE_FALLBACK — 화면에 표시해 무엇이 생성했는지 밝힌다. */
    public record Narrative(String text, String source) {}
}
