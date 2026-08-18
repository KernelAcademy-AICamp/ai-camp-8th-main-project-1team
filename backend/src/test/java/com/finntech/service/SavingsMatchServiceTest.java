package com.finntech.service;

import com.finntech.service.FundFlowService.FixedCostLevel;
import com.finntech.service.FundFlowService.FundFlowProfile;
import com.finntech.service.FundFlowService.IncomeRegularity;
import com.finntech.service.FundFlowService.Liquidity;
import com.finntech.service.FundFlowService.LiquidityNeed;
import com.finntech.service.FundFlowService.Preferential;
import com.finntech.service.FundFlowService.Stability;
import com.finntech.service.FundFlowService.StabilityLevel;
import com.finntech.service.SavingsMatchInputs.AccrualType;
import com.finntech.service.SavingsMatchInputs.AmountLimit;
import com.finntech.service.SavingsMatchInputs.AmountUnit;
import com.finntech.service.SavingsMatchInputs.EarlyTermination;
import com.finntech.service.SavingsMatchInputs.IssuerScope;
import com.finntech.service.SavingsMatchInputs.PreferentialCondition;
import com.finntech.service.SavingsMatchInputs.ProductCandidate;
import com.finntech.service.SavingsMatchInputs.RequiredCondition;
import com.finntech.service.SavingsMatchService.GroupOrderBasis;
import com.finntech.service.SavingsMatchService.MatchResult;
import com.finntech.service.SavingsMatchService.SavingsMatch;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FP-01 매칭 규칙 M1~M9의 순수 판정을 검증한다(외부·DB 없음).
 * 정본은 `07_취향분석및추천_Agent_설계.md` §4.5.
 */
class SavingsMatchServiceTest {

    private static final int SHORT_CYCLE = 3;
    private final SavingsMatchService service = new SavingsMatchService(SHORT_CYCLE);

    // ── 테스트 픽스처 ────────────────────────────────────────────

    private static FundFlowProfile profile(StabilityLevel buffer, Integer cycleMonths,
                                           LiquidityNeed liquidity, Preferential l5) {
        Double aom = switch (buffer) {
            case THIN -> 0.5;
            case MODERATE -> 2.0;
            case THICK -> 4.0;
            case UNKNOWN -> null;
        };
        return new FundFlowProfile(1L, IncomeRegularity.REGULAR, FixedCostLevel.LOW,
                new Stability(aom, buffer), new Liquidity(cycleMonths, liquidity), l5);
    }

    /** 우대조건 재료 있음 — 카드실적·급여이체 충족 여부를 지정한다(금융사 구분 없음). */
    private static Preferential l5(boolean card, boolean salary) {
        return new Preferential(card, salary, true);
    }

    /** 금융사별 재료까지 있는 L5 — 당행 한정 조건(M6 ③④) 시험용. */
    private static Preferential l5Own(Set<String> cardCompanies, Set<String> salaryBanks) {
        return new Preferential(!cardCompanies.isEmpty(), !salaryBanks.isEmpty(), true,
                cardCompanies, salaryBanks);
    }

    /** 우대조건 재료 없음(①의 (B) 대기). */
    private static Preferential l5Unknown() {
        return new Preferential(false, false, false);
    }

    /** 금융사를 가리지 않는 조건. */
    private static RequiredCondition req(PreferentialCondition type) {
        return RequiredCondition.any(type);
    }

    /** 당행 한정 조건 — 그 금융사 것으로 좁혀 판정한다(M6 ④). */
    private static RequiredCondition reqOwn(PreferentialCondition type) {
        return new RequiredCondition(type, IssuerScope.OWN);
    }

    /** 월 납입 금액 조건(적금). 안 쓰는 쪽은 null. */
    private static AmountLimit monthly(Long min, Long max) {
        return new AmountLimit(min, max, AmountUnit.MONTHLY);
    }

    private static ProductCandidate product(String key, String company, AccrualType type,
                                            double base, double max) {
        return new ProductCandidate(key, company, key + " 상품", type, base, max, 12,
                null, List.of(), null);
    }

    /** 조건·금액·중도해지를 지정하는 전체 생성자 — 시험마다 필요한 칸만 채운다. */
    private static ProductCandidate product(String key, String company, AccrualType type,
                                            double base, double max, int termMonths,
                                            AmountLimit limit, List<RequiredCondition> conditions,
                                            EarlyTermination early) {
        return new ProductCandidate(key, company, key + " 상품", type, base, max, termMonths,
                limit, conditions, early);
    }

    private static SavingsMatchInputs inputs(List<ProductCandidate> candidates, Long keptMean) {
        return new SavingsMatchInputs(candidates, keptMean);
    }

    /** M6 단건 판정 — 그룹·정렬을 거치지 않고 조건 판정만 본다. */
    private SavingsMatch evaluate(ProductCandidate product, Preferential l5) {
        return service.evaluate(product,
                profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5), null);
    }

    private static List<String> keysOf(List<SavingsMatch> matches) {
        return matches.stream().map(m -> m.product().productKey()).toList();
    }

    /** 상단 그룹 = 추천 결과 그 자체(M1). Java 17이라 getFirst()를 쓸 수 없다. */
    private static AccrualType topGroup(MatchResult result) {
        return result.groups().get(0).type();
    }

    private static AccrualType bottomGroup(MatchResult result) {
        return result.groups().get(result.groups().size() - 1).type();
    }

    /** 그룹 나열 순서 — 이것 자체가 추천 결과다(M1). */
    private static List<AccrualType> typesOf(MatchResult result) {
        return result.groups().stream().map(SavingsMatchService.MatchGroup::type).toList();
    }

    private static <T> List<T> reversed(List<T> list) {
        List<T> copy = new ArrayList<>(list);
        Collections.reverse(copy);
        return copy;
    }

    // ── M1 그룹 분류 ─────────────────────────────────────────────

    @Test
    void M1_적립방식별로_그룹을_가르고_단일_순위표로_합치지_않는다() {
        MatchResult out = service.match(
                profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5(true, true)),
                inputs(List.of(
                        product("P1", "가은행", AccrualType.PARKING, 2.0, 2.0),
                        product("F1", "나은행", AccrualType.FLEXIBLE, 3.0, 3.0),
                        product("D1", "라은행", AccrualType.DEPOSIT, 3.8, 3.8),
                        product("X1", "다은행", AccrualType.FIXED, 4.0, 4.0)), null));

        assertThat(out.groups()).hasSize(4);
        assertThat(out.groups()).allSatisfy(g ->
                assertThat(g.matches()).allMatch(m -> m.product().accrualType() == g.type()));
        // 금리만 보면 정액(4.0)이 최상단이어야 하지만, 그룹을 가르므로 합쳐지지 않는다.
        assertThat(bottomGroup(out)).isEqualTo(AccrualType.FIXED);
    }

    /** 예금은 목돈을 만기까지 묶는다 — 유동성이 급하면 뒤로, 묶어도 되면 앞으로. */
    @Test
    void M1_예금은_유동성_상황에_따라_파킹통장_앞뒤로_움직인다() {
        var liquidityFirst = service.match(
                profile(StabilityLevel.THIN, null, LiquidityNeed.SMOOTH, l5(true, true)),
                inputs(List.of(), null));
        var yieldFirst = service.match(
                profile(StabilityLevel.THICK, 12, LiquidityNeed.PREDICTABLE_LUMPY, l5(true, true)),
                inputs(List.of(), null));

        // 유동성 우선 — 파킹이 예금보다 앞
        assertThat(typesOf(liquidityFirst)).containsExactly(AccrualType.PARKING, AccrualType.FLEXIBLE,
                AccrualType.DEPOSIT, AccrualType.FIXED);
        // 수익률 우선 — 묶어도 되니 예금이 파킹보다 앞
        assertThat(typesOf(yieldFirst)).containsExactly(AccrualType.FLEXIBLE, AccrualType.DEPOSIT,
                AccrualType.PARKING, AccrualType.FIXED);
    }

    /** 모를 때는 덜 묶는 쪽이 안전하다 — 중립에서는 예금이 파킹보다 뒤다. */
    @Test
    void M1_중립이면_예금은_파킹통장_뒤다() {
        var neutral = service.match(
                profile(StabilityLevel.UNKNOWN, null, LiquidityNeed.UNKNOWN, l5Unknown()),
                inputs(List.of(), null));

        assertThat(typesOf(neutral)).containsExactly(AccrualType.FLEXIBLE, AccrualType.PARKING,
                AccrualType.DEPOSIT, AccrualType.FIXED);
    }

    // ── M2 유동성 우선 ───────────────────────────────────────────

    @Test
    void M2_버퍼가_얇으면_파킹통장이_상단() {
        MatchResult out = service.match(
                profile(StabilityLevel.THIN, null, LiquidityNeed.SMOOTH, l5(true, true)),
                inputs(List.of(), null));
        assertThat(out.basis()).isEqualTo(GroupOrderBasis.LIQUIDITY_FIRST);
        assertThat(topGroup(out)).isEqualTo(AccrualType.PARKING);
    }

    @Test
    void M2_큰지출이_예측불가면_파킹통장이_상단() {
        MatchResult out = service.match(
                profile(StabilityLevel.THICK, 12, LiquidityNeed.UNPREDICTABLE_LUMPY, l5(true, true)),
                inputs(List.of(), null));
        assertThat(out.basis()).isEqualTo(GroupOrderBasis.LIQUIDITY_FIRST);
    }

    @Test
    void M2_큰지출_주기가_짧으면_파킹통장이_상단() {
        MatchResult shortCycle = service.match(
                profile(StabilityLevel.THICK, SHORT_CYCLE, LiquidityNeed.PREDICTABLE_LUMPY, l5(true, true)),
                inputs(List.of(), null));
        MatchResult longCycle = service.match(
                profile(StabilityLevel.THICK, SHORT_CYCLE + 9, LiquidityNeed.PREDICTABLE_LUMPY, l5(true, true)),
                inputs(List.of(), null));

        assertThat(shortCycle.basis()).isEqualTo(GroupOrderBasis.LIQUIDITY_FIRST);
        assertThat(longCycle.basis()).isEqualTo(GroupOrderBasis.YIELD_FIRST);
    }

    /** 유동성은 제약, 수익률은 최적화 — 둘 다 성립하면 제약이 이긴다. */
    @Test
    void M2가_M3보다_우선한다_버퍼두껍고_예측가능해도_주기가_짧으면_파킹() {
        MatchResult out = service.match(
                profile(StabilityLevel.THICK, 2, LiquidityNeed.PREDICTABLE_LUMPY, l5(true, true)),
                inputs(List.of(), null));
        assertThat(out.basis()).isEqualTo(GroupOrderBasis.LIQUIDITY_FIRST);
    }

    // ── M3 수익률 우선 ───────────────────────────────────────────

    @Test
    void M3_버퍼가_두껍고_큰지출이_예측가능하면_자유적립식이_상단() {
        MatchResult out = service.match(
                profile(StabilityLevel.THICK, 12, LiquidityNeed.PREDICTABLE_LUMPY, l5(true, true)),
                inputs(List.of(), null));
        assertThat(out.basis()).isEqualTo(GroupOrderBasis.YIELD_FIRST);
        assertThat(topGroup(out)).isEqualTo(AccrualType.FLEXIBLE);
    }

    @Test
    void M3_큰지출이_아예_없어도_예측가능으로_본다() {
        MatchResult out = service.match(
                profile(StabilityLevel.THICK, null, LiquidityNeed.SMOOTH, l5(true, true)),
                inputs(List.of(), null));
        assertThat(out.basis()).isEqualTo(GroupOrderBasis.YIELD_FIRST);
    }

    /** 재료가 없다는 사실만으로 파킹을 올리지 않는다 — 없는 신호를 지어내지 않는다(§14). */
    @Test
    void 재료가_UNKNOWN이면_어느_규칙도_켜지지_않고_중립이다() {
        MatchResult out = service.match(
                profile(StabilityLevel.UNKNOWN, null, LiquidityNeed.UNKNOWN, l5Unknown()),
                inputs(List.of(), null));
        assertThat(out.basis()).isEqualTo(GroupOrderBasis.NEUTRAL);
    }

    @Test
    void 프로필이_null이어도_그룹_뼈대는_유지된다() {
        MatchResult out = service.match(null, inputs(List.of(), null));
        assertThat(out.basis()).isEqualTo(GroupOrderBasis.NEUTRAL);
        assertThat(out.groups()).hasSize(4);
        assertThat(out.userId()).isNull();
    }

    // ── 수용기준: 프로필이 다르면 추천 상품군이 갈린다 (§4.3의 A·B) ──

    @Test
    void 같은_후보라도_프로필이_다르면_상단_그룹이_갈린다() {
        List<ProductCandidate> same = List.of(
                product("P1", "가은행", AccrualType.PARKING, 2.5, 2.5),
                product("F1", "나은행", AccrualType.FLEXIBLE, 3.5, 3.5));

        // A — 급여 규칙적·큰 지출 없음·버퍼 두꺼움 → 만기 긴 상품도 가능
        MatchResult a = service.match(
                profile(StabilityLevel.THICK, null, LiquidityNeed.SMOOTH, l5(true, true)),
                inputs(same, null));
        // B — 버퍼 얇고 분기마다 큰 지출 → 파킹통장
        MatchResult b = service.match(
                profile(StabilityLevel.THIN, 3, LiquidityNeed.UNPREDICTABLE_LUMPY, l5(true, true)),
                inputs(same, null));

        assertThat(topGroup(a)).isEqualTo(AccrualType.FLEXIBLE);
        assertThat(topGroup(b)).isEqualTo(AccrualType.PARKING);
    }

    // ── M4 정액적립식 ────────────────────────────────────────────

    @Test
    void M4_정액적립식은_후보가_없어도_그룹이_노출되고_사실_문구만_붙는다() {
        MatchResult out = service.match(
                profile(StabilityLevel.THICK, null, LiquidityNeed.SMOOTH, l5(true, true)),
                inputs(List.of(product("F1", "가은행", AccrualType.FLEXIBLE, 3.0, 3.0)), null));

        var fixed = out.groups().stream().filter(g -> g.type() == AccrualType.FIXED).findFirst().orElseThrow();
        assertThat(fixed.matches()).isEmpty();
        assertThat(fixed.note()).isEqualTo("매달 고정 금액 납입 필요");
        // 판단 표현이 새어나가지 않는다(M4 금지 목록).
        assertThat(fixed.note()).doesNotContain("맞지 않", "부적합", "추천");
    }

    @Test
    void M4_판정그룹에는_문구를_붙이지_않는다() {
        MatchResult out = service.match(
                profile(StabilityLevel.THIN, null, LiquidityNeed.SMOOTH, l5(true, true)),
                inputs(List.of(), null));
        assertThat(out.groups()).filteredOn(g -> g.type() != AccrualType.FIXED)
                .allSatisfy(g -> assertThat(g.note()).isNull());
    }

    // ── M5 가입 금액 ─────────────────────────────────────────────

    @Test
    void M5_하한에_못_미치는_상품은_목록에서_뺀다() {
        ProductCandidate small = product("S", "가은행", AccrualType.FLEXIBLE, 3.0, 3.0, 12,
                monthly(50_000L, null), List.of(), null);
        ProductCandidate big = product("B", "나은행", AccrualType.FLEXIBLE, 3.0, 3.0, 12,
                monthly(500_000L, null), List.of(), null);

        MatchResult out = service.match(
                profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5(true, true)),
                inputs(List.of(small, big), 100_000L));

        assertThat(out.sizeFilterApplied()).isTrue();
        assertThat(keysOf(out.flattened())).containsExactly("S");   // 월 50만 하한은 가입 자체가 안 된다
    }

    /** 상한 초과는 가입이 되므로 빼지 않는다 — 사실만 알리고 고르는 것은 사용자다(사용자 결정 2026-08-11). */
    @Test
    void M5_상한을_넘으면_빼지_않고_표시만_한다() {
        ProductCandidate capped = product("C", "가은행", AccrualType.FLEXIBLE, 3.0, 3.0, 12,
                monthly(10_000L, 500_000L), List.of(), null);

        MatchResult out = service.match(
                profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5(true, true)),
                inputs(List.of(capped), 800_000L));

        assertThat(keysOf(out.flattened())).containsExactly("C");
        assertThat(out.flattened().get(0).amountCapped()).isTrue();
    }

    /** 예금의 가입금액은 목돈 기준이라 월 저축액과 비교 대상이 아니다 — 걸러내면 근거 없이 사라진다. */
    @Test
    void M5_예금은_금액으로_거르지_않는다() {
        ProductCandidate deposit = product("D", "가은행", AccrualType.DEPOSIT, 3.0, 3.0, 12,
                new AmountLimit(1_000_000L, null, AmountUnit.TOTAL), List.of(), null);

        MatchResult out = service.match(
                profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5(true, true)),
                inputs(List.of(deposit), 100_000L));

        assertThat(keysOf(out.flattened())).containsExactly("D");
        assertThat(out.flattened().get(0).amountCapped()).isFalse();
    }

    /** ②의 지킨 돈 이력이 아직 없다. 없는 값으로 후보를 지우지 않는다. */
    @Test
    void M5_kept_mean이_없으면_규모필터를_건너뛰고_그_사실을_낸다() {
        ProductCandidate big = product("B", "나은행", AccrualType.FLEXIBLE, 3.0, 3.0, 12,
                monthly(500_000L, null), List.of(), null);

        MatchResult out = service.match(
                profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5(true, true)),
                inputs(List.of(big), null));

        assertThat(out.sizeFilterApplied()).isFalse();
        assertThat(keysOf(out.flattened())).containsExactly("B");
    }

    @Test
    void M5_상품의_금액조건이_미수집이면_통과시킨다() {
        assertThat(SavingsMatchService.joinable(product("A", "가은행", AccrualType.FLEXIBLE, 3.0, 3.0), 10L))
                .isTrue();
    }

    // ── M6 실수령 금리 ───────────────────────────────────────────

    @Test
    void M6_우대조건을_모두_충족하면_최고금리() {
        ProductCandidate p = product("A", "가은행", AccrualType.FLEXIBLE, 3.0, 4.0, 12,
                null, List.of(req(PreferentialCondition.CARD_PERFORMANCE)), null);

        SavingsMatch m = evaluate(p, l5(true, true));

        assertThat(m.effectiveRate()).isEqualTo(4.0);
        assertThat(m.unmetConditions()).isEmpty();
        assertThat(m.conditionsKnown()).isTrue();
    }

    @Test
    void M6_하나라도_미충족이면_기본금리이고_부분가산은_하지_않는다() {
        ProductCandidate p = product("A", "가은행", AccrualType.FLEXIBLE, 3.0, 4.0, 12, null,
                List.of(req(PreferentialCondition.CARD_PERFORMANCE),
                        req(PreferentialCondition.SALARY_TRANSFER)), null);

        SavingsMatch m = evaluate(p, l5(true, false));

        assertThat(m.effectiveRate()).isEqualTo(3.0);   // 3.5 같은 중간값이 나오지 않는다
        assertThat(m.unmetConditions()).containsExactly(PreferentialCondition.SALARY_TRANSFER);
    }

    /** 라벨링 전(null)은 "미충족 없음"이 아니라 "아직 못 읽음"이다. */
    @Test
    void M6_우대조건_미라벨링이면_기본금리이고_확인불가로_표시된다() {
        ProductCandidate unlabeled = product("A", "가은행", AccrualType.FLEXIBLE, 3.0, 4.0, 12,
                null, null, null);

        SavingsMatch m = evaluate(unlabeled, l5(true, true));

        assertThat(m.effectiveRate()).isEqualTo(3.0);
        assertThat(m.labeled()).isFalse();
        assertThat(m.conditionsKnown()).isFalse();
    }

    /**
     * 실측에 우대조건이 `없음`인 상품이 있다(`퍼스트가계적금`). 채울 조건이 없으니 곧바로 최고금리이고,
     * <b>없는 조건을 미충족으로 적으면 안 된다</b> — 개정 전 코드가 실제로 그랬다.
     */
    @Test
    void M6_요구조건이_빈_목록이면_곧바로_최고금리이고_미충족을_지어내지_않는다() {
        ProductCandidate none = product("A", "가은행", AccrualType.FLEXIBLE, 3.0, 4.0, 12,
                null, List.of(), null);

        SavingsMatch m = evaluate(none, l5(false, false));   // 아무것도 못 채운 사용자라도

        assertThat(m.effectiveRate()).isEqualTo(4.0);
        assertThat(m.unmetConditions()).isEmpty();
        assertThat(m.unknownConditions()).isEmpty();
        assertThat(m.noConditions()).isTrue();
    }

    /**
     * 실측 83%가 이 자리다 — 우리가 판정할 수 없는 조건만 요구하는 상품.
     * `미충족`이 아니라 `확인 못함`으로 나가야 한다.
     */
    @Test
    void M6_판정할_수_없는_조건만_있으면_미충족이_아니라_확인불가다() {
        ProductCandidate p = product("A", "가은행", AccrualType.FLEXIBLE, 3.0, 4.0, 12, null,
                List.of(req(PreferentialCondition.AUTO_TRANSFER),
                        req(PreferentialCondition.MARKETING_CONSENT)), null);

        SavingsMatch m = evaluate(p, l5(true, true));

        assertThat(m.effectiveRate()).isEqualTo(3.0);
        assertThat(m.unmetConditions()).isEmpty();                       // 미충족이라 말하지 않는다
        assertThat(m.unknownConditions()).containsExactly(
                PreferentialCondition.AUTO_TRANSFER, PreferentialCondition.MARKETING_CONSENT);
        assertThat(m.labeled()).isTrue();
        assertThat(m.conditionsKnown()).isFalse();
        assertThat(m.noConditions()).isFalse();                          // 조건이 없는 것과 다르다
    }

    /** 당행 한정인데 그 금융사와 거래가 없으면 미충족이 <b>확정</b>이다(M6 ③). */
    @Test
    void M6_당행_한정인데_그_금융사_거래가_없으면_미충족_확정() {
        ProductCandidate p = product("A", "우리은행", AccrualType.FLEXIBLE, 3.0, 4.0, 12,
                null, List.of(reqOwn(PreferentialCondition.CARD_PERFORMANCE)), null);

        SavingsMatch m = evaluate(p, l5Own(Set.of("신한카드"), Set.of()));

        assertThat(m.effectiveRate()).isEqualTo(3.0);
        assertThat(m.unmetConditions()).containsExactly(PreferentialCondition.CARD_PERFORMANCE);
        assertThat(m.conditionsKnown()).isTrue();          // 모르는 게 아니라 아니라는 것을 안다
    }

    /** 표기가 달라도 같은 그룹이면 충족이다 — `우리은행` 상품 ↔ `우리카드` 실적(M6 ④). */
    @Test
    void M6_당행_한정은_금융사_표기가_달라도_같은_그룹이면_충족() {
        ProductCandidate p = product("A", "우리은행", AccrualType.FLEXIBLE, 3.0, 4.0, 12,
                null, List.of(reqOwn(PreferentialCondition.CARD_PERFORMANCE)), null);

        SavingsMatch m = evaluate(p, l5Own(Set.of("우리카드"), Set.of()));

        assertThat(m.effectiveRate()).isEqualTo(4.0);
        assertThat(m.unmetConditions()).isEmpty();
    }

    /**
     * 당행 한정을 <b>전체 실적</b>으로 판정하면 다른 카드사에서 채운 실적으로 이 은행의 우대를
     * 받았다고 말하게 된다. 개정 전 L5가 정확히 그랬다.
     */
    @Test
    void M6_다른_카드사_실적으로_당행_조건을_채웠다고_말하지_않는다() {
        ProductCandidate p = product("A", "국민은행", AccrualType.FLEXIBLE, 3.0, 4.0, 12,
                null, List.of(reqOwn(PreferentialCondition.CARD_PERFORMANCE)), null);

        // 전체 기준으로는 실적을 채웠지만(cardPerformanceMet=true) 그 카드사가 국민이 아니다
        SavingsMatch m = evaluate(p, l5Own(Set.of("삼성카드"), Set.of()));

        assertThat(m.effectiveRate()).isEqualTo(3.0);
    }

    /** 금융사명을 알아볼 수 없으면 판정을 강행하지 않는다 — 엉뚱한 미충족을 확정하지 않기 위해서다. */
    @Test
    void M6_금융사명을_못_알아보면_확인불가로_둔다() {
        ProductCandidate p = product("A", "  ", AccrualType.FLEXIBLE, 3.0, 4.0, 12,
                null, List.of(reqOwn(PreferentialCondition.SALARY_TRANSFER)), null);

        SavingsMatch m = evaluate(p, l5Own(Set.of(), Set.of("우리은행")));

        assertThat(m.unmetConditions()).isEmpty();
        assertThat(m.unknownConditions()).containsExactly(PreferentialCondition.SALARY_TRANSFER);
    }

    @Test
    void M6_사용자_우대재료가_없으면_기본금리로_간다() {
        ProductCandidate p = product("A", "가은행", AccrualType.FLEXIBLE, 3.0, 4.0, 12,
                null, List.of(req(PreferentialCondition.CARD_PERFORMANCE)), null);

        SavingsMatch m = evaluate(p, l5Unknown());

        assertThat(m.effectiveRate()).isEqualTo(3.0);
        assertThat(m.conditionsKnown()).isFalse();
    }

    /** 최고금리가 0으로 누락 신고된 데이터가 있다 — 실수령이 기본금리 아래로 내려가지 않는다. */
    @Test
    void M6_최고금리가_기본금리보다_낮게_신고돼도_실수령은_기본금리_아래로_안_간다() {
        ProductCandidate broken = product("A", "가은행", AccrualType.FLEXIBLE, 3.0, 0.0, 12,
                null, List.of(), null);

        SavingsMatch m = evaluate(broken, l5(true, true));

        assertThat(m.effectiveRate()).isEqualTo(3.0);
    }

    // ── M7·M9 정렬 ───────────────────────────────────────────────

    @Test
    void M7_그룹_내부에서만_실수령_금리_내림차순() {
        MatchResult out = service.match(
                profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5(true, true)),
                inputs(List.of(
                        product("F_LOW", "가은행", AccrualType.FLEXIBLE, 2.0, 2.0),
                        product("F_HIGH", "나은행", AccrualType.FLEXIBLE, 3.5, 3.5),
                        product("P_MID", "다은행", AccrualType.PARKING, 3.0, 3.0)), null));

        var flexible = out.groups().stream().filter(g -> g.type() == AccrualType.FLEXIBLE)
                .findFirst().orElseThrow();
        // 파킹 3.0이 자유 2.0보다 높지만 그룹을 넘어 끼어들지 않는다.
        assertThat(keysOf(flexible.matches())).containsExactly("F_HIGH", "F_LOW");
    }

    @Test
    void M9_동점이면_미충족_조건수가_적은_것이_먼저() {
        ProductCandidate met = product("MET", "가은행", AccrualType.FLEXIBLE, 3.0, 3.0, 12,
                null, List.of(), null);
        ProductCandidate unmet = product("UNMET", "가은행", AccrualType.FLEXIBLE, 3.0, 5.0, 12,
                null, List.of(req(PreferentialCondition.SALARY_TRANSFER)), null);

        MatchResult out = service.match(
                profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5(true, false)),
                inputs(List.of(unmet, met), null));

        assertThat(keysOf(out.flattened())).containsExactly("MET", "UNMET");
    }

    @Test
    void M9_그다음은_만기_짧은것_기본금리_높은것_금융사명_가나다순() {
        // 실수령·미충족수 동일 → 만기로 갈린다
        ProductCandidate long24 = product("L", "가은행", AccrualType.FLEXIBLE, 3.0, 3.0, 24,
                null, List.of(), null);
        ProductCandidate short6 = product("S", "가은행", AccrualType.FLEXIBLE, 3.0, 3.0, 6,
                null, List.of(), null);
        // 만기까지 같으면 금융사명 가나다순 (라 < 마 < 바)
        ProductCandidate bank바 = product("B3", "바은행", AccrualType.FLEXIBLE, 3.0, 3.0, 6,
                null, List.of(), null);
        ProductCandidate bank라 = product("B1", "라은행", AccrualType.FLEXIBLE, 3.0, 3.0, 6,
                null, List.of(), null);

        MatchResult out = service.match(
                profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5(true, true)),
                inputs(List.of(long24, bank바, short6, bank라), null));

        assertThat(keysOf(out.flattened())).containsExactly("S", "B1", "B3", "L");
    }

    /** 확인 불가는 미충족 0건과 같은 자리에 놓이면 안 된다 — 셀 수 없는 것이다. */
    @Test
    void M9_우대조건_확인불가_상품은_뒤로_간다() {
        ProductCandidate known = product("KNOWN", "가은행", AccrualType.FLEXIBLE, 3.0, 3.0, 12,
                null, List.of(), null);
        ProductCandidate unknown = product("UNKNOWN", "가은행", AccrualType.FLEXIBLE, 3.0, 9.0, 12,
                null, null, null);

        MatchResult out = service.match(
                profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5(true, true)),
                inputs(List.of(unknown, known), null));

        assertThat(keysOf(out.flattened())).containsExactly("KNOWN", "UNKNOWN");
    }

    // ── M10 중도해지이율 ─────────────────────────────────────────

    /**
     * 케이뱅크 `코드K 자유적금`의 실제 공시 표다(2026-08-11 수집). 고정이율 구간과
     * `기본금리 × N% × 경과일수/계약일수 (최저 연 0.5%)` 구간이 한 표에 섞여 있다.
     */
    private static EarlyTermination 코드K표() {
        return new EarlyTermination(List.of(
                EarlyTermination.Tier.fixed(0, 0.1),                        // 1개월 미만
                EarlyTermination.Tier.fixed(1, 0.3),
                EarlyTermination.Tier.fixed(3, 0.5),
                new EarlyTermination.Tier(6, false, null, 0.7, true, 0.5),
                new EarlyTermination.Tier(9, false, null, 0.8, true, 0.5),
                new EarlyTermination.Tier(11, false, null, 0.9, true, 0.5)),
                LocalDate.of(2026, 8, 11));
    }

    /**
     * 토스뱅크 `먼저 이자 받는 정기예금`의 실제 표(2026-08-12 수집). 케이뱅크와 달리 구간이
     * <b>`초과`(하한 미포함)</b> 로 끊긴다 — `3개월 초과 6개월 이하`.
     */
    private static EarlyTermination 토스표() {
        return new EarlyTermination(List.of(
                EarlyTermination.Tier.fixed(0, 0.1),                        // 1개월 이하
                new EarlyTermination.Tier(1, true, 0.3, null, false, null),
                new EarlyTermination.Tier(3, true, null, 0.5, true, 0.3),
                new EarlyTermination.Tier(6, true, null, 0.7, true, 0.4),
                new EarlyTermination.Tier(9, true, null, 0.8, true, 0.5),
                new EarlyTermination.Tier(11, true, null, 0.9, true, 0.5)),
                LocalDate.of(2026, 8, 12));
    }

    /**
     * <b>경계에서 결과가 갈린다.</b> 12개월 상품을 6개월에 깨면 토스뱅크 표에서는
     * `3개월 초과 6개월 이하` 구간(50%)이지 다음 구간(70%)이 아니다. `초과`를 `이상`으로 읽으면
     * 실제보다 많이 준다고 말하게 된다.
     */
    @Test
    void M10_하한이_미포함이면_그_경계에서는_앞_구간이_적용된다() {
        assertThat(토스표().rateAt(6, 12, 3.6)).isEqualTo(3.6 * 0.5 * 0.5);   // 70%가 아니다
        assertThat(토스표().rateAt(7, 12, 3.6)).isEqualTo(3.6 * 0.7 * (7.0 / 12));
        assertThat(토스표().rateAt(1, 12, 3.6)).isEqualTo(0.1);               // `1개월 이하`
    }

    @Test
    void M10_고정이율_구간은_그_값을_그대로_준다() {
        assertThat(코드K표().rateAt(0, 12, 3.7)).isEqualTo(0.1);
        assertThat(코드K표().rateAt(2, 12, 3.7)).isEqualTo(0.3);
        assertThat(코드K표().rateAt(4, 12, 3.7)).isEqualTo(0.5);
    }

    /** 배수 구간에는 경과일수 비례가 붙는다 — 절반 시점에 깨면 절반만 받는다. */
    @Test
    void M10_배수_구간은_경과일수에_비례한다() {
        // 6개월 구간: 3.7 × 0.7 × (6/12) = 1.295
        assertThat(코드K표().rateAt(6, 12, 3.7)).isEqualTo(3.7 * 0.7 * 0.5);
    }

    /** 비례로 깎여도 최저이율 아래로는 안 내려간다. */
    @Test
    void M10_최저이율_아래로는_내려가지_않는다() {
        // 3.7 × 0.7 × (6/36) = 0.43 → 최저 0.5로 올라간다
        assertThat(코드K표().rateAt(6, 36, 3.7)).isEqualTo(0.5);
    }

    /** 하한이 가장 큰 구간이 이긴다 — 순서가 뒤집히면 늘 첫 구간만 걸린다. */
    @Test
    void M10_하한이_가장_큰_구간을_고른다() {
        assertThat(코드K표().rateAt(11, 12, 4.0)).isEqualTo(4.0 * 0.9 * (11.0 / 12));
    }

    /** 못 구한 상품은 null이다 — 0%로 바꿔 읽으면 "깨도 손해 없다"는 거짓말이 된다. */
    @Test
    void M10_구간이_비면_null이지_0이_아니다() {
        assertThat(new EarlyTermination(List.of(), LocalDate.of(2026, 8, 11)).rateAt(6, 12, 4.0))
                .isNull();
        assertThat(new EarlyTermination(null, LocalDate.of(2026, 8, 11)).rateAt(6, 12, 4.0))
                .isNull();
    }

    /** 유동성이 급한 사용자에게는 "깼을 때 얼마 남나"가 만기 길이보다 실질적이다. */
    @Test
    void M10_유동성_우선이면_중도해지이율이_높은_것이_먼저() {
        EarlyTermination good = new EarlyTermination(
                List.of(EarlyTermination.Tier.fixed(0, 2.0)), LocalDate.of(2026, 8, 11));
        EarlyTermination bad = new EarlyTermination(
                List.of(EarlyTermination.Tier.fixed(0, 0.1)), LocalDate.of(2026, 8, 11));
        ProductCandidate 좋음 = product("GOOD", "가은행", AccrualType.FLEXIBLE, 3.0, 3.0, 12,
                null, List.of(), good);
        ProductCandidate 나쁨 = product("BAD", "가은행", AccrualType.FLEXIBLE, 3.0, 3.0, 12,
                null, List.of(), bad);

        MatchResult out = service.match(
                profile(StabilityLevel.THIN, null, LiquidityNeed.SMOOTH, l5(true, true)),
                inputs(List.of(나쁨, 좋음), null));

        assertThat(out.basis()).isEqualTo(GroupOrderBasis.LIQUIDITY_FIRST);
        assertThat(keysOf(out.flattened())).containsExactly("GOOD", "BAD");
    }

    /**
     * 만기까지 갈 사람에게 중도해지는 일어나지 않는 일이다 — 그 사람의 순위를 이 값으로 흔들면
     * 안 된다. 여기서는 만기(M9 ②)가 이겨야 한다.
     */
    @Test
    void M10_유동성_우선이_아니면_중도해지이율로_순서를_바꾸지_않는다() {
        EarlyTermination good = new EarlyTermination(
                List.of(EarlyTermination.Tier.fixed(0, 2.0)), LocalDate.of(2026, 8, 11));
        ProductCandidate 장기좋음 = product("LONG", "가은행", AccrualType.FLEXIBLE, 3.0, 3.0, 24,
                null, List.of(), good);
        ProductCandidate 단기없음 = product("SHORT", "가은행", AccrualType.FLEXIBLE, 3.0, 3.0, 6,
                null, List.of(), null);

        MatchResult out = service.match(
                profile(StabilityLevel.THICK, null, LiquidityNeed.SMOOTH, l5(true, true)),
                inputs(List.of(장기좋음, 단기없음), null));

        assertThat(out.basis()).isEqualTo(GroupOrderBasis.YIELD_FIRST);
        assertThat(keysOf(out.flattened())).containsExactly("SHORT", "LONG");
    }

    /** 같은 입력이 같은 순서를 낸다(설계원칙 3 재현성). */
    @Test
    void 모든_동점_기준이_같아도_순서가_흔들리지_않는다() {
        List<ProductCandidate> tied = List.of(
                product("K2", "가은행", AccrualType.FLEXIBLE, 3.0, 3.0, 12, null, List.of(), null),
                product("K1", "가은행", AccrualType.FLEXIBLE, 3.0, 3.0, 12, null, List.of(), null));
        var profile = profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5(true, true));

        assertThat(keysOf(service.match(profile, inputs(tied, null)).flattened()))
                .containsExactly("K1", "K2")
                .isEqualTo(keysOf(service.match(profile, inputs(reversed(tied), null)).flattened()));
    }

    // ── M8 표시 재료 ─────────────────────────────────────────────

    @Test
    void M8_실수령과_최고금리_미충족조건을_함께_낸다() {
        ProductCandidate p = product("A", "가은행", AccrualType.FLEXIBLE, 3.2, 4.0, 12, null,
                List.of(req(PreferentialCondition.CARD_PERFORMANCE),
                        req(PreferentialCondition.SALARY_TRANSFER)), null);

        SavingsMatch m = evaluate(p, l5(false, true));

        assertThat(m.effectiveRate()).isEqualTo(3.2);
        assertThat(m.maxRate()).isEqualTo(4.0);
        assertThat(m.unmetConditions()).containsExactly(PreferentialCondition.CARD_PERFORMANCE);
    }

    /**
     * 미충족과 확인 불가가 섞이면 <b>둘을 따로</b> 낸다. 화면이 `미충족 3개`로 뭉치면
     * 채울 수 있는 조건과 알 수 없는 조건을 사용자가 구분하지 못한다.
     */
    @Test
    void M8_미충족과_확인불가를_섞지_않는다() {
        ProductCandidate p = product("A", "가은행", AccrualType.FLEXIBLE, 3.0, 4.0, 12, null,
                List.of(req(PreferentialCondition.CARD_PERFORMANCE),
                        req(PreferentialCondition.AUTO_TRANSFER)), null);

        SavingsMatch m = evaluate(p, l5(false, true));

        assertThat(m.unmetConditions()).containsExactly(PreferentialCondition.CARD_PERFORMANCE);
        assertThat(m.unknownConditions()).containsExactly(PreferentialCondition.AUTO_TRANSFER);
    }
}
