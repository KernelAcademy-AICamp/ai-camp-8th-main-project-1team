package com.finntech.freechannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 무료 통로(NVIDIA)로 나가는 <b>모든</b> 요청이 지나는 한 문.
 *
 * <p><b>왜 한 문인가.</b> 통로가 정하는 것은 "분당 몇 번"이지 "누가 먼저"가 아니다. 부르는 쪽이
 * 각자 부르면 예산이 없는 것과 같고, 급한 것과 안 급한 것이 같은 속도로 나간다. 문을 하나로
 * 모으면 <b>예산과 순서를 한 곳에서</b> 정할 수 있다.
 *
 * <pre>
 *   ① 넣는다   submit(차선, 키, 할 일)   — 같은 키는 접힌다
 *   ② 2초마다  예산이 허락하는 만큼 꺼내 전용 풀에 던진다
 *   ③ 실패     아무것도 안 한다 — 다음 스캔이 다시 넣는다
 * </pre>
 *
 * <p><b>예산과 동시성은 따로 정하는 값이 아니다.</b> 한 건이 6~10초 걸리므로 순차로는 분당
 * 6~10건이 천장이다. 분당 40건을 내려면 <i>40/분 × 8초 ≈ 5</i> 건이 늘 떠 있어야 한다 —
 * 그래서 풀 크기가 예산에서 나온다. 반대로 풀만 키우면 예산을 넘겨 거절당한다. 둘은 짝이다.
 *
 * <p><b>큐는 할 일의 사본이지 원본이 아니다.</b> 재기동하면 대기 목록이 날아가는데 그것으로
 * 잃는 것이 없어야 한다 — 원본은 DB 상태(문장이 낡았다·브랜드가 없다)이고 그것은 안 날아간다.
 * 그래서 <b>여기 들어올 자격은 하나다: 다시 넣어도 안전하고, 다시 넣어질 근거가 코드에 있을 것.</b>
 * "이 결제에 알림 한 번 보내기" 같은 일은 넣으면 안 된다 — 사라지면 다시 넣어 줄 근거가 없다.
 *
 * <p><b>다중 인스턴스에서는 예산이 인스턴스 수만큼 곱해진다.</b> 버킷이 메모리에 있기 때문이다.
 * 지금은 단일 인스턴스라 문제가 없고, 늘리는 날 공유 버킷(예: Redis)으로 옮겨야 한다.
 */
@Component
public class FreeChannelQueue {

    private static final Logger log = LoggerFactory.getLogger(FreeChannelQueue.class);

    /** 한 번 꺼낼 때 {@link Lane#USER_REFRESH} 아래 차선에서 가져올 최대 건수. */
    private static final int LOW_LANE_PER_TICK = 2;

    private final int perMinute;
    private final int maxQueued;
    private final ExecutorService workers;
    private final Semaphore slots;

    /**
     * <b>이미 아는 일</b> — 대기 중이거나 지금 나가 있는 것 전부.
     *
     * <p>이것이 중복을 막는 자리다. 트리거가 <b>사용자의 아무 상호작용</b>이라 같은 스캔이
     * 몇 초 사이에 여러 번 돈다 — 페이지를 넘길 때마다 한 번씩이다. 그때 이미 큐에 있는 것을
     * 또 넣으면 같은 문장을 두 번 만들고, 통로에는 두 번 나간다.
     *
     * <p><b>대기와 진행을 함께 담는 것이 요점이다.</b> 대기만 보면, 방금 꺼내 나간 일이 아직
     * 안 끝났는데 다음 스캔이 같은 것을 또 넣는다. 그래서 키는 <b>끝날 때</b> 지운다
     * (성공이든 실패든).
     *
     * <p><b>여기가 막는 것은 "지금 진행 중"까지다 — "이미 끝냈다"는 못 막는다.</b> 끝나면 키가
     * 빠지므로, 그 뒤에 같은 것이 또 들어오는 것을 막는 일은 <b>넣는 쪽의 판단</b>에 있다.
     * 넣는 쪽은 "아직 필요한가"를 DB 상태로 물어야 하고, 그 물음이 <b>성공과 실패를 모두</b>
     * 반영해야 한다 — 성공은 결과가 적혀서 저절로 닫히지만, 실패는 아무것도 안 변해서
     * 안 닫힌다. 그래서 시도 기록(`attempted_at`·`failures`)이 필요하다(V25 주석 참조).
     * 그것이 없으면 통로 장애 하나가 <b>사용자가 페이지를 넘길 때마다</b> 예산을 먹는다.
     */
    private final Map<String, Job> known = new ConcurrentHashMap<>();

    /** 차선별 대기 줄. 키만 담고 실체는 {@link #known} 에 있다. */
    private final Map<Lane, ConcurrentLinkedDeque<String>> lanes = new EnumMap<>(Lane.class);

    /** 남은 토큰 ×1000 — 2초마다 조금씩 차오르므로 정수로는 표현이 안 된다. */
    private final AtomicLong milliTokens = new AtomicLong();
    /**
     * @param calls 이 일이 통로에 낼 <b>HTTP 호출 수</b>. 예산은 작업이 아니라 호출을 센다.
     */
    private record Job(Lane lane, String key, Runnable work, int calls) {}

    /**
     * 한 작업이 낼 수 있는 호출 수의 상한 — 예산 버킷의 천장이 이보다 낮으면 그 일은
     * <b>영영 못 나간다</b>. {@link #refill} 의 cap 이 이 값을 품는다.
     */
    public static final int MAX_CALLS_PER_JOB = 3;

    public FreeChannelQueue(@Value("${finntech.free-channel.per-minute:40}") int perMinute,
                            @Value("${finntech.free-channel.concurrency:6}") int concurrency,
                            @Value("${finntech.free-channel.max-queued:500}") int maxQueued) {
        this.perMinute = Math.max(1, perMinute);
        this.maxQueued = Math.max(1, maxQueued);
        this.slots = new Semaphore(Math.max(1, concurrency));
        this.workers = Executors.newFixedThreadPool(Math.max(1, concurrency), r -> {
            Thread t = new Thread(r, "free-channel");
            t.setDaemon(true);       // 종료를 막지 않는다 — 못 끝낸 일은 다음에 다시 들어온다
            return t;
        });
        for (Lane lane : Lane.values()) lanes.put(lane, new ConcurrentLinkedDeque<>());
        // 처음부터 한 회차분은 낼 수 있게 채워 둔다(상한과 같은 셈법).
        milliTokens.set(1000L * this.perMinute / 30 + 999L);
    }

    /**
     * 할 일을 넣는다 — <b>같은 키가 이미 있으면 접는다.</b>
     *
     * @param lane 차선. 기본값을 주는 오버로드는 만들지 않는다 — 고르지 않고 지나갈 길을 두면
     *             새 코드가 전부 그리로 간다({@link Lane}).
     * @param key  같은 일을 가리키는 이름. 이것이 같으면 같은 일이다.
     *             (예: {@code "narrative:24:REPORT"} · {@code "brand:GS25 강남역점"})
     * @return 새로 들어갔으면 true, 이미 있어서 접혔으면 false
     */
    public boolean submit(Lane lane, String key, Runnable work) {
        return submit(lane, key, 1, work);
    }

    /**
     * 할 일을 넣는다 — <b>낼 호출 수를 함께 알린다.</b>
     *
     * <p>예산은 작업이 아니라 <b>호출</b>을 센다. 업종 분류 한 건은 3단계라 호출이 셋인데
     * 예전에는 토큰을 하나만 뺐다 — {@code per-minute: 40} 이면 큐는 분당 40이라 믿지만
     * 통로에는 <b>분당 120</b>이 나갔다(2026-08-21 감사). 차선 배분도 같이 흐려진다:
     * {@code LOW_LANE_PER_TICK} 이 2 인데 실제로는 6 회였다.
     *
     * @param calls 이 일이 낼 HTTP 호출 수. {@link #MAX_CALLS_PER_JOB} 를 넘길 수 없다.
     */
    public boolean submit(Lane lane, String key, int calls, Runnable work) {
        if (lane == null || key == null || work == null) return false;
        int cost = Math.max(1, Math.min(MAX_CALLS_PER_JOB, calls));
        // **대기 중이거나 나가 있는 것은 다시 안 넣는다.** putIfAbsent 하나로 둘 다 걸린다 —
        // 키는 일이 끝날 때 지우기 때문이다.
        if (known.putIfAbsent(key, new Job(lane, key, work, cost)) != null) return false;
        lanes.get(lane).addLast(key);
        trimIfOverCapacity();
        return true;
    }

    /** 지금 대기 중인 일의 수 — 밀렸는지를 이 숫자 하나로 본다. */
    public int queued() {
        return lanes.values().stream().mapToInt(ConcurrentLinkedDeque::size).sum();
    }

    /**
     * <b>2초마다 예산이 허락하는 만큼 꺼낸다.</b> 없으면 아무것도 안 한다.
     *
     * <p>이 메서드 자체는 <b>빨라야 한다</b> — 꺼내서 풀에 던지고 곧바로 돌아온다. 스케줄러
     * 스레드를 붙잡으면 5분 동기화·지킴이 배치·04시 파기가 그만큼 밀린다.
     */
    @Scheduled(fixedDelay = 2000L)
    public void dispatch() {
        refill();
        List<String> batch = take();
        for (int i = 0; i < batch.size(); i++) {
            Job job = known.get(batch.get(i));
            if (job == null) continue;                  // 상한에 밀려 버려진 것
            if (!slots.tryAcquire()) {
                // **이번에 꺼낸 것을 전부 되돌린다.** 하나만 되돌리면 나머지는 덱에서 빠진 채
                // known 에는 남아, 실행도 안 되고 `submit` 도 접혀서 **프로세스가 죽을 때까지
                // 그 일이 사라진다**(2026-08-08 PR 직전 감사). 뒤에서부터 addFirst 하면 원래
                // 순서가 보존된다 — dispatch 는 fixedDelay 라 자기끼리 겹치지 않는다.
                for (int j = batch.size() - 1; j >= i; j--) {
                    Job back = known.get(batch.get(j));
                    if (back == null) continue;
                    lanes.get(back.lane()).addFirst(batch.get(j));
                    giveBack(back.calls());
                }
                return;
            }
            workers.execute(() -> run(job));
        }
    }

    private void run(Job job) {
        try {
            job.work().run();
        } catch (RuntimeException e) {
            // **실패해도 아무것도 안 한다.** 있던 값이 그대로 남고, 다음 스캔이 다시 넣는다.
            //
            // 거절(429) 뒤에 쉬는 판단은 여기가 아니라 통로가 한다
            // ({@code TempClassifierService.usable()} 의 유예). 여기에도 사본을 두었었는데,
            // 통로가 예외를 안쪽에서 삼켜 **이 자리까지 올라오지 않아 죽은 코드였다**
            // (2026-08-08 PR 직전 감사). 있는 척하는 층은 없느니만 못하다.
            log.debug("무료 통로 작업 실패 — 다음 회차에 다시. key={} : {}", job.key(), e.toString());
        } finally {
            slots.release();
            // **끝날 때 지운다.** 이 순간부터 같은 키가 다시 들어올 수 있다.
            known.remove(job.key());
        }
    }

    /**
     * 2초치를 채운다 — <b>상한은 한 회차분 + 한 건</b>이다.
     *
     * <p>상한을 정확히 한 회차분으로 두면 두 가지가 깨진다(2026-08-08 PR 직전 감사).
     *
     * <pre>
     *   perMinute 40 → 회차당 1.333 토큰인데 1건만 꺼내고 남은 0.333 이 잘려 나간다
     *                  → 실효 처리량이 30/분. 문서·설정이 유도한 "40/분 → 동시성 6" 이 무너진다
     *   perMinute 29 이하 → 회차당 토큰이 1건에 못 미쳐 **한 건도 안 나간다.** 느려지는 것이
     *                  아니라 통째로 멎고, 로그도 안 남는다(큐만 상한까지 찬다)
     * </pre>
     *
     * <p>한 건분(999)을 얹으면 인출 뒤 잔량이 늘 1000 미만이라 다음 회차의 {@code min} 이
     * 구속하지 않는다 — 장기 처리량이 {@code perMinute} 에 수렴한다. 유휴 시 누적은 여전히
     * "한 회차분 + 한 건"까지라 오래 쉬었다고 몰아 내보내지도 않는다.
     */
    private void refill() {
        long perTick = 1000L * perMinute / 30;
        // **천장은 가장 비싼 일을 품어야 한다.** 3회짜리 일이 있는데 천장이 1회분이면 그 일은
        // 영영 못 나가고 큐만 찬다 — 예산을 호출 단위로 세면서 같이 올린다(2026-08-21).
        long cap = perTick + (MAX_CALLS_PER_JOB * 1000L) - 1L;
        milliTokens.updateAndGet(t -> Math.min(cap, t + perTick));
    }

    private void giveBack(int calls) {
        milliTokens.addAndGet(1000L * Math.max(1, calls));
    }

    /**
     * 차선 순서대로 꺼낸다 — <b>위는 있는 대로, 아래는 회차당 몇 건만.</b>
     *
     * <p>아래를 제한하는 것이 "몰아서 보내지 않는다"의 구현이다. 위가 많으면 아래는 자연히
     * 뒤로 밀리고, 위가 비면 아래가 예산을 다 쓴다 — 이월이라는 상태 전이 없이 같은 결과가 된다.
     */
    private List<String> take() {
        List<String> out = new ArrayList<>();
        int low = 0;
        for (Lane lane : Lane.values()) {
            ConcurrentLinkedDeque<String> q = lanes.get(lane);
            boolean limited = lane.ordinal() > Lane.USER_REFRESH.ordinal();
            while (milliTokens.get() >= 1000L) {
                if (limited && low >= LOW_LANE_PER_TICK) break;
                String key = q.pollFirst();
                if (key == null) break;
                // **값은 호출 수로 낸다.** 3단계짜리 분류 하나가 편지 하나와 같은 값일 수 없다.
                Job job = known.get(key);
                long cost = 1000L * (job == null ? 1 : job.calls());
                if (milliTokens.get() < cost) {   // 이번 회차엔 못 산다 — 앞자리로 되돌린다
                    q.addFirst(key);
                    break;
                }
                milliTokens.addAndGet(-cost);
                if (limited) low++;
                out.add(key);
            }
        }
        return out;
    }

    /**
     * 상한을 넘으면 <b>가장 낮은 차선의 오래된 것부터</b> 버린다.
     *
     * <p>버려도 안전하다 — 문장은 다음 화면 열림에, 브랜드는 다음 동기화에 다시 들어온다.
     * 상한이 없는 큐가 더 위험하다. 문제가 생겼을 때 조용히 메모리를 먹다가 터지고, 그때는
     * 원인이 큐라는 것도 안 보인다.
     */
    private void trimIfOverCapacity() {
        int over = queued() - maxQueued;
        if (over <= 0) return;
        Lane[] all = Lane.values();
        for (int i = all.length - 1; i >= 0 && over > 0; i--) {
            ConcurrentLinkedDeque<String> q = lanes.get(all[i]);
            while (over > 0) {
                String key = q.pollFirst();
                if (key == null) break;
                known.remove(key);
                over--;
            }
        }
        log.warn("무료 통로 큐가 상한({})을 넘어 오래된 후순위를 버렸다 — 남은 {}", maxQueued, queued());
    }
}
