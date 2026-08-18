package com.finntech.ledger;

import com.finntech.domain.AppUser;
import com.finntech.domain.SpendingLedger;
import com.finntech.domain.SpendingLedgerDirty;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 소비 원장의 <b>사실 칸</b>이 원장을 따라오는가 — 표시부터 재작성까지.
 *
 * <p>배수 배치를 꺼 두고 시험이 손으로 돌린다. 켜 두면 배경 스레드가 같은 줄을 쓰는 사이에
 * 단정이 걸려 경주가 된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "finntech.ledger.drain.enabled=false")
class SpendingLedgerFactsTest {

    @Autowired AppUserRepository users;
    @Autowired UserPaymentRepository payments;
    @Autowired SpendingLedgerRepository ledger;
    @Autowired SpendingLedgerDirtyRepository dirty;
    @Autowired SpendingLedgerDirtyMarker marker;
    @Autowired SpendingLedgerDrainer drainer;
    @Autowired SpendingLedgerFactsWriter factsWriter;
    @Autowired TransactionTemplate transactions;

    private AppUser realUser;

    @BeforeEach
    void setUp() {
        dirty.deleteAll();
        ledger.deleteAll();
        realUser = newUser(true);
    }

    private AppUser newUser(boolean realPerson) {
        AppUser user = users.save(new AppUser("ledger-" + System.nanoTime(),
                new BigDecimal("3000000"), new BigDecimal("1000000"), 6));
        user.setRealPerson(realPerson);
        return users.save(user);
    }

    /** 실사람 명세서에서 온 결제 — 접두가 {@code real-} 이라야 그렇게 읽힌다. */
    private UserPayment realPayment(AppUser user, String id, LocalDateTime at, int amount,
                                    String merchant, String bizno) {
        return payments.save(new UserPayment(
                UserPayment.rowId(user.getId(), "real-" + id), user.getId(), "S1", 9001L,
                at, "642004", "카테고리없음", amount, merchant, bizno));
    }

    // ── 표시가 뜨는가 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("결제를 넣으면 엔티티 콜백이 그 사용자를 표시한다 — 호출부를 고치지 않고")
    void 콜백이_표시를_남긴다() {
        // 이 시험이 지키는 것은 **엔티티 리스너에 스프링 빈이 주입된다**는 가정이다.
        // 주입이 안 되면 콜백이 조용히 아무 일도 안 하고, 표는 영영 원장을 못 따라간다.
        transactions.executeWithoutResult(status ->
                realPayment(realUser, "p1", LocalDateTime.of(2026, 8, 9, 12, 41), 3200,
                        "GS25 포스텍학술정보관점", "2345678901"));

        assertTrue(dirty.findDistinctUserIds().contains(realUser.getId()),
                "결제를 넣었는데 표시가 안 떴다 — 리스너에 빈이 안 붙었을 수 있다");
    }

    @Test
    @DisplayName("분류를 바꾸면 표시가 뜬다 — 더티체킹으로 바뀌는 자리까지 잡는다")
    void 분류_변경도_표시된다() {
        String paymentId = transactions.execute(status ->
                realPayment(realUser, "p1", LocalDateTime.of(2026, 8, 9, 12, 41), 3200,
                        "GS25 포스텍학술정보관점", "2345678901").getPaymentId());
        drainer.drainAll();
        assertTrue(dirty.findDistinctUserIds().isEmpty(), "배수가 대기열을 비웠어야 한다");

        // applyResolved·confirm 같은 자리가 실제로 하는 일 — save() 를 부르지 않는다.
        transactions.executeWithoutResult(status ->
                payments.findById(paymentId).orElseThrow().confirmCategory2("편의점", "REGISTRY"));

        assertTrue(dirty.findDistinctUserIds().contains(realUser.getId()),
                "save() 없이 필드만 바꾼 것도 잡아야 한다");
    }

    // ── 재작성이 맞는가 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("사실 칸이 결제와 맞는다 — 달로 자를 수 있고, 판정 칸은 아직 비어 있다")
    void 사실_칸을_쓴다() {
        transactions.executeWithoutResult(status -> {
            realPayment(realUser, "p1", LocalDateTime.of(2026, 7, 30, 8, 10), 4500, "스타벅스 포항공대점", "1234567890");
            realPayment(realUser, "p2", LocalDateTime.of(2026, 8, 9, 12, 41), 3200, "GS25 포스텍학술정보관점", "2345678901");
            realPayment(realUser, "p3", LocalDateTime.of(2026, 8, 22, 23, 10), 17000, "넷플릭스", "2208162517");
        });
        drainer.drainAll();

        assertEquals(3, ledger.countByUserId(realUser.getId()));
        List<SpendingLedger> august =
                ledger.findByUserIdAndMonthKeyOrderByPaidAtAscPaymentIdAsc(realUser.getId(), "2026-08");
        assertEquals(2, august.size(), "8월로 자르면 두 줄");
        SpendingLedger first = august.get(0);
        assertEquals("GS25 포스텍학술정보관점", first.getMerchantName());
        assertEquals(3200, first.getAmount());
        assertEquals("2345678901", first.getBusinessNumber());
        assertEquals("BIZ:2345678901", first.getMerchantKey());
        assertEquals("점심", first.getDaypart());
        assertEquals("REAL", first.getOrigin());
        assertEquals("642004", first.getNtsIndustryCode());
        assertNotNull(first.getFactsUpdatedAt());

        // **판정은 아직 안 돌았다.** 표가 계산을 일으키지 않는다는 원칙이 여기서 보인다 —
        // 비어 있는 것이 사실이고, '아니다'(거짓)가 아니다.
        assertNull(first.getFixed(), "고정지출 판정이 안 돌았으면 NULL 이다");
        assertNull(first.getWaste());
        assertNull(first.getFixedRecordedAt());
        assertNull(first.getWasteRecordedAt());
    }

    @Test
    @DisplayName("두 번 돌려도 같다 — 멱등")
    void 재작성은_멱등이다() {
        transactions.executeWithoutResult(status ->
                realPayment(realUser, "p1", LocalDateTime.of(2026, 8, 9, 12, 41), 3200, "GS25", "2345678901"));
        drainer.drainAll();
        LocalDateTime firstWriteAt = ledger.findByUserIdOrderByPaidAtAscPaymentIdAsc(realUser.getId())
                .get(0).getFactsUpdatedAt();

        factsWriter.write(realUser.getId());
        factsWriter.write(realUser.getId());

        assertEquals(1, ledger.countByUserId(realUser.getId()), "줄이 늘어나면 안 된다");
        assertNotNull(firstWriteAt);
    }

    @Test
    @DisplayName("사라진 결제의 줄을 치운다 — 재연동으로 결제가 0건이 돼도")
    void 사라진_결제를_치운다() {
        transactions.executeWithoutResult(status -> {
            realPayment(realUser, "p1", LocalDateTime.of(2026, 8, 9, 12, 41), 3200, "GS25", "2345678901");
            realPayment(realUser, "p2", LocalDateTime.of(2026, 8, 22, 23, 10), 17000, "넷플릭스", "2208162517");
        });
        drainer.drainAll();
        assertEquals(2, ledger.countByUserId(realUser.getId()));

        // 재연동이 하는 일 — 벌크 삭제라 엔티티 콜백이 안 뜬다. 명시 표시가 없으면 여기서 실패한다.
        payments.deleteByUserId(realUser.getId());
        marker.mark(realUser.getId(), SpendingLedgerDirty.Reason.PAYMENT);
        drainer.drainAll();

        assertEquals(0, ledger.countByUserId(realUser.getId()),
                "없어진 결제가 표에 남아 있으면 읽는 쪽이 유령을 본다");
    }

    @Test
    @DisplayName("더미 사용자는 이 표에 들어오지 않는다 — 실사용자였다가 아니게 돼도 지운다")
    void 더미는_들어오지_않는다() {
        AppUser dummy = newUser(false);
        transactions.executeWithoutResult(status -> payments.save(new UserPayment(
                UserPayment.rowId(dummy.getId(), "seed-1"), dummy.getId(), "S1", 9001L,
                LocalDateTime.of(2026, 8, 9, 12, 41), "552101", "식비", 12000, "동네식당", "1112233334")));

        drainer.drainAll();

        assertEquals(0, ledger.countByUserId(dummy.getId()));
        assertTrue(dirty.findDistinctUserIds().isEmpty(), "건너뛴 것도 처리다 — 표시는 치워야 한다");
    }

    // ── 대기열이 안전한가 ────────────────────────────────────────────────────

    @Test
    @DisplayName("재작성 중에 들어온 표시는 살아남는다 — 수위 표시 이하만 지운다")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void 수위_표시_뒤의_표시는_남는다() {
        marker.mark(realUser.getId(), SpendingLedgerDirty.Reason.BACKFILL);
        Long watermark = dirty.findWatermark(realUser.getId());
        assertNotNull(watermark);

        // 재작성이 도는 동안 새 표시가 들어온 상황을 그대로 만든다.
        marker.mark(realUser.getId(), SpendingLedgerDirty.Reason.CATEGORY);
        int cleared = dirty.clearUpTo(realUser.getId(), watermark);

        assertEquals(1, cleared, "수위 표시 이하만 지워야 한다");
        assertFalse(dirty.findDistinctUserIds().isEmpty(), "그 뒤에 들어온 표시는 남아 다음 회차가 집는다");
    }

    @Test
    @DisplayName("표시만 있어도 재작성된다 — 재기동으로 나팔을 놓쳐도 복구한다")
    void 표시가_있으면_배치가_잇는다() {
        transactions.executeWithoutResult(status ->
                realPayment(realUser, "p1", LocalDateTime.of(2026, 8, 9, 12, 41), 3200, "GS25", "2345678901"));
        drainer.drainAll();
        ledger.deleteByUserId(realUser.getId());          // 표는 사라졌는데 원장은 그대로인 상태

        marker.mark(realUser.getId(), SpendingLedgerDirty.Reason.BACKFILL);
        int done = drainer.drainAll();

        assertEquals(1, done);
        assertEquals(1, ledger.countByUserId(realUser.getId()));
    }
}
