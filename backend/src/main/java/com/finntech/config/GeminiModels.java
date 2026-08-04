package com.finntech.config;

/**
 * Gemini 모델 이름의 <b>단일 출처</b>.
 *
 * <h2>왜 기본값을 yml 이 아니라 여기 두는가</h2>
 *
 * <p>Spring 의 {@code ${VAR:기본값}} 은 <b>변수가 없을 때만</b> 기본값을 쓴다. 변수가 있는데
 * <b>비어 있으면</b> 빈 문자열이 그대로 값이 된다 — 실측:
 *
 * <pre>
 *   GEMINI_MODEL 이 빈 값  →  ''        ← 기본값이 안 먹는다
 *   GEMINI_MODEL 이 없음   →  기본값
 * </pre>
 *
 * <p>그런데 compose 는 {@code GEMINI_MODEL: ${GEMINI_MODEL:-}} 로 <b>빈 값을 넣어 준다</b>
 * (변수를 지우는 문법이 없다). 그래서 yml 에만 기본값을 두면 배포된 컨테이너에서 모델 이름이
 * {@code ""} 가 되고, 요청 URI 가 {@code /v1beta/models/:generateContent} 가 되어 404 → 폴백이다.
 * <b>키도 네트워크도 멀쩡한데 문장만 안 나온다</b> — 2026-08-04에 이미 한 번 겪은 그 증상 그대로다(§8-X).
 *
 * <p>그래서 yml 은 환경변수를 <b>그대로 넘기기만</b> 하고({@code model: ${GEMINI_MODEL:}}),
 * "비었으면 무엇을 쓸지"는 여기서 정한다. 이름이 코드 한 곳에만 있으므로 yml·compose·자바가
 * 서로 어긋날 자리가 없다.
 */
public final class GeminiModels {

    /**
     * 기본 모델.
     *
     * <p>{@code gemini-2.0-flash} 는 이 키에 배정이 0이었고({@code 429 limit: 0}),
     * {@code gemini-2.5-flash-lite} 는 신규 키에 이미 닫혔다({@code 404 no longer available to new users}).
     * 실측으로 문장이 나오는 것을 골랐다. 하는 일이 집계 수치를 한 문장으로 옮기는 것뿐이라
     * lite 로 충분하다(full flash 와 품질 차이 없음).
     *
     * <p>이것도 언젠가 은퇴한다. 그때는 <b>배포 없이</b> 서버 {@code .env} 의 {@code GEMINI_MODEL} 로
     * 갈아탄다 — 그러라고 환경변수로 빼 뒀다.
     */
    public static final String DEFAULT = "gemini-3.1-flash-lite";

    private GeminiModels() {}

    /** 설정값이 비었으면({@code null}·공백·빈 문자열) 기본 모델. 앞뒤 공백은 걷어낸다. */
    public static String orDefault(String configured) {
        return configured == null || configured.isBlank() ? DEFAULT : configured.trim();
    }
}
