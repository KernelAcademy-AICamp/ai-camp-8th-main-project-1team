package com.finntech.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * CI(연계정보) 산식 — 마이데이터 서버(backend-mydata)의 {@code com.finntech.mydata.util.Ci}와 <b>반드시 동일</b>하다.
 * {@code CI = SHA-256(이름 + 주민등록번호앞7자리 + 전화번호)}. 실 NICE 인증값이 아니라 본인인증으로 받은 가상 생성값이다(§13).
 */
public final class Ci {
    private Ci() {}

    public static String of(String name, String social7, String phone) {
        String raw = name + social7 + phone;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte octet : hash) {
                hex.append(Character.forDigit((octet >> 4) & 0xF, 16));
                hex.append(Character.forDigit(octet & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
