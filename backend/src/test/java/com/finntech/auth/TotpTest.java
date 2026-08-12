package com.finntech.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TOTP (RFC 6238) — IP 허용목록을 쓰지 않으므로 <b>이것이 admin 의 유일한 2차 방어</b>다.
 */
class TotpTest {

    /**
     * RFC 4648 §10 시험 벡터. Base32 를 직접 구현했으므로 표준과 맞는지 못 박는다 —
     * 여기가 어긋나면 인증 앱이 만든 코드와 서버가 만든 코드가 영영 다르다.
     */
    @Test
    @DisplayName("Base32 인코딩이 RFC 4648 시험 벡터와 같다")
    void base32MatchesRfcVectors() {
        assertThat(Totp.base32Encode("f".getBytes())).isEqualTo("MY");
        assertThat(Totp.base32Encode("fo".getBytes())).isEqualTo("MZXQ");
        assertThat(Totp.base32Encode("foo".getBytes())).isEqualTo("MZXW6");
        assertThat(Totp.base32Encode("foob".getBytes())).isEqualTo("MZXW6YQ");
        assertThat(Totp.base32Encode("fooba".getBytes())).isEqualTo("MZXW6YTB");
        assertThat(Totp.base32Encode("foobar".getBytes())).isEqualTo("MZXW6YTBOI");
    }

    @Test
    @DisplayName("인코딩한 것을 되돌리면 원본이다")
    void base32RoundTrips() {
        byte[] original = "abcdefghij0123456789".getBytes();
        assertThat(Totp.base32Decode(Totp.base32Encode(original))).isEqualTo(original);
    }

    @Test
    @DisplayName("코드는 6자리이며 앞자리 0을 살린다")
    void codeIsSixDigits() {
        String secret = Totp.newSecret();
        for (long step = 0; step < 200; step++) {
            assertThat(Totp.codeAt(secret, step)).hasSize(6).containsOnlyDigits();
        }
    }

    @Test
    @DisplayName("지금 코드는 통과하고, 구간 번호를 돌려준다")
    void verifiesCurrentCode() {
        String secret = Totp.newSecret();
        long now = 1_760_000_000L;
        String code = Totp.codeAt(secret, Totp.stepOf(now));
        assertThat(Totp.verify(secret, code, now, null)).isEqualTo(Totp.stepOf(now));
    }

    @Test
    @DisplayName("시계가 30초 어긋나도 받는다 — 그것 때문에 로그인이 막히면 2FA 를 끄게 된다")
    void acceptsOneStepDrift() {
        String secret = Totp.newSecret();
        long now = 1_760_000_000L;
        long step = Totp.stepOf(now);
        assertThat(Totp.verify(secret, Totp.codeAt(secret, step - 1), now, null)).isEqualTo(step - 1);
        assertThat(Totp.verify(secret, Totp.codeAt(secret, step + 1), now, null)).isEqualTo(step + 1);
    }

    @Test
    @DisplayName("두 구간 밖은 거부한다 — 창을 넓힐수록 훔쳐본 코드의 수명이 길어진다")
    void rejectsFarDrift() {
        String secret = Totp.newSecret();
        long now = 1_760_000_000L;
        long step = Totp.stepOf(now);
        assertThat(Totp.verify(secret, Totp.codeAt(secret, step + 3), now, null)).isNull();
    }

    @Test
    @DisplayName("같은 코드를 두 번 쓰지 못한다 — 어깨너머로 본 코드를 30초 안에 재사용하는 것을 막는다")
    void rejectsReuse() {
        String secret = Totp.newSecret();
        long now = 1_760_000_000L;
        long step = Totp.stepOf(now);
        String code = Totp.codeAt(secret, step);

        assertThat(Totp.verify(secret, code, now, null)).isEqualTo(step);
        // 방금 그 구간을 썼다고 알려주면 같은 코드는 더 이상 통하지 않는다
        assertThat(Totp.verify(secret, code, now, step)).isNull();
    }

    @Test
    @DisplayName("등록 주소에 발급자·계정·비밀이 들어간다")
    void provisioningUriCarriesFields() {
        String uri = Totp.provisioningUri("MOA", "admin1", "JBSWY3DPEHPK3PXP");
        assertThat(uri).startsWith("otpauth://totp/MOA:admin1?")
                .contains("secret=JBSWY3DPEHPK3PXP")
                .contains("issuer=MOA")
                .contains("digits=6")
                .contains("period=30");
    }

    @Test
    @DisplayName("6자리가 아니면 계산도 하지 않는다")
    void rejectsMalformedInput() {
        String secret = Totp.newSecret();
        assertThat(Totp.verify(secret, "12345", 1_760_000_000L, null)).isNull();
        assertThat(Totp.verify(secret, "", 1_760_000_000L, null)).isNull();
        assertThat(Totp.verify(secret, null, 1_760_000_000L, null)).isNull();
    }
}
