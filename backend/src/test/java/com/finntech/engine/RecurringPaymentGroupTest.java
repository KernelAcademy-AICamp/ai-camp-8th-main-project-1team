package com.finntech.engine;

import com.finntech.config.AnalysisProperties;
import com.finntech.domain.UserPayment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 고정형 묶음의 <b>명단</b> 검증 — 결제 한 줄마다 고정지출을 적으려면 이 성질들이 참이어야 한다.
 *
 * <p>{@link RecurringPaymentDetectorTest} 는 <i>무엇이 고정지출인가</i>를 본다. 여기는
 * <i>그 판정이 어떤 결제들 위에 서 있는가</i>를 본다.
 */
class RecurringPaymentGroupTest {

    private final AnalysisProperties.Recurring recurring = new AnalysisProperties.Recurring();
    private final AnalysisProperties.Daypart daypart = new AnalysisProperties.Daypart();

    /** PG 를 하나도 모르는 상태 — 번호가 있으면 그대로 키가 된다. */
    private static final Predicate<String> NO_AGENCY = biz -> false;

    private static UserPayment tx(LocalDateTime at, String cat2, int amount, String merchant, String bizno) {
        return new UserPayment(at + "-" + merchant + "-" + amount, 1L, "S1", 9001L,
                at, "생활", cat2, amount, merchant, bizno);
    }

    // ── 판정 로직이 한 벌인가 ────────────────────────────────────────────────

    @Test
    @DisplayName("요약만 내는 길과 명단까지 내는 길이 같은 답을 낸다 — 판정은 한 벌뿐이다")
    void 두_진입점이_같은_판정을_낸다() {
        // 갈라지면 화면의 정기결제와 표의 고정지출이 다른 것을 가리키는데, 그 차이는 아무 데도 안 찍힌다.
        List<UserPayment> txns = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 2, 5);
        for (int i = 0; i < 6; i++) {
            txns.add(tx(start.plusMonths(i).atTime(9, 0), "통신비", 55000, "이통사", "1112233334"));
            txns.add(tx(start.plusMonths(i).plusDays(10).atTime(21, 0), "취미/여가", 13500, "넷플릭스", "1658700119"));
        }
        txns.add(tx(LocalDate.of(2026, 4, 3).atTime(12, 30), "카페", 4800, "동네카페", "5556667778"));
        LocalDateTime reference = LocalDateTime.of(2026, 8, 1, 12, 0);

        List<RecurringPayment> viaSummary = RecurringPaymentDetector
                .detectFrom(txns, reference, recurring, daypart, NO_AGENCY).stream()
                .filter(r -> r.type() == RecurringPayment.Type.FIXED).toList();
        List<RecurringPayment> viaGroups = RecurringPaymentDetector
                .fixedGroupsFrom(txns, reference, recurring, NO_AGENCY).stream()
                .map(FixedGroup::summary).toList();

        assertEquals(viaSummary, viaGroups, "두 진입점의 고정형 목록은 순서까지 같아야 한다");
        assertEquals(2, viaGroups.size(), "통신비·넷플릭스 두 계약: " + viaGroups);
    }

    // ── 명단이 분할인가 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("한 결제는 많아야 한 묶음에 든다 — 표가 결제 한 줄에 고정지출 칸 한 벌만 두는 근거")
    void 명단은_겹치지_않는다() {
        // 앱마켓처럼 한 가맹점 아래 구독이 둘이면 금액으로 갈리는데, 그때도 겹치면 안 된다.
        List<UserPayment> txns = new ArrayList<>();
        for (int month = 4; month <= 7; month++) {
            txns.add(tx(LocalDateTime.of(2026, month, 5, 12, 0), "취미/여가", 2500, "Apple", "5278800686"));
            txns.add(tx(LocalDateTime.of(2026, month, 11, 12, 0), "취미/여가", 14000, "Apple", "5278800686"));
        }
        txns.add(tx(LocalDateTime.of(2026, 5, 19, 12, 0), "취미/여가", 3800, "Apple", "5278800686"));

        List<FixedGroup> groups = RecurringPaymentDetector.fixedGroupsFrom(
                txns, LocalDateTime.of(2026, 8, 1, 12, 0), recurring, biz -> "5278800686".equals(biz));

        assertEquals(2, groups.size(), "두 구독이 각각 잡혀야 한다: " + groups);
        Set<String> seen = new HashSet<>();
        for (FixedGroup group : groups) {
            for (String paymentId : group.paymentIds()) {
                assertTrue(seen.add(paymentId), "결제 " + paymentId + " 가 두 묶음에 들었다");
            }
        }
        assertEquals(8, seen.size(), "일회성 3,800원은 어느 묶음에도 안 든다");
    }

    @Test
    @DisplayName("요금이 올라도 명단은 하나다 — 통째로 먼저 보는 순서가 지켜지는지 명단으로 확인한다")
    void 요금인상은_명단을_쪼개지_않는다() {
        int[] amounts = {13500, 13500, 13500, 13500, 17000, 17000};
        List<UserPayment> txns = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 2, 15);
        for (int i = 0; i < 6; i++) {
            txns.add(tx(start.plusMonths(i).atTime(9, 0), "취미/여가", amounts[i], "넷플릭스", "1658700119"));
        }

        List<FixedGroup> groups = RecurringPaymentDetector.fixedGroupsFrom(
                txns, LocalDateTime.of(2026, 7, 20, 0, 0), recurring, NO_AGENCY);

        assertEquals(1, groups.size(), "한 구독이어야 한다: " + groups);
        assertEquals(6, groups.get(0).paymentIds().size(), "여섯 건 전부가 그 구독의 명단이다");
    }

    @Test
    @DisplayName("명단은 결제일 오름차순으로 고정된다 — 저장소가 내주는 역순을 그대로 흘리지 않는다")
    void 명단_정렬이_고정이다() {
        // 재현성(마스터 §4 원칙 3). 같은 입력에 같은 출력이라야 표를 두 번 써도 같은 값이다.
        List<UserPayment> ascending = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 3, 8);
        for (int i = 0; i < 5; i++) {
            ascending.add(tx(start.plusMonths(i).atTime(9, 0), "통신비", 44000, "이통사", "1112233334"));
        }
        List<UserPayment> descending = new ArrayList<>(ascending);
        java.util.Collections.reverse(descending);
        LocalDateTime reference = LocalDateTime.of(2026, 8, 1, 12, 0);

        List<String> fromAscending = RecurringPaymentDetector
                .fixedGroupsFrom(ascending, reference, recurring, NO_AGENCY).get(0).paymentIds();
        List<String> fromDescending = RecurringPaymentDetector
                .fixedGroupsFrom(descending, reference, recurring, NO_AGENCY).get(0).paymentIds();

        assertEquals(fromAscending, fromDescending, "입력 순서가 명단 순서를 바꾸면 안 된다");
        assertEquals(5, fromAscending.size());
        assertTrue(fromAscending.get(0).startsWith("2026-03-08"), "첫 결제가 맨 앞: " + fromAscending);
    }

    // ── 함께 내보내는 값들 ───────────────────────────────────────────────────

    @Test
    @DisplayName("주기 종류와 간격 CV 를 함께 낸다 — 판정 안에서 계산되고 버려지던 값이다")
    void 주기종류와_간격CV를_함께_낸다() {
        List<UserPayment> weekly = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 2, 1);
        for (int i = 0; i < 5; i++) {
            weekly.add(tx(start.plusDays(7L * i).atTime(19, 0), "학원", 30000, "요가원", "2223344445"));
        }
        FixedGroup weeklyGroup = RecurringPaymentDetector
                .fixedGroupsFrom(weekly, LocalDateTime.of(2026, 3, 5, 0, 0), recurring, NO_AGENCY).get(0);
        assertSame(FixedGroup.PeriodKind.WEEKLY, weeklyGroup.periodKind());
        assertEquals(0.0, weeklyGroup.gapCv(), 1e-9, "간격이 정확히 7일씩이면 CV 가 0이다");

        List<UserPayment> monthly = new ArrayList<>();
        LocalDate monthlyStart = LocalDate.of(2025, 12, 5);
        for (int i = 0; i < 4; i++) {
            monthly.add(tx(monthlyStart.plusMonths(i).atTime(9, 0), "통신비", 55000, "이통사", "1112233334"));
        }
        FixedGroup monthlyGroup = RecurringPaymentDetector
                .fixedGroupsFrom(monthly, LocalDateTime.of(2026, 3, 10, 0, 0), recurring, NO_AGENCY).get(0);
        assertSame(FixedGroup.PeriodKind.MONTHLY, monthlyGroup.periodKind());
        assertTrue(monthlyGroup.gapCv() <= recurring.getFixedGapCvMax(),
                "판정을 통과했으면 CV 가 임계 이하여야 한다: " + monthlyGroup.gapCv());
    }

    // ── 묶음 키 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("묶음 키는 사업자번호 우선, PG 면 이름으로 물러나고, 둘 다 없으면 없다")
    void 묶음키가_묶는_규칙과_같다() {
        assertEquals("BIZ:1658700119",
                RecurringPaymentDetector.merchantKeyOf("1658700119", "넷플릭스", NO_AGENCY));
        assertEquals("NAME:넷플릭스",
                RecurringPaymentDetector.merchantKeyOf("2208155597", "넷플릭스", biz -> true),
                "PG 번호는 결제처를 말해 주지 않는다 — 이름으로 물러난다");
        assertEquals("NAME:어떤구독",
                RecurringPaymentDetector.merchantKeyOf("", "어떤구독", NO_AGENCY));
        assertEquals("NAME:어떤구독",
                RecurringPaymentDetector.merchantKeyOf(null, "어떤구독", NO_AGENCY));
        assertNull(RecurringPaymentDetector.merchantKeyOf(null, null, NO_AGENCY),
                "번호도 이름도 없으면 어느 묶음에도 못 든다");
        assertNull(RecurringPaymentDetector.merchantKeyOf("  ", "  ", NO_AGENCY));
    }

    @Test
    @DisplayName("묶음이 실제로 쓴 키가 그 키다 — 표에 적히는 값과 묶는 값이 갈라지지 않는다")
    void 내보내는_키가_묶는_키다() {
        String agency = "2208155597";
        List<UserPayment> txns = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 2, 22);
        for (int i = 0; i < 5; i++) {
            txns.add(tx(start.plusMonths(i).atTime(12, 0), "취미/여가", 22000,
                    "넷플릭스서비시스코리아 유한회사", agency));
        }

        FixedGroup group = RecurringPaymentDetector.fixedGroupsFrom(
                txns, LocalDateTime.of(2026, 7, 1, 12, 0), recurring, agency::equals).get(0);

        assertEquals("NAME:넷플릭스서비시스코리아 유한회사", group.merchantKey());
        assertEquals(RecurringPaymentDetector.merchantKeyOf(agency, "넷플릭스서비시스코리아 유한회사",
                agency::equals), group.merchantKey(), "내보낸 키는 묶을 때 쓴 함수의 답과 같아야 한다");
    }
}
