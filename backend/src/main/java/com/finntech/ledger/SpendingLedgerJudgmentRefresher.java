package com.finntech.ledger;

import com.finntech.engine.RecurringPaymentDetector;
import com.finntech.ml.WasteScoringService;
import com.finntech.repository.SpendingLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 판정이 낡은 사용자를 찾아 <b>고정지출·낭비 층을 다시 채운다</b>.
 *
 * <h2>이것은 원칙 ①을 일부 양보한 것이다 — 적어 둔다</h2>
 *
 * <p>이 표의 첫 번째 원칙은 <b>"표는 계산을 일으키지 않는다"</b> 였다. 판정은 남이 제 볼일로
 * 돌 때 나오고 표는 그 답을 받아 적을 뿐이라는 것. 여기 있는 것은 <b>표를 채우려고 판정을
 * 부른다</b> — 그 원칙에 어긋난다.
 *
 * <p><b>왜 양보하는가.</b> 원칙대로 두면 고정지출·낭비 칸은 그 사용자가 분석·낭비 화면을
 * 열어야만 찬다. 안 열면 영영 빈다. 그런데 이 표의 목적은 <i>"뒤에 붙을 알고리즘 프로그램이
 * 이 표만 읽고 뽑는다"</i> 이므로, <b>화면을 안 연 사람은 그 프로그램에서 통째로 빠진다.</b>
 * 그 구멍을 사람이 기억해서 손으로 백필하는 것으로 막으면, 언젠가 잊는다 — 이 저장소가
 * 여러 번 놓친 종류다. (사용자 결정 2026-08-14)
 *
 * <p><b>양보의 범위를 좁힌다.</b>
 *
 * <ul>
 *   <li><b>낡은 사람만</b> 부른다. 이미 사실과 짝이 맞는 판정이 있으면 건너뛴다 — 화면이
 *       열려 이미 채워진 사용자에게는 이 배치가 아무 일도 안 한다.
 *   <li><b>밤에 한 번</b>만 돈다. 상시로 돌면 원칙이 아니라 이름만 남는다.
 *   <li><b>예산이 있다.</b> 넘기면 남기고 나오고 다음 회차가 잇는다.
 * </ul>
 *
 * <h2>모델이 꺼져 있으면 낭비 층을 건드리지 않는다</h2>
 *
 * <p>{@code scoreUser} 는 모델이 준비 안 되면 <b>빈 목록</b>을 준다. 그것을 그대로 기록하면
 * 이미 적혀 있던 판정이 전부 {@code UNJUDGED} 로 <b>덮인다</b> — 어제의 답이 틀려진 것이
 * 아니라 오늘 물어볼 수 없을 뿐인데 답을 지우는 셈이다. 그래서 준비됐을 때만 적는다.
 */
@Service
public class SpendingLedgerJudgmentRefresher {

    private static final Logger log = LoggerFactory.getLogger(SpendingLedgerJudgmentRefresher.class);

    private final SpendingLedgerRepository ledger;
    private final SpendingLedgerFixedRecorder fixedRecorder;
    private final SpendingLedgerWasteRecorder wasteRecorder;
    private final RecurringPaymentDetector detector;
    private final WasteScoringService wasteScoring;
    private final Clock clock;
    private final long maxMillis;
    private final long progressLogSeconds;

    /** 한 번에 하나만 돈다 — 손으로 부른 백필과 밤 배치가 같은 줄을 함께 쓰지 않게. */
    private final AtomicBoolean running = new AtomicBoolean();

    public SpendingLedgerJudgmentRefresher(SpendingLedgerRepository ledger,
                                           SpendingLedgerFixedRecorder fixedRecorder,
                                           SpendingLedgerWasteRecorder wasteRecorder,
                                           RecurringPaymentDetector detector,
                                           WasteScoringService wasteScoring, Clock clock,
                                           @Value("${finntech.ledger.refresh.max-millis:600000}")
                                           long maxMillis,
                                           @Value("${finntech.ledger.refresh.progress-log-seconds:60}")
                                           long progressLogSeconds) {
        this.ledger = ledger;
        this.fixedRecorder = fixedRecorder;
        this.wasteRecorder = wasteRecorder;
        this.detector = detector;
        this.wasteScoring = wasteScoring;
        this.clock = clock;
        this.maxMillis = maxMillis;
        this.progressLogSeconds = progressLogSeconds;
    }

    /** 한 회차의 결과 — 로그와 운영 점검이 읽는다. */
    public record Result(int staleUsers, int refreshed, int failed, boolean budgetHit) {}

    /**
     * 판정이 낡은 사용자를 모두 갱신한다.
     *
     * <p>이미 짝이 맞는 사용자는 대상에서 아예 빠지므로, 평소에는 <b>질의 하나로 끝난다</b>.
     */
    public Result refreshStale() {
        if (!running.compareAndSet(false, true)) {
            log.debug("소비 원장 판정 갱신이 이미 돌고 있다 — 이번 회차는 건너뛴다");
            return new Result(0, 0, 0, false);
        }
        long startedAt = System.nanoTime();
        long lastLoggedAt = startedAt;
        int refreshed = 0;
        int failed = 0;
        boolean budgetHit = false;
        try {
            List<Long> stale = ledger.findUsersWithStaleJudgments(
                    SpendingLedgerFixedRecorder.DETECTOR_VERSION, wasteScoring.modelFingerprint());
            if (stale.isEmpty()) return new Result(0, 0, 0, false);

            log.info("소비 원장 판정 갱신 시작 — 낡은 사용자 {}명", stale.size());
            LocalDateTime referenceTime = LocalDateTime.now(clock);
            for (Long userId : stale) {
                try {
                    refreshOne(userId, referenceTime);
                    refreshed++;
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("소비 원장 판정 갱신 실패 — userId={} (다음 회차가 다시 집는다)", userId, e);
                }
                long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
                if (Duration.ofNanos(System.nanoTime() - lastLoggedAt).toSeconds() >= progressLogSeconds) {
                    log.info("소비 원장 판정 갱신 진행 — {}/{}명, 경과 {}초",
                            refreshed + failed, stale.size(), elapsedMillis / 1000);
                    lastLoggedAt = System.nanoTime();
                }
                if (elapsedMillis >= maxMillis) {
                    budgetHit = true;
                    log.info("소비 원장 판정 갱신 — 예산 {}ms 를 채워 {}명에서 멈춘다. 남은 것은 다음 회차가 잇는다",
                            maxMillis, refreshed + failed);
                    break;
                }
            }
            log.info("소비 원장 판정 갱신 끝 — 대상 {}명, 갱신 {}, 실패 {}, {}초",
                    stale.size(), refreshed, failed,
                    Duration.ofNanos(System.nanoTime() - startedAt).toSeconds());
            return new Result(stale.size(), refreshed, failed, budgetHit);
        } finally {
            running.set(false);
        }
    }

    /**
     * 한 사용자의 고정지출·낭비 층을 채운다 — <b>판정을 여기서 부른다.</b>
     *
     * <p>손으로 부르는 백필도 이 메서드를 쓴다. 판정을 부르는 자리가 둘이 되면 "모델이 꺼졌을
     * 때 어떻게 하는가" 같은 규칙이 한쪽에만 들어가고, 실제로 그렇게 갈라진 적이 있다.
     */
    public void refreshOne(Long userId, LocalDateTime referenceTime) {
        fixedRecorder.record(userId, detector.fixedGroups(userId, referenceTime));

        // 모델이 준비 안 됐으면 **낭비 층을 건드리지 않는다.** 빈 판정을 적으면 이미 있던
        // 답이 전부 UNJUDGED 로 덮인다 — 오늘 물어볼 수 없다는 것이 어제의 답을 지울 이유는 아니다.
        if (!wasteScoring.modelReady()) {
            log.warn("소비 원장 — 모델이 준비되지 않아 낭비 층을 건너뛴다(userId={}). 적힌 답은 그대로 둔다", userId);
            return;
        }
        wasteRecorder.record(userId, wasteScoring.scoreUser(userId),
                wasteScoring.modelThreshold(), wasteScoring.modelFingerprint());
    }
}
