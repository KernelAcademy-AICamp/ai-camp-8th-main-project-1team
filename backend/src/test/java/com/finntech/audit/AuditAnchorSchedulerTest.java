package com.finntech.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>앵커링을 부르는 사람이 있는가.</b>
 *
 * <p>변조 방어 세 겹 중 ③(RFC 3161 타임스탬프 + 외부 사본)이 <b>3주 동안 한 번도 안 돌았다</b> —
 * TSA 키도 S3 Object Lock 버킷도 다 만들어 두고 {@code anchorPendingBatches()} 를 부르는 곳이
 * HTTP 엔드포인트 하나뿐이었다(2026-08-13 운영 실측: {@code audit_batch} PENDING 1건이
 * 2026-07-23 부터 그대로, 버킷 {@code Total Objects: 0}).
 *
 * <p>고친 것은 코드 몇 줄이지만, <b>다시 잠들지 않는 것</b>이 이 시험의 목적이다.
 */
class AuditAnchorSchedulerTest {

    @Test
    @DisplayName("회차마다 앵커링을 부른다 — 이것이 없으면 방어의 3분의 2가 잠든다")
    void callsAnchoringEveryTick() {
        AuditService service = mock(AuditService.class);
        when(service.anchorPendingBatches())
                .thenReturn(new AuditService.AnchorReport(0, 0, 0, true, List.of()));

        new AuditAnchorScheduler(service).anchorPending();

        verify(service, times(1)).anchorPendingBatches();
    }

    @Test
    @DisplayName("앵커링이 터져도 회차가 죽지 않는다 — 다음 회차가 다시 집는다")
    void survivesAFailure() {
        AuditService service = mock(AuditService.class);
        doThrow(new IllegalStateException("TSA 응답 없음")).when(service).anchorPendingBatches();

        AuditAnchorScheduler scheduler = new AuditAnchorScheduler(service);

        // 여기서 예외가 새면 스프링이 그 스케줄을 멈춘다 — **한 번의 TSA 장애로 앵커링이
        // 영영 안 돌게 된다.** 실패 배치는 PENDING 으로 남으므로 삼켜도 잃는 것이 없다.
        assertThatCode(scheduler::anchorPending).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TSA 가 꺼져 있어도 부르기는 한다 — 켜는 순간 밀린 것이 나간다")
    void stillRunsWhenTsaIsOff() {
        AuditService service = mock(AuditService.class);
        when(service.anchorPendingBatches())
                .thenReturn(new AuditService.AnchorReport(3, 0, 0, false,
                        List.of("TSA 비활성화")));

        new AuditAnchorScheduler(service).anchorPending();

        verify(service, times(1)).anchorPendingBatches();
    }
}
