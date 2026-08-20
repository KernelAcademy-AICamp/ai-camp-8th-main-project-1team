package com.finntech.guardian;

import com.finntech.guardian.domain.GuardianEnums.PhrasingMode;
import com.finntech.guardian.domain.GuardianEnums.Tone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 지킴이의 문장 — <b>판단은 규칙이, 표현은 AI가</b> (마스터 §4 원칙 1 · 설계서 §5).
 *
 * <p>규칙 엔진이 이미 "보낼지 말지"와 "무슨 숫자를"까지 다 정한 뒤에야 이 클래스가 불린다.
 * LLM의 유일한 임무는 주어진 숫자를 문장으로 옮기는 것이다 — 계산도, 판단도, 추정도 하지 않는다.
 *
 * <p><b>폴백이 먼저다(설계서 §5.4).</b> {@link GuardianCopy#fallback}으로 모든 구간을 먼저 완성하고,
 * LLM은 맨 마지막에 얹는다. 새벽 배치는 전 사용자 분량이 한꺼번에 생성돼 호출이 몰리므로
 * 폴백이 특히 중요한 구간이다. 실패는 조용히 폴백으로 떨어지고 {@code isFallback}에 기록된다
 * (폴백 사용률 목표 5% 이하).
 *
 * <p>기존 {@code NarrativeService}와 같은 구조·같은 Gemini 설정을 쓴다. 다만 지킴이는
 * 케이스별 톤·화법 지침이 붙는 전용 시스템 프롬프트가 필요해 분리했다.
 *
 * <p><b>개인정보(마스터 §4 원칙 1).</b> 외부 AI에는 <b>집계 수치와 카테고리명만</b> 보낸다.
 * 가맹점 이름·개별 결제 원문은 프롬프트에 넣지 않는다.
 */
@Service
public class GuardianNarrative {

    private static final Logger log = LoggerFactory.getLogger(GuardianNarrative.class);

    /**
     * 케이스별 톤 지침 뒤에 붙는 공통 규칙. 설계서 §5의 금지 항목을 그대로 옮겼다.
     *
     * <p>※ 원문 한글이 인코딩 유실로 복원 불가여서, 설계서에서 복원된 <b>규칙 목록</b>에 맞춰 새로 썼다
     * ({@link GuardianCopy} 클래스 주석 참고).
     */
    static final String SYSTEM_PROMPT = """
            당신은 소비 다이어트 앱의 '지킴이'입니다. 사용자가 스스로 정한 절약 목표를
            지키도록 곁에서 돕는 역할입니다.

            [당신의 유일한 임무]
            규칙 엔진이 이미 "메시지를 보내기로" 결정한 건에 대해, 제목과 본문을 씁니다.

            [절대 하지 않는 것]
            1. 계산하지 마세요. 금액·비율·일수는 아래 numbers에 주어진 문자열을 그대로 쓰세요.
               주어지지 않은 숫자를 새로 만들거나 추정하지 마세요.
            2. 판단하지 마세요. 과소비인지 아닌지, 목표를 달성했는지, 어떤 사물을 줄지는
               이미 정해져 있습니다.
            3. 비난하지 마세요. 금지 표현: "또", "역시", "이번에도", "낭비", "충동적",
               "참으세요", "안 됩니다", "실패", "포기", "이러다", "습관을 고쳐야".
            4. 과장하지 마세요. 느낌표는 문장당 최대 1개, 이모지는 쓰지 않습니다.
            5. 사용자를 평가하지 마세요. 지적은 '패턴'에 대해서만 하고 '사람'에 대해서는
               하지 않습니다. ("금요일 저녁에 배달이 몰려요" O / "충동적이시네요" X)
            6. 확률·포인트 잔액·사물 획득 가능성을 언급하지 마세요. 주어지지 않았습니다.

            [화법 — phrasing_mode]
            TENTATIVE 일 때:
              이 결제는 사용자가 24시간 안에 되돌릴 수 있습니다. 아직 확정되지 않았으므로
              단정하지 마세요.
              - 일어난 일은 결제 사실까지만 쓰세요.
                O "배달 32,000원 결제가 들어왔어요"    X "배달에 32,000원 썼어요"
              - 결과 숫자는 조건부로 감싸세요.
                O "챌린지에 넣으면 118,000원 남아요"   X "118,000원 남았어요"
              - 항상 '지출'이 아니라 '결제'라고 쓰세요.
              - 금지: 썼어요 / 남았어요 / 넘었어요 / 소진됐어요 같은 완료 단정형.

            DEFINITIVE 일 때:
              이미 확정된 사실입니다. 조건부로 쓰지 마세요 — 알려주기만 합니다.
              - 금지: ~인 것 같아요 / ~할 수도 있어요 / 아마 / ~로 보여요.

            [고정구 — 반복해도 되는 표현]
            아래 표현은 매 알림에서 써야 하는 고정구입니다.
            recent_key_phrases에 들어 있더라도 그대로 다시 쓰세요.
              "결제가 들어왔어요" / "챌린지에 넣으면" / "이 결제까지 넣으면"
              "들어온 결제가" / "챌린지랑 상관없어요" / "알려줘서 고마워요"

            [톤 지침]
            - soft_reminder    : 사실 전달 + 여유 있음을 알려주는 마무리. 2문장.
            - pattern_hint     : 관찰한 패턴 1개 + 구체적 대안 1개. 3문장 이내. 명령형 금지.
            - reward_warning   : 남은 금액 + 보상과의 연결. 2문장. 위협조 금지.
            - fact_reset       : 사실 통보 + 지금까지 확보한 성과 + 다음 선택지 제시. 3문장.
                                 "실패"라는 단어를 쓰지 말고 "이번 회차는 여기까지"로 표현.
            - praise           : 짧은 인정 + 이 페이스의 결과. 2문장. 추켜세우지 않게.
            - neutral_ask      : 질문만. 평가·조언 금지. 1문장.
            - nudge_ahead      : 아직 일어나지 않은 일이므로 단정하지 말 것. 질문으로 끝낼 것.
            - morning_ceremony : 어제의 결과와 오늘 도착한 사물을 잇는 1~2문장.
                                 사물 이름을 반드시 언급. 훈계·다짐 요구·내일에 대한 압박 금지.
            - weekly_recap     : 지난주 요약 3문장. 지출이 0원이어도 칭찬으로 쓰지 않는다.

            [문체]
            - tone_preference=casual이면 해요체, formal이면 합니다체.
            - 제목 20자 이내, 본문 90자 이내.
            - recent_key_phrases에 있는 표현은 다시 쓰지 마세요(고정구는 예외).

            [출력]
            아래 JSON만 출력합니다. 다른 텍스트를 덧붙이지 마세요.
            { "title": "...", "body": "...", "key_phrases": ["...", "..."] }
            key_phrases에는 이번 문장에서 쓴 특징적 표현 2~3개를 넣습니다.
            """;

    private final String apiKey;
    private final String model;
    private final RestClient restClient;

    public GuardianNarrative(
            @Value("${finntech.gemini.api-key:}") String apiKey,
            @Value("${finntech.gemini.model:}") String model,
            @Value("${finntech.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl) {
        this.apiKey = apiKey;
        this.model = com.finntech.config.GeminiModels.orDefault(model);
        // **타임아웃이 없으면 배치가 영구히 선다.** 이 호출은 `GuardianService.ingest` 의
        // `@Transactional` 안에서 일어나고, 그 ingest 를 5분 배치가 부른다. 스케줄러 스레드는
        // 기본 하나라 여기서 응답이 안 오면 ① 커넥션 하나가 무한 점유되고 ② 뒤 사용자의
        // 동기화가 영영 안 돌며 ③ 지킴이 배치·04시 파기 크론까지 같이 선다. 그리고 그 방아쇠는
        // 대개 더미의 결제다 — 실측으로 문장 호출 14건 중 13건이 더미 몫이었다(2026-08-07 재감사).
        // `RestClient.builder()` 는 정적 팩터리라 부트 자동구성이 안 붙는다({@link HttpClients}).
        this.restClient = RestClient.builder().baseUrl(baseUrl)
                .requestFactory(com.finntech.util.HttpClients.factory(
                        java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(8)))
                .build();
    }

    public boolean aiEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 지킴이가 낸 한 건의 문장. {@code fallback=true}면 LLM이 아니라 고정 템플릿이 만든 것이다. */
    public record Message(String title, String body, List<String> keyPhrases, boolean fallback) {}

    /**
     * <b>모델을 부르지 않고</b> 규칙이 만든 문장만 낸다 — 폴백이 먼저인 구조의 그 폴백이다.
     *
     * <p>알림을 <b>먼저 저장하고 나중에 갈아 끼우는</b> 길이 이 메서드를 쓴다
     * ({@code GuardianSentenceQueue}). 화면이 LLM 을 기다리지 않으려면 지금 당장 보여줄
     * 문장이 있어야 하고, 그것을 만드는 자리가 여기다.
     */
    public Message template(String caseId, Map<String, Object> numbers) {
        return new Message(
                GuardianCopy.fallbackTitle(caseId, numbers),
                GuardianCopy.fallback(caseId, numbers),
                GuardianCopy.fallbackKeyPhrases(caseId),
                true);
    }

    /**
     * 케이스 하나의 문장을 만든다.
     *
     * @param caseId           C1..C14 · W1 · M1
     * @param tone             케이스가 정한 톤
     * @param phrasingMode     되돌릴 수 있으면 TENTATIVE
     * @param numbers          이미 계산이 끝난 값들. LLM은 여기 있는 것만 쓴다.
     * @param recentKeyPhrases 최근 쓴 표현 — 반복을 피하게 한다(고정구는 예외)
     */
    public Message compose(String caseId, Tone tone, PhrasingMode phrasingMode,
                           Map<String, Object> numbers, List<String> recentKeyPhrases,
                           boolean formalTone, boolean allowAi) {
        Message template = template(caseId, numbers);
        // **더미에는 Gemini 를 안 부른다**(사용자 규칙 2026-08-08). 생성기가 만든 결제에 대해
        // 문장을 지어 내는 데 유료 호출을 쓸 이유가 없다 — 실측으로 문장 호출 14건 중 13건이
        // 더미 몫이었다. 폴백이 먼저인 구조라 막아도 화면은 그대로 템플릿 문장이 나간다.
        if (!allowAi || !aiEnabled()) return template;

        String prompt = SYSTEM_PROMPT + """

                ---
                case_id: %s
                tone: %s
                phrasing_mode: %s
                tone_preference: %s
                must_include_numbers: %s
                recent_key_phrases: %s
                reference_sentence: %s
                """.formatted(
                caseId,
                tone == null ? "" : tone.wire(),
                phrasingMode == null ? "" : phrasingMode.name(),
                formalTone ? "formal" : "casual",
                numbers,
                recentKeyPhrases == null ? List.of() : recentKeyPhrases,
                template.body());

        return callGemini(prompt, template);
    }

    private Message callGemini(String prompt, Message fallback) {
        try {
            Map<?, ?> response = restClient.post()
                    .uri("/v1beta/models/{model}:generateContent?key={key}", model, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))))
                    .retrieve()
                    .body(Map.class);

            String text = extractText(response);
            if (text == null || text.isBlank()) return fellBack("빈 응답", null, fallback);

            Message parsed = parseJson(text);
            // 길이 위반은 프롬프트가 아니라 코드가 막는다 — LLM이 지키리라 믿고 두면 화면이 깨진다.
            if (parsed == null) return fellBack("JSON 파싱 실패", null, fallback);
            if (parsed.title().length() > GuardianCopy.MAX_TITLE_LEN
                    || parsed.body().length() > GuardianCopy.MAX_BODY_LEN) {
                return fellBack("길이 초과(제목 " + parsed.title().length()
                        + " / 본문 " + parsed.body().length() + ")", null, fallback);
            }
            return parsed;
        } catch (Exception e) {
            // 시연 중 네트워크·쿼터 문제로 알림이 비면 안 된다. 문장은 템플릿으로 떨어지되,
            // <b>이유는 남긴다</b> — 예전에는 조용히 삼켜서 폴백률이 100%여도 원인을 알 수 없었다.
            return fellBack("호출 실패", e, fallback);
        }
    }

    /**
     * 템플릿으로 떨어진 사실과 <b>그 이유</b>를 남긴다.
     *
     * <p>예전에는 {@code catch}가 예외를 통째로 삼켜서, 폴백률이 100%로 나와도
     * 키가 없는 건지·쿼터가 끊긴 건지·응답 형식이 바뀐 건지 알 방법이 없었다.
     * 시연이 안 죽는 것과 원인을 못 보는 것은 <b>다른 문제</b>인데 하나로 묶여 있었다.
     * (`/api/ops/health` 가 폴백률을 세고, 이 로그가 그 이유를 댄다. 2026-08-02)
     *
     * <p>WARN이다 — 오류는 아니지만(설계된 폴백) 목표가 5% 이하라 자주 뜨면 봐야 한다.
     */
    private Message fellBack(String why, Exception cause, Message fallback) {
        // 인증키가 URI 질의문자열에 실리고, 연결 실패 예외는 메시지에 URI를 통째로 담는다 —
        // 사유를 그대로 찍으면 로그 파일에 키가 박힌다. 사유는 남기되 키만 지운다.
        log.warn("지킴이 문장 생성이 템플릿으로 폴백 — {}{}", why,
                cause == null ? "" : ": " + com.finntech.util.Redact.cause(cause));
        return fallback;
    }

    /** 코드펜스로 감싸 오는 경우가 잦아 앞뒤를 걷어내고 중괄호 구간만 읽는다. */
    private Message parseJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        String json = raw.substring(start, end + 1);
        String title = extractField(json, "title");
        String body = extractField(json, "body");
        if (title == null || body == null || title.isBlank() || body.isBlank()) return null;
        return new Message(title, body, extractArray(json, "key_phrases"), false);
    }

    private static String extractField(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\n", " ").trim() : null;
    }

    private static List<String> extractArray(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]*)\\]")
                .matcher(json);
        if (!m.find()) return List.of();
        return java.util.Arrays.stream(m.group(1).split(","))
                .map(s -> s.trim().replaceAll("^\"|\"$", "").trim())
                .filter(s -> !s.isEmpty())
                .toList();
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
}
