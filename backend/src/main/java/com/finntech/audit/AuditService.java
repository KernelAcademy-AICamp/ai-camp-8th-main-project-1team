package com.finntech.audit;

import com.finntech.domain.AuditBatch;
import com.finntech.domain.AuditLog;
import com.finntech.repository.AuditBatchRepository;
import com.finntech.repository.AuditLogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 감사로그 3계층 (문서 §5-4).
 *
 * <p>계층 1(체인)과 계층 2(Merkle)는 여기서 완결된다. 계층 3(RFC 3161 TSA 앵커링)은
 * 2단계 작업이며 {@link #anchorPendingBatches()}가 자리만 잡아 둔다.
 *
 * <p><b>계층 3이 없으면 무엇을 못 막는가</b>: 운영자가 DB를 통째로 재생성하면
 * 완벽하게 일관된 가짜 체인이 만들어지고 검증도 통과한다. 루트 해시가 운영자가 닿을 수 없는
 * 곳에 게시되어야만 그 공격이 막힌다.
 */
@Service
public class AuditService {

    private final AuditLogRepository logRepository;
    private final AuditBatchRepository batchRepository;
    private final TsaClient tsaClient;
    private final long anchorDelayMillis;

    public AuditService(AuditLogRepository logRepository, AuditBatchRepository batchRepository,
                        TsaClient tsaClient,
                        @Value("${finntech.tsa.request-delay-millis:15000}") long anchorDelayMillis) {
        this.logRepository = logRepository;
        this.batchRepository = batchRepository;
        this.tsaClient = tsaClient;
        this.anchorDelayMillis = anchorDelayMillis;
    }

    /** 로그 1건 append. payload는 정규화 JSON으로 저장되며 그 바이트가 해시 입력이 된다. */
    @Transactional
    public AuditLog append(String eventType, Map<String, ?> payload, LocalDateTime at) {
        AuditLog prev = logRepository.findTopByOrderBySeqDesc().orElse(null);
        long seq = (prev == null) ? 1L : prev.getSeq() + 1;
        String prevHash = (prev == null) ? Hashing.ZERO_HASH : prev.getEntryHash();

        Map<String, Object> enriched = new LinkedHashMap<>(payload);
        enriched.put("seq", seq);
        enriched.put("eventType", eventType);
        enriched.put("at", at.toString());

        String canonical = CanonicalJson.write(enriched);
        String entryHash = Hashing.entryHash(prevHash, canonical);

        return logRepository.save(new AuditLog(seq, eventType, canonical, prevHash, entryHash, at));
    }

    /** 아직 배치에 묶이지 않은 로그를 Merkle 루트로 봉인한다 (계층 2). */
    @Transactional
    public AuditBatch sealBatch(LocalDateTime at) {
        List<AuditLog> unsealed = logRepository.findAllByOrderBySeqAsc().stream()
                .filter(l -> l.getBatchId() == null)
                .toList();
        if (unsealed.isEmpty()) return null;

        List<String> hashes = unsealed.stream().map(AuditLog::getEntryHash).toList();
        String root = Hashing.merkleRoot(hashes);
        String prevRoot = batchRepository.findTopByOrderByIdDesc()
                .map(AuditBatch::getBatchRoot).orElse(Hashing.ZERO_HASH);

        AuditBatch batch = batchRepository.save(new AuditBatch(
                root, prevRoot,
                unsealed.get(0).getSeq(),
                unsealed.get(unsealed.size() - 1).getSeq(),
                at));

        for (AuditLog l : unsealed) l.setBatchId(batch.getId());
        logRepository.saveAll(unsealed);
        return batch;
    }

    /**
     * 계층 3 — PENDING 배치를 RFC 3161 TSA에 앵커링한다 (문서 §5-4).
     *
     * <p><b>요청 간 15초 지연을 지킨다.</b> 공개 TSA가 연속 호출을 거부하기 때문이며,
     * 이것이 "매 로그마다"가 아니라 "배치 앵커링"이어야 하는 실무적 이유다.
     *
     * <p>실패해도 예외를 던지지 않는다 — 앵커링 실패가 서비스 장애가 되면 안 된다.
     * 실패 배치는 PENDING으로 남아 다음 호출에서 재시도된다.
     */
    @Transactional
    public AnchorReport anchorPendingBatches() {
        List<AuditBatch> pending = batchRepository.findAllByOrderByIdAsc().stream()
                .filter(b -> b.getAnchorStatus() != AuditBatch.AnchorStatus.ANCHORED)
                .toList();

        if (pending.isEmpty()) {
            return new AnchorReport(0, 0, 0, tsaClient.isEnabled(), List.of());
        }
        if (!tsaClient.isEnabled()) {
            return new AnchorReport(pending.size(), 0, 0, false,
                    List.of("TSA 비활성화 — finntech.tsa.enabled=true 로 켜세요"));
        }

        int ok = 0, failed = 0;
        List<String> messages = new ArrayList<>();

        for (int i = 0; i < pending.size(); i++) {
            if (i > 0) sleepBetweenRequests();

            AuditBatch batch = pending.get(i);
            TsaClient.TsaResult result = tsaClient.stamp(batch.getBatchRoot());

            if (result.ok()) {
                batch.setTsaResponse(result.tsrBase64());
                batch.setTsaQuery(result.tsqBase64());
                batch.setTsaGenTime(result.genTime());
                batch.setTsaName(result.tsaName());
                batch.setAnchorError(null);
                batch.setAnchorStatus(AuditBatch.AnchorStatus.ANCHORED);
                ok++;
                messages.add("batch=" + batch.getId() + " 앵커링 완료 (genTime=" + result.genTime() + ")");
            } else {
                batch.setAnchorStatus(AuditBatch.AnchorStatus.FAILED);
                batch.setAnchorError(result.message());
                failed++;
                messages.add("batch=" + batch.getId() + " 실패: " + result.message());
            }
            batchRepository.save(batch);
        }
        return new AnchorReport(pending.size(), ok, failed, true, messages);
    }

    private void sleepBetweenRequests() {
        try {
            Thread.sleep(anchorDelayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record AnchorReport(
            int pendingCount,
            int anchored,
            int failed,
            boolean tsaEnabled,
            List<String> messages
    ) {}

    /**
     * 검증 (문서 §5-4 검증 절차). 시연에서 DB의 한 행을 UPDATE로 바꾼 뒤 이걸 돌려
     * <b>어느 엔트리에서 체인이 깨지는지</b> 화면에 보여준다.
     */
    @Transactional(readOnly = true)
    public VerificationResult verify() {
        List<AuditLog> logs = logRepository.findAllByOrderBySeqAsc();
        List<String> problems = new ArrayList<>();

        String expectedPrev = Hashing.ZERO_HASH;
        Long firstBrokenSeq = null;

        for (AuditLog log : logs) {
            if (!log.getPrevHash().equals(expectedPrev)) {
                problems.add("seq=" + log.getSeq() + ": prev_hash 불일치 (체인 끊김)");
                if (firstBrokenSeq == null) firstBrokenSeq = log.getSeq();
            }
            String recomputed = Hashing.entryHash(log.getPrevHash(), log.getPayloadJson());
            if (!recomputed.equals(log.getEntryHash())) {
                problems.add("seq=" + log.getSeq() + ": entry_hash 불일치 (페이로드 변조)");
                if (firstBrokenSeq == null) firstBrokenSeq = log.getSeq();
            }
            expectedPrev = log.getEntryHash();
        }

        // 계층 2 — 배치별 Merkle 루트 재계산 / 계층 3 — 앵커 검증
        List<AuditBatch> batches = batchRepository.findAllByOrderByIdAsc();
        List<AnchorStatusView> anchorViews = new ArrayList<>();
        String expectedPrevRoot = Hashing.ZERO_HASH;

        for (AuditBatch b : batches) {
            List<String> hashes = logRepository
                    .findBySeqBetweenOrderBySeqAsc(b.getFromSeq(), b.getToSeq())
                    .stream().map(AuditLog::getEntryHash).toList();
            String recomputed = Hashing.merkleRoot(hashes);
            if (!recomputed.equals(b.getBatchRoot())) {
                problems.add("batch=" + b.getId() + ": Merkle 루트 불일치");
            }
            if (!b.getPrevBatchRoot().equals(expectedPrevRoot)) {
                problems.add("batch=" + b.getId() + ": 배치 체인 끊김");
            }
            expectedPrevRoot = b.getBatchRoot();

            // 계층 3 — 저장된 .tsr을 다시 파싱해 messageImprint가 현재 루트와 맞는지 확인.
            // 여기서 불일치가 나면 "DB 전체 재생성" 공격이 잡힌 것이다. 계층 1·2로는 못 잡는다.
            String anchorProblem = null;
            java.time.Instant genTime = b.getTsaGenTime();
            if (b.getAnchorStatus() == AuditBatch.AnchorStatus.ANCHORED && b.getTsaResponse() != null) {
                TsaClient.VerifyResult vr = tsaClient.verifyStoredToken(b.getTsaResponse(), b.getBatchRoot());
                if (!vr.valid()) {
                    anchorProblem = vr.problem();
                    problems.add("batch=" + b.getId() + ": TSA 앵커 검증 실패 — " + vr.problem());
                } else {
                    genTime = vr.genTime();
                }
            }
            anchorViews.add(new AnchorStatusView(
                    b.getId(), b.getBatchRoot(), b.getAnchorStatus().name(),
                    genTime, b.getTsaName(),
                    anchorProblem != null ? anchorProblem : b.getAnchorError()));
        }

        long anchored = batches.stream()
                .filter(b -> b.getAnchorStatus() == AuditBatch.AnchorStatus.ANCHORED).count();

        return new VerificationResult(
                problems.isEmpty(), logs.size(), batches.size(), anchored,
                firstBrokenSeq, problems, anchorViews);
    }

    public record VerificationResult(
            boolean valid,
            int entryCount,
            int batchCount,
            long anchoredBatchCount,
            Long firstBrokenSeq,
            List<String> problems,
            List<AnchorStatusView> batches
    ) {}

    /** 배치별 앵커 상태 — 화면에 "언제 제3자가 서명했는가"를 보여주기 위함. */
    public record AnchorStatusView(
            Long batchId,
            String batchRoot,
            String anchorStatus,
            java.time.Instant tsaGenTime,
            String tsaName,
            String problem
    ) {}
}
