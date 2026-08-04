package com.finntech.service;

import com.finntech.service.FundFlowService.FundFlowProfile;
import com.finntech.service.FundFlowService.LiquidityNeed;
import com.finntech.service.FundFlowService.StabilityLevel;
import com.finntech.service.SavingsMatchInputs.AccrualType;
import com.finntech.service.SavingsMatchInputs.PreferentialCondition;
import com.finntech.service.SavingsMatchInputs.ProductCandidate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 저축 상품 매칭 (FP-01) — 규칙 M1~M9. 정본은 `07_취향분석및추천_Agent_설계.md` §4.5.
 *
 * <p><b>판정·정렬은 전부 여기(코드)서 하고 LLM은 문장만 만든다</b>(마스터 §4 원칙 1 · §4.5 「LLM 역할 범위」).
 * LLM은 상품 판정·그룹 분류·금리 계산·정렬 순서에 관여하지 않는다.
 *
 * <p><b>입력은 두 갈래다.</b> 사용자 쪽은 {@link FundFlowProfile}(자금 흐름 5축), 상품 쪽은
 * {@link SavingsMatchInputs}(후보 + 규모 재료). <b>취향 축은 입력에 없다</b> — 저축 매칭에 취향이 들어가면
 * `영화 좋아하시니 영화 적금` 같은 소비 목표 유도가 되어 R9 위반이다. 규칙으로 막는 대신 입력에서 빼
 * 구조적으로 불가능하게 만든다(§4.3).
 *
 * <p><b>임계치는 설정값</b>(설계원칙 4). 순수 함수라 {@code now()}·난수를 읽지 않고, 정렬은 마지막에
 * {@code productKey}까지 내려가 <b>완전한 전순서</b>를 만든다(설계원칙 3 재현성).
 */
@Service
public class SavingsMatchService {

    /**
     * M4가 정액적립식에 붙일 수 있는 <b>유일한</b> 문구. 여기서 한 글자도 더 나가지 않는다 —
     * `MOA 흐름과 맞지 않음`·`부적합`·`추천하지 않음`은 §4.5 M4가 명시적으로 금지한 판단 표현이고,
     * 적합 여부는 사용자가 정한다.
     */
    static final String FIXED_ACCRUAL_NOTE = "매달 고정 금액 납입 필요";

    /** M2의 `large_expense_cycle이 짧음` 기준(개월). 이 값 이하이면 짧다고 본다. §8 미정 — 잠정 3. */
    private final int shortLargeExpenseCycleMonths;

    public SavingsMatchService(
            @Value("${finntech.savings-match.short-large-expense-cycle-months:3}")
            int shortLargeExpenseCycleMonths) {
        this.shortLargeExpenseCycleMonths = shortLargeExpenseCycleMonths;
    }

    /** 프로필 + 후보 상품 → 그룹별 추천. 순수(입력만 본다). */
    public MatchResult match(FundFlowProfile profile, SavingsMatchInputs inputs) {
        List<ProductCandidate> candidates =
                inputs == null || inputs.candidates() == null ? List.of() : inputs.candidates();
        Long keptMean = inputs == null ? null : inputs.keptMeanAmount();

        // M5 — 규모 필터. kept_mean이 없으면 거르지 않는다(없는 값으로 후보를 지우지 않는다).
        boolean sizeFilterApplied = keptMean != null;
        List<ProductCandidate> sized = candidates.stream()
                .filter(p -> fitsSize(p, keptMean))
                .toList();

        // M6·M8 — 실수령 금리와 미충족 조건. 상품마다 한 번씩.
        List<SavingsMatch> matched = sized.stream()
                .map(p -> evaluate(p, profile))
                .toList();

        // M1 — 적립 방식으로 가른다. M2·M3가 그룹 순서를 정한다.
        GroupOrderBasis basis = decideBasis(profile);
        List<MatchGroup> groups = new ArrayList<>();
        for (AccrualType type : groupOrder(basis)) {
            groups.add(new MatchGroup(type, sortWithinGroup(matched, type), noteFor(type)));
        }

        return new MatchResult(profile == null ? null : profile.userId(), List.copyOf(groups),
                basis, sizeFilterApplied);
    }

    // ── M2·M3 그룹 순서 ───────────────────────────────────────────────────────

    /**
     * M2/M3 판정. <b>M2가 M3보다 우선한다</b> — 둘은 동시에 성립할 수 있는데(버퍼 THICK + 예측 가능 +
     * 주기 짧음), 유동성은 <b>제약</b>이고 수익률은 <b>최적화</b>라 제약이 이긴다. 돈이 묶이면 안 되는
     * 사람에게 수익률을 앞세우면 그 사람이 실제로 겪는 문제를 못 푼다.
     *
     * <p><b>재료가 없으면 어느 쪽도 켜지 않는다.</b> UNKNOWN을 `예측 불가`로 읽으면 재료가 없다는 사실만으로
     * 파킹이 상단에 오는데, 이는 없는 신호를 지어내는 것이다(거울 원칙 · §14).
     */
    GroupOrderBasis decideBasis(FundFlowProfile profile) {
        if (profile == null) return GroupOrderBasis.NEUTRAL;
        if (needsLiquidity(profile)) return GroupOrderBasis.LIQUIDITY_FIRST;
        if (canChaseYield(profile)) return GroupOrderBasis.YIELD_FIRST;
        return GroupOrderBasis.NEUTRAL;
    }

    /** M2 — 버퍼 얇음 · 또는 예측 불가한 큰 지출 · 또는 큰 지출 주기가 짧음. */
    private boolean needsLiquidity(FundFlowProfile p) {
        boolean thinBuffer = p.l3Stability() != null && p.l3Stability().level() == StabilityLevel.THIN;
        boolean unpredictable = p.l4Liquidity() != null
                && p.l4Liquidity().level() == LiquidityNeed.UNPREDICTABLE_LUMPY;
        Integer cycle = p.l4Liquidity() == null ? null : p.l4Liquidity().cycleMonths();
        boolean shortCycle = cycle != null && cycle <= shortLargeExpenseCycleMonths;
        return thinBuffer || unpredictable || shortCycle;
    }

    /**
     * M3 — 버퍼 두껍고 큰 지출이 예측 가능.
     *
     * <p>`큰 지출이 아예 없음`(SMOOTH)도 예측 가능에 넣는다. 예측을 어렵게 만드는 지출 자체가 없으니
     * 만기가 긴 상품을 감당 못 할 이유가 없고, 이걸 빼면 <b>버퍼 두껍고 큰 지출도 없는 사람</b>이
     * 중립으로 떨어져 §4.3의 A 사례(만기 긴 상품도 가능)와 어긋난다.
     */
    private boolean canChaseYield(FundFlowProfile p) {
        if (p.l3Stability() == null || p.l3Stability().level() != StabilityLevel.THICK) return false;
        if (p.l4Liquidity() == null) return false;
        LiquidityNeed need = p.l4Liquidity().level();
        return need == LiquidityNeed.PREDICTABLE_LUMPY || need == LiquidityNeed.SMOOTH;
    }

    /**
     * 그룹 나열 순서. 정액적립식(M4)은 <b>항상 마지막에, 항상 포함</b>한다.
     *
     * <p>마지막에 두는 것은 판정이 아니라 축의 문제다 — 추천의 중심축은 `파킹통장 ↔ 자유적립식`이고
     * (§15) 정액은 그 축 위에 있지 않다. 자리로 우열을 말하지 않기 위해 그룹에는 판단 문구를 붙이지 않고
     * 사실({@link #FIXED_ACCRUAL_NOTE})만 단다.
     */
    static List<AccrualType> groupOrder(GroupOrderBasis basis) {
        return switch (basis) {
            case LIQUIDITY_FIRST -> List.of(AccrualType.PARKING, AccrualType.FLEXIBLE, AccrualType.FIXED);
            // 중립·수익률 우선 모두 자유적립식이 앞이다. 중립은 "근거가 없어 기본 순서"라는 뜻이며
            // basis 값으로 화면·LLM이 그 사실을 그대로 말할 수 있다.
            case YIELD_FIRST, NEUTRAL -> List.of(AccrualType.FLEXIBLE, AccrualType.PARKING, AccrualType.FIXED);
        };
    }

    private static String noteFor(AccrualType type) {
        return type == AccrualType.FIXED ? FIXED_ACCRUAL_NOTE : null;
    }

    // ── M5 규모 필터 ──────────────────────────────────────────────────────────

    /**
     * M5 — {@code kept_mean}으로 최소 가입/권장 납입금액 조건에 맞는 상품만 남긴다.
     * <b>규모는 필터 기준일 뿐 추천 방향을 정하지 않는다</b>(§4.5 M5).
     *
     * <p>둘 중 하나라도 모르면 통과시킨다 — 상품의 최소금액이 미수집이거나 사용자의 kept_mean 이력이
     * 없을 때 걸러내면, 근거 없이 후보가 사라진다.
     */
    static boolean fitsSize(ProductCandidate p, Long keptMean) {
        if (keptMean == null || p.minMonthlyAmount() == null) return true;
        return keptMean >= p.minMonthlyAmount();
    }

    // ── M6·M8 실수령 금리와 미충족 조건 ───────────────────────────────────────

    /**
     * M6 — 우대조건 <b>전부 충족이면 최고금리, 하나라도 미충족이면 기본금리</b>. 조건별 부분 가산은
     * 하지 않는다: 출처가 우대조건을 자연어 {@code spclCnd}로만 줘서 조건별 가산폭이 파싱 산물이라
     * 정확도를 보장할 수 없다(D2 · §8.1).
     *
     * <p>M8 — 실수령·최고 금리와 함께 <b>지금 미충족인 조건</b>을 사실로 노출한다. 여기서 만드는 것은
     * 목록까지이고, `이 조건을 채우면 +0.x% 더 받아요` 같은 <b>행동 유도 문장은 만들지 않는다</b>(R9 · §8.1).
     */
    SavingsMatch evaluate(ProductCandidate product, FundFlowProfile profile) {
        Set<PreferentialCondition> required = product.requiredConditions();
        FundFlowService.Preferential l5 = profile == null ? null : profile.l5Preferential();

        // 상품의 우대조건을 못 읽었거나(D2 미파싱) 사용자의 충족 재료가 없으면 '확인 불가'다.
        // 이때 최고금리를 주면 받지도 못할 금리를 표시하게 되므로 기본금리로 간다.
        if (required == null || l5 == null || !l5.known()) {
            return new SavingsMatch(product, product.baseRate(), List.of(), false);
        }

        List<PreferentialCondition> unmet = required.stream()
                .filter(c -> !meets(l5, c))
                .sorted()
                .toList();

        // 최고금리가 기본금리보다 낮게 신고된 데이터가 있다(누락 시 0으로 옴).
        // 실수령이 기본금리 아래로 내려가는 일은 없어야 하므로 바닥을 기본금리로 둔다.
        double effective = unmet.isEmpty() ? Math.max(product.baseRate(), product.maxRate()) : product.baseRate();
        return new SavingsMatch(product, effective, unmet, true);
    }

    private static boolean meets(FundFlowService.Preferential l5, PreferentialCondition condition) {
        return switch (condition) {
            case CARD_PERFORMANCE -> l5.cardPerformanceMet();
            case SALARY_TRANSFER -> l5.salaryTransferMet();
        };
    }

    // ── M7·M9 그룹 내 정렬 ────────────────────────────────────────────────────

    /**
     * M7 — 각 그룹 <b>내부에서만</b> 실수령 금리 내림차순. 그룹 간 우열은 매기지 않는다.
     * M9 — 동점이면 ① 미충족 조건 수 적은 것 → ② 만기 짧은 것 → ③ 기본금리 높은 것 → ④ 금융사명 가나다순.
     *
     * <p>마지막에 {@code productKey}를 한 단계 더 둔다. ①~④가 모두 같은 상품이 있어도 순서가 흔들리지
     * 않아야 같은 입력이 같은 화면을 만든다(설계원칙 3 재현성).
     *
     * <p>{@link Collator}는 스레드 안전이 보장되지 않아 호출마다 새로 만든다.
     */
    List<SavingsMatch> sortWithinGroup(List<SavingsMatch> all, AccrualType type) {
        Collator korean = Collator.getInstance(Locale.KOREAN);
        return all.stream()
                .filter(m -> m.product().accrualType() == type)
                .sorted(Comparator
                        .comparingDouble(SavingsMatch::effectiveRate).reversed()          // M7
                        .thenComparingInt(SavingsMatchService::unmetRank)                  // M9 ①
                        .thenComparingInt(m -> m.product().termMonths())                   // M9 ②
                        .thenComparing(Comparator.comparingDouble(
                                (SavingsMatch m) -> m.product().baseRate()).reversed())    // M9 ③
                        .thenComparing(m -> m.product().company(), korean)                 // M9 ④
                        .thenComparing(m -> m.product().productKey()))                     // 전순서 보장
                .toList();
    }

    /**
     * M9 ①의 정렬 키. 조건을 <b>확인하지 못한</b> 상품은 맨 뒤로 보낸다 — 미충족이 0건이어서가 아니라
     * 셀 수 없는 것이라, 0으로 세면 확인된 완전충족 상품과 같은 자리에 놓이게 된다.
     */
    private static int unmetRank(SavingsMatch m) {
        return m.conditionsKnown() ? m.unmetConditions().size() : Integer.MAX_VALUE;
    }

    // ── 산출 타입 ─────────────────────────────────────────────────────────────

    /** 그룹 순서를 정한 근거. 화면·LLM이 "왜 이 순서인지"를 사실대로 말할 수 있게 함께 낸다. */
    public enum GroupOrderBasis {
        /** M2 — 유동성 우선(파킹통장 상단). */
        LIQUIDITY_FIRST,
        /** M3 — 수익률 우선(자유적립식 상단). */
        YIELD_FIRST,
        /** 어느 규칙도 켜지지 않음(중간 구간이거나 재료 부족) — 기본 순서. */
        NEUTRAL
    }

    /**
     * 상품 한 건의 매칭 결과.
     *
     * @param effectiveRate    M6 실수령 금리(%).
     * @param unmetConditions  M8 현재 미충족 우대조건. {@code conditionsKnown=false}면 의미 없는 빈 목록이다.
     * @param conditionsKnown  우대조건을 판정할 수 있었는지. false면 화면은 `확인 불가`로 말해야 하며
     *                         `미충족 없음`으로 읽으면 안 된다.
     */
    public record SavingsMatch(ProductCandidate product, double effectiveRate,
                               List<PreferentialCondition> unmetConditions, boolean conditionsKnown) {

        /** M8 표시용 — 최고금리. 실수령과 나란히 노출한다. */
        public double maxRate() {
            return product().maxRate();
        }
    }

    /** 적립 방식 그룹. {@code note}는 정액적립식에만 붙는 사실 문구(M4)이며 다른 그룹은 null. */
    public record MatchGroup(AccrualType type, List<SavingsMatch> matches, String note) {}

    /**
     * 매칭 산출물.
     *
     * @param basis            그룹 순서의 근거(M2·M3·중립).
     * @param sizeFilterApplied M5 규모 필터가 실제로 걸렸는지. ②의 지킨 돈 이력이 없어 false면
     *                          "규모는 아직 못 봤다"는 뜻이고, 화면이 이를 숨기지 않는다(§14).
     */
    public record MatchResult(Long userId, List<MatchGroup> groups,
                              GroupOrderBasis basis, boolean sizeFilterApplied) {

        /** 그룹 순서를 유지한 전체 목록. 그룹 경계를 무시한 단일 순위표가 아니다(M1·M7). */
        public List<SavingsMatch> flattened() {
            return groups.stream().flatMap(g -> g.matches().stream()).toList();
        }
    }
}
