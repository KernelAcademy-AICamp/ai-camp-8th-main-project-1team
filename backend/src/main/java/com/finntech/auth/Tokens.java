package com.finntech.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 인증 토큰·복구 코드의 생성과 지문.
 *
 * <p><b>토큰 원문은 DB 에 저장하지 않는다.</b> 저장하는 것은 여기서 만든 SHA-256 지문뿐이라,
 * 표가 통째로 유출돼도 그 값으로 로그인할 수 없다.
 *
 * <p>비밀번호와 달리 <b>느린 KDF 를 쓰지 않는다.</b> 토큰은 32바이트 무작위라 애초에 추측이
 * 불가능하고(경우의 수 2^256), 요청마다 검사해야 하므로 빠른 SHA-256 이 맞다. 여기에 Argon2 를
 * 쓰면 모든 요청이 느려질 뿐 얻는 것이 없다.
 */
public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** 32바이트 = 256비트. 무차별 대입이 원리적으로 불가능한 범위. */
    private static final int TOKEN_BYTES = 32;

    private Tokens() {}

    /** URL 에 그대로 실을 수 있는 무작위 토큰. 이 값은 <b>발급 순간에만</b> 존재한다. */
    public static String newToken() {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * 복구 코드 — 사람이 종이에 적고 다시 입력해야 하므로 짧고 읽기 쉬워야 한다.
     *
     * <p>혼동되는 글자를 뺀 문자집합을 쓴다({@code 0/O}, {@code 1/I/L}). 종이에 적힌 코드를
     * 잘못 읽어 못 들어오면 복구 코드를 만든 이유가 사라진다.
     */
    private static final char[] READABLE = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    public static String newRecoveryCode() {
        StringBuilder sb = new StringBuilder(11);
        for (int i = 0; i < 10; i++) {
            if (i == 5) sb.append('-');
            sb.append(READABLE[RANDOM.nextInt(READABLE.length)]);
        }
        return sb.toString();
    }

    /** 저장·조회에 쓰는 지문. 되돌릴 수 없다. */
    public static String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte octet : out) {
                hex.append(Character.forDigit((octet >> 4) & 0xF, 16));
                hex.append(Character.forDigit(octet & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /**
     * 복구 코드 입력을 저장 형식에 맞춘다 — 하이픈·공백·대소문자를 흡수한다.
     *
     * <p>표기 차이로 맞는 코드가 틀렸다고 나오면 안 된다. {@code Ci.of} 가 전화번호를
     * 정규화하는 것과 같은 태도다.
     */
    public static String normalizeRecoveryCode(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toUpperCase().toCharArray()) {
            if (c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') sb.append(c);
        }
        return sb.toString();
    }
}
