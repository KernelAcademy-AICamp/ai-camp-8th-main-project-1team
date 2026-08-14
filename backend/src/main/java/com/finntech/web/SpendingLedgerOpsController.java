package com.finntech.web;

import com.finntech.ledger.SpendingLedgerBackfill;
import com.finntech.ledger.SpendingLedgerDrainer;
import com.finntech.ledger.SpendingLedgerFixedRecorder;
import com.finntech.ledger.SpendingLedgerVerifier;
import com.finntech.ml.WasteScoringService;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.SpendingLedgerDirtyRepository;
import com.finntech.repository.SpendingLedgerRepository;
import com.finntech.repository.UserPaymentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 정리된 소비 원장(V34)을 <b>채우고 들여다보는 문</b>.
 *
 * <p><b>기본은 꺼져 있다.</b> {@link MerchantDictionaryOpsController} 와 같은 이유다 — nginx 는
 * {@code /api/} 아래를 경로별 구분 없이 백엔드로 넘기므로 여기 만든 매핑은 배포되는 순간
 * 공개된다.
 *
 * <p>백필은 <b>기본이 dry-run</b>이다. 이 문 하나가 실사용자 전원의 판정을 한 번씩 돌리므로,
 * 규모를 보지 않고 실행할 일이 아니다.
 */
@RestController
@RequestMapping("/api/ops")
@ConditionalOnProperty(name = "finntech.ops.enabled", havingValue = "true")
public class SpendingLedgerOpsController {

    private final SpendingLedgerBackfill backfill;
    private final SpendingLedgerVerifier verifier;
    private final SpendingLedgerDrainer drainer;
    private final SpendingLedgerRepository ledger;
    private final SpendingLedgerDirtyRepository dirty;
    private final UserPaymentRepository payments;
    private final AppUserRepository users;
    private final WasteScoringService wasteScoring;

    public SpendingLedgerOpsController(SpendingLedgerBackfill backfill, SpendingLedgerVerifier verifier,
                                       SpendingLedgerDrainer drainer, SpendingLedgerRepository ledger,
                                       SpendingLedgerDirtyRepository dirty, UserPaymentRepository payments,
                                       AppUserRepository users, WasteScoringService wasteScoring) {
        this.backfill = backfill;
        this.verifier = verifier;
        this.drainer = drainer;
        this.ledger = ledger;
        this.dirty = dirty;
        this.payments = payments;
        this.users = users;
        this.wasteScoring = wasteScoring;
    }

    /** 처음 채우기 — 여기가 표가 계산을 일으키는 유일한 자리다. */
    @PostMapping("/spending-ledger/backfill")
    public SpendingLedgerBackfill.Result backfill(@RequestParam(defaultValue = "true") boolean dryRun) {
        return backfill.run(dryRun);
    }

    /**
     * 사실 칸을 다시 만들어 저장된 줄과 견준다 — 쓰지 않는다. 어긋난 사용자는 표시만 남긴다.
     *
     * <p>{@code userIds} 를 주면 <b>그 사람들만</b> 본다. 안 주면 번호 순으로 앞에서
     * {@code users} 명을 뜬금 표본으로 본다 — 신고받은 사용자를 짚어 보려면 앞의 형태를 쓴다.
     */
    @PostMapping("/spending-ledger/verify")
    public SpendingLedgerVerifier.Result verify(@RequestParam(defaultValue = "5") int users,
                                                @RequestParam(required = false) List<Long> userIds) {
        return userIds == null || userIds.isEmpty()
                ? verifier.verify(users)
                : verifier.verifyUsers(userIds);
    }

    /** 대기 중인 재작성을 지금 돌린다 — 배수가 멎어 있을 때 손으로 민다. */
    @PostMapping("/spending-ledger/drain")
    public Map<String, Object> drain() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("drainedUsers", drainer.drainAll());
        out.put("pendingUsers", dirty.findDistinctUserIds().size());
        return out;
    }

    /**
     * 계기판 — <b>표가 원장을 따라오고 있는가.</b>
     *
     * <p>건수 어긋남·배수 멈춤·모델 갈아탐 셋을 한 화면에서 본다. 값을 고치지 않는다.
     */
    @GetMapping("/spending-ledger/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();

        // 사용자별 결제 수 대 표 줄 수 — 어긋난 사람만 보여 준다. 맞는 사람은 볼 것이 없다.
        List<Map<String, Object>> gaps = new ArrayList<>();
        users.findAll().stream()
                .filter(com.finntech.domain.AppUser::isRealPerson)
                .sorted(java.util.Comparator.comparing(com.finntech.domain.AppUser::getId))
                .forEach(user -> {
                    long paymentRows = payments.findByUserIdOrderByPaymentDateDesc(user.getId()).size();
                    long ledgerRows = ledger.countByUserId(user.getId());
                    if (paymentRows == ledgerRows) return;
                    Map<String, Object> gap = new LinkedHashMap<>();
                    gap.put("userId", user.getId());
                    gap.put("payments", paymentRows);
                    gap.put("ledger", ledgerRows);
                    gaps.add(gap);
                });
        out.put("countGaps", gaps);

        out.put("rows", ledger.count());
        out.put("oldestFactsUpdatedAt", ledger.findOldestFactsUpdatedAt());
        // 낡음 = 사실이 바뀐 뒤로 그 판정이 다시 안 돈 것. 0이 아니어도 고장이 아니다 —
        // 표가 판정을 일으키지 않는다는 원칙의 당연한 결과다. 늘기만 하면 그때 본다.
        out.put("staleFixed", ledger.countStaleFixed());
        out.put("staleWaste", ledger.countStaleWaste());

        // 판·모델이 섞여 있나. 지금 것과 다른 값이 남아 있으면 그만큼이 옛 회차의 답이다.
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("detectorNow", SpendingLedgerFixedRecorder.DETECTOR_VERSION);
        versions.put("detectorInTable", ledger.findDistinctDetectorVersions());
        versions.put("modelNow", wasteScoring.modelFingerprint());
        versions.put("modelInTable", ledger.findDistinctModelFingerprints());
        versions.put("modelReady", wasteScoring.modelReady());
        out.put("versions", versions);

        Map<String, Object> queue = new LinkedHashMap<>();
        queue.put("pendingUsers", dirty.findDistinctUserIds());
        queue.put("oldestMarkedAt", dirty.findOldestMarkedAt());
        // 계속 터져서 대기열에서 빠진 사용자 — 사람이 봐야 할 목록이다.
        queue.put("stuckUsers", drainer.stuckUserIds());
        queue.put("lastDrainAt", drainer.lastRunAt());
        queue.put("lastDrainUsers", drainer.lastRunUsers());
        out.put("queue", queue);
        return out;
    }
}
