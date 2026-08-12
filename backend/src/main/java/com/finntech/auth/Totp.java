package com.finntech.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

/**
 * TOTP (RFC 6238) — admin 2단계 인증.
 *
 * <p><b>서버와 폰은 통신하지 않는다.</b> 같은 비밀과 같은 시각(30초 구간)으로 각자 계산해
 * 같은 6자리를 낸다. 그래서 폰이 비행기모드여도 코드가 나온다.
 *
 * <p>IP 허용목록을 채택하지 않았으므로 <b>이것이 유일한 2차 방어</b>다. 비밀번호가 새어도
 * 폰이 없으면 못 들어온다.
 *
 * <p>라이브러리를 들이지 않는다 — HMAC-SHA1 은 JDK 표준({@link Mac})이고, JDK 에 없는 것은
 * Base32 뿐이라 여기서 직접 쓴다(RFC 4648). 새 의존성 하나를 아끼는 것보다,
 * <b>이 계산이 무엇을 하는지 코드에서 바로 보이는 것</b>이 인증 코드에서는 더 값지다.
 */
public final class Totp {

    /** RFC 6238 기본값. 인증 앱(Google Authenticator 등)이 이 값을 전제한다. */
    private static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    /** RFC 4226 이 정한 HMAC 알고리즘. 인증 앱 호환을 위해 바꾸지 않는다. */
    private static final String HMAC = "HmacSHA1";
    /** RFC 6238 권장 최소 길이(160비트). */
    private static final int SECRET_BYTES = 20;

    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    /** 새 비밀을 Base32 문자열로 만든다. 이 값이 QR 에 실리고 DB 에는 암호화되어 들어간다. */
    public static String newSecret() {
        byte[] raw = new byte[SECRET_BYTES];
        RANDOM.nextBytes(raw);
        return base32Encode(raw);
    }

    /**
     * 인증 앱이 읽는 등록 주소.
     *
     * <p>QR 로 보여주되 <b>문자열도 같이 띄운다</b> — 카메라가 안 되는 환경에서 손으로 넣을
     * 길이 없으면 등록 자체가 막힌다.
     */
    public static String provisioningUri(String issuer, String account, String secret) {
        String label = urlEncode(issuer) + ":" + urlEncode(account);
        return "otpauth://totp/" + label
                + "?secret=" + secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    /** 지금이 몇 번째 30초 구간인가. 재사용 판정에 쓰는 값이라 검증과 같은 식이어야 한다. */
    public static long stepOf(long epochSeconds) {
        return epochSeconds / STEP_SECONDS;
    }

    /**
     * 그 구간의 6자리를 계산한다.
     *
     * @return {@code "046831"} 처럼 <b>앞자리 0을 살린</b> 6자리
     */
    public static String codeAt(String base32Secret, long step) {
        byte[] key = base32Decode(base32Secret);
        byte[] counter = ByteBuffer.allocate(8).putLong(step).array();
        byte[] mac;
        try {
            Mac hmac = Mac.getInstance(HMAC);
            hmac.init(new SecretKeySpec(key, HMAC));
            mac = hmac.doFinal(counter);
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC 계산 실패", exception);
        }
        // RFC 4226 동적 절단 — 마지막 바이트 하위 4비트가 어디서 4바이트를 뗄지 가리킨다.
        int offset = mac[mac.length - 1] & 0x0F;
        int binary = ((mac[offset] & 0x7F) << 24)
                | ((mac[offset + 1] & 0xFF) << 16)
                | ((mac[offset + 2] & 0xFF) << 8)
                | (mac[offset + 3] & 0xFF);
        int otp = binary % 1_000_000;
        return String.format("%0" + DIGITS + "d", otp);
    }

    /**
     * 입력한 코드가 맞는가. 맞으면 <b>그 구간 번호</b>를, 아니면 {@code null} 을 준다.
     *
     * <p>호출부는 돌려받은 구간 번호를 저장해 <b>같은 코드의 재사용을 막아야 한다</b> —
     * 그러지 않으면 어깨너머로 본 코드를 30초 안에 그대로 쓸 수 있다.
     *
     * <p>앞뒤 한 구간(±30초)까지 받는다. 폰과 서버 시계가 몇 초 어긋나는 것은 흔한 일이고,
     * 그것 때문에 로그인이 막히면 2FA 를 끄게 된다. 창을 더 넓히지는 않는다 —
     * 넓힐수록 훔쳐본 코드의 유효 시간이 길어진다.
     *
     * @param lastUsedStep 이 계정이 마지막으로 성공한 구간. {@code null} 이면 처음이다.
     */
    public static Long verify(String base32Secret, String input, long epochSeconds, Long lastUsedStep) {
        if (base32Secret == null || input == null) return null;
        String cleaned = input.replaceAll("\\D", "");
        if (cleaned.length() != DIGITS) return null;

        long now = stepOf(epochSeconds);
        for (long step = now - 1; step <= now + 1; step++) {
            if (lastUsedStep != null && step <= lastUsedStep) continue;   // 재사용 차단
            if (constantTimeEquals(codeAt(base32Secret, step), cleaned)) return step;
        }
        return null;
    }

    /**
     * 자리마다 끝까지 비교한다.
     *
     * <p>{@code String.equals} 는 다른 자리를 만나면 즉시 끝나서, 응답 시간이 <b>몇 자리까지
     * 맞았는지</b>를 흘린다. 6자리라 실익이 크진 않지만, 인증 비교에서 습관을 가르지 않는다.
     */
    private static boolean constantTimeEquals(String left, String right) {
        if (left.length() != right.length()) return false;
        int diff = 0;
        for (int i = 0; i < left.length(); i++) diff |= left.charAt(i) ^ right.charAt(i);
        return diff == 0;
    }

    // ── Base32 (RFC 4648) — JDK 에 없는 유일한 조각 ────────────────────────────

    static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32[(buffer >> (bits - 5)) & 0x1F]);
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(BASE32[(buffer << (5 - bits)) & 0x1F]);
        return sb.toString();
    }

    static byte[] base32Decode(String encoded) {
        String cleaned = encoded.replace("=", "").replace(" ", "").toUpperCase();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(cleaned.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char c : cleaned.toCharArray()) {
            int value = indexOf(c);
            if (value < 0) throw new IllegalArgumentException("Base32 가 아닌 문자: " + c);
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static int indexOf(char c) {
        for (int i = 0; i < BASE32.length; i++) if (BASE32[i] == c) return i;
        return -1;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
