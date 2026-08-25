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
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 밤 갱신 — <b>화면을 안 연 사용자도 판정 칸이 찬다.</b>
 *
 * <p>이 배치는 표를 채우려고 판정을 부른다. 원칙("표는 계산을 일으키지 않는다")을 일부 양보한
 * 유일한 상시 경로라, 그 양보가 <b>좁게 유지되는지</b>를 여기서 지킨다 — 낡은 사람만 고르고,
 * 이미 맞는 사람에게는 아무 일도 안 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SpendingLedgerJudgmentRefresherTest {

    @Autowired AppUserRepository users;
    @Autowired UserPaymentRepository payments;
    @Autowired SpendingLedgerRepository ledger;
    @Autowired SpendingLedgerDirtyRepository dirty;
    @Autowired SpendingLedgerFactsWriter factsWriter;
    @Autowired SpendingLedgerJudgmentRefresher refresher;
    @Autowired TransactionTemplate transactions;

    private AppUser user;

    @BeforeEach
    void setUp() {
        dirty.deleteAll();
        ledger.deleteAll();
        payments.deleteAll();
        user = users.save(new AppUser("refresh-" + System.nanoTime(),
                new BigDecimal("3000000"), new BigDecimal("1000000"), 6));
        user.setRealPerson(true);
        user = users.save(user);

        transactions.executeWithoutResult(status -> {
            LocalDate start = LocalDate.of(2026, 2, 22);
            for (int i = 0; i < 6; i++) {
                payments.save(new UserPayment(
                        UserPayment.rowId(user.getId(), "real-sub" + i), user.getId(), "S1", 9001L,
                        start.plusMonths(i).atTime(23, 10), "000000", "취미/여가",
                        17000, "넷플릭스", "1658700119"));
            }
        });
        factsWriter.write(user.getId());     // 1층만 채운다 — 화면을 안 연 상태 그대로
        dirty.deleteAll();
    }

    @Test
    @DisplayName("화면을 안 열어도 밤 갱신이 두 층을 채운다")
    void 낡은_사용자를_채운다() {
        List<SpendingLedger> before = ledger.findByUserIdOrderByPaidAtAscPaymentIdAsc(user.getId());
        assertEquals(6, before.size());
        assertNull(before.get(0).getFixed(), "판정이 아직 안 돌았다");
        assertNull(before.get(0).getWasteRecordedAt());

        SpendingLedgerJudgmentRefresher.Result result = refresher.refreshStale();

        assertTrue(result.staleUsers() >= 1);
        assertTrue(result.refreshed() >= 1);
        assertEquals(0, result.failed());

        SpendingLedger after = ledger.findByUserIdOrderByPaidAtAscPaymentIdAsc(user.getId()).get(0);
        assertNotNull(after.getFixed(), "고정지출 칸이 채워져야 한다");
        assertTrue(after.getFixed(), "매달 22일 넷플릭스는 고정지출이다");
        assertNotNull(after.getFixedRecordedAt());
        assertNotNull(after.getWasteRecordedAt(), "낭비 판정도 돌았어야 한다");
        assertNotNull(after.getModelFingerprint());
    }

    @Test
    @DisplayName("이미 맞는 사용자에게는 아무 일도 안 한다 — 양보를 좁게 유지한다")
    void 맞으면_건드리지_않는다() {
        refresher.refreshStale();
        assertEquals(0, ledger.countStaleFixed());
        assertEquals(0, ledger.countStaleWaste());

        SpendingLedgerJudgmentRefresher.Result second = refresher.refreshStale();

        assertEquals(0, second.staleUsers(), "낡은 사람이 없으면 대상이 0이라 판정을 아예 안 부른다");
        assertEquals(0, second.refreshed());
    }

    @Test
    @DisplayName("사실이 바뀌면 그 사용자만 다시 대상이 된다")
    void 바뀐_사람만_다시_집는다() {
        refresher.refreshStale();
        assertEquals(0, refresher.refreshStale().staleUsers());

        String changed = UserPayment.rowId(user.getId(), "real-sub0");
        transactions.executeWithoutResult(status ->
                payments.findById(changed).orElseThrow().confirmCategory2("쇼핑", "USER"));
        factsWriter.write(user.getId());

        assertEquals(1, ledger.countStaleFixed(), "바뀐 그 줄만 낡는다");
        SpendingLedgerJudgmentRefresher.Result result = refresher.refreshStale();

        assertEquals(1, result.staleUsers());
        assertEquals(1, result.refreshed());
        assertEquals(0, ledger.countStaleFixed(), "갱신 뒤에는 다시 짝이 맞는다");
    }

    @Test
    @DisplayName("한 사용자를 짚어 갱신할 수도 있다 — 백필이 쓰는 그 길이다")
    void 짚어서도_갱신한다() {
        refresher.refreshOne(user.getId(), LocalDateTime.of(2026, 8, 1, 12, 0));

        SpendingLedger row = ledger.findByUserIdOrderByPaidAtAscPaymentIdAsc(user.getId()).get(0);
        assertNotNull(row.getFixedRecordedAt());
        assertNotNull(row.getWasteRecordedAt());
    }
}
