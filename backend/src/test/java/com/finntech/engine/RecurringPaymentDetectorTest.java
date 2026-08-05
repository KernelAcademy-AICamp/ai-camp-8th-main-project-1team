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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    @DisplayName("PG 가 바뀌어도 한 구독이다 — 번호가 아니라 가맹점명으로 모인다")
    void 대행사가_갈라놓지_못한다() {
        // 2026-08-05 운영 실측 그대로. 넷플릭스가 매달 22일 22,000원씩 7회 결제됐는데
        // 사업자번호는 KG이니시스 5건·NHNKCP 2건으로 오갔다. 번호로 묶으면 앞은 2월이 비어
        // 주기가 안 맞고 뒤는 최소 건수 미달이라 **완벽한 월 구독이 어느 쪽에서도 안 잡혔다.**
        String KG = "2208155597", KCP = "1138521083";
        List<UserPayment> txns = List.of(
                tx(LocalDateTime.of(2026, 1, 22, 12, 0), "취미/여가", 22000, "넷플릭스서비시스코리아 유한회사", KG),
                tx(LocalDateTime.of(2026, 2, 22, 12, 0), "취미/여가", 22000, "넷플릭스서비시스코리아 유한회사", KCP),
                tx(LocalDateTime.of(2026, 3, 22, 12, 0), "취미/여가", 22000, "넷플릭스서비시스코리아 유한회사", KG),
                tx(LocalDateTime.of(2026, 4, 22, 12, 0), "취미/여가", 22000, "넷플릭스서비시스코리아 유한회사", KG),
                tx(LocalDateTime.of(2026, 5, 22, 12, 0), "취미/여가", 22000, "넷플릭스서비시스코리아 유한회사", KG),
                tx(LocalDateTime.of(2026, 6, 22, 12, 0), "취미/여가", 22000, "넷플릭스서비시스코리아 유한회사", KG),
                tx(LocalDateTime.of(2026, 7, 22, 12, 0), "취미/여가", 22000, "넷플릭스서비시스코리아 유한회사", KCP));

        List<RecurringPayment> found = RecurringPaymentDetector.detectFrom(
                txns, LocalDateTime.of(2026, 8, 5, 12, 0), recurring, daypart,
                biz -> KG.equals(biz) || KCP.equals(biz));
        RecurringPayment r = only(found, RecurringPayment.Type.FIXED);
        assertEquals(7, r.occurrenceDays(), "일곱 번이 한 묶음이어야 한다");
        assertEquals(22000, r.representativeAmount());

        // 번호를 그대로 키로 쓰면(= PG 를 모르면) 갈라져서 안 잡힌다 — 회귀를 못박는다.
        assertTrue(RecurringPaymentDetector.detectFrom(txns, LocalDateTime.of(2026, 8, 5, 12, 0),
                        recurring, daypart).stream()
                        .noneMatch(x -> x.type() == RecurringPayment.Type.FIXED),
                "PG 를 모르면 갈라진다 — 이 사실이 바뀌면 위 단정의 의미도 바뀐다");
    }

    @Test
    @DisplayName("한 가맹점에 구독이 둘이면 금액으로 갈라 본다 — 앱마켓")
    void 앱마켓의_두_구독() {
        // 2026-08-05 실사용자: `Apple` 15건이 통째로는 어느 주기에도 안 맞았는데,
        // 금액으로 나누니 매달 5일 2,500원과 매달 11일 14,000원 두 구독이 드러났다.
        List<UserPayment> txns = new java.util.ArrayList<>();
        for (int m = 4; m <= 7; m++) {
            txns.add(tx(LocalDateTime.of(2026, m, 5, 12, 0), "취미/여가", 2500, "Apple", "5278800686"));
            txns.add(tx(LocalDateTime.of(2026, m, 11, 12, 0), "취미/여가", 14000, "Apple", "5278800686"));
        }
        // 사이사이 일회성 결제 — 통째로 보면 주기를 흐트러뜨리는 잡음이다.
        txns.add(tx(LocalDateTime.of(2026, 5, 19, 12, 0), "취미/여가", 3800, "Apple", "5278800686"));
        txns.add(tx(LocalDateTime.of(2026, 6, 29, 12, 0), "취미/여가", 3500, "Apple", "5278800686"));

        List<RecurringPayment> fixed = RecurringPaymentDetector
                .detectFrom(txns, LocalDateTime.of(2026, 8, 1, 12, 0), recurring, daypart,
                        biz -> "5278800686".equals(biz))
                .stream().filter(r -> r.type() == RecurringPayment.Type.FIXED).toList();

        assertEquals(2, fixed.size(), "두 구독이 각각 잡혀야 한다: " + fixed);
        assertTrue(fixed.stream().anyMatch(r -> r.representativeAmount() == 2500 && r.occurrenceDays() == 4));
        assertTrue(fixed.stream().anyMatch(r -> r.representativeAmount() == 14000 && r.occurrenceDays() == 4));
    }

    @Test
    @DisplayName("요금 인상은 금액이 달라도 한 구독이다 — 통째로 먼저 본다")
    void 요금인상은_갈라지지_않는다() {
        // 금액으로 **먼저** 나누면 13,500×4 와 17,000×2 가 별개 구독이 된다. 통째로 먼저
        // 보는 순서가 그것을 막는다(§8-W 계단 변화).
        List<UserPayment> txns = List.of(
                tx(LocalDateTime.of(2026, 2, 15, 12, 0), "취미/여가", 13500, "어떤구독", ""),
                tx(LocalDateTime.of(2026, 3, 15, 12, 0), "취미/여가", 13500, "어떤구독", ""),
                tx(LocalDateTime.of(2026, 4, 15, 12, 0), "취미/여가", 13500, "어떤구독", ""),
                tx(LocalDateTime.of(2026, 5, 15, 12, 0), "취미/여가", 13500, "어떤구독", ""),
                tx(LocalDateTime.of(2026, 6, 15, 12, 0), "취미/여가", 17000, "어떤구독", ""),
                tx(LocalDateTime.of(2026, 7, 15, 12, 0), "취미/여가", 17000, "어떤구독", ""));

        RecurringPayment r = only(detect(txns, LocalDateTime.of(2026, 8, 1, 12, 0)),
                RecurringPayment.Type.FIXED);
        assertEquals(6, r.occurrenceDays(), "여섯 번이 한 구독이어야 한다");
        assertEquals(17000, r.representativeAmount(), "인상 뒤 금액이 대표금액이다");
        assertEquals(13500L, r.priorAmount(), "이전 요금을 말할 수 있어야 한다");
    }

    @Test
    @DisplayName("분류가 바뀌어도 한 묶음이다 — 카테고리는 키가 아니다")
    void 분류가_묶음을_깨지_못한다() {
        // 사용자가 중간에 "이건 식비예요"를 누르거나 추정이 확정으로 승격되기만 해도
        // 그때까지 잡히던 정기결제가 사라지면 안 된다. 계약인지는 어디서 얼마를 언제 냈느냐다.
        List<UserPayment> txns = List.of(
                tx(LocalDateTime.of(2026, 3, 10, 12, 0), "카테고리없음", 9900, "어떤구독", ""),
                tx(LocalDateTime.of(2026, 4, 10, 12, 0), "카테고리없음", 9900, "어떤구독", ""),
                tx(LocalDateTime.of(2026, 5, 10, 12, 0), "취미/여가", 9900, "어떤구독", ""),
                tx(LocalDateTime.of(2026, 6, 10, 12, 0), "취미/여가", 9900, "어떤구독", ""));

        RecurringPayment r = only(detect(txns, LocalDateTime.of(2026, 7, 1, 12, 0)),
                RecurringPayment.Type.FIXED);
        assertEquals(4, r.occurrenceDays(), "분류가 갈려도 네 번이 한 묶음이어야 한다");
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
    @DisplayName("금액이 달라도 매달 같은 날 같은 곳이면 고정형 — 통신비는 사용량따라 변한다")
    void acceptsVariableAmountMonthlyFixed() {
        // 2026-08-04 전에는 이 케이스를 "변동금액은 FIXED 아님"으로 **거부**했다. 그 전제가 틀렸다 —
        // 매달 5일에 같은 통신사에서 빠지면 금액이 흔들려도 고정지출이다. 오탐 방어는 금액이 아니라
        // 주기 안정성(fixed-gap-cv-max)이 맡는다.
        int[] amts = {30000, 55000, 42000, 60000};
        List<UserPayment> t = new ArrayList<>();
        LocalDate d = LocalDate.of(2025, 12, 5);
        for (int i = 0; i < 4; i++) t.add(tx(d.plusMonths(i).atTime(9, 0), "통신비", amts[i], "이통사", "1112233334"));

        RecurringPayment f = only(detect(t, LocalDateTime.of(2026, 3, 10, 0, 0)), RecurringPayment.Type.FIXED);
        assertTrue(f.amountVaries(), "금액이 흔들린다고 표시해야");
        assertEquals(60000, f.representativeAmount(), "흔들리면 최근 결제액이 대표금액");
    }

    @Test
    @DisplayName("주기가 흩어지면 금액이 같아도 고정형 아님 — 오탐 방어선은 여기다")
    void rejectsIrregularInterval() {
        // 월간 범위 평균(≈30일) 안에 들어와도 간격이 12/48/30 처럼 흩어지면 계약이 아니다.
        // 이것이 금액 게이트를 뺀 자리를 대신 막는 방어선이다.
        List<UserPayment> t = List.of(
                tx(LocalDateTime.of(2026, 1, 1, 9, 0), "쇼핑", 30000, "가게", "1112233334"),
                tx(LocalDateTime.of(2026, 1, 13, 9, 0), "쇼핑", 30000, "가게", "1112233334"),
                tx(LocalDateTime.of(2026, 3, 2, 9, 0), "쇼핑", 30000, "가게", "1112233334"),
                tx(LocalDateTime.of(2026, 4, 1, 9, 0), "쇼핑", 30000, "가게", "1112233334"));

        assertTrue(detect(t, LocalDateTime.of(2026, 4, 10, 0, 0)).stream()
                .noneMatch(r -> r.type() == RecurringPayment.Type.FIXED), "간격이 흩어지면 FIXED 아님");
    }

    @Test
    @DisplayName("요금 인상 1회로 탐지가 사라지지 않는다 — 이전 금액도 함께 준다")
    void survivesPriceIncrease() {
        // 예전에는 이 한 번의 인상으로 평균 CV 가 0.123 이 되어 6개월치가 통째로 사라졌다.
        int[] amts = {13500, 13500, 13500, 13500, 17000, 17000};
        List<UserPayment> t = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 2, 15);
        for (int i = 0; i < 6; i++) t.add(tx(d.plusMonths(i).atTime(9, 0), "취미/여가", amts[i], "넷플릭스", "1658700119"));

        RecurringPayment f = only(detect(t, LocalDateTime.of(2026, 7, 20, 0, 0)), RecurringPayment.Type.FIXED);
        assertEquals(17000, f.representativeAmount(), "지금 내는 금액");
        assertEquals(13500L, f.priorAmount(), "올리기 전 금액");
        assertTrue(f.amountVaries());
        assertEquals(RecurringPayment.Status.ACTIVE, f.status());
    }

    @Test
    @DisplayName("부분환불 1건이 섞여도 잡고, 대표금액이 환불액에 끌려가지 않는다")
    void survivesOneOffRefund() {
        int[] amts = {13500, 13500, 2000, 13500, 13500, 13500};
        List<UserPayment> t = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 2, 15);
        for (int i = 0; i < 6; i++) t.add(tx(d.plusMonths(i).atTime(9, 0), "취미/여가", amts[i], "넷플릭스", "1658700119"));

        RecurringPayment f = only(detect(t, LocalDateTime.of(2026, 7, 20, 0, 0)), RecurringPayment.Type.FIXED);
        assertEquals(13500, f.representativeAmount(), "이상치 한 건에 끌려가면 안 된다");
    }

    @Test
    @DisplayName("사업자번호가 같으면 표기가 달라도 한 건으로 묶인다")
    void mergesMerchantNameVariantsByBusinessNumber() {
        // 실 카드명세서는 같은 가맹점을 여러 표기로 찍는다(넷플릭스 / NETFLIX.COM / 넷플릭스서비시스코리아).
        // 예전 그룹 키는 category2 를 포함해, 제공자가 업종을 갱신하면 한 구독이 두 그룹으로 쪼개졌다.
        String[] names = {"넷플릭스", "NETFLIX.COM", "넷플릭스서비시스코리아", "넷플릭스"};
        String[] cats = {"취미/여가", "취미/여가", "쇼핑", "취미/여가"};   // 업종이 한 번 흔들려도
        List<UserPayment> t = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 3, 15);
        for (int i = 0; i < 4; i++) {
            t.add(tx(d.plusMonths(i).atTime(9, 0), cats[i], 13500, names[i], "1658700119"));
        }

        RecurringPayment f = only(detect(t, LocalDateTime.of(2026, 7, 1, 0, 0)), RecurringPayment.Type.FIXED);
        assertEquals(4, f.occurrenceDays(), "네 건이 한 그룹이어야");
        assertEquals("1658700119", f.businessNumber());
    }

    @Test
    @DisplayName("주간은 금액이 흔들리면 여전히 거부 — 습관과 계약을 가른다")
    void rejectsWeeklyWithVariableAmount() {
        int[] amts = {4500, 9800, 3200, 12000, 5100};
        List<UserPayment> t = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 2, 3);
        for (int i = 0; i < 5; i++) t.add(tx(d.plusDays(7L * i).atTime(8, 0), "카페", amts[i], "카페", "2223344445"));

        assertTrue(detect(t, LocalDateTime.of(2026, 3, 5, 0, 0)).stream()
                .noneMatch(r -> r.type() == RecurringPayment.Type.FIXED), "주간 변동금액은 습관이지 계약이 아니다");
    }

    @Test
    @DisplayName("해지한 구독은 ENDED — 과거 날짜를 '다음 예상일'이라고 하지 않는다")
    void endedSubscriptionHasNoNextExpected() {
        // 2026-08-04 운영에서 실제로 발견: user 2 의 AIG손해보험이 마지막 결제 07-04 인데
        // 화면에 "다음 2026-08-03"(이미 지난 날)이 떠 있었다.
        List<UserPayment> t = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 4);
        for (int i = 0; i < 6; i++) t.add(tx(d.plusMonths(i).atTime(9, 0), "취미/여가", 13500, "넷플릭스", "1658700119"));

        // 마지막 결제 2026-06-04. 기준일 2026-09-01 이면 주기(30일)의 1.5배를 훌쩍 넘는다.
        RecurringPayment f = only(detect(t, LocalDateTime.of(2026, 9, 1, 0, 0)), RecurringPayment.Type.FIXED);
        assertEquals(RecurringPayment.Status.ENDED, f.status());
        assertNull(f.nextExpected(), "끝난 구독에 다음 예상일은 없다");
        assertEquals(LocalDate.of(2026, 1, 4), f.firstSeen());
        assertEquals(LocalDate.of(2026, 6, 4), f.lastSeen());
    }

    @Test
    @DisplayName("진행 중인 구독은 ACTIVE — 구독 기간을 함께 준다")
    void activeSubscriptionReportsSpan() {
        List<UserPayment> t = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 2, 4);
        for (int i = 0; i < 6; i++) t.add(tx(d.plusMonths(i).atTime(9, 0), "취미/여가", 13500, "넷플릭스", "1658700119"));

        RecurringPayment f = only(detect(t, LocalDateTime.of(2026, 7, 20, 0, 0)), RecurringPayment.Type.FIXED);
        assertEquals(RecurringPayment.Status.ACTIVE, f.status());
        assertEquals(LocalDate.of(2026, 2, 4), f.firstSeen());
        assertEquals(LocalDate.of(2026, 7, 4), f.lastSeen());
        assertEquals(f.lastSeen().plusDays(f.periodDays()), f.nextExpected());
        assertFalse(f.amountVaries());
        assertNull(f.priorAmount(), "변한 적이 없으면 이전 금액도 없다");
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
