package com.finntech.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * CI(연계정보) 산식 — 마이데이터 서버(backend-mydata)의 {@code com.finntech.mydata.util.Ci}와 <b>반드시 동일</b>하다.
 * {@code CI = SHA-256(이름 + 주민등록번호앞7자리 + 전화번호)}. 실 NICE 인증값이 아니라 본인인증으로 받은 가상 생성값이다(§13).
 *
 * <p><b>입력을 먼저 정규화한다.</b> 예전에는 받은 문자열을 그대로 이어 붙여 해시했다. 그래서
 * {@code 010-4814-0667} 과 {@code 01048140667} 이 <b>서로 다른 사람</b>이 됐다 — 같은 번호인데
 * 하이픈 하나로 CI가 갈렸다. 프론트가 숫자만 보내 왔기에 앱 경로에서는 드러나지 않았지만,
 * CI가 곧 계정 키가 된 뒤로는(tech_log §8-P) <b>같은 사람이 계정 두 개</b>가 될 수 있는 구멍이다.
 *
 * <p>그래서 전화번호·주민앞7은 숫자만 남기고 이름은 앞뒤 공백을 턴다. <b>기존 CI는 하나도 바뀌지
 * 않는다</b> — 저장된 CI가 전부 숫자만의 전화번호로 만들어졌기 때문이다(4,511명 전수 확인).
 * 정규화는 값을 바꾸는 게 아니라, 이미 지켜지던 계약을 코드가 강제하게 만드는 것이다. (2026-08-02)
 */
public final class Ci {
    private Ci() {}

    public static String of(String name, String social7, String phone) {
        String raw = trimmed(name) + digits(social7) + digits(phone);
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
