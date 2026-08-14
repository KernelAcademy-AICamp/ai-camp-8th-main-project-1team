package com.finntech.ledger;

import com.finntech.config.AnalysisProperties;
import com.finntech.domain.SpendingLedger;
import com.finntech.domain.UserMerchantStance;
import com.finntech.domain.UserPayment;
import com.finntech.engine.FixedGroup;
import com.finntech.engine.RecurringPaymentDetector;
import com.finntech.ml.WasteScoringService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 소비 원장 한 줄을 만드는 규칙 — 값 하나하나를 붙들고 본다. */
class SpendingLedgerRowMapperTest {

    private final AnalysisProperties.Daypart daypart = new AnalysisProperties.Daypart();
    private final AnalysisProperties.Recurring recurring = new AnalysisProperties.Recurring();

    private static final Predicate<String> NO_AGENCY = biz -> false;
    private static final String DETECTOR_VERSION = "v1";

    /** 실사람 명세서에서 온 결제 — 접두가 {@code real-} 이라야 그렇게 읽힌다. */
    private static UserPayment realPayment(String id, LocalDateTime at, int amount,
                                           String merchant, String bizno) {
        return new UserPayment(UserPayment.rowId(7L, "real-" + id), 7L, "S1", 9001L,
                at, "642004", "카페", amount, merchant, bizno);
    }

    private static UserPayment syntheticPayment(String id, LocalDateTime at) {
        return new UserPayment(UserPayment.rowId(7L, id), 7L, "S1", 9001L,
                at, "552101", "식비", 12000, "동네식당", "1112233334");
    }

    /** 아직 아무 분류도 없는 실사람 결제 — 실 명세서에 업종코드가 없어 대개 이 상태로 들어온다. */
    private static UserPayment unclassifiedPayment(String id, LocalDateTime at, int amount,
                                                   String merchant, String bizno) {
        return new UserPayment(UserPayment.rowId(7L, "real-" + id), 7L, "S1", 9001L,
                at, "642004", "카테고리없음", amount, merchant, bizno);
    }

    // ── 유도되는 값 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("월·날짜·일·요일·시간대를 결제일시에서 만든다 — 읽는 쪽이 계산하지 않게")
    void 시간_칸들을_유도한다() {
        UserPayment payment = realPayment("p1", LocalDateTime.of(2026, 8, 9, 12, 41), 3200,
                "GS25 포스텍학술정보관점", "2345678901");

        SpendingLedger.Facts facts = SpendingLedgerRowMapper.factsOf(
                payment, SpendingLedgerRowMapper.MerchantFacts.EMPTY, daypart, NO_AGENCY);

        assertEquals("2026-08", facts.monthKey());
        assertEquals(LocalDate.of(2026, 8, 9), facts.paidOn());
        assertEquals(9, facts.dayOfMonth());
        assertEquals(7, facts.dayOfWeek(), "2026-08-09 는 일요일 = 7");
        assertEquals("점심", facts.daypart());
        assertEquals(daypart.bucketOf(12), facts.daypart(), "시간대는 Daypart.bucketOf 한 곳에서만 나온다");
    }

    @Test
    @DisplayName("실사람 결제와 생성기 결제를 가른다 — 실사용자 계정에도 둘이 섞인다")
    void 출처를_가른다() {
        var atNoon = LocalDateTime.of(2026, 8, 9, 12, 0);
        assertEquals("REAL", SpendingLedgerRowMapper.factsOf(
                realPayment("p1", atNoon, 3200, "GS25", "2345678901"),
                SpendingLedgerRowMapper.MerchantFacts.EMPTY, daypart, NO_AGENCY).origin());
        assertEquals("SYNTHETIC", SpendingLedgerRowMapper.factsOf(
                syntheticPayment("p2", atNoon),
                SpendingLedgerRowMapper.MerchantFacts.EMPTY, daypart, NO_AGENCY).origin());
    }

    // ── 확정과 추정을 가르는 자리 ────────────────────────────────────────────

    @Test
    @DisplayName("추정은 추정 칸에만 들어간다 — 확정 자리를 차지하지 못한다")
    void 추정과_확정을_가른다() {
        UserPayment payment = unclassifiedPayment("p1", LocalDateTime.of(2026, 8, 5, 23, 30), 2500,
                "Apple", "");
        payment.suggestCategory2("구독·콘텐츠", "TEMP");

        SpendingLedger.Facts facts = SpendingLedgerRowMapper.factsOf(
                payment, SpendingLedgerRowMapper.MerchantFacts.EMPTY, daypart, NO_AGENCY);

        assertEquals("구독·콘텐츠", facts.category2Llm(), "추정은 추정 칸에");
        assertEquals("TEMP", facts.category2LlmSource(), "무료 통로의 답임을 남긴다");
        assertEquals("NONE", facts.category2Source(), "확정은 아직 없다");
    }

    @Test
    @DisplayName("확정 출처는 그대로 옮긴다 — 추정 출처는 비운다")
    void 확정_출처를_옮긴다() {
        UserPayment payment = realPayment("p1", LocalDateTime.of(2026, 8, 9, 12, 41), 3200,
                "GS25 포스텍학술정보관점", "2345678901");
        payment.confirmCategory2("편의점", "DICT");

        SpendingLedger.Facts facts = SpendingLedgerRowMapper.factsOf(
                payment, SpendingLedgerRowMapper.MerchantFacts.EMPTY, daypart, NO_AGENCY);

        assertEquals("편의점", facts.category2());
        assertEquals("DICT", facts.category2Source());
        assertNull(facts.category2LlmSource(), "확정만 있는 줄에 추정 출처를 지어내지 않는다");
    }

    @Test
    @DisplayName("확정 값은 있는데 출처를 잃었으면 모른다고 적는다 — NONE 이라고 하지 않는다")
    void 잃어버린_확정_출처는_모른다고_적는다() {
        // 원장은 확정 출처와 추정 출처를 한 칸에 겸해 담는다. 추정이 나중에 칠해지면
        // 그 전의 확정 출처는 되찾을 길이 없다. 그때 'NONE'(아직 아무것도 없다)이라고
        // 적으면 값이 있는데도 없다고 말하는 셈이라 거짓이 된다.
        UserPayment payment = realPayment("p1", LocalDateTime.of(2026, 8, 9, 12, 41), 3200,
                "GS25 포스텍학술정보관점", "2345678901");
        payment.confirmCategory2("편의점", "DICT");
        payment.suggestCategory2("카페·간식", "LLM");

        SpendingLedger.Facts facts = SpendingLedgerRowMapper.factsOf(
                payment, SpendingLedgerRowMapper.MerchantFacts.EMPTY, daypart, NO_AGENCY);

        assertEquals("편의점", facts.category2(), "확정 값은 그대로 있다");
        assertEquals("UNKNOWN", facts.category2Source());
        assertEquals("LLM", facts.category2LlmSource());
    }

    @Test
    @DisplayName("종결('기타')은 확정이다 — 추정으로 밀려나지 않는다")
    void 종결도_확정이다() {
        UserPayment payment = realPayment("p1", LocalDateTime.of(2026, 8, 9, 12, 41), 3200,
                "어디인지모를곳", "2345678901");
        payment.confirmCategory2("기타", "GIVE_UP");

        SpendingLedger.Facts facts = SpendingLedgerRowMapper.factsOf(
                payment, SpendingLedgerRowMapper.MerchantFacts.EMPTY, daypart, NO_AGENCY);

        assertEquals("GIVE_UP", facts.category2Source());
    }

    // ── 가맹점 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("사업자번호는 숫자만 남기고, PG 면 표시하고 이름으로 묶는다")
    void 가맹점_칸을_채운다() {
        UserPayment payment = realPayment("p1", LocalDateTime.of(2026, 8, 22, 23, 10), 17000,
                "넷플릭스", "220-81-62517");

        SpendingLedger.Facts plain = SpendingLedgerRowMapper.factsOf(
                payment, SpendingLedgerRowMapper.MerchantFacts.EMPTY, daypart, NO_AGENCY);
        assertEquals("2208162517", plain.businessNumber(), "하이픈을 지워 사전과 같은 형태로 맞춘다");
        assertFalse(plain.paymentAgency());
        assertEquals("BIZ:220-81-62517", plain.merchantKey(),
                "묶음 키는 묶는 함수가 만든다 — 여기서 따로 정규화하지 않는다");

        SpendingLedger.Facts viaAgency = SpendingLedgerRowMapper.factsOf(
                payment, SpendingLedgerRowMapper.MerchantFacts.EMPTY, daypart, biz -> true);
        assertTrue(viaAgency.paymentAgency());
        assertEquals("NAME:넷플릭스", viaAgency.merchantKey(), "PG 번호는 결제처를 말해 주지 않는다");
    }

    @Test
    @DisplayName("브랜드는 사전이 먼저, 없으면 대기표에서 — 주소·등록업종명은 사전에만 있다")
    void 가맹점_부가정보를_고른다() {
        var fromPending = SpendingLedgerRowMapper.MerchantFacts.of(null, "GS25");
        assertEquals("GS25", fromPending.brand());
        assertNull(fromPending.address());
        assertNull(fromPending.registryIndustryName());

        assertEquals(SpendingLedgerRowMapper.MerchantFacts.EMPTY,
                SpendingLedgerRowMapper.MerchantFacts.of(null, null));
    }

    // ── 고정지출 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("끝난 구독에도 '주기대로라면 다음'을 적는다 — 끝났는지는 읽는 쪽이 정한다")
    void 끝난_구독에도_다음_날짜가_있다() {
        // 요약(RecurringPayment)의 nextExpected 는 판정이 돈 날의 referenceTime 에 달렸다.
        // 그것을 그대로 옮기면 이 줄은 쓰인 날의 답을 영원히 들고 있게 된다.
        List<UserPayment> txns = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 4);
        for (int i = 0; i < 6; i++) {
            txns.add(realPayment("sub" + i, start.plusMonths(i).atTime(9, 0), 13500,
                    "넷플릭스", "1658700119"));
        }
        // 마지막 결제 2026-06-04, 기준일 2026-09-01 → 주기의 1.5배를 넘어 요약은 ENDED 다.
        FixedGroup group = RecurringPaymentDetector.fixedGroupsFrom(
                txns, LocalDateTime.of(2026, 9, 1, 0, 0), recurring, NO_AGENCY).get(0);
        assertNull(group.summary().nextExpected(), "요약은 끝난 구독에 다음 예상일을 안 준다");

        SpendingLedger.FixedFacts fixed = SpendingLedgerRowMapper.fixedOf(group, DETECTOR_VERSION);

        assertTrue(fixed.fixed());
        assertEquals(LocalDate.of(2026, 6, 4), fixed.groupLastPaidOn());
        assertNotNull(fixed.nextExpectedOn(), "표는 '마지막 + 주기'를 그대로 적는다");
        assertEquals(fixed.groupLastPaidOn().plusDays(fixed.periodDays()), fixed.nextExpectedOn());
        assertEquals(6, fixed.groupPaymentCount());
        assertEquals(6, fixed.groupOccurrenceDays());
        assertEquals("MONTHLY", fixed.periodKind());
        assertEquals(DETECTOR_VERSION, fixed.detectorVersion());
    }

    @Test
    @DisplayName("어느 묶음에도 안 든 결제는 '아니다'로 적는다 — 아직 모른다(NULL)와 다르다")
    void 고정지출이_아님을_적는다() {
        SpendingLedger.FixedFacts notFixed = SpendingLedger.FixedFacts.notFixed(DETECTOR_VERSION);
        assertFalse(notFixed.fixed());
        assertNull(notFixed.periodDays());
        assertNull(notFixed.nextExpectedOn());
        assertEquals(DETECTOR_VERSION, notFixed.detectorVersion());
    }

    // ── 낭비 ────────────────────────────────────────────────────────────────

    private static WasteScoringService.WasteJudgment judgment(boolean waste, double probability,
                                                              List<WasteScoringService.Factor> factors) {
        return new WasteScoringService.WasteJudgment("7:real-p1", "카페", 4500,
                LocalDateTime.of(2026, 8, 3, 8, 30), probability, waste, "설명", factors);
    }

    @Test
    @DisplayName("모델 판정을 그대로 옮기고 적용 임계를 함께 적는다")
    void 모델_판정을_옮긴다() {
        var factors = List.of(new WasteScoringService.Factor("평소보다 큰 금액", "평소 23,000원 → 78,000원", 0.42));

        SpendingLedger.WasteFacts facts = SpendingLedgerRowMapper.wasteOf(
                judgment(true, 0.71, factors), UserMerchantStance.Stance.NORMAL,
                OptionalDouble.of(0.479), false, 0.479, "abc");

        assertTrue(facts.waste());
        assertEquals(0.71, facts.probability());
        assertEquals("MODEL", facts.labelSource());
        assertEquals(0.479, facts.threshold());
        assertEquals("NORMAL", facts.stance());
        assertEquals(0.479, facts.modelThreshold());
        assertEquals("abc", facts.modelFingerprint());
        assertEquals(1, facts.factors().size());
        assertEquals("평소보다 큰 금액", facts.factors().get(0).label());
    }

    @Test
    @DisplayName("EXCLUDED 는 임계가 없는 것이다 — Double.MAX_VALUE 를 적지 않는다")
    void 제외된_가맹점은_임계가_없다() {
        SpendingLedger.WasteFacts facts = SpendingLedgerRowMapper.wasteOf(
                judgment(false, 0.93, List.of()), UserMerchantStance.Stance.EXCLUDED,
                OptionalDouble.empty(), false, 0.479, "abc");

        assertNull(facts.threshold(), "숫자로 적으면 읽는 쪽이 그것으로 산술을 한다");
        assertEquals("EXCLUDED", facts.stance());
        assertFalse(facts.waste(), "확률이 높아도 낭비로 보지 않는다");
        assertEquals(0.93, facts.probability(), "확률 자체는 남긴다 — 사실이다");
    }

    @Test
    @DisplayName("개인화로 뒤집힌 줄은 출처가 OVERRIDE — 문구를 뜯어보지 않고 표를 읽어 정한다")
    void 개인화는_출처가_다르다() {
        SpendingLedger.WasteFacts facts = SpendingLedgerRowMapper.wasteOf(
                judgment(false, 0.88, List.of()), null, OptionalDouble.of(0.479), true, 0.479, "abc");

        assertEquals("OVERRIDE", facts.labelSource());
        assertEquals("NORMAL", facts.stance(), "성향이 없으면 NORMAL 이다");
        assertTrue(facts.factors().isEmpty(), "개인화에는 모델 근거가 없다");
    }

    @Test
    @DisplayName("분류가 없어 판정을 안 한 줄은 UNJUDGED — '낭비가 아니다'와 다르다")
    void 판정하지_않은_줄() {
        SpendingLedger.WasteFacts facts = SpendingLedgerRowMapper.wasteOf(
                null, null, OptionalDouble.empty(), false, 0.479, "abc");

        assertEquals("UNJUDGED", facts.labelSource());
        assertNull(facts.waste(), "거짓이 아니라 없음이다");
        assertNull(facts.probability());
        assertNull(facts.stance());
        assertEquals("abc", facts.modelFingerprint(), "어느 모델 아래서 건너뛰었는지는 남긴다");
    }

    @Test
    @DisplayName("근거는 셋까지만 적는다 — 칸이 셋이다")
    void 근거는_셋까지다() {
        var factors = List.of(
                new WasteScoringService.Factor("가", "1", 0.5),
                new WasteScoringService.Factor("나", "2", 0.4),
                new WasteScoringService.Factor("다", "3", 0.3),
                new WasteScoringService.Factor("라", "4", 0.2));

        SpendingLedger.WasteFacts facts = SpendingLedgerRowMapper.wasteOf(
                judgment(true, 0.9, factors), null, OptionalDouble.of(0.479), false, 0.479, "abc");

        assertEquals(SpendingLedger.FACTOR_SLOTS, facts.factors().size());
        assertEquals("다", facts.factors().get(2).label());
    }
}
