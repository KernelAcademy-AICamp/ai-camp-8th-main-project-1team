package com.finntech.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * <b>감사 배치를 스스로 앵커링한다.</b>
 *
 * <h2>왜 만들었나 — 세 주 동안 아무것도 안 올라갔다</h2>
 *
 * <p>변조 방어는 세 겹이다. ① 해시 체인 ② 배치 루트 ③ <b>RFC 3161 타임스탬프 + 외부 사본</b>.
 * ③이 있어야 "DB 를 통째로 갈아엎고 도장을 새로 받아 바꿔치기하는" 공격이 막힌다 —
 * 타임스탬프는 '존재 증명'이지 '유일성 증명'이 아니라서, 사본이 <b>우리가 못 지우는 곳</b>에
 * 있어야 비교가 성립한다(S3 Object Lock · COMPLIANCE).
 *
 * <p>그런데 {@link AuditService#anchorPendingBatches()} 를 부르는 곳이 <b>HTTP 엔드포인트
 * 하나뿐</b>이었다({@code ApiController}). 스케줄러도 cron 도 없어서 아무도 안 불렀고,
 * 운영 실측 결과(2026-08-13):
 *
 * <pre>
 *   audit_batch   PENDING 1건 — 2026-07-23 부터 3주간 그대로
 *   S3 버킷        Total Objects: 0        ← 사본이 한 번도 안 올라갔다
 * </pre>
 *
 * <p>키도 버킷도 보존 정책도 다 만들어 두고 <b>부르는 사람이 없어</b> 방어의 3분의 2가 잠들어
 * 있었다. 설정이 켜져 있다는 것과 동작한다는 것은 다르다.
 *
 * <h2>왜 별도 클래스인가</h2>
 *
 * <p>{@code AuditService} 는 {@code @Transactional} 이고 앵커링은 <b>바깥 서버(TSA)를 부르며
 * 요청 사이에 15초를 쉰다.</b> 스케줄 애너테이션을 그 안에 붙이면 트랜잭션이 그 시간만큼 열려
 * 있게 된다. 부르는 자리를 밖에 두어 경계를 분명히 한다.
 *
 * <h2>간격</h2>
 *
 * <p><b>{@code fixedDelay} 다.</b> 배치가 여럿 밀려 있으면 한 회차가 길어지는데(건당 15초),
 * {@code fixedRate} 면 그동안 다음 회차가 겹쳐 들어와 공개 TSA 가 거절한다. 이전 실행이
 * <b>끝난 뒤부터</b> 센다 — 자동 동기화 배치와 같은 규칙이다.
 *
 * <p>기본 한 시간이다. 감사 배치는 자주 생기지 않고, 늦게 찍혀도 <b>찍히기만 하면</b> 증명이
 * 성립한다. 자주 두드려서 얻는 것보다 남의 서버를 덜 괴롭히는 쪽이 낫다.
 */
@Component
@ConditionalOnProperty(name = "finntech.audit.anchor-schedule.enabled",
        havingValue = "true", matchIfMissing = true)
public class AuditAnchorScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuditAnchorScheduler.class);

    private final AuditService auditService;

    public AuditAnchorScheduler(AuditService auditService) {
        this.auditService = auditService;
    }

    @Scheduled(
            fixedDelayString = "${finntech.audit.anchor-schedule.interval-ms:3600000}",
            initialDelayString = "${finntech.audit.anchor-schedule.initial-delay-ms:120000}")
    public void anchorPending() {
        try {
            AuditService.AnchorReport report = auditService.anchorPendingBatches();
            if (report.pendingCount() == 0) {
                // 대부분의 회차가 여기다. INFO 로 남기면 한 시간마다 "0건"이 로그를 채운다.
                log.debug("앵커링할 배치 없음");
                return;
            }
            // **여기는 INFO 다.** 앵커링은 드물게 일어나고, 일어났다는 사실 자체가 기록이다.
            log.info("감사 앵커링 — 대상 {}건, 성공 {}건, 실패 {}건, TSA {}",
                    report.pendingCount(), report.anchored(), report.failed(),
                    report.tsaEnabled() ? "켜짐" : "꺼짐");
            report.messages().forEach(message -> log.info("  {}", message));
        } catch (RuntimeException exception) {
            // **앵커링 실패가 서비스 장애가 되면 안 된다.** 실패 배치는 PENDING 으로 남아
            // 다음 회차가 다시 집는다 — 그 재시도 구조가 있으므로 여기서 삼켜도 잃는 것이 없다.
            log.warn("감사 앵커링 회차 실패 — 다음 회차가 다시 시도한다: {}", exception.toString());
        }
    }
}
