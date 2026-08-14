package com.finntech.ledger;

import com.finntech.repository.SpendingLedgerDirtyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 표시된 사용자들을 <b>한 번에 하나씩</b> 다시 쓴다.
 *
 * <h2>여기가 유일한 재작성 지점이다</h2>
 *
 * <p>원장을 고치는 자리는 열 곳이 넘지만 표를 쓰는 자리는 여기 하나다. 그래서 대량 재분류가
 * 같은 사용자를 수천 번 다시 쓰는 일이 구조적으로 안 생기고, 재작성 규칙을 고칠 때 볼 곳도
 * 한 곳이다.
 *
 * <h2>수위 표시로 재작성 중에 들어온 표시를 지키다</h2>
 *
 * <p>쓰기 전에 그 사용자의 표시 중 가장 나중 번호를 손에 쥐고, 끝난 뒤 <b>그 번호 이하만</b>
 * 지운다. 재작성이 도는 동안 새로 들어온 표시는 번호가 더 커서 살아남고, 다음 회차가 그
 * 사용자를 한 번 더 쓴다. 재작성이 멱등이라 한 번 더 도는 것은 손해가 아니지만,
 * <b>놓치는 것은 손해다.</b>
 *
 * <h2>계속 터지는 사용자를 건너뛴다</h2>
 *
 * <p>실패하면 {@code attempts} 를 올린다. 상한을 넘으면 대기열 질의가 그 사용자를 빼므로,
 * 한 사람이 배수를 통째로 붙잡아 <b>뒤에 선 사람들이 한 번도 안 써지는</b> 일이 없다.
 * 멈춘 사용자는 운영 점검이 보여 준다.
 */
@Service
public class SpendingLedgerDrainer {

    private static final Logger log = LoggerFactory.getLogger(SpendingLedgerDrainer.class);

    private final SpendingLedgerDirtyRepository dirty;
    private final SpendingLedgerFactsWriter factsWriter;
    private final Executor executor;
    private final Clock clock;
    private final int maxAttempts;
    private final long maxMillis;
    private final long progressLogSeconds;

    /**
     * 저절로 도는가 — 나팔과 주기 배치를 함께 끈다.
     *
     * <p>끄는 이유는 시험이다. 배경 스레드가 같은 줄을 쓰는 사이에 단정이 걸리면 <b>경주</b>가
     * 된다(2026-08-14 실측: 나팔이 먼저 집어 가 시험의 {@code drainAll()} 이 0을 받고, 단정은
     * 아직 안 써진 표를 봤다). 꺼도 {@link #drainAll()} 을 직접 부르는 길은 열려 있어,
     * 시험은 자기가 원하는 시점에 돌린다.
     */
    private final boolean autoDrain;

    /** 한 번에 하나만 돈다 — 나팔과 배치가 겹쳐 같은 사용자를 둘이 쓰는 것을 막는다. */
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile LocalDateTime lastRunAt;
    private volatile int lastRunUsers;

    public SpendingLedgerDrainer(SpendingLedgerDirtyRepository dirty,
                                 SpendingLedgerFactsWriter factsWriter,
                                 @Qualifier(SpendingLedgerExecutorConfig.BEAN) Executor executor,
                                 Clock clock,
                                 @Value("${finntech.ledger.drain.max-attempts:5}") int maxAttempts,
                                 @Value("${finntech.ledger.drain.max-millis:60000}") long maxMillis,
                                 @Value("${finntech.ledger.drain.progress-log-seconds:60}")
                                 long progressLogSeconds,
                                 @Value("${finntech.ledger.drain.enabled:true}") boolean autoDrain) {
        this.autoDrain = autoDrain;
        this.dirty = dirty;
        this.factsWriter = factsWriter;
        this.executor = executor;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.maxMillis = maxMillis;
        this.progressLogSeconds = progressLogSeconds;
    }

    /**
     * 배수를 깨운다 — <b>신호일 뿐이다.</b>
     *
     * <p>일꾼 큐가 하나라 이미 대기 중인 신호가 있으면 이것은 버려진다. 버려도 잃는 것이 없다:
     * 할 일 목록은 표에 있고, 대기 중인 그 신호 하나가 표를 통째로 훑는다.
     */
    public void nudge() {
        if (!autoDrain) return;
        executor.execute(this::drainAll);
    }

    /**
     * 대기열을 비운다 — 회차 예산을 넘기면 남기고 나온다.
     *
     * @return 이번 회차에 다시 쓴 사용자 수
     */
    public int drainAll() {
        if (!running.compareAndSet(false, true)) return 0;   // 이미 돌고 있다
        long startedAt = System.nanoTime();
        long lastLoggedAt = startedAt;
        int done = 0;
        try {
            Long userId;
            while ((userId = dirty.findNextUserId(maxAttempts)) != null) {
                drainOne(userId);
                done++;
                long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
                if (Duration.ofNanos(System.nanoTime() - lastLoggedAt).toSeconds() >= progressLogSeconds) {
                    log.info("소비 원장 재작성 진행 — 사용자 {}명 완료, 대기 {}명, 경과 {}초",
                            done, dirty.findDistinctUserIds().size(), elapsedMillis / 1000);
                    lastLoggedAt = System.nanoTime();
                }
                if (elapsedMillis >= maxMillis) {
                    log.info("소비 원장 재작성 — 회차 예산 {}ms 를 채워 {}명에서 멈춘다. 남은 것은 다음 회차가 잇는다",
                            maxMillis, done);
                    break;
                }
            }
        } finally {
            lastRunAt = LocalDateTime.now(clock);
            lastRunUsers = done;
            running.set(false);
        }
        return done;
    }

    /**
     * 한 사용자를 다시 쓴다. 실패해도 <b>위로 던지지 않는다</b> — 한 사람이 터졌다고 나머지가
     * 못 써지면 안 된다. 대신 실패를 세어 상한을 넘기면 대기열에서 빠지게 한다.
     */
    private void drainOne(Long userId) {
        Long watermark = dirty.findWatermark(userId);
        if (watermark == null) return;                       // 그사이 누가 치웠다
        try {
            long startedAt = System.nanoTime();
            SpendingLedgerFactsWriter.Result result = factsWriter.write(userId);
            dirty.clearUpTo(userId, watermark);
            long millis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            if (result.skipped()) {
                log.info("소비 원장 — userId={} 는 실사용자가 아니라 건너뛴다(치운 줄 {})",
                        userId, result.removed());
            } else {
                log.info("소비 원장 재작성 — userId={} {}달 {}줄(치운 줄 {}) {}ms",
                        userId, result.months(), result.written(), result.removed(), millis);
            }
        } catch (RuntimeException e) {
            int marks = dirty.noteFailure(userId);
            log.warn("소비 원장 재작성 실패 — userId={} (표시 {}개의 시도 횟수를 올렸다, 상한 {})",
                    userId, marks, maxAttempts, e);
        }
    }

    /** 마지막으로 돈 시각 — 배수가 멈췄는지 보는 계기판. 한 번도 안 돌았으면 {@code null}. */
    public LocalDateTime lastRunAt() { return lastRunAt; }

    /** 마지막 회차에 다시 쓴 사용자 수. */
    public int lastRunUsers() { return lastRunUsers; }

    /** 상한에 걸려 멈춘 사용자들 — 사람이 봐야 할 목록이다. */
    public java.util.List<Long> stuckUserIds() { return dirty.findStuckUserIds(maxAttempts); }
}
