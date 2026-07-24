package com.finntech.engine;

import com.finntech.config.AnalysisProperties;
import com.finntech.domain.UserPayment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 반복 결제 탐지(②) 순수함수 검증 — 고정형/루틴형 인식과 오탐 거부. */
class RecurringPaymentDetectorTest {

    private final AnalysisProperties.Recurring recurring = new AnalysisProperties.Recurring();
    private final AnalysisProperties.Daypart daypart = new AnalysisProperties.Daypart();

    private static UserPayment tx(LocalDateTime at, String cat2, int amount, String merchant, String bizno) {
        return new UserPayment(at + "-" + merchant + "-" + amount, 1L, "S1", 9001L,
                at, "생활", cat2, amount, merchant, 0, bizno);
    }

    private List<RecurringPayment> detect(List<UserPayment> txns, LocalDateTime ref) {
        return RecurringPaymentDetector.detectFrom(txns, ref, recurring, daypart);
    }

    private static RecurringPayment only(List<RecurringPayment> rs, RecurringPayment.Type type) {
        List<RecurringPayment> f = rs.stream().filter(r -> r.type() == type).toList();
        assertEquals(1, f.size(), type + " 1건이어야 함: " + rs);
        return f.get(0);
    }

    @Test
    @DisplayName("월간 고정결제(일정금액) → FIXED, 월간주기·다음예상일·대표금액")
    void detectsMonthlyFixed() {
        List<UserPayment> t = List.of(
                tx(LocalDateTime.of(2025, 12, 5, 9, 0), "통신비", 55000, "이통사", "1112233334"),
                tx(LocalDateTime.of(2026, 1, 5, 9, 0), "통신비", 55000, "이통사", "1112233334"),
                tx(LocalDateTime.of(2026, 2, 5, 9, 0), "통신비", 55000, "이통사", "1112233334"),
                tx(LocalDateTime.of(2026, 3, 5, 9, 0), "통신비", 55000, "이통사", "1112233334"));

        RecurringPayment f = only(detect(t, LocalDateTime.of(2026, 3, 10, 0, 0)), RecurringPayment.Type.FIXED);
        assertEquals("통신비", f.category2());
        assertEquals("1112233334", f.businessNumber());
        assertEquals(55000, f.representativeAmount());
        assertTrue(f.periodDays() >= 27 && f.periodDays() <= 33, "월간 주기여야: " + f.periodDays());
        assertEquals(LocalDate.of(2026, 3, 5).plusDays(f.periodDays()), f.nextExpected());
        assertEquals(4, f.occurrenceDays());
    }

    @Test
    @DisplayName("주간 고정결제 → FIXED, 주기 7일")
    void detectsWeeklyFixed() {
        List<UserPayment> t = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 2, 1);
        for (int i = 0; i < 5; i++) t.add(tx(d.plusDays(7L * i).atTime(19, 0), "학원", 30000, "요가원", "2223344445"));

        RecurringPayment f = only(detect(t, LocalDateTime.of(2026, 3, 5, 0, 0)), RecurringPayment.Type.FIXED);
        assertEquals(7, f.periodDays());
        assertEquals(30000, f.representativeAmount());
    }

    @Test
    @DisplayName("금액이 들쭉날쭉하면 고정형 아님")
    void rejectsVariableAmountFixed() {
        int[] amts = {30000, 55000, 42000, 60000};
        List<UserPayment> t = new ArrayList<>();
        LocalDate d = LocalDate.of(2025, 12, 5);
        for (int i = 0; i < 4; i++) t.add(tx(d.plusMonths(i).atTime(9, 0), "통신비", amts[i], "이통사", "1112233334"));

        assertTrue(detect(t, LocalDateTime.of(2026, 3, 10, 0, 0)).stream()
                .noneMatch(r -> r.type() == RecurringPayment.Type.FIXED), "변동금액은 FIXED 아님");
    }

    @Test
    @DisplayName("아침 카페 습관(가맹점 제각각) → ROUTINE(아침), 등장일수·대표금액")
    void detectsMorningCoffeeRoutine() {
        int[] amts = {4500, 4800, 4200, 4500, 4600, 4400, 4500, 4700, 4300, 4500};
        List<UserPayment> t = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 2, 2);
        // 가맹점·사업자번호가 매번 달라도(습관은 가맹점 무관) 루틴형으로 잡혀야 한다
        for (int i = 0; i < 10; i++) {
            t.add(tx(d.plusDays(2L * i).atTime(8, 0), "카페", amts[i], "동네카페" + i, "999999999" + (i % 10)));
        }

        RecurringPayment r = only(detect(t, LocalDateTime.of(2026, 3, 1, 12, 0)), RecurringPayment.Type.ROUTINE);
        assertEquals("카페", r.category2());
        assertEquals("아침", r.daypart());
        assertEquals(10, r.occurrenceDays());
        assertTrue(Math.abs(r.representativeAmount() - 4500) <= 200, "대표금액≈4500: " + r.representativeAmount());
    }

    @Test
    @DisplayName("등장일수가 바닥값 미만이면 루틴형 아님")
    void rejectsSparseRoutine() {
        List<UserPayment> t = List.of(
                tx(LocalDateTime.of(2026, 2, 10, 8, 0), "카페", 4500, "카페", "9999999990"),
                tx(LocalDateTime.of(2026, 2, 20, 8, 0), "카페", 4500, "카페", "9999999990"));

        assertTrue(detect(t, LocalDateTime.of(2026, 3, 1, 12, 0)).isEmpty(), "2일 등장은 반복 아님");
    }
}
