package com.finntech.service;

import com.finntech.freechannel.FreeChannelQueue;
import com.finntech.freechannel.Lane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>무료가 먼저, 유료는 비상용</b> — 그리고 무료는 반드시 큐를 거친다.
 *
 * <p>다섯 서비스가 각자 Gemini {@code RestClient} 를 들고 있었다. 전부 유료인데, 실측에서
 * 무료 1위가 유료보다 정확했고(72.3% 대 70.5%) 값이 0 이다. 사용자 결정(2026-08-21)으로
 * 이 관문에 모았다 — <i>"지금 즉시 답해야 하는 경우가 아니면 전부 무료로."</i>
 *
 * <p>여기서 잠그는 것은 셋이다 — ① 무료가 큐를 거치는가 ② 무료가 꺼져 있으면 조용히
 * 비켜서 부르는 쪽이 유료로 가는가 ③ 화면 요청이 오래 안 멈추는가.
 */
class ModelGatewayTest {

    private FreeChannelQueue realQueue() {
        return new FreeChannelQueue(40, 6, 500);
    }

    @Test
    @DisplayName("무료가 꺼져 있으면 큐를 건드리지 않고 비켜선다 — 부르는 쪽이 유료로 간다")
    void offMeansStepAside() {
        TempClassifierService free = mock(TempClassifierService.class);
        when(free.usable()).thenReturn(false);
        FreeChannelQueue queue = mock(FreeChannelQueue.class);

        ModelGateway gateway = new ModelGateway(free, queue);

        assertThat(gateway.freeUsable()).isFalse();
        assertThat(gateway.askNow(Lane.USER_NOW, "k", "프롬프트")).isEmpty();
        assertThat(gateway.submit(Lane.USER_BACKGROUND, "k", "프롬프트", s -> { })).isFalse();
        verify(queue, never()).submit(org.mockito.ArgumentMatchers.any(), anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    /**
     * <b>큐를 거치는 것이 이 관문의 존재 이유다.</b> 직접 부르면 분당 예산과 차선이 무의미해진다
     * ({@code OneDoorTest} 가 같은 규약을 소스 수준에서 지킨다).
     */
    @Test
    @DisplayName("무료는 큐에 올려서 부른다")
    void freeGoesThroughTheQueue() {
        TempClassifierService free = mock(TempClassifierService.class);
        when(free.usable()).thenReturn(true);
        when(free.sentence(anyString())).thenReturn(Optional.of("답"));
        FreeChannelQueue queue = mock(FreeChannelQueue.class);
        when(queue.submit(org.mockito.ArgumentMatchers.any(), anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);

        gatewaySubmit(new ModelGateway(free, queue));

        verify(queue).submit(org.mockito.ArgumentMatchers.eq(Lane.USER_BACKGROUND),
                org.mockito.ArgumentMatchers.eq("k"), org.mockito.ArgumentMatchers.any());
    }

    private void gatewaySubmit(ModelGateway gateway) {
        gateway.submit(Lane.USER_BACKGROUND, "k", "프롬프트", s -> { });
    }

    /**
     * 큐는 {@code @Scheduled(fixedDelay=2000)} 로 비워진다 — 단위 시험에는 스케줄러가 없으므로
     * {@code dispatch()} 를 직접 돌려 준다. 그러지 않으면 관문이 3초를 기다렸다 비어서 온다.
     */
    private void drainSoon(FreeChannelQueue queue) {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 30; i++) {
                queue.dispatch();
                try { Thread.sleep(20); } catch (InterruptedException e) { return; }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @Test
    @DisplayName("올린 일이 끝나면 그 답을 준다")
    void answersWhenTheQueueFinishes() {
        TempClassifierService free = mock(TempClassifierService.class);
        when(free.usable()).thenReturn(true);
        when(free.sentence(anyString())).thenReturn(Optional.of("커피 전문점"));
        FreeChannelQueue queue = realQueue();

        ModelGateway gateway = new ModelGateway(free, queue);
        drainSoon(queue);

        assertThat(gateway.askNow(Lane.USER_NOW, "k1", "프롬프트")).contains("커피 전문점");
    }

    /**
     * 못 받는 것은 실패가 아니다 — 부르는 쪽은 전부 폴백(규칙 파서·템플릿·빈 결과)을 갖고 있다.
     */
    @Test
    @DisplayName("무료가 답을 못 주면 비어서 돌아간다")
    void emptyWhenFreeHasNoAnswer() {
        TempClassifierService free = mock(TempClassifierService.class);
        when(free.usable()).thenReturn(true);
        when(free.sentence(anyString())).thenReturn(Optional.empty());

        FreeChannelQueue queue = realQueue();
        ModelGateway gateway = new ModelGateway(free, queue);
        drainSoon(queue);

        assertThat(gateway.askNow(Lane.USER_NOW, "k2", "프롬프트")).isEmpty();
    }

    /**
     * <b>같은 일을 두 번 올리지 않는다.</b> 큐가 키로 접으므로 두 번째는 거절되고, 그때
     * 기다리면 영영 안 온다 — 곧바로 비켜서 다음 요청이 받게 한다.
     */
    @Test
    @DisplayName("같은 키가 이미 대기 중이면 기다리지 않는다")
    void doesNotWaitOnADuplicate() {
        TempClassifierService free = mock(TempClassifierService.class);
        when(free.usable()).thenReturn(true);
        FreeChannelQueue queue = mock(FreeChannelQueue.class);
        when(queue.submit(org.mockito.ArgumentMatchers.any(), anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(false);   // 이미 있다

        long began = System.nanoTime();
        Optional<String> got = new ModelGateway(free, queue).askNow(Lane.USER_NOW, "k", "프롬프트");
        long tookMs = (System.nanoTime() - began) / 1_000_000;

        assertThat(got).isEmpty();
        assertThat(tookMs).as("기다리면 안 된다 — 그 일은 이미 남이 하고 있다").isLessThan(500);
    }
}
