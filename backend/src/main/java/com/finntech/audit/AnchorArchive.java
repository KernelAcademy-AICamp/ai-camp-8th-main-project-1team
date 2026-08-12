package com.finntech.audit;

import com.finntech.domain.AuditBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 앵커 사본을 <b>우리가 지울 수 없는 곳</b>에 남긴다 (설계서 Phase 5-1).
 *
 * <h2>계층 3만으로는 못 막는 것</h2>
 *
 * <p>{@link AuditService} 주석은 <i>"루트 해시가 운영자가 닿을 수 없는 곳에 게시되어야만
 * 그 공격이 막힌다"</i> 고 적어 두었지만, <b>게시하는 코드가 없었다.</b> 그래서 지금까지는
 * TSA 토큰이 {@code audit_batch}, 즉 <b>위조 대상과 같은 DB</b> 에 있었다.
 *
 * <p>공격자가 DB 를 갈아엎고 <b>새 ROOT 로 도장을 새로 받아 바꿔치기하면 통과한다.</b>
 * 타임스탬프는 <b>"존재 증명"이지 "유일성 증명"이 아니기</b> 때문이다 — "이 데이터가 이 시각에
 * 있었다"는 증명하지만 "이것 말고 다른 버전은 없었다"는 증명하지 못한다.
 *
 * <p>다만 <b>과거로 되돌리는 위조는 원천적으로 불가능하다</b> — 시각은 TSA 가 자기 시계로 찍고
 * 그것을 바꾸려면 TSA 의 비밀키가 있어야 한다. 공격자가 할 수 있는 것은 "전부 오늘 것으로 새로
 * 만들기"뿐이고, 그래서 <b>밖에 옛 도장이 남아 있으면 즉시 드러난다.</b>
 *
 * <h2>S3 Object Lock (Compliance)</h2>
 *
 * <p>버킷은 <b>Compliance 모드</b>여야 한다. Governance 는 권한자가 지울 수 있어, 막으려던
 * 바로 그 사람(내부자)에게 통하지 않는다. Compliance 는 보존기간 내에는 <b>AWS 계정 root 도</b>
 * 못 지운다.
 *
 * <p>EC2 역할에는 이 버킷에 대해 <b>{@code s3:PutObject} 만</b> 준다 — {@code Delete}·{@code Get}
 * 을 주지 않으면 <b>서버가 털려도 자기가 쓴 것을 읽지도 지우지도 못한다.</b> 검증은 사람이
 * 별도 자격증명으로 한다.
 *
 * <p>TSA 토큰 <b>원문</b>을 넣는다. 해시만 넣으면 우리 코드 없이는 검증할 수 없어,
 * 독립 검증이라는 목적을 잃는다 — 원문이 있으면 {@code openssl ts -verify} 로 누구나 확인한다.
 *
 * <p><b>실패해도 예외를 던지지 않는다.</b> 앵커 사본을 못 남겼다고 서비스가 멈추면 안 되고,
 * 배치는 다음 호출에서 다시 시도된다. 다만 실패는 로그에 남긴다 — 조용히 안 남기는 것이
 * 가장 나쁘다.
 */
@Component
public class AnchorArchive {

    private static final Logger log = LoggerFactory.getLogger(AnchorArchive.class);

    private final boolean enabled;
    private final String bucket;
    private final S3Client s3;

    public AnchorArchive(@Value("${finntech.audit.anchor-archive.enabled:false}") boolean enabled,
                         @Value("${finntech.audit.anchor-archive.bucket:}") String bucket) {
        this.enabled = enabled && !bucket.isBlank();
        this.bucket = bucket;
        this.s3 = this.enabled ? S3Client.create() : null;
        if (enabled && bucket.isBlank()) {
            log.warn("앵커 사본이 켜져 있는데 버킷이 비었다 — 사본을 남기지 않는다");
        }
    }

    public boolean isEnabled() { return enabled; }

    /**
     * 앵커 한 건을 사본으로 남긴다.
     *
     * <p>키는 {@code anchor/<batchId>.json} 이다. 배치 번호가 곧 순번이라 빠진 구간이 눈에 띈다 —
     * 중간이 비어 있으면 그 자체가 신호다.
     *
     * @return 남겼으면 {@code true}. 꺼져 있거나 실패해도 예외를 던지지 않는다
     */
    public boolean archive(AuditBatch batch) {
        if (!enabled) return false;
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("batchId", batch.getId());
            record.put("fromSeq", batch.getFromSeq());
            record.put("toSeq", batch.getToSeq());
            record.put("batchRoot", batch.getBatchRoot());
            record.put("prevBatchRoot", batch.getPrevBatchRoot());
            record.put("tsaGenTime", batch.getTsaGenTime() == null ? null : batch.getTsaGenTime().toString());
            record.put("tsaName", batch.getTsaName());
            // 원문을 넣는다 — 이것이 있어야 우리 코드 없이 검증할 수 있다.
            record.put("tsaQuery", batch.getTsaQuery());
            record.put("tsaResponse", batch.getTsaResponse());

            byte[] body = CanonicalJson.write(record).getBytes(StandardCharsets.UTF_8);
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key("anchor/" + batch.getId() + ".json")
                            .contentType("application/json")
                            .build(),
                    RequestBody.fromBytes(body));
            log.info("앵커 사본 남김 — batch={} bucket={}", batch.getId(), bucket);
            return true;
        } catch (RuntimeException exception) {
            // 같은 키에 이미 있으면(재시도) Object Lock 이 덮어쓰기를 막는다 — 그것은 정상이다.
            log.warn("앵커 사본 실패 — batch={} : {}", batch.getId(), exception.toString());
            return false;
        }
    }
}
