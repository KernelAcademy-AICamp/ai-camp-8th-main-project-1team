package com.finntech.util;

import java.util.regex.Pattern;

/**
 * 로그로 나가면 안 되는 값을 가린다.
 *
 * <p><b>왜 필요한가.</b> Gemini는 인증키를 <b>URI 질의문자열</b>에 싣는다
 * ({@code ...:generateContent?key=<KEY>}). 연결 실패 계열 예외({@code ResourceAccessException})는
 * 메시지에 <b>URI를 통째로</b> 담으므로, 폴백 사유를 그대로 로그에 남기면 키가 로그 파일에 박힌다.
 *
 * <p>로그는 사람이 보라고 남기는 것이고 사람이 보는 곳에는 키가 없어야 한다. 그래서 사유는 남기되
 * 키만 지운다 — <b>둘 중 하나를 포기하지 않는다.</b>
 */
public final class Redact {

    /** {@code key=...} 뒤의 값. 구분자(&·공백·따옴표·닫는 괄호)를 만날 때까지가 값이다. */
    private static final Pattern QUERY_KEY = Pattern.compile("(?i)([?&](?:key|api[_-]?key|auth)=)[^&\\s\"'>\\])]+");

    private Redact() {}

    /**
     * 질의문자열에 실린 인증값을 {@code [REDACTED]} 로 바꾼다. {@code null} 은 {@code null} 그대로.
     *
     * <p>키가 없으면 원문을 그대로 돌려준다 — 가릴 것이 없을 때 문자열을 건드리면
     * 사유를 읽기만 어려워진다.
     */
    public static String secrets(String message) {
        if (message == null || message.isEmpty()) return message;
        return QUERY_KEY.matcher(message).replaceAll("$1[REDACTED]");
    }

    /** 예외를 로그 한 줄로 — 종류와 사유를 남기되 키는 지운다. */
    public static String cause(Throwable t) {
        if (t == null) return "";
        return t.getClass().getSimpleName() + " " + secrets(t.getMessage());
    }
}
