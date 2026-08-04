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
import com.finntech.service.SavingsMatchInputs.PreferentialCondition;
import com.finntech.service.SavingsMatchInputs.ProductCandidate;
import com.finntech.service.SavingsMatchService.GroupOrderBasis;
import com.finntech.service.SavingsMatchService.MatchResult;
import com.finntech.service.SavingsMatchService.SavingsMatch;
import org.junit.jupiter.api.Test;

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

    /** 우대조건 재료 있음 — 카드실적·급여이체 충족 여부를 지정한다. */
    private static Preferential l5(boolean card, boolean salary) {
        return new Preferential(card, salary, true);
    }

    /** 우대조건 재료 없음(①의 (B) 대기). */
    private static Preferential l5Unknown() {
        return new Preferential(false, false, false);
    }

    private static ProductCandidate product(String key, String company, AccrualType type,
                                            double base, double max) {
        return new ProductCandidate(key, company, key + " 상품", type, base, max, 12, null, Set.of());
    }

    private static SavingsMatchInputs inputs(List<ProductCandidate> candidates, Long keptMean) {
        return new SavingsMatchInputs(candidates, keptMean);
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

    // ── M5 규모 필터 ─────────────────────────────────────────────

    @Test
    void M5_kept_mean으로_최소납입금액_조건에_맞는_상품만_남는다() {
        ProductCandidate small = new ProductCandidate("S", "가은행", "소액", AccrualType.FLEXIBLE,
                3.0, 3.0, 12, 50_000L, Set.of());
        ProductCandidate big = new ProductCandidate("B", "나은행", "고액", AccrualType.FLEXIBLE,
                3.0, 3.0, 12, 500_000L, Set.of());

        MatchResult out = service.match(
                profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5(true, true)),
                inputs(List.of(small, big), 100_000L));

        assertThat(out.sizeFilterApplied()).isTrue();
        assertThat(keysOf(out.flattened())).containsExactly("S");
    }

    /** ②의 지킨 돈 이력이 아직 없다. 없는 값으로 후보를 지우지 않는다. */
    @Test
    void M5_kept_mean이_없으면_규모필터를_건너뛰고_그_사실을_낸다() {
        ProductCandidate big = new ProductCandidate("B", "나은행", "고액", AccrualType.FLEXIBLE,
                3.0, 3.0, 12, 500_000L, Set.of());

        MatchResult out = service.match(
                profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5(true, true)),
                inputs(List.of(big), null));

        assertThat(out.sizeFilterApplied()).isFalse();
        assertThat(keysOf(out.flattened())).containsExactly("B");
    }

    @Test
    void M5_상품의_최소금액이_미수집이면_통과시킨다() {
        assertThat(SavingsMatchService.fitsSize(product("A", "가은행", AccrualType.FLEXIBLE, 3.0, 3.0), 10L))
                .isTrue();
    }

    // ── M6 실수령 금리 ───────────────────────────────────────────

    @Test
    void M6_우대조건을_모두_충족하면_최고금리() {
        ProductCandidate p = new ProductCandidate("A", "가은행", "적금", AccrualType.FLEXIBLE,
                3.0, 4.0, 12, null, Set.of(PreferentialCondition.CARD_PERFORMANCE));

        SavingsMatch m = service.evaluate(p,
                profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5(true, true)));

        assertThat(m.effectiveRate()).isEqualTo(4.0);
        assertThat(m.unmetConditions()).isEmpty();
        assertThat(m.conditionsKnown()).isTrue();
    }

    @Test
    void M6_하나라도_미충족이면_기본금리이고_부분가산은_하지_않는다() {
        ProductCandidate p = new ProductCandidate("A", "가은행", "적금", AccrualType.FLEXIBLE,
                3.0, 4.0, 12, null,
                Set.of(PreferentialCondition.CARD_PERFORMANCE, PreferentialCondition.SALARY_TRANSFER));

        SavingsMatch m = service.evaluate(p,
                profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5(true, false)));

        assertThat(m.effectiveRate()).isEqualTo(3.0);   // 3.5 같은 중간값이 나오지 않는다
        assertThat(m.unmetConditions()).containsExactly(PreferentialCondition.SALARY_TRANSFER);
    }

    /** spclCnd 미파싱(D2)은 "미충족 없음"이 아니라 "확인 불가"다. */
    @Test
    void M6_우대조건_미파싱이면_기본금리이고_확인불가로_표시된다() {
        ProductCandidate unparsed = new ProductCandidate("A", "가은행", "적금", AccrualType.FLEXIBLE,
                3.0, 4.0, 12, null, null);

        SavingsMatch m = service.evaluate(unparsed,
                profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5(true, true)));

        assertThat(m.effectiveRate()).isEqualTo(3.0);
        assertThat(m.conditionsKnown()).isFalse();
    }

    @Test
    void M6_사용자_우대재료가_없으면_기본금리로_간다() {
        ProductCandidate p = new ProductCandidate("A", "가은행", "적금", AccrualType.FLEXIBLE,
                3.0, 4.0, 12, null, Set.of(PreferentialCondition.CARD_PERFORMANCE));

        SavingsMatch m = service.evaluate(p,
                profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5Unknown()));

        assertThat(m.effectiveRate()).isEqualTo(3.0);
        assertThat(m.conditionsKnown()).isFalse();
    }

    /** 최고금리가 0으로 누락 신고된 데이터가 있다 — 실수령이 기본금리 아래로 내려가지 않는다. */
    @Test
    void M6_최고금리가_기본금리보다_낮게_신고돼도_실수령은_기본금리_아래로_안_간다() {
        ProductCandidate broken = new ProductCandidate("A", "가은행", "적금", AccrualType.FLEXIBLE,
                3.0, 0.0, 12, null, Set.of());

        SavingsMatch m = service.evaluate(broken,
                profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5(true, true)));

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
        ProductCandidate met = new ProductCandidate("MET", "가은행", "충족", AccrualType.FLEXIBLE,
                3.0, 3.0, 12, null, Set.of());
        ProductCandidate unmet = new ProductCandidate("UNMET", "가은행", "미충족", AccrualType.FLEXIBLE,
                3.0, 5.0, 12, null, Set.of(PreferentialCondition.SALARY_TRANSFER));

        MatchResult out = service.match(
                profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5(true, false)),
                inputs(List.of(unmet, met), null));

        assertThat(keysOf(out.flattened())).containsExactly("MET", "UNMET");
    }

    @Test
    void M9_그다음은_만기_짧은것_기본금리_높은것_금융사명_가나다순() {
        // 실수령·미충족수 동일 → 만기로 갈린다
        ProductCandidate long24 = new ProductCandidate("L", "가은행", "장기", AccrualType.FLEXIBLE,
                3.0, 3.0, 24, null, Set.of());
        ProductCandidate short6 = new ProductCandidate("S", "가은행", "단기", AccrualType.FLEXIBLE,
                3.0, 3.0, 6, null, Set.of());
        // 만기까지 같으면 금융사명 가나다순 (라 < 마 < 바)
        ProductCandidate bank바 = new ProductCandidate("B3", "바은행", "동일", AccrualType.FLEXIBLE,
                3.0, 3.0, 6, null, Set.of());
        ProductCandidate bank라 = new ProductCandidate("B1", "라은행", "동일", AccrualType.FLEXIBLE,
                3.0, 3.0, 6, null, Set.of());

        MatchResult out = service.match(
                profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5(true, true)),
                inputs(List.of(long24, bank바, short6, bank라), null));

        assertThat(keysOf(out.flattened())).containsExactly("S", "B1", "B3", "L");
    }

    /** 확인 불가는 미충족 0건과 같은 자리에 놓이면 안 된다 — 셀 수 없는 것이다. */
    @Test
    void M9_우대조건_확인불가_상품은_뒤로_간다() {
        ProductCandidate known = new ProductCandidate("KNOWN", "가은행", "확인됨", AccrualType.FLEXIBLE,
                3.0, 3.0, 12, null, Set.of());
        ProductCandidate unknown = new ProductCandidate("UNKNOWN", "가은행", "미파싱", AccrualType.FLEXIBLE,
                3.0, 9.0, 12, null, null);

        MatchResult out = service.match(
                profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5(true, true)),
                inputs(List.of(unknown, known), null));

        assertThat(keysOf(out.flattened())).containsExactly("KNOWN", "UNKNOWN");
    }

    /** 같은 입력이 같은 순서를 낸다(설계원칙 3 재현성). */
    @Test
    void 모든_동점_기준이_같아도_순서가_흔들리지_않는다() {
        List<ProductCandidate> tied = List.of(
                new ProductCandidate("K2", "가은행", "동일", AccrualType.FLEXIBLE, 3.0, 3.0, 12, null, Set.of()),
                new ProductCandidate("K1", "가은행", "동일", AccrualType.FLEXIBLE, 3.0, 3.0, 12, null, Set.of()));
        var profile = profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5(true, true));

        assertThat(keysOf(service.match(profile, inputs(tied, null)).flattened()))
                .containsExactly("K1", "K2")
                .isEqualTo(keysOf(service.match(profile, inputs(reversed(tied), null)).flattened()));
    }

    // ── M8 표시 재료 ─────────────────────────────────────────────

    @Test
    void M8_실수령과_최고금리_미충족조건을_함께_낸다() {
        ProductCandidate p = new ProductCandidate("A", "가은행", "적금", AccrualType.FLEXIBLE,
                3.2, 4.0, 12, null,
                Set.of(PreferentialCondition.CARD_PERFORMANCE, PreferentialCondition.SALARY_TRANSFER));

        SavingsMatch m = service.evaluate(p,
                profile(StabilityLevel.MODERATE, null, LiquidityNeed.SMOOTH, l5(false, true)));

        assertThat(m.effectiveRate()).isEqualTo(3.2);
        assertThat(m.maxRate()).isEqualTo(4.0);
        assertThat(m.unmetConditions()).containsExactly(PreferentialCondition.CARD_PERFORMANCE);
    }
}
