package com.finntech.ledger;

import com.finntech.domain.AppUser;
import com.finntech.domain.SpendingLedger;
import com.finntech.domain.UserMerchantStance;
import com.finntech.domain.UserPayment;
import com.finntech.domain.UserSpendingOverride;
import com.finntech.engine.FixedGroup;
import com.finntech.engine.RecurringPaymentDetector;
import com.finntech.ml.WasteScoringService;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.SpendingLedgerDirtyRepository;
import com.finntech.repository.SpendingLedgerRepository;
import com.finntech.repository.UserMerchantStanceRepository;
import com.finntech.repository.UserPaymentRepository;
import com.finntech.repository.UserSpendingOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 판정 결과가 소비 원장에 <b>받아 적히는가</b> — 2층(고정지출)·3층(낭비).
 *
 * <p>여기서 지키는 성질은 하나다. <b>표가 판정을 시키지 않는다.</b> 판정이 제 볼일로 돌 때만
 * 칸이 차고, 안 돌면 비어 있다. 그래서 시험도 판정을 직접 부른 뒤 기록기를 부른다.
 */
@SpringBootTest
@ActiveProfiles("test")
@RecordApplicationEvents
class SpendingLedgerJudgmentTest {

    @Autowired AppUserRepository users;
    @Autowired UserPaymentRepository payments;
    @Autowired SpendingLedgerRepository ledger;
    @Autowired SpendingLedgerDirtyRepository dirty;
    @Autowired UserMerchantStanceRepository stances;
    @Autowired UserSpendingOverrideRepository overrides;
    @Autowired SpendingLedgerFactsWriter factsWriter;
    @Autowired SpendingLedgerFixedRecorder fixedRecorder;
    @Autowired SpendingLedgerWasteRecorder wasteRecorder;
    @Autowired RecurringPaymentDetector detector;
    @Autowired WasteScoringService wasteScoring;
    @Autowired TransactionTemplate transactions;
    @Autowired ApplicationEvents events;

    private AppUser user;

    @BeforeEach
    void setUp() {
        dirty.deleteAll();
        ledger.deleteAll();
        user = users.save(new AppUser("judge-" + System.nanoTime(),
                new BigDecimal("3000000"), new BigDecimal("1000000"), 6));
        user.setRealPerson(true);
        user = users.save(user);
    }

    private UserPayment pay(String id, LocalDateTime at, int amount, String merchant,
                            String bizno, String category2) {
        return payments.save(new UserPayment(
                UserPayment.rowId(user.getId(), "real-" + id), user.getId(), "S1", 9001L,
                at, "642004", category2, amount, merchant, bizno));
    }

    /** 매달 22일 넷플릭스 + 어쩌다 한 번 편의점 — 고정지출과 일반 지출이 섞인 원장. */
    private void seedSubscriptionAndOneOff() {
        transactions.executeWithoutResult(status -> {
            LocalDate start = LocalDate.of(2026, 2, 22);
            for (int i = 0; i < 6; i++) {
                pay("sub" + i, start.plusMonths(i).atTime(23, 10), 17000, "넷플릭스", "1658700119", "취미/여가");
            }
            pay("shop1", LocalDateTime.of(2026, 5, 9, 12, 41), 3200, "GS25 포스텍점", "2345678901", "편의점");
        });
        factsWriter.write(user.getId());
    }

    private Map<String, SpendingLedger> rowsByPaymentId() {
        return ledger.findByUserIdOrderByPaidAtAscPaymentIdAsc(user.getId()).stream()
                .collect(Collectors.toMap(SpendingLedger::getPaymentId, row -> row));
    }

    // ── 통지가 나가는가 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("판정이 돌면 통지가 나간다 — 표가 판정기를 부르지 않는다")
    void 판정이_통지를_낸다() {
        seedSubscriptionAndOneOff();

        detector.detect(user.getId(), LocalDateTime.of(2026, 8, 1, 12, 0));

        List<LedgerJudgmentEvents.FixedGroupsDetected> published = events
                .stream(LedgerJudgmentEvents.FixedGroupsDetected.class).toList();
        assertEquals(1, published.size(), "탐지 한 번에 통지 한 번");
        assertEquals(user.getId(), published.get(0).userId());
        assertEquals(1, published.get(0).groups().size(), "넷플릭스 한 묶음");
    }

    // ── 2층: 고정지출 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("묶음에 든 줄은 고정지출, 안 든 줄은 '아니다' — 비워 두지 않는다")
    void 고정지출을_받아_적는다() {
        seedSubscriptionAndOneOff();
        List<FixedGroup> groups = detector.fixedGroups(user.getId(), LocalDateTime.of(2026, 8, 1, 12, 0));
        assertEquals(1, groups.size());

        int touched = fixedRecorder.record(user.getId(), groups);

        assertEquals(7, touched, "그 사용자의 모든 줄을 정한다");
        Map<String, SpendingLedger> rows = rowsByPaymentId();
        SpendingLedger subscription = rows.get(UserPayment.rowId(user.getId(), "real-sub5"));
        assertTrue(subscription.getFixed());
        assertEquals("FIXED", subscription.getRecurringType());
        assertEquals("MONTHLY", subscription.getPeriodKind());
        assertEquals("BIZ:1658700119", subscription.getMerchantKey());
        assertEquals(6, subscription.getGroupPaymentCount());
        assertEquals(17000L, subscription.getRepresentativeAmount());
        assertNotNull(subscription.getGapCv());
        assertNotNull(subscription.getNextExpectedOn());
        assertEquals(SpendingLedgerFixedRecorder.DETECTOR_VERSION, subscription.getDetectorVersion());
        assertNotNull(subscription.getFixedRecordedAt());

        SpendingLedger oneOff = rows.get(UserPayment.rowId(user.getId(), "real-shop1"));
        assertFalse(oneOff.getFixed(), "'고정지출이 아니다'도 적힌 정보다");
        assertNull(oneOff.getPeriodDays());
        assertNotNull(oneOff.getFixedRecordedAt(), "판정이 돌았다는 사실은 남는다");
    }

    @Test
    @DisplayName("바뀔 것이 없으면 다시 쓰지 않는다 — 화면을 열 때마다 수천 줄을 갈아엎지 않는다")
    void 낡지_않았으면_건너뛴다() {
        seedSubscriptionAndOneOff();
        List<FixedGroup> groups = detector.fixedGroups(user.getId(), LocalDateTime.of(2026, 8, 1, 12, 0));

        assertEquals(7, fixedRecorder.record(user.getId(), groups));
        assertEquals(0, fixedRecorder.record(user.getId(), groups), "두 번째는 할 일이 없다");
    }

    @Test
    @DisplayName("사실이 바뀌면 판정이 낡은 것으로 보인다 — 다음 판정 때 다시 써진다")
    void 사실이_바뀌면_다시_쓴다() {
        seedSubscriptionAndOneOff();
        List<FixedGroup> groups = detector.fixedGroups(user.getId(), LocalDateTime.of(2026, 8, 1, 12, 0));
        fixedRecorder.record(user.getId(), groups);

        String changed = UserPayment.rowId(user.getId(), "real-sub5");
        transactions.executeWithoutResult(status ->
                payments.findById(changed).orElseThrow().confirmCategory2("쇼핑", "USER"));
        factsWriter.write(user.getId());

        SpendingLedger row = rowsByPaymentId().get(changed);
        assertTrue(row.getFixedRecordedAt().isBefore(row.getFactsUpdatedAt()),
                "판정이 사실보다 낡았다는 것이 시각 비교로 드러나야 한다");
        assertTrue(fixedRecorder.record(user.getId(), groups) > 0, "낡았으면 다시 쓴다");
    }

    @Test
    @DisplayName("달라진 것이 없으면 사실 칸도 손대지 않는다 — 낡음이 '누가 돌렸나'가 되면 안 된다")
    void 안_바뀌면_시각도_그대로다() {
        // 늘 새 시각을 찍으면 분류 한 건을 고쳤을 뿐인데 그 사용자의 모든 줄이 다시 써지고,
        // 판정 칸이 통째로 낡아 보인다. 그러면 낡음 신호로는 아무것도 판단할 수 없다.
        seedSubscriptionAndOneOff();
        fixedRecorder.record(user.getId(),
                detector.fixedGroups(user.getId(), LocalDateTime.of(2026, 8, 1, 12, 0)));
        Map<String, LocalDateTime> before = rowsByPaymentId().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getFactsUpdatedAt()));

        assertEquals(0, factsWriter.write(user.getId()).written(), "달라진 줄이 없다");

        rowsByPaymentId().forEach((paymentId, row) ->
                assertEquals(before.get(paymentId), row.getFactsUpdatedAt(),
                        paymentId + " 의 사실 기록 시각이 이유 없이 움직였다"));
        assertEquals(0, ledger.countStaleFixed(), "판정도 낡지 않았어야 한다");
    }

    // ── 3층: 낭비 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("분류가 없는 줄은 UNJUDGED — '낭비가 아니다'와 다르다")
    void 판정하지_않은_줄은_UNJUDGED다() {
        transactions.executeWithoutResult(status -> {
            pay("known", LocalDateTime.of(2026, 8, 3, 8, 30), 4500, "스타벅스", "1234567890", "카페");
            pay("unknown", LocalDateTime.of(2026, 8, 4, 9, 0), 9900, "알수없는곳", "9998887776", "카테고리없음");
        });
        factsWriter.write(user.getId());

        var judgments = wasteScoring.scoreUser(user.getId());
        wasteRecorder.record(user.getId(), judgments,
                wasteScoring.modelThreshold(), wasteScoring.modelFingerprint());

        Map<String, SpendingLedger> rows = rowsByPaymentId();
        SpendingLedger unknown = rows.get(UserPayment.rowId(user.getId(), "real-unknown"));
        assertEquals("UNJUDGED", unknown.getWasteLabelSource());
        assertNull(unknown.getWaste(), "거짓이 아니라 없음이다");
        assertNull(unknown.getWasteProbability());
        assertNotNull(unknown.getWasteRecordedAt(), "언제 건너뛰었는지는 남는다");
        assertNotNull(unknown.getModelFingerprint(), "어느 모델 아래서 건너뛰었는지도 남는다");
    }

    @Test
    @DisplayName("제외한 가맹점은 임계가 없다 — Double.MAX_VALUE 를 적지 않는다")
    void 제외된_가맹점의_임계는_비어_있다() {
        transactions.executeWithoutResult(status ->
                pay("cafe", LocalDateTime.of(2026, 8, 3, 8, 30), 4500, "스타벅스", "1234567890", "카페"));
        factsWriter.write(user.getId());
        UserMerchantStance stance = new UserMerchantStance(user.getId(), "1234567890", "스타벅스",
                LocalDateTime.of(2026, 8, 1, 0, 0));
        stance.excludedByUser(3, LocalDateTime.of(2026, 8, 1, 0, 0));
        stances.save(stance);

        var judgments = wasteScoring.scoreUser(user.getId());
        wasteRecorder.record(user.getId(), judgments,
                wasteScoring.modelThreshold(), wasteScoring.modelFingerprint());

        SpendingLedger row = rowsByPaymentId().get(UserPayment.rowId(user.getId(), "real-cafe"));
        assertEquals("EXCLUDED", row.getStance());
        assertNull(row.getWasteThreshold(), "임계가 없는 것과 아주 큰 임계는 다르다");
        assertNotNull(row.getModelThreshold(), "전역 임계는 그대로 남아 집계 쪽 답을 되살릴 수 있다");
    }

    @Test
    @DisplayName("개인화로 뒤집은 카테고리는 출처가 OVERRIDE")
    void 개인화는_출처가_다르다() {
        transactions.executeWithoutResult(status ->
                pay("cafe", LocalDateTime.of(2026, 8, 3, 8, 30), 4500, "스타벅스", "1234567890", "카페"));
        factsWriter.write(user.getId());
        overrides.save(new UserSpendingOverride(user.getId(), "카페", true,
                LocalDateTime.of(2026, 8, 1, 0, 0)));

        var judgments = wasteScoring.scoreUser(user.getId());
        wasteRecorder.record(user.getId(), judgments,
                wasteScoring.modelThreshold(), wasteScoring.modelFingerprint());

        SpendingLedger row = rowsByPaymentId().get(UserPayment.rowId(user.getId(), "real-cafe"));
        assertEquals("OVERRIDE", row.getWasteLabelSource());
        assertTrue(row.getWaste(), "사용자가 낭비라고 정했다");
        assertNull(row.getFactor1Label(), "개인화에는 모델 근거가 없다");
    }

    // ── 두 층은 서로를 건드리지 않는다 ───────────────────────────────────────

    @Test
    @DisplayName("한 판정이 다른 층을 지우지 않는다 — 층마다 제 사건에만 반응한다")
    void 층은_서로를_건드리지_않는다() {
        seedSubscriptionAndOneOff();
        fixedRecorder.record(user.getId(),
                detector.fixedGroups(user.getId(), LocalDateTime.of(2026, 8, 1, 12, 0)));

        var judgments = wasteScoring.scoreUser(user.getId());
        wasteRecorder.record(user.getId(), judgments,
                wasteScoring.modelThreshold(), wasteScoring.modelFingerprint());

        SpendingLedger row = rowsByPaymentId().get(UserPayment.rowId(user.getId(), "real-sub5"));
        assertTrue(row.getFixed(), "낭비를 적었다고 고정지출이 지워지면 안 된다");
        assertNotNull(row.getWasteLabelSource());
        assertNotNull(row.getMerchantName(), "사실 칸도 그대로다");
    }
}
