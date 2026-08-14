package com.finntech.ledger;

import com.finntech.domain.AppUser;
import com.finntech.domain.SpendingLedger;
import com.finntech.domain.UserPayment;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.SpendingLedgerDirtyRepository;
import com.finntech.repository.SpendingLedgerRepository;
import com.finntech.repository.UserPaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 처음 채우기와 어긋남 찾기 — 운영이 손으로 부르는 두 문. */
@SpringBootTest
@ActiveProfiles("test")
class SpendingLedgerOpsTest {

    @Autowired AppUserRepository users;
    @Autowired UserPaymentRepository payments;
    @Autowired SpendingLedgerRepository ledger;
    @Autowired SpendingLedgerDirtyRepository dirty;
    @Autowired SpendingLedgerBackfill backfill;
    @Autowired SpendingLedgerVerifier verifier;
    @Autowired SpendingLedgerFactsWriter factsWriter;
    @Autowired TransactionTemplate transactions;

    private AppUser user;

    @BeforeEach
    void setUp() {
        dirty.deleteAll();
        ledger.deleteAll();
        payments.deleteAll();
        user = users.save(new AppUser("ops-" + System.nanoTime(),
                new BigDecimal("3000000"), new BigDecimal("1000000"), 6));
        user.setRealPerson(true);
        user = users.save(user);

        transactions.executeWithoutResult(status -> {
            LocalDate start = LocalDate.of(2026, 2, 22);
            for (int i = 0; i < 6; i++) {
                payments.save(new UserPayment(
                        UserPayment.rowId(user.getId(), "real-sub" + i), user.getId(), "S1", 9001L,
                        start.plusMonths(i).atTime(23, 10), "642004", "취미/여가",
                        17000, "넷플릭스", "1658700119"));
            }
        });
        dirty.deleteAll();     // 적재가 남긴 표시는 여기서 볼 것이 아니다
    }

    @Test
    @DisplayName("dry-run 은 규모만 보여 주고 아무것도 쓰지 않는다")
    void 미리보기는_쓰지_않는다() {
        SpendingLedgerBackfill.Result result = backfill.run(true);

        assertTrue(result.dryRun());
        assertTrue(result.users() >= 1);
        assertTrue(result.paymentRows() >= 6);
        assertEquals(0, ledger.countByUserId(user.getId()), "미리보기가 표를 건드리면 안 된다");
    }

    @Test
    @DisplayName("채우면 사실·고정지출·낭비 칸이 한 번에 선다 — 여기가 표가 계산을 일으키는 유일한 자리다")
    void 처음_채우기가_세_층을_세운다() {
        SpendingLedgerBackfill.Result result = backfill.run(false);

        assertFalse(result.dryRun());
        assertEquals(0, result.ledgerRowsBefore());
        assertTrue(result.ledgerRowsAfter() >= 6);
        assertTrue(result.notes().isEmpty(), "실패한 사용자가 없어야 한다: " + result.notes());

        SpendingLedger row = ledger.findByUserIdOrderByPaidAtAscPaymentIdAsc(user.getId()).get(0);
        assertNotNull(row.getFactsUpdatedAt(), "사실 칸");
        assertNotNull(row.getFixedRecordedAt(), "고정지출 판정이 한 번은 돌았다");
        assertTrue(row.getFixed(), "매달 22일 넷플릭스는 고정지출이다");
        assertNotNull(row.getWasteRecordedAt(), "낭비 판정도 한 번은 돌았다");
    }

    @Test
    @DisplayName("앞에 실사용자가 아무리 많아도 짚은 사람은 본다 — 표본 자르기에 기대지 않는다")
    void 표본_자르기에_기대지_않는다() {
        // CI 실측(2026-08-14): 앞선 시험들이 만든 실사용자가 쌓여 이 시험의 사용자가
        // `verify(10)` 의 앞 열 명 밖으로 밀렸고, 어긋남을 넣어 두고도 "0건"이 나왔다.
        // 내 기계에서는 실행 순서가 달라 통과했다 — 그래서 조건을 여기서 직접 만든다.
        for (int i = 0; i < 12; i++) {
            AppUser earlier = users.save(new AppUser("ops-앞선-" + System.nanoTime() + "-" + i,
                    new BigDecimal("3000000"), new BigDecimal("1000000"), 6));
            earlier.setRealPerson(true);
            users.save(earlier);
        }
        AppUser later = users.save(new AppUser("ops-나중-" + System.nanoTime(),
                new BigDecimal("3000000"), new BigDecimal("1000000"), 6));
        later.setRealPerson(true);
        later = users.save(later);
        payments.save(new UserPayment(UserPayment.rowId(later.getId(), "real-solo"), later.getId(),
                "S1", 9001L, LocalDate.of(2026, 8, 9).atTime(12, 41), "642004", "편의점",
                3200, "GS25", "2345678901"));
        factsWriter.write(later.getId());
        payments.deleteById(UserPayment.rowId(later.getId(), "real-solo"));   // 유령 줄을 남긴다

        SpendingLedgerVerifier.Result byPick = verifier.verifyUsers(List.of(later.getId()));
        SpendingLedgerVerifier.Result bySample = verifier.verify(10);

        assertTrue(byPick.mismatched() > 0, "짚어서 부르면 번호가 뒤여도 본다");
        assertEquals(1, byPick.checkedUsers(), "짚은 한 명만 본다");
        // 대조군 — 표본 자르기는 이 사용자를 **아예 안 본다**. 그래서 여기 기대면
        // "어긋남이 없다"가 "안 봤다"를 뜻하게 된다. CI 가 초록불을 준 이유가 그것이다.
        assertTrue(bySample.checkedUsers() <= 10, "표본은 열 명까지다");
        assertFalse(bySample.mismatchedUsers().contains(later.getId()),
                "번호가 뒤인 사용자는 표본에 안 들어간다");
    }

    @Test
    @DisplayName("맞는 표에서는 어긋남이 0")
    void 맞으면_어긋남이_없다() {
        backfill.run(false);

        SpendingLedgerVerifier.Result result = verifier.verifyUsers(List.of(user.getId()));

        assertEquals(0, result.mismatched(), "어긋남 표본: " + result.samples());
        assertTrue(result.checkedRows() >= 6);
    }

    @Test
    @DisplayName("표가 원장과 벌어지면 찾아내고, 고치지는 않되 표시를 남긴다")
    void 어긋나면_찾아내고_표시한다() {
        backfill.run(false);
        dirty.deleteAll();

        // 표시를 놓친 상황을 그대로 만든다 — 원장은 바뀌었는데 표는 옛 값 그대로.
        transactions.executeWithoutResult(status ->
                payments.findById(UserPayment.rowId(user.getId(), "real-sub0")).orElseThrow()
                        .confirmCategory2("구독·콘텐츠", "USER"));
        dirty.deleteAll();

        SpendingLedgerVerifier.Result result = verifier.verifyUsers(List.of(user.getId()));

        assertTrue(result.mismatched() > 0, "분류가 갈렸는데 못 찾았다");
        assertTrue(result.mismatchedUsers().contains(user.getId()));
        assertTrue(dirty.findDistinctUserIds().contains(user.getId()),
                "고치지는 않되, 스스로 낫도록 표시는 남겨야 한다");
        assertEquals("취미/여가",
                ledger.findByUserIdOrderByPaidAtAscPaymentIdAsc(user.getId()).get(0).getCategory2(),
                "대조는 읽기만 한다 — 여기서 고치면 표를 쓰는 자리가 둘이 된다");
    }

    @Test
    @DisplayName("원장에 없는 유령 줄도 찾아낸다")
    void 유령_줄을_찾아낸다() {
        backfill.run(false);
        dirty.deleteAll();
        payments.deleteById(UserPayment.rowId(user.getId(), "real-sub0"));

        SpendingLedgerVerifier.Result result = verifier.verifyUsers(List.of(user.getId()));

        assertTrue(result.samples().stream().anyMatch(m -> m.column().equals("(유령 줄)")),
                "없어진 결제의 줄이 남아 있으면 읽는 쪽이 유령을 본다: " + result.samples());
    }

    @Test
    @DisplayName("낡음은 시각 비교로 드러난다 — 값을 다시 계산해 보지 않는다")
    void 낡음은_시각으로_안다() {
        backfill.run(false);
        assertEquals(0, ledger.countStaleFixed());
        assertEquals(0, ledger.countStaleWaste());

        // 사실이 실제로 바뀌어야 낡는다. 재작성을 돌렸다는 것만으로는 아무것도 안 낡는다 —
        // 그랬다면 낡음 신호가 "누가 돌렸나"를 뜻하게 되어 쓸모가 없다.
        transactions.executeWithoutResult(status ->
                payments.findById(UserPayment.rowId(user.getId(), "real-sub0")).orElseThrow()
                        .confirmCategory2("쇼핑", "USER"));
        factsWriter.write(user.getId());

        assertEquals(1, ledger.countStaleFixed(), "바뀐 그 줄만 낡아야 한다");
        assertEquals(1, ledger.countStaleWaste());
    }
}
