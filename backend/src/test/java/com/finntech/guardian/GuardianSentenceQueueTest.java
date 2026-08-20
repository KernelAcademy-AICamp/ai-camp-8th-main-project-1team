package com.finntech.guardian;

import com.finntech.guardian.domain.GuardianEnums.PhrasingMode;
import com.finntech.guardian.domain.GuardianEnums.Tone;
import com.finntech.guardian.domain.GuardianNotification;
import com.finntech.guardian.repository.GuardianNotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>화면은 문장을 기다리지 않는다.</b>
 *
 * <p>알림은 규칙이 만든 템플릿으로 <b>먼저</b> 저장돼 화면에 뜨고, 모델 문장은 뒤에서 받아
 * 갈아 끼운다. 그 갈아 끼우기가 하는 일과 <b>하지 말아야 할 일</b>을 잠근다 —
 * 못 받았을 때 알림을 망가뜨리지 않는 것이 특히 중요하다. 폴백이 먼저인 구조에서
 * "문장을 못 받았다"는 정상이지 실패가 아니다.
 */
class GuardianSentenceQueueTest {

    /** 같은 스레드에서 곧바로 돌린다 — 배경 스레드로 도망가면 검사할 수가 없다. */
    private static final Executor NOW = Runnable::run;

    private GuardianNotification template() {
        return GuardianNotification.spoken(1L, 2L, 3L, "C1", Tone.SOFT_REMINDER,
                PhrasingMode.DEFINITIVE, com.finntech.guardian.domain.GuardianEnums.DeliveryKind.PUSH,
                "템플릿 제목", "템플릿 본문", List.of("고정구"), true, "v1", LocalDateTime.now());
    }

    private GuardianSentenceQueue.Job job() {
        return new GuardianSentenceQueue.Job(7L, "C1", Tone.SOFT_REMINDER, PhrasingMode.DEFINITIVE,
                Map.of("amount", "10,000"), List.of(), true);
    }

    @Test
    @DisplayName("모델 문장을 받으면 그 알림을 갈아 끼운다")
    void 받으면_갈아_끼운다() {
        GuardianNarrative narrative = mock(GuardianNarrative.class);
        when(narrative.aiEnabled()).thenReturn(true);
        when(narrative.compose(anyString(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(new GuardianNarrative.Message("모델 제목", "모델 본문", List.of("새 표현"), false));
        GuardianNotificationRepository repo = mock(GuardianNotificationRepository.class);
        GuardianNotification saved = template();
        when(repo.findById(7L)).thenReturn(Optional.of(saved));

        new GuardianSentenceQueue(narrative, repo, NOW).submit(job());

        assertThat(saved.getTitle()).isEqualTo("모델 제목");
        assertThat(saved.getBody()).isEqualTo("모델 본문");
        assertThat(saved.isFallback()).isFalse();
        verify(repo).save(saved);
    }

    @Test
    @DisplayName("못 받으면 템플릿을 그대로 둔다 — 알림이 망가지지 않는다")
    void 못_받으면_그대로_둔다() {
        GuardianNarrative narrative = mock(GuardianNarrative.class);
        when(narrative.aiEnabled()).thenReturn(true);
        // 폴백으로 떨어진 답 — 통로 장애·길이 초과·JSON 깨짐 전부 이 모양으로 온다.
        when(narrative.compose(anyString(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(new GuardianNarrative.Message("템플릿 제목", "템플릿 본문", List.of(), true));
        GuardianNotificationRepository repo = mock(GuardianNotificationRepository.class);
        GuardianNotification saved = template();
        when(repo.findById(7L)).thenReturn(Optional.of(saved));

        new GuardianSentenceQueue(narrative, repo, NOW).submit(job());

        assertThat(saved.getTitle()).isEqualTo("템플릿 제목");
        assertThat(saved.isFallback()).isTrue();
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("모델이 죽어도 알림은 살아 있다")
    void 예외가_나도_알림은_산다() {
        GuardianNarrative narrative = mock(GuardianNarrative.class);
        when(narrative.aiEnabled()).thenReturn(true);
        when(narrative.compose(anyString(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenThrow(new IllegalStateException("통로 장애"));
        GuardianNotificationRepository repo = mock(GuardianNotificationRepository.class);

        // 던지지 않는 것이 이 시험의 전부다 — 배경 일꾼의 예외가 위로 새면 안 된다.
        new GuardianSentenceQueue(narrative, repo, NOW).submit(job());

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("더미에는 모델을 부르지 않는다")
    void 더미는_안_부른다() {
        GuardianNarrative narrative = mock(GuardianNarrative.class);
        when(narrative.aiEnabled()).thenReturn(true);
        GuardianNotificationRepository repo = mock(GuardianNotificationRepository.class);
        var dummy = new GuardianSentenceQueue.Job(7L, "C1", Tone.SOFT_REMINDER,
                PhrasingMode.DEFINITIVE, Map.of(), List.of(), false);

        new GuardianSentenceQueue(narrative, repo, NOW).submit(dummy);

        verify(narrative, never()).compose(anyString(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("이미 모델 문장이 든 알림은 다시 안 덮는다")
    void 이미_모델_문장이면_안_덮는다() {
        GuardianNotification already = GuardianNotification.spoken(1L, 2L, 3L, "C1",
                Tone.SOFT_REMINDER, PhrasingMode.DEFINITIVE,
                com.finntech.guardian.domain.GuardianEnums.DeliveryKind.PUSH,
                "먼저 온 모델 제목", "먼저 온 모델 본문", List.of(), false, "v1", LocalDateTime.now());

        boolean changed = already.upgradeSentence("나중 제목", "나중 본문", List.of());

        assertThat(changed).isFalse();
        assertThat(already.getTitle()).isEqualTo("먼저 온 모델 제목");
    }

    @Test
    @DisplayName("큐가 가득 차면 조용히 건너뛴다 — 알림은 이미 저장돼 있다")
    void 큐가_차면_건너뛴다() {
        GuardianNarrative narrative = mock(GuardianNarrative.class);
        when(narrative.aiEnabled()).thenReturn(true);
        GuardianNotificationRepository repo = mock(GuardianNotificationRepository.class);
        Executor full = r -> { throw new java.util.concurrent.RejectedExecutionException("가득"); };

        new GuardianSentenceQueue(narrative, repo, full).submit(job());

        verify(repo, never()).findById(anyLong());
    }
}
