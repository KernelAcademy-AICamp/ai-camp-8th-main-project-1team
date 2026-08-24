package com.finntech.freechannel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>예산은 작업이 아니라 호출을 센다.</b>
 *
 * <p>업종 분류 한 건은 3단계라 통로에 HTTP 를 셋 낸다. 그런데 큐는 토큰을 하나만 뺐다 —
 * {@code per-minute: 40} 이면 큐는 분당 40이라 믿지만 실제로는 <b>분당 120</b>이 나갔다
 * (2026-08-21 감사). 차선 배분도 같이 흐려진다: {@code LOW_LANE_PER_TICK} 이 2 인데
 * 실제 호출은 6 이었다.
 *
 * <p>여기서 잠그는 것은 셋이다 — ① 값이 호출 수만큼 나가는가 ② 비싼 일이 굶지 않는가
 * ③ 예산이 모자라면 되돌려 두는가.
 */
class CallBudgetTest {

    /** perMinute 를 아주 작게 두면 회차당 토큰이 한 건에 못 미쳐 셈이 눈에 보인다. */
    private FreeChannelQueue queue(int perMinute) {
        return new FreeChannelQueue(perMinute, 6, 500);
    }

    private Runnable count(AtomicInteger ran) {
        return ran::incrementAndGet;
    }

    /**
     * 작업은 전용 스레드풀에서 돈다 — {@code dispatch()} 가 돌아왔다고 끝난 것이 아니다.
     * 기대값에 닿거나 시간이 다 될 때까지 기다린다.
     */
    private static void awaitAtLeast(AtomicInteger ran, int expected) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (ran.get() < expected && System.nanoTime() < deadline) {
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    /** 아무것도 안 나갔음을 보이려면 잠깐 기다렸다 봐야 한다 — 늦게 나갈 수도 있으니. */
    private static void settle() {
        try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Test
    @DisplayName("3회짜리 일은 1회짜리 셋만큼 값을 낸다")
    void 값은_호출_수만큼() {
        AtomicInteger ran = new AtomicInteger();
        // 분당 15 → 회차당 500. 생성자가 미리 채우는 몫(500+999)을 더해도 첫 회차는 1,999 라
        // 3회짜리(3,000)를 못 산다. 1회짜리였다면 첫 회차에 바로 나갔을 자리다.
        FreeChannelQueue q = queue(15);
        q.submit(Lane.USER_NOW, "three", 3, count(ran));

        q.dispatch();
        settle();
        int afterFirst = ran.get();
        for (int i = 0; i < 10; i++) q.dispatch();     // 몇 회차 더 채우면 살 수 있다
        awaitAtLeast(ran, 1);

        assertThat(afterFirst).as("첫 회차엔 예산이 모자라 못 나간다").isZero();
        assertThat(ran.get()).as("모이면 나간다 — 굶으면 안 된다").isEqualTo(1);
    }

    /**
     * <b>가장 비싼 일이 천장보다 비싸면 영영 못 나간다.</b> 예산을 호출 단위로 세면서
     * {@code refill} 의 천장도 같이 올렸다 — 그 짝이 맞는지 본다.
     */
    @Test
    @DisplayName("천장이 가장 비싼 일을 품는다 — 굶는 일이 없다")
    void 천장이_비싼_일을_품는다() {
        AtomicInteger ran = new AtomicInteger();
        FreeChannelQueue q = queue(1);                 // 극단 — 분당 1
        q.submit(Lane.USER_NOW, "three", FreeChannelQueue.MAX_CALLS_PER_JOB, count(ran));

        for (int i = 0; i < 200; i++) q.dispatch();    // 오래 기다리면 결국 나간다
        awaitAtLeast(ran, 1);

        assertThat(ran.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("1회짜리는 예전과 같다 — 문장·브랜드 작업이 느려지면 안 된다")
    void 한_회짜리는_그대로() {
        AtomicInteger ran = new AtomicInteger();
        FreeChannelQueue q = queue(60);
        q.submit(Lane.USER_NOW, "a", count(ran));
        q.submit(Lane.USER_NOW, "b", count(ran));

        q.dispatch();
        awaitAtLeast(ran, 2);

        assertThat(ran.get()).as("2,000 밀리토큰이면 1회짜리 둘은 한 회차에 나간다").isEqualTo(2);
    }

    /** 못 산 것은 <b>제자리로</b> 돌아가야 한다 — 덱에서 빠진 채 known 에만 남으면 사라진다. */
    @Test
    @DisplayName("예산이 모자라 못 꺼낸 것은 큐에 남는다")
    void 못_꺼낸_것은_남는다() {
        FreeChannelQueue q = queue(15);
        q.submit(Lane.USER_NOW, "three", 3, () -> { });

        q.dispatch();
        settle();

        assertThat(q.queued()).as("사라지면 안 된다").isEqualTo(1);
    }

    @Test
    @DisplayName("호출 수는 상한을 넘길 수 없다")
    void 상한을_넘길_수_없다() {
        AtomicInteger ran = new AtomicInteger();
        FreeChannelQueue q = queue(1);
        q.submit(Lane.USER_NOW, "huge", 99, count(ran));   // 상한으로 깎인다

        for (int i = 0; i < 200; i++) q.dispatch();
        awaitAtLeast(ran, 1);

        assertThat(ran.get()).as("상한으로 깎였으니 결국 나간다").isEqualTo(1);
    }
}
