package com.finntech.audit;

import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * RFC 3161 Time-Stamp Protocol 클라이언트 — 감사로그 <b>계층 3</b> (문서 §5-4).
 *
 * <p><b>왜 이게 최대 차별화 지점인가</b>: 계층 1(해시체인)과 계층 2(Merkle)만으로는
 * "운영자가 DB를 통째로 재생성하는 공격"을 막지 못한다. 체인을 처음부터 다시 계산하면
 * 완벽하게 일관된 가짜 체인이 만들어지고 검증도 통과한다.
 * <b>루트 해시가 운영자가 닿을 수 없는 곳에 게시되어야만</b> 그 공격이 막힌다.
 *
 * <p>TSA는 신뢰받는 제3자로서 "이 해시값이 이 시각 이전에 존재했다"에 서명한다.
 * 중요한 건 <b>TSA가 원본 데이터를 보지 않는다</b>는 점이다 — 해시만 전송하므로
 * 로그 내용의 기밀성이 유지된다. 개인정보 처리방침 5번(제3자 제공 없음)과도 충돌하지 않는다.
 *
 * <p>구현 함정 (문서 §5-4):
 * <ul>
 *   <li>messageImprint는 {@code openssl ts -query -data merkle_root.bin -sha512}와 동일하게
 *       <b>batch_root의 raw 32바이트를 SHA-512</b>한 값이다. hex 문자열을 해시하면 안 된다.</li>
 *   <li>공개 TSA는 연속 호출 시 요청 간 15초 이상 지연을 요구한다 — 이것이 "매 로그마다"가 아니라
 *       "배치 앵커링"이어야 하는 실무적 이유다. {@code AuditService}가 이 간격을 지킨다.</li>
 *   <li>certReq=true로 요청해 TSA 인증서를 응답에 포함시킨다. 나중에 웹에서 못 구하기 때문이다.</li>
 * </ul>
 */
@Component
public class TsaClient {

    private static final Logger log = LoggerFactory.getLogger(TsaClient.class);

    private final String url;
    private final boolean enabled;
    private final HttpClient http;

    public TsaClient(
            @Value("${finntech.tsa.url:https://freetsa.org/tsr}") String url,
            @Value("${finntech.tsa.enabled:false}") boolean enabled,
            @Value("${finntech.tsa.timeout-seconds:20}") int timeoutSeconds) {
        this.url = url;
        this.enabled = enabled;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    public boolean isEnabled() { return enabled; }
    public String getUrl() { return url; }

    /**
     * batch_root(hex)에 대한 타임스탬프를 받아온다.
     *
     * @return 성공 시 결과, 실패 시 {@code failure()} — <b>예외를 던지지 않는다.</b>
     *         앵커링 실패가 서비스 장애가 되면 안 된다. 실패한 배치는 PENDING으로 남아 재시도된다.
     */
    public TsaResult stamp(String batchRootHex) {
        if (!enabled) {
            return TsaResult.skipped("TSA 비활성화 (finntech.tsa.enabled=false)");
        }
        try {
            byte[] rootBytes = Hashing.unhex(batchRootHex);
            byte[] imprint = MessageDigest.getInstance("SHA-512").digest(rootBytes);

            TimeStampRequestGenerator gen = new TimeStampRequestGenerator();
            gen.setCertReq(true);   // 인증서를 응답에 포함 — 10년 뒤 웹에서 못 구한다
            BigInteger nonce = new BigInteger(64, new java.security.SecureRandom());
            TimeStampRequest tsq = gen.generate(
                    new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha512), imprint, nonce);

            HttpResponse<byte[]> res = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .header("Content-Type", "application/timestamp-query")
                            .timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.ofByteArray(tsq.getEncoded()))
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            if (res.statusCode() != 200) {
                return TsaResult.failure("TSA HTTP " + res.statusCode());
            }

            TimeStampResponse tsr = new TimeStampResponse(res.body());
            tsr.validate(tsq);   // nonce·imprint 일치 검증 — 응답 바꿔치기 방어

            TimeStampToken token = tsr.getTimeStampToken();
            if (token == null) {
                return TsaResult.failure("TSA 토큰 없음 (status=" + tsr.getStatus() + ")");
            }
            Instant genTime = token.getTimeStampInfo().getGenTime().toInstant();
            String tsrBase64 = Base64.getEncoder().encodeToString(res.body());
            String tsqBase64 = Base64.getEncoder().encodeToString(tsq.getEncoded());

            log.info("TSA 앵커링 성공: root={} genTime={}", batchRootHex.substring(0, 16) + "…", genTime);
            String tsaName = token.getTimeStampInfo().getTsa() == null
                    ? url : token.getTimeStampInfo().getTsa().toString();
            return TsaResult.success(tsrBase64, tsqBase64, genTime, truncate(tsaName, 990));

        } catch (Exception e) {
            log.warn("TSA 앵커링 실패 — 배치는 PENDING으로 남아 재시도된다: {}", e.toString());
            return TsaResult.failure(truncate(e.getClass().getSimpleName() + ": " + e.getMessage(), 1990));
        }
    }

    /**
     * 외부에서 온 문자열은 길이를 신뢰할 수 없다. FreeTSA의 DN이 212자라
     * 컬럼(200)을 넘겨 저장이 통째로 실패한 적이 있다 — 앵커링은 성공했는데 기록이 날아갔다.
     */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * 저장된 .tsr을 다시 파싱해 messageImprint가 batch_root와 일치하는지 확인한다.
     * 감사자 검증 절차 1단계에 해당한다 (문서 §5-4).
     */
    public VerifyResult verifyStoredToken(String tsrBase64, String batchRootHex) {
        try {
            byte[] tsrBytes = Base64.getDecoder().decode(tsrBase64);
            TimeStampResponse tsr = new TimeStampResponse(tsrBytes);
            TimeStampToken token = tsr.getTimeStampToken();
            if (token == null) return new VerifyResult(false, null, "토큰 없음");

            byte[] expected = MessageDigest.getInstance("SHA-512")
                    .digest(Hashing.unhex(batchRootHex));
            byte[] actual = token.getTimeStampInfo().getMessageImprintDigest();

            if (!MessageDigest.isEqual(expected, actual)) {
                return new VerifyResult(false, null,
                        "messageImprint 불일치 — 앵커된 루트가 현재 배치 루트와 다르다");
            }
            return new VerifyResult(true, token.getTimeStampInfo().getGenTime().toInstant(), null);
        } catch (Exception e) {
            return new VerifyResult(false, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public record TsaResult(
            Status status,
            String tsrBase64,
            String tsqBase64,
            Instant genTime,
            String tsaName,
            String message
    ) {
        public enum Status { SUCCESS, FAILURE, SKIPPED }

        static TsaResult success(String tsr, String tsq, Instant genTime, String tsaName) {
            return new TsaResult(Status.SUCCESS, tsr, tsq, genTime, tsaName, null);
        }
        static TsaResult failure(String message) {
            return new TsaResult(Status.FAILURE, null, null, null, null, message);
        }
        static TsaResult skipped(String message) {
            return new TsaResult(Status.SKIPPED, null, null, null, null, message);
        }
        public boolean ok() { return status == Status.SUCCESS; }
    }

    public record VerifyResult(boolean valid, Instant genTime, String problem) {}
}
