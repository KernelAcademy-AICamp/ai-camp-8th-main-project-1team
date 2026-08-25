package com.finntech.freechannel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 큐가 지켜야 할 네 가지 — 접기·순서·예산·상한.
 *
 * <p>넷 다 <b>안 지켜도 기능은 돈다</b>는 공통점이 있다. 중복이 나가도 문장은 만들어지고,
 * 순서가 뒤집혀도 언젠가는 처리되며, 예산을 넘겨도 대개는 통과한다. 그래서 시험이 없으면
 * 조용히 무너진다.
 */
class FreeChannelQueueTest {

    private static FreeChannelQueue queue(int perMinute, int concurrency, int maxQueued) {
        return new FreeChannelQueue(perMinute, concurrency, maxQueued);
    }

    /**
     * <b>트리거가 사용자의 아무 상호작용</b>이라 같은 스캔이 몇 초 사이에 여러 번 돈다.
     * 접지 않으면 같은 문장을 열 번 만들고 통로에는 열 번 나간다.
     */
    @Test
    @DisplayName("같은 키는 접힌다 — 대기 중이든, 지금 나가 있든")
    void identicalKeysAreFolded() throws Exception {
        FreeChannelQueue q = queue(600, 1, 100);
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);

        assertThat(q.submit(Lane.USER_NOW, "same", () -> {
            running.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        })).isTrue();
        assertThat(q.submit(Lane.USER_NOW, "same", () -> {})).as("대기 중이면 안 들어간다").isFalse();

        q.dispatch();
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

        // **여기가 요점이다.** 꺼내 나갔다고 키를 지우면, 아직 안 끝난 일을 다음 스캔이 또 넣는다.
        assertThat(q.submit(Lane.USER_NOW, "same", () -> {}))
                .as("나가 있는 동안에도 안 들어간다").isFalse();

        release.countDown();
    }

    /** 끝나면 다시 받아야 한다 — 안 그러면 그 일은 두 번 다시 못 한다. */
    @Test
    @DisplayName("끝난 뒤에는 같은 키를 다시 받는다")
    void keysAreReusableAfterCompletion() {
        FreeChannelQueue q = queue(600, 1, 100);
        q.submit(Lane.USER_NOW, "once", () -> {});
        q.dispatch();
        await(() -> q.queued() == 0);
        // 실행이 끝나 키가 빠질 때까지 잠깐 기다린다.
        await(() -> q.submit(Lane.USER_NOW, "once", () -> {}));
    }

    /**
     * 차선을 안 지키면 브랜드 273곳이 실사용자의 문장을 굶긴다 — 화면이 템플릿에 머문다.
     */
    @Test
    @DisplayName("위 차선이 먼저 나가고, 아래 차선은 회차당 몇 건만 나간다")
    void higherLanesGoFirstAndLowerLanesAreRationed() {
        // 예산·동시성은 넉넉히 — 여기서 보는 것은 순서와 아래 차선의 몫뿐이다.
        FreeChannelQueue q = queue(600, 8, 100);
        List<String> order = new CopyOnWriteArrayList<>();

        for (int i = 0; i < 5; i++) {
            String n = "dummy" + i;
            q.submit(Lane.DUMMY, n, () -> order.add(n));
        }
        for (int i = 0; i < 3; i++) {
            String n = "now" + i;
            q.submit(Lane.USER_NOW, n, () -> order.add(n));
        }

        q.dispatch();
        await(() -> order.size() >= 5);

        // **실행 순서가 아니라 '무엇이 꺼내졌는가'를 본다.** 풀이 여덟이라 다섯이 동시에 뜨고,
        // 어느 스레드가 먼저 `order` 에 담느냐는 스케줄러 마음이다 — 순서를 단언하면 시험이
        // 이따금 실패한다(2026-08-08 감사에서 300회 중 4회 실패로 지적됐다).
        List<String> ran = new ArrayList<>(order);
        assertThat(ran).as("실사용자의 급한 것은 한 회차에 전부 나간다")
                .contains("now0", "now1", "now2");
        assertThat(ran.stream().filter(s -> s.startsWith("dummy")).count())
                .as("아래 차선은 한 회차에 몰아 나가지 않는다").isEqualTo(2);
    }

    /**
     * 예산이 없으면 통로가 거절하고, 거절이 쌓이면 통로가 통째로 막힌다.
     *
     * <p><b>천장이 예전보다 넓다</b>(2026-08-21). 예산을 <i>작업</i>이 아니라 <i>호출</i>로
     * 세면서, 가장 비싼 일({@link FreeChannelQueue#MAX_CALLS_PER_JOB} 회)이 한 번에 살 수
     * 있도록 천장을 그만큼 올렸다. 안 올리면 3회짜리 분류가 <b>영영 못 나가고</b> 큐만 찬다.
     *
     * <p>그래서 여기서 잠그는 것은 "회차당 몇 건"이 아니라 <b>회차당 몇 호출</b>이다 —
     * 장기 처리량이 {@code perMinute} 로 수렴한다는 성질은 그대로다.
     */
    @Test
    @DisplayName("한 회차에 예산을 넘겨 내보내지 않는다")
    void oneTickNeverExceedsTheBudget() {
        FreeChannelQueue q = queue(30, 4, 100);       // 30/분 → 2초치 1호출
        List<String> ran = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 10; i++) {
            String n = "job" + i;
            q.submit(Lane.USER_NOW, n, () -> ran.add(n));
        }

        q.dispatch();
        await(() -> !ran.isEmpty());

        // 한 회차치(1) + 쌓아 둘 수 있는 몫(가장 비싼 일 하나) = 최대 네 호출.
        int ceiling = 1 + FreeChannelQueue.MAX_CALLS_PER_JOB;
        assertThat(ran.size()).as("천장을 넘겨 몰아 내보내지 않는다").isLessThanOrEqualTo(ceiling);
        assertThat(q.queued()).as("나머지는 큐에 남는다").isGreaterThan(0);
    }

    /**
     * <b>비싼 일이 굶으면 안 된다.</b> 천장을 안 올렸을 때 실제로 생기던 일 —
     * 3회짜리 분류가 큐에 들어가서 나오지 못하고, 그 뒤 같은 키는 {@code submit} 이 접었다.
     */
    @Test
    @DisplayName("가장 비싼 일도 결국 나간다")
    void theMostExpensiveJobStillGoesOut() {
        FreeChannelQueue q = queue(30, 4, 100);
        List<String> ran = new CopyOnWriteArrayList<>();
        q.submit(Lane.USER_NOW, "three", FreeChannelQueue.MAX_CALLS_PER_JOB, () -> ran.add("three"));

        for (int i = 0; i < 50; i++) q.dispatch();
        await(() -> !ran.isEmpty());

        assertThat(ran).containsExactly("three");
    }

    /**
     * 상한이 없는 큐는 문제가 생겼을 때 조용히 메모리를 먹다가 터지고, 그때는 원인이 큐라는
     * 것도 안 보인다. 버려도 안전하다 — 다음 상호작용이 다시 넣는다.
     */
    @Test
    @DisplayName("상한을 넘으면 가장 낮은 차선부터 버린다")
    void overflowDropsTheLowestLaneFirst() {
        FreeChannelQueue q = queue(600, 1, 4);
        for (int i = 0; i < 4; i++) q.submit(Lane.DUMMY, "d" + i, () -> {});
        for (int i = 0; i < 3; i++) q.submit(Lane.USER_NOW, "n" + i, () -> {});

        assertThat(q.queued()).isLessThanOrEqualTo(4);
        // 실사용자의 급한 것은 살아 있어야 한다 — 버릴 것은 아래 차선이다.
        assertThat(q.submit(Lane.USER_NOW, "n0", () -> {}))
                .as("아직 큐에 있으므로 접힌다 = 안 버려졌다").isFalse();
    }

    /**
     * 예산을 낮추면 <b>느려지는 것이 아니라 통째로 멎었다.</b> 회차당 토큰이 한 건에 못 미치면
     * 상한이 그것을 잘라 영원히 문턱을 못 넘었다 — 로그도 안 남고 큐만 상한까지 찬다.
     */
    @Test
    @DisplayName("예산을 30/분 아래로 낮춰도 통로가 멎지 않는다")
    void lowBudgetsStillDispatch() {
        FreeChannelQueue q = queue(20, 4, 100);       // 회차당 0.666 토큰
        List<String> ran = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 5; i++) {
            String n = "job" + i;
            q.submit(Lane.USER_NOW, n, () -> ran.add(n));
        }
        for (int i = 0; i < 5; i++) q.dispatch();     // 10초치
        await(() -> !ran.isEmpty());
    }

    /**
     * 나누어떨어지지 않는 예산에서 소수 토큰이 버려지면 40/분 설정이 30/분으로 동작한다 —
     * 그러면 "40/분 → 동시성 6" 이라는 유도가 무너져 슬롯이 놀고 처리량이 모자란다.
     */
    @Test
    @DisplayName("나누어떨어지지 않는 예산도 장기 처리량이 설정값에 수렴한다")
    void fractionalBudgetsAreNotLost() {
        FreeChannelQueue q = queue(40, 8, 500);       // 회차당 1.333 토큰
        List<String> ran = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 100; i++) {
            String n = "job" + i;
            q.submit(Lane.USER_NOW, n, () -> ran.add(n));
        }
        // 회차 사이에 숨을 준다 — 실제 발사기는 2초 간격이라 슬롯이 늘 비어 있다.
        // 틈 없이 30번 부르면 풀이 못 따라와 되돌리기만 반복하고, 그건 예산 산술과 무관하다.
        for (int i = 0; i < 30; i++) {
            q.dispatch();
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        await(() -> ran.size() >= 38);                // 30 에 고정되면 여기서 실패한다
    }

    /**
     * 슬롯이 모자랄 때 하나만 되돌리면 <b>나머지가 영구히 사라진다</b> — 덱에서는 빠졌는데
     * {@code known} 에는 남아 실행도 재투입도 안 된다.
     */
    @Test
    @DisplayName("풀이 찼을 때 이번 회차에 꺼낸 것을 전부 되돌린다")
    void nothingIsLostWhenThePoolIsFull() throws Exception {
        FreeChannelQueue q = queue(600, 1, 100);      // 예산은 넉넉, 슬롯은 하나
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        q.submit(Lane.USER_NOW, "blocker", () -> {
            running.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        for (int i = 0; i < 9; i++) q.submit(Lane.USER_NOW, "job" + i, () -> {});

        q.dispatch();
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(q.queued()).as("되돌린 9건이 살아 있어야 한다").isEqualTo(9);
        assertThat(q.submit(Lane.USER_NOW, "job8", () -> {}))
                .as("큐에 있으니 접힌다 — 유령이 아니다").isFalse();

        release.countDown();
    }

    private static void await(java.util.function.BooleanSupplier until) {
        for (int i = 0; i < 200; i++) {
            if (until.getAsBoolean()) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("기다렸는데 조건이 안 됐다");
    }
}
