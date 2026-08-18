package com.finntech.ml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 모델 파일 지문 — <b>재학습을 알아보는 유일한 수단</b>이라 성질을 못박는다.
 *
 * <p>모델에는 버전 식별자가 없고 {@code decision_threshold} 로는 두 회차가 같은 값을 낼 수
 * 있다. 표에 낭비 판정을 적어 두려면 "어느 모델이 낸 답인가"를 함께 적어야 하는데,
 * 그 자리에 들어가는 값이 이것이다.
 */
class SpendingClassifierFingerprintTest {

    @Test
    @DisplayName("소문자 hex 64자를 낸다")
    void 지문은_소문자_hex_64자다() {
        String fingerprint = SpendingClassifier.fingerprintOf("{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(64, fingerprint.length(), "SHA-256 은 32바이트 = hex 64자");
        assertTrue(fingerprint.matches("[0-9a-f]{64}"), "소문자 hex 라야 비교가 문자열 하나로 끝난다: " + fingerprint);
    }

    @Test
    @DisplayName("같은 바이트면 같은 지문 — 재배포만으로 회차가 바뀌었다고 하지 않는다")
    void 같은_파일은_같은_지문이다() {
        byte[] model = "{\"intercept\":0.5}".getBytes(StandardCharsets.UTF_8);
        assertEquals(SpendingClassifier.fingerprintOf(model), SpendingClassifier.fingerprintOf(model.clone()));
    }

    @Test
    @DisplayName("한 바이트만 달라도 다른 지문 — 임계가 같은 두 회차를 가른다")
    void 한_바이트_차이도_잡는다() {
        // 실제로 걱정하는 상황이다: 형상함수만 바뀌고 decision_threshold 는 그대로인 재학습.
        String before = SpendingClassifier.fingerprintOf(
                "{\"decision_threshold\":0.479,\"intercept\":-1.20}".getBytes(StandardCharsets.UTF_8));
        String after = SpendingClassifier.fingerprintOf(
                "{\"decision_threshold\":0.479,\"intercept\":-1.21}".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(before, after, "임계가 같아도 다른 모델이면 다른 지문이라야 한다");
    }

    @Test
    @DisplayName("빈 파일도 지문을 낸다 — 못 읽은 것과 비어 있는 것은 다르다")
    void 빈_바이트도_지문이_있다() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                SpendingClassifier.fingerprintOf(new byte[0]), "SHA-256 of empty");
    }
}
