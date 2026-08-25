package com.finntech.web;

import com.finntech.ledger.SpendingLedgerBackfill;
import com.finntech.ledger.SpendingLedgerDrainer;
import com.finntech.ledger.SpendingLedgerFixedRecorder;
import com.finntech.ledger.SpendingLedgerJudgmentRefresher;
import com.finntech.ledger.SpendingLedgerVerifier;
import com.finntech.ml.WasteScoringService;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.SpendingLedgerDirtyRepository;
import com.finntech.repository.SpendingLedgerRepository;
import com.finntech.repository.UserPaymentRepository;
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
 * 정리된 소비 원장(V34)을 <b>채우고 들여다보는 문</b> — <b>admin 전용</b>.
 *
 * <h2>왜 {@code /api/ops} 가 아닌가</h2>
 *
 * <p>처음에는 {@code /api/ops} 에 두었다. 잘못이었다. 그 자리는 <b>운영에서 기본으로 켜져
 * 있고</b>({@code FINNTECH_OPS_ENABLED:-true}) 켜 두는 근거가 {@link ObservabilityController}
 * 머리말에 적혀 있다 — <i>"개인정보를 안 내고 사용자별로 쪼개지 않는다."</i> 여기 있는 것은
 * 그 약속을 두 군데서 어긴다.
 *
 * <ul>
 *   <li>{@code backfill}·{@code drain} 은 <b>쓰기</b>다. 실사용자 전원의 판정을 돌린다.
 *   <li>{@code verify} 의 표본에는 결제 식별자(사용자 번호를 품는다)·가맹점명·사업자번호가
 *       담긴다 — <b>남의 개인정보</b>다.
 *   <li>{@code /api/ops} 는 {@code /api/admin/} 밖이라 {@code AuthFilter} 가 <b>사용자 토큰</b>만
 *       요구하고, 경로에 사용자 번호가 없어 소유 확인도 안 걸린다. 즉 <b>로그인한 아무나</b>
 *       부를 수 있었다.
 * </ul>
 *
 * <p>그래서 {@code /api/admin/} 으로 옮긴다. 그 접두는 {@code AuthFilter} 가 admin 쿠키를
 * 요구하고, 사용자 토큰으로는 <b>403</b> 이다(역할이 경로를 가른다).
 *
 * <p>백필은 <b>기본이 dry-run</b>이다. 이 문 하나가 실사용자 전원의 판정을 한 번씩 돌리므로,
 * 규모를 보지 않고 실행할 일이 아니다.
 */
@RestController
@RequestMapping("/api/admin")
public class SpendingLedgerAdminController {

    private final SpendingLedgerBackfill backfill;
    private final SpendingLedgerVerifier verifier;
    private final SpendingLedgerJudgmentRefresher refresher;
    private final SpendingLedgerDrainer drainer;
    private final SpendingLedgerRepository ledger;
    private final SpendingLedgerDirtyRepository dirty;
    private final UserPaymentRepository payments;
    private final AppUserRepository users;
    private final WasteScoringService wasteScoring;
    private final com.finntech.service.SubCategorySweeper subSweeper;
    private final com.finntech.service.BrandCoverageReport brandCoverage;

    public SpendingLedgerAdminController(SpendingLedgerBackfill backfill, SpendingLedgerVerifier verifier,
                                       SpendingLedgerJudgmentRefresher refresher,
                                       SpendingLedgerDrainer drainer, SpendingLedgerRepository ledger,
                                       SpendingLedgerDirtyRepository dirty, UserPaymentRepository payments,
                                       AppUserRepository users, WasteScoringService wasteScoring,
                                       com.finntech.service.SubCategorySweeper subSweeper,
                                       com.finntech.service.BrandCoverageReport brandCoverage) {
        this.backfill = backfill;
        this.verifier = verifier;
        this.refresher = refresher;
        this.drainer = drainer;
        this.ledger = ledger;
        this.dirty = dirty;
        this.payments = payments;
        this.users = users;
        this.wasteScoring = wasteScoring;
        this.subSweeper = subSweeper;
        this.brandCoverage = brandCoverage;
    }

    /**
     * <b>브랜드가 상호에 제대로 붙는가</b> — 회사명이 서비스를 가린 자리를 찾는다. 값을 안 고친다.
     *
     * <p>브랜드 표는 회사명과 서비스명을 갈라 두고 회사명에는 소분류를 안 붙인다. 그래서
     * 어떤 상호가 <b>회사명에만 걸리면 소분류를 영원히 못 얻는다</b> — {@code 카카오스타일}
     * (지그재그 운영사)이 {@code 카카오} 에 걸려 그랬다. 회사를 하나 넣을 때마다 그 회사의
     * 서비스 표기가 같이 들어와야 하는데, 손으로 찾으면 놓친다.
     *
     * <p>표를 고친 뒤 이 문을 부르면, 어느 회사명 아래 상호가 쌓였는지가 곧 <b>다음에 넣을
     * 표기 목록</b>이다.
     */
    @GetMapping("/dictionary/brand-coverage")
    public com.finntech.service.BrandCoverageReport.Result brandCoverage(
            @RequestParam(defaultValue = "REAL") String origin) {
        return brandCoverage.scan(origin);
    }

    /**
     * <b>소분류와 어긋난 사전 행을 훑는다</b> — 기본은 <b>맛보기</b>라 아무것도 안 고친다.
     *
     * <p>표를 고쳐도 이미 확정이 적힌 가맹점은 다시 묻지 않아 옛 답을 든 채로 굳는다.
     * 여기가 그것을 푸는 자리다. 두 무리를 되돌린다 — 중분류가 소분류와 어긋난 행,
     * 그리고 모호한 업종(전자상거래 소매업 등)으로 확정돼 근거를 잃은 행.
     *
     * <p>사람이 손으로 확인한 것({@code USER_CONFIRMED}·{@code USER_CSV})은 안 건드린다.
     */
    @PostMapping("/dictionary/subcategory-sweep")
    public com.finntech.service.SubCategorySweeper.Result sweepSubCategory(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        return subSweeper.sweep(dryRun);
    }

    /** 어긋남을 <b>중분류 쌍으로 세어</b> 규모만 본다 — 고치기 전에 보는 자리. 값을 안 고친다. */
    @GetMapping("/dictionary/subcategory-drift")
    public Map<String, Object> subCategoryDrift() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Integer> byMid = subSweeper.byMid();
        out.put("byMid", byMid);
        out.put("total", byMid.values().stream().mapToInt(Integer::intValue).sum());
        return out;
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

    /**
     * 판정이 낡은 사용자를 지금 갱신한다 — 밤 배치를 손으로 당겨 부르는 문.
     *
     * <p>배치가 하루 한 번이라, 방금 들어온 사용자의 고정지출·낭비를 바로 보고 싶을 때 쓴다.
     */
    @PostMapping("/spending-ledger/refresh")
    public SpendingLedgerJudgmentRefresher.Result refresh() {
        return refresher.refreshStale();
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
