package com.finntech.mydata.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * CI(연계정보) 산식 — 본체와 <b>반드시 동일</b>해야 마이데이터 조회가 매칭된다.
 * {@code CI = SHA-256(이름 + 주민등록번호앞7자리(생년월일6+성별세대1) + 전화번호)}.
 * SHA-256 해시를 사용자 식별자(PK)로 쓰는 CI 산식.
 */
public final class Ci {
    private Ci() {}

    /** social7 = 주민번호 앞 7자리(YYMMDD + 성별세대코드 1자리). phone = 하이픈 없는 숫자. */
    public static String of(String name, String social7, String phone) {
        String raw = name + social7 + phone;
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
}
