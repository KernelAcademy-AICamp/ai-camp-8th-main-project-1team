package com.finntech.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 폴백 사유를 로그에 남기되 인증키는 남기지 않는다. */
class RedactTest {

    private static final String KEY = "AIzaSyD-EXAMPLE-not-a-real-key-000000000";

    @Test
    @DisplayName("Gemini URI 의 key= 값을 지운다 — 사유는 남기고 키만")
    void hidesGeminiQueryKey() {
        String msg = "I/O error on POST request for "
                + "\"https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"
                + "?key=" + KEY + "\": Connection reset";

        String out = Redact.secrets(msg);
        assertFalse(out.contains(KEY), "키가 남으면 안 된다: " + out);
        assertTrue(out.contains("[REDACTED]"));
        assertTrue(out.contains("Connection reset"), "사유는 읽을 수 있어야 한다");
        assertTrue(out.contains("gemini-flash-latest"), "어느 모델이었는지도 남아야 한다");
    }

    @Test
    @DisplayName("금감원 auth= 도 같이 지운다 — 같은 자리에 실리는 다른 키")
    void hidesFssAuthParam() {
        String msg = "GET https://finlife.fss.or.kr/finlifeapi/savingProductsSearch.json"
                + "?auth=0123456789abcdef0123456789abcdef&topFinGrpNo=020000 failed";
        String out = Redact.secrets(msg);
        assertFalse(out.contains("0123456789abcdef0123456789abcdef"));
        assertTrue(out.contains("topFinGrpNo=020000"), "키 뒤의 다른 파라미터는 살아 있어야 한다");
    }

    @Test
    @DisplayName("가릴 것이 없으면 원문 그대로 — 멀쩡한 사유를 읽기 어렵게 만들지 않는다")
    void leavesCleanMessagesAlone() {
        String msg = "429 Too Many Requests: quota exceeded for model gemini-flash-latest";
        assertEquals(msg, Redact.secrets(msg));
        assertNull(Redact.secrets(null));
        assertEquals("", Redact.secrets(""));
    }

    @Test
    @DisplayName("예외 한 줄 요약 — 종류와 사유를 남기되 키는 지운다")
    void summarisesCause() {
        Exception e = new IllegalStateException("call to https://x/y?key=" + KEY + " failed");
        String out = Redact.cause(e);
        assertTrue(out.startsWith("IllegalStateException "), out);
        assertFalse(out.contains(KEY));
        assertEquals("", Redact.cause(null));
    }

    @Test
    @DisplayName("실제 RestClient 예외 형태에서도 동작한다 — URI 를 통째로 담는 그 예외")
    void handlesUriBearingException() {
        URI uri = URI.create("https://generativelanguage.googleapis.com/v1beta/models/m:generateContent?key=" + KEY);
        Exception e = new java.io.IOException("I/O error on POST request for \"" + uri + "\": timeout");
        assertFalse(Redact.cause(e).contains(KEY));
    }
}
