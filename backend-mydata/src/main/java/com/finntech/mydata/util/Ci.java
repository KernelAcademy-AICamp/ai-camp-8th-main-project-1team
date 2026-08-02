package com.finntech.mydata.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * CI(연계정보) 산식 — 본체와 <b>반드시 동일</b>해야 마이데이터 조회가 매칭된다.
 * {@code CI = SHA-256(이름 + 주민등록번호앞7자리(생년월일6+성별세대1) + 전화번호)}.
 * SHA-256 해시를 사용자 식별자(PK)로 쓰는 CI 산식.
 *
 * <p><b>입력을 먼저 정규화한다</b> — 본체 {@code com.finntech.util.Ci}와 한 글자도 다르면 안 된다.
 * 아래 javadoc은 원래부터 "phone = 하이픈 없는 숫자"라고 적어 뒀지만 코드가 강제하지는 않아서,
 * {@code 010-4814-0667} 로 들어오면 조용히 다른 CI가 나왔다. 문서가 주장하던 계약을 코드가 지키게 한 것이고,
 * <b>기존 CI는 하나도 바뀌지 않는다</b>(저장된 4,511명 전부 숫자만의 전화번호로 만들어졌다). (2026-08-02)
 */
public final class Ci {
    private Ci() {}

    /** social7 = 주민번호 앞 7자리(YYMMDD + 성별세대코드 1자리). phone = 하이픈 없는 숫자. */
    public static String of(String name, String social7, String phone) {
        String raw = trimmed(name) + digits(social7) + digits(phone);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte octet : hash) {
                hex.append(Character.forDigit((octet >> 4) & 0xF, 16));
                hex.append(Character.forDigit(octet & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 숫자만 남긴다 — 하이픈·공백·괄호 같은 표기 차이가 신원을 가르지 않게. */
    private static String digits(String value) {
        if (value == null) return "";
        StringBuilder only = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') only.append(c);
        }
        return only.toString();
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
