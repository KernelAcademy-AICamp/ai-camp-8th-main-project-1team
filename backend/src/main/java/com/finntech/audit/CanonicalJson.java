package com.finntech.audit;

import java.util.Map;
import java.util.TreeMap;

/**
 * 정규화 JSON (문서 §5-4 구현 함정 1).
 *
 * <p><b>키 순서·공백이 흔들리면 해시 검증이 전부 조용히 깨진다.</b> Jackson의 기본 직렬화는
 * Map 구현체에 따라 순서가 달라질 수 있으므로 쓰지 않고, 여기서 직접 만든다.
 * 규칙은 Python {@code json.dumps(sort_keys=True, separators=(",", ":"), ensure_ascii=False)}와 동일하다.
 */
public final class CanonicalJson {

    private CanonicalJson() {}

    /** 키 정렬, 공백 없음, 유니코드 이스케이프 없음. */
    public static String write(Map<String, ?> data) {
        TreeMap<String, Object> sorted = new TreeMap<>(data);
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            appendValue(sb, e.getValue());
        }
        return sb.append('}').toString();
    }

    private static void appendValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, ?> cast = (Map<String, ?>) m;
            sb.append(write(cast));
        } else {
            sb.append('"').append(escape(v.toString())).append('"');
        }
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
