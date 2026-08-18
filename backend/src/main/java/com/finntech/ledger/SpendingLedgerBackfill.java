package com.finntech.ledger;

import com.finntech.domain.AppUser;
import com.finntech.domain.SpendingLedgerDirty;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.SpendingLedgerRepository;
import com.finntech.repository.UserPaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 소비 원장을 <b>처음 한 번</b> 채운다.
 *
 * <h2>여기가 표가 계산을 일으키는 유일한 자리다</h2>
 *
 * <p>평소에는 판정이 제 볼일로 돌 때 그 답을 받아 적을 뿐이다. 그런데 그러면 표를 처음 만든
 * 날에는 아무도 안 열어 본 사용자의 칸이 계속 비어 있다. 시작점을 만들어 주는 일 하나만
 * 손으로 한다 — <b>실사용자마다 고정지출·낭비 판정을 한 번씩 돌린다.</b>
 *
 * <h2>기동 때 자동으로 돌지 않는다</h2>
 *
 * <p>{@code UserIdentityBackfill} 이 그렇게 만들어졌다가 자기 호출로 {@code @Transactional} 을
 * 잃어 <b>한 행도 안 써진 채 "10만 행 채웠다"를 찍었다</b>. 기동 백필은 재기동마다 돌고,
 * 실패해도 사람이 안 본다. 손으로 부르는 문 하나가 낫다.
 *
 * <p>중단돼도 안전하다 — 사실 칸은 {@code spending_ledger_dirty} 에 표시가 남아 배수가 잇고,
 * 판정 칸은 다음에 그 판정이 돌 때 다시 온다.
 */
@Service
public class SpendingLedgerBackfill {

    private static final Logger log = LoggerFactory.getLogger(SpendingLedgerBackfill.class);

    /** 진행 보고 간격 — 오래 걸리는 작업은 1분 단위로 어디까지 왔는지 남긴다. */
    private static final long PROGRESS_LOG_SECONDS = 60;

    private final AppUserRepository users;
    private final UserPaymentRepository payments;
    private final SpendingLedgerRepository ledger;
    private final SpendingLedgerDirtyMarker marker;
    private final SpendingLedgerFactsWriter factsWriter;
    /** 판정 두 층은 여기 하나에만 맡긴다 — 부르는 자리가 둘이면 규칙이 한쪽에만 들어간다. */
    private final SpendingLedgerJudgmentRefresher refresher;
    private final Clock clock;

    public SpendingLedgerBackfill(AppUserRepository users, UserPaymentRepository payments,
                                  SpendingLedgerRepository ledger, SpendingLedgerDirtyMarker marker,
                                  SpendingLedgerFactsWriter factsWriter,
                                  SpendingLedgerJudgmentRefresher refresher, Clock clock) {
        this.users = users;
        this.payments = payments;
        this.ledger = ledger;
        this.marker = marker;
        this.factsWriter = factsWriter;
        this.refresher = refresher;
        this.clock = clock;
    }

    /** 채우기 전에 보여 주는 것 / 채운 뒤 돌려주는 것. */
    public record Result(boolean dryRun, int users, long paymentRows, long ledgerRowsBefore,
                         long ledgerRowsAfter, List<String> notes) {}

    /**
     * 실사용자 전원의 표를 채운다.
     *
     * @param dryRun 참이면 아무것도 쓰지 않고 규모만 돌려준다
     */
    public Result run(boolean dryRun) {
        List<AppUser> targets = new ArrayList<>();
        for (AppUser user : users.findAll()) {
            if (user.isRealPerson()) targets.add(user);
        }
        targets.sort(java.util.Comparator.comparing(AppUser::getId));   // 정렬 고정(원칙 3)

        long paymentRows = 0;
        long before = 0;
        for (AppUser user : targets) {
            paymentRows += payments.findByUserIdOrderByPaymentDateDesc(user.getId()).size();
            before += ledger.countByUserId(user.getId());
        }
        if (dryRun) {
            return new Result(true, targets.size(), paymentRows, before, before,
                    List.of("dryRun=false 로 다시 부르면 실제로 채운다"));
        }

        LocalDateTime referenceTime = LocalDateTime.now(clock);
        long startedAt = System.nanoTime();
        long lastLoggedAt = startedAt;
        List<String> notes = new ArrayList<>();
        int done = 0;
        for (AppUser user : targets) {
            try {
                fillOne(user.getId(), referenceTime);
            } catch (RuntimeException e) {
                // 한 사람이 터졌다고 나머지를 멈추지 않는다. 표시는 남아 있으므로 배수가 잇는다.
                marker.mark(user.getId(), SpendingLedgerDirty.Reason.BACKFILL);
                notes.add("userId=" + user.getId() + " 실패: " + e.getClass().getSimpleName());
                log.warn("소비 원장 백필 실패 — userId={} (표시를 남겨 배수가 잇는다)", user.getId(), e);
            }
            done++;
            if (Duration.ofNanos(System.nanoTime() - lastLoggedAt).toSeconds() >= PROGRESS_LOG_SECONDS) {
                long elapsed = Duration.ofNanos(System.nanoTime() - startedAt).toSeconds();
                long remaining = done == 0 ? 0 : elapsed * (targets.size() - done) / done;
                log.info("소비 원장 백필 진행 — 사용자 {}/{}, 경과 {}초, 남은 예상 {}초",
                        done, targets.size(), elapsed, remaining);
                lastLoggedAt = System.nanoTime();
            }
        }

        long after = 0;
        for (AppUser user : targets) after += ledger.countByUserId(user.getId());
        log.info("소비 원장 백필 끝 — 사용자 {}명, 줄 {} → {} ({}초)", targets.size(), before, after,
                Duration.ofNanos(System.nanoTime() - startedAt).toSeconds());
        return new Result(false, targets.size(), paymentRows, before, after, notes);
    }

    /**
     * 한 사용자를 채운다 — 사실 → 고정지출 → 낭비 순.
     *
     * <p>순서가 있다. 사실 칸이 먼저 있어야 판정을 적을 줄이 존재하고, 낡음 판단
     * ({@code *_recorded_at} 대 {@code facts_updated_at})도 그 순서라야 맞는다.
     *
     * <p>판정 두 층은 {@link SpendingLedgerJudgmentRefresher#refreshOne} 에 맡긴다 —
     * <b>판정을 부르는 자리를 하나로 둔다.</b> 둘로 두었더니 "모델이 꺼졌을 때 손대지 않는다"는
     * 규칙이 한쪽에만 들어가, 손으로 부른 백필이 이미 적힌 판정을 {@code UNJUDGED} 로 덮을 수
     * 있었다(2026-08-14 발견).
     */
    private void fillOne(Long userId, LocalDateTime referenceTime) {
        SpendingLedgerFactsWriter.Result facts = factsWriter.write(userId);
        if (facts.skipped()) return;

        refresher.refreshOne(userId, referenceTime);
    }
}
