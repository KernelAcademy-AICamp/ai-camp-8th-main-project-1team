package com.finntech.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 계층 3 TSA 앵커링 테스트.
 *
 * <p>실제 FreeTSA를 때리는 테스트는 <b>기본으로 꺼져 있다</b> — 외부 네트워크에 의존하는 테스트를
 * 기본 빌드에 넣으면 오프라인이나 TSA 장애 시 CI가 빨개진다. 명시적으로 켜서 돌린다:
 * <pre>./mvnw test -Dtsa.live=true -Dtest=TsaClientTest</pre>
 */
class TsaClientTest {

    private static final String ROOT = Hashing.merkleRoot(List.of("ab".repeat(32), "cd".repeat(32)));

    @Test
    @DisplayName("TSA 비활성화 시 SKIPPED를 반환하고 예외를 던지지 않는다")
    void disabledReturnsSkipped() {
        TsaClient client = new TsaClient("https://freetsa.org/tsr", false, 5);
        TsaClient.TsaResult r = client.stamp(ROOT);
        assertEquals(TsaClient.TsaResult.Status.SKIPPED, r.status());
        assertFalse(r.ok());
    }

    @Test
    @DisplayName("도달 불가 URL이어도 예외 대신 FAILURE를 반환한다 — 앵커링 실패가 서비스 장애가 되면 안 된다")
    void unreachableUrlFailsGracefully() {
        TsaClient client = new TsaClient("http://127.0.0.1:9/tsr", true, 2);
        TsaClient.TsaResult r = assertDoesNotThrow(() -> client.stamp(ROOT));
        assertEquals(TsaClient.TsaResult.Status.FAILURE, r.status());
        assertNotNull(r.message());
    }

    @Test
    @DisplayName("깨진 토큰 검증은 예외 없이 invalid를 반환한다")
    void verifyGarbageToken() {
        TsaClient client = new TsaClient("https://freetsa.org/tsr", false, 5);
        TsaClient.VerifyResult v = client.verifyStoredToken("bm90LWEtdHNy", ROOT);
        assertFalse(v.valid());
        assertNotNull(v.problem());
    }

    @Test
    @EnabledIfSystemProperty(named = "tsa.live", matches = "true")
    @DisplayName("[LIVE] FreeTSA에서 실제 타임스탬프를 받고, 그 토큰이 루트와 일치한다")
    void liveRoundTrip() {
        TsaClient client = new TsaClient("https://freetsa.org/tsr", true, 20);

        TsaClient.TsaResult r = client.stamp(ROOT);
        assertTrue(r.ok(), "TSA 응답 실패: " + r.message());
        assertNotNull(r.genTime(), "genTime이 있어야 한다 — 제3자가 서명한 시각");
        assertNotNull(r.tsrBase64());
        assertNotNull(r.tsqBase64(), "tsq도 보관해야 나중에 openssl로 검증할 수 있다");

        // 받은 토큰이 정말 이 루트에 대한 것인지 재검증
        TsaClient.VerifyResult v = client.verifyStoredToken(r.tsrBase64(), ROOT);
        assertTrue(v.valid(), "검증 실패: " + v.problem());
        assertEquals(r.genTime(), v.genTime());

        // 다른 루트로는 검증이 실패해야 한다 — 이게 "DB 전체 재생성" 공격을 잡는 지점
        String otherRoot = Hashing.merkleRoot(List.of("11".repeat(32)));
        TsaClient.VerifyResult bad = client.verifyStoredToken(r.tsrBase64(), otherRoot);
        assertFalse(bad.valid(), "다른 루트인데 검증이 통과하면 앵커링이 무의미하다");
    }
}
