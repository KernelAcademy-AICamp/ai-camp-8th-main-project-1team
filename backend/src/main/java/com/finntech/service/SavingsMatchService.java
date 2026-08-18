package com.finntech.service;

import com.finntech.service.FundFlowService.FundFlowProfile;
import com.finntech.service.FundFlowService.LiquidityNeed;
import com.finntech.service.FundFlowService.StabilityLevel;
import com.finntech.service.SavingsMatchInputs.AccrualType;
import com.finntech.service.SavingsMatchInputs.AmountLimit;
import com.finntech.service.SavingsMatchInputs.AmountUnit;
import com.finntech.service.SavingsMatchInputs.IssuerScope;
import com.finntech.service.SavingsMatchInputs.PreferentialCondition;
import com.finntech.service.SavingsMatchInputs.ProductCandidate;
import com.finntech.service.SavingsMatchInputs.RequiredCondition;
import com.finntech.util.FinancialCompanyNames;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 저축 상품 매칭 (FP-01) — 규칙 M1~M10. 정본은 `07_취향분석및추천_Agent_설계.md` §4.5.
 *
 * <p><b>판정·정렬은 전부 여기(코드)서 하고 LLM은 문장만 만든다</b>(마스터 §4 원칙 1 · §4.5 「LLM 역할 범위」).
 * LLM은 상품 판정·그룹 분류·금리 계산·정렬 순서에 관여하지 않는다. 우대조건을 <b>구조로 옮기는</b> 일은
 * {@link PreferentialLabelService}가 따로 하며, 그 결과를 받아 `충족했나`를 가르는 것은 여기다.
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

        // M5 — 가입 금액. 하한 미달만 뺀다(상한 초과는 남기고 evaluate가 표시 플래그를 단다).
        boolean sizeFilterApplied = keptMean != null;
        List<ProductCandidate> sized = candidates.stream()
                .filter(p -> joinable(p, keptMean))
                .toList();

        // M6·M8 — 실수령 금리와 조건 판정. 상품마다 한 번씩.
        List<SavingsMatch> matched = sized.stream()
                .map(p -> evaluate(p, profile, keptMean))
                .toList();

        // M1 — 적립 방식으로 가른다. M2·M3가 그룹 순서를 정한다.
        GroupOrderBasis basis = decideBasis(profile);
        List<MatchGroup> groups = new ArrayList<>();
        for (AccrualType type : groupOrder(basis)) {
            groups.add(new MatchGroup(type, sortWithinGroup(matched, type, basis), noteFor(type)));
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
            // 유동성이 급하면 묶이는 순서의 역순 — 파킹(수시) → 자유적립(안 넣어도 됨) → 예금(만기까지 묶임).
            case LIQUIDITY_FIRST ->
                    List.of(AccrualType.PARKING, AccrualType.FLEXIBLE, AccrualType.DEPOSIT, AccrualType.FIXED);
            // 묶어도 되는 사람이라 예금이 파킹보다 앞이다(같은 기간이면 금리가 높다).
            case YIELD_FIRST ->
                    List.of(AccrualType.FLEXIBLE, AccrualType.DEPOSIT, AccrualType.PARKING, AccrualType.FIXED);
            // 중립은 "근거가 없어 기본 순서"라는 뜻이다(basis로 화면·LLM이 그 사실을 그대로 말한다).
            // 모를 때는 덜 묶는 쪽이 안전하므로 예금을 파킹보다 뒤에 둔다.
            case NEUTRAL ->
                    List.of(AccrualType.FLEXIBLE, AccrualType.PARKING, AccrualType.DEPOSIT, AccrualType.FIXED);
        };
    }

    private static String noteFor(AccrualType type) {
        return type == AccrualType.FIXED ? FIXED_ACCRUAL_NOTE : null;
    }

    // ── M5 가입 금액 ──────────────────────────────────────────────────────────

    /**
     * M5 — 상품이 받아 주는 금액과 `kept_mean`을 견준다. <b>하한 미달만 뺀다.</b>
     *
     * <p>하한을 못 채우면 <b>가입 자체가 안 되므로</b> 목록에 둘 이유가 없다. 반대로 상한을 넘는 것은
     * 가입이 되며 초과분만 못 담는 것이라, 빼지 않고 {@link SavingsMatch#amountCapped()}로 사실만 알린다 —
     * 담을지 말지는 사용자가 정한다(거울 원칙 · 사용자 결정 2026-08-11).
     *
     * <p><b>적립식에만 건다.</b> 예금의 가입금액은 `최소 100만원 이상`처럼 <b>목돈</b> 기준이라 월 단위인
     * `kept_mean`과 비교 대상이 아니다. 예금의 재원은 월 저축액이 아니라 이미 모인 목돈인데 우리는 그중
     * 얼마를 묶을지 모른다 — 여기서 걸러내면 근거 없이 후보가 사라진다.
     *
     * <p>둘 중 하나라도 모르면 통과시킨다(상품 금액 미수집이거나 사용자 이력 없음).
     */
    static boolean joinable(ProductCandidate p, Long keptMean) {
        AmountLimit limit = p.amountLimit();
        if (keptMean == null || limit == null) return true;
        if (limit.unit() != AmountUnit.MONTHLY) return true;   // 예금·파킹은 표시만
        return !limit.belowMin(keptMean);
    }

    /** 이 사람의 월 저축액이 상품 상한을 넘는가(화면 표시용). 모르면 false — 없는 조건을 지어내지 않는다. */
    static boolean amountCapped(ProductCandidate p, Long keptMean) {
        AmountLimit limit = p.amountLimit();
        if (keptMean == null || limit == null || limit.unit() != AmountUnit.MONTHLY) return false;
        return limit.aboveMax(keptMean);
    }

    // ── M6·M8 실수령 금리와 조건 판정 ─────────────────────────────────────────

    /** 조건 하나의 판정 결과. <b>UNKNOWN은 UNMET이 아니다</b> — 모르는 것과 안 되는 것은 다르다. */
    enum Judgement { MET, UNMET, UNKNOWN }

    /**
     * M6 — 실수령 금리를 <b>3분기</b>로 낸다(2026-08-11 개정).
     *
     * <pre>
     *   요구 조건이 빈 목록                  → max_rate   (채울 게 없다)
     *   전부 확정 판정 &amp; 전부 충족           → max_rate
     *   확정 미충족이 하나라도 있음            → base_rate  + 미충족 목록
     *   확정 미충족은 없고 판정 불가만 있음     → base_rate  + `확인 못한 조건 N개`
     * </pre>
     *
     * <p>옛 규칙은 이분(전부 충족 / 미충족)이었는데 <b>모든 상품이 카드 실적·급여이체를 요구한다</b>는
     * 가정 위에 서 있었다. 실측(2026-08-11 적금 58건)상 그 가정은 <b>83%에서 틀린다</b> — 그 상품들에
     * `미충족: 카드 실적, 급여이체`라고 <b>없는 조건을 지어내 말하고</b> 있었다.
     *
     * <p>판정 불가일 때 기본금리로 내리는 것은 <b>덜 준다고 말하는 방향</b>이라 사용자가 받을 금리를
     * 부풀리지 않는다. 다만 화면은 `미충족`이 아니라 `확인 못함`으로 말해야 하고, 그 구분을
     * {@link SavingsMatch#unknownConditions()}가 들고 있다.
     *
     * <p>M8 — 여기서 만드는 것은 <b>목록까지</b>다. `이 조건을 채우면 +0.x% 더 받아요` 같은 행동 유도
     * 문장은 만들지 않는다(R9 · §8.1).
     */
    SavingsMatch evaluate(ProductCandidate product, FundFlowProfile profile, Long keptMean) {
        List<RequiredCondition> required = product.requiredConditions();
        FundFlowService.Preferential l5 = profile == null ? null : profile.l5Preferential();
        boolean capped = amountCapped(product, keptMean);

        // 아직 라벨링하지 않은 상품 — 요구 조건이 없는 것과 다르다. 목록을 만들 수 없으므로 labeled=false.
        if (required == null) {
            return new SavingsMatch(product, product.baseRate(), List.of(), List.of(), false, capped);
        }
        // 라벨링했더니 요구 조건이 없더라 — 채울 것이 없으니 곧바로 최고금리다.
        if (required.isEmpty()) {
            return new SavingsMatch(product, atLeastBase(product), List.of(), List.of(), true, capped);
        }

        List<PreferentialCondition> unmet = new ArrayList<>();
        List<PreferentialCondition> unknown = new ArrayList<>();
        for (RequiredCondition c : required) {
            switch (judge(c, l5, product.company())) {
                case UNMET -> unmet.add(c.type());
                case UNKNOWN -> unknown.add(c.type());
                case MET -> { /* 채운 조건은 목록에 남기지 않는다 — 화면이 말할 것이 없다 */ }
            }
        }

        double effective = unmet.isEmpty() && unknown.isEmpty()
                ? atLeastBase(product)
                : product.baseRate();
        return new SavingsMatch(product, effective, List.copyOf(unmet), List.copyOf(unknown), true, capped);
    }

    /**
     * 최고금리가 기본금리보다 낮게 신고된 데이터가 있다(누락 시 0으로 온다).
     * 실수령이 기본금리 아래로 내려가는 일은 없어야 하므로 바닥을 기본금리로 둔다.
     */
    private static double atLeastBase(ProductCandidate p) {
        return Math.max(p.baseRate(), p.maxRate());
    }

    /**
     * 조건 하나를 판정한다(§4.5 M6의 여섯 줄 표). 순수.
     *
     * <p>당행 한정({@link IssuerScope#OWN})이면 <b>그 금융사 것으로 좁혀서</b> 본다 — 다른 카드사에서
     * 채운 실적으로 이 은행 상품의 우대를 받았다고 말하지 않기 위해서다. 금융사 이름을 알아볼 수 없으면
     * (표기가 출처마다 다르다) 판정을 강행하지 않고 UNKNOWN으로 둔다.
     */
    static Judgement judge(RequiredCondition condition, FundFlowService.Preferential l5, String productCompany) {
        if (!condition.type().judgeable()) return Judgement.UNKNOWN;   // ①②⑥ — 우리 축이 아니다
        if (l5 == null || !l5.known()) return Judgement.UNKNOWN;       // 사용자 재료가 없다

        if (condition.scope() == IssuerScope.OWN) {
            Iterable<String> mine = switch (condition.type()) {
                case CARD_PERFORMANCE -> l5.cardPerformanceCompanies();
                case SALARY_TRANSFER -> l5.salaryBanks();
                default -> List.of();
            };
            Boolean hit = FinancialCompanyNames.containsGroup(mine, productCompany);
            if (hit == null) return Judgement.UNKNOWN;                 // 금융사명을 못 알아봤다
            return hit ? Judgement.MET : Judgement.UNMET;              // ③④
        }

        // ⑤ 금융사 한정이 없다 — 아무 카드/계좌로도 채울 수 있다.
        boolean met = switch (condition.type()) {
            case CARD_PERFORMANCE -> l5.cardPerformanceMet();
            case SALARY_TRANSFER -> l5.salaryTransferMet();
            default -> false;
        };
        return met ? Judgement.MET : Judgement.UNMET;
    }

    // ── M7·M9·M10 그룹 내 정렬 ────────────────────────────────────────────────

    /**
     * M7 — 각 그룹 <b>내부에서만</b> 실수령 금리 내림차순. 그룹 간 우열은 매기지 않는다.
     * M9 — 동점이면 ① 미충족 조건 수 적은 것 → (M2일 때만 중도해지이율 높은 것) → ② 만기 짧은 것 →
     * ③ 기본금리 높은 것 → ④ 금융사명 가나다순.
     *
     * <p>마지막에 {@code productKey}를 한 단계 더 둔다. 앞이 모두 같은 상품이 있어도 순서가 흔들리지
     * 않아야 같은 입력이 같은 화면을 만든다(설계원칙 3 재현성).
     *
     * <p>{@link Collator}는 스레드 안전이 보장되지 않아 호출마다 새로 만든다.
     */
    List<SavingsMatch> sortWithinGroup(List<SavingsMatch> all, AccrualType type, GroupOrderBasis basis) {
        Collator korean = Collator.getInstance(Locale.KOREAN);
        Comparator<SavingsMatch> order = Comparator
                .comparingDouble(SavingsMatch::effectiveRate).reversed()              // M7
                .thenComparingInt(SavingsMatchService::unmetRank);                    // M9 ①
        if (basis == GroupOrderBasis.LIQUIDITY_FIRST) {
            order = order.thenComparing(SavingsMatchService::earlyTerminationRate,    // M10
                    Comparator.nullsLast(Comparator.reverseOrder()));
        }
        return all.stream()
                .filter(m -> m.product().accrualType() == type)
                .sorted(order
                        .thenComparingInt(m -> m.product().termMonths())              // M9 ②
                        .thenComparing(Comparator.comparingDouble(
                                (SavingsMatch m) -> m.product().baseRate()).reversed()) // M9 ③
                        .thenComparing(m -> m.product().company(), korean)            // M9 ④
                        .thenComparing(m -> m.product().productKey()))                // 전순서 보장
                .toList();
    }

    /**
     * M9 ①의 정렬 키. <b>조건을 확인하지 못한 상품은 맨 뒤</b>로 보낸다 — 미충족이 0건이어서가 아니라
     * 셀 수 없는 것이라, 0으로 세면 확인된 완전충족 상품과 같은 자리에 놓인다.
     */
    private static int unmetRank(SavingsMatch m) {
        return m.conditionsKnown() ? m.unmetConditions().size() : Integer.MAX_VALUE;
    }

    /**
     * M10의 정렬 키 — <b>만기의 절반 시점</b>에 깼다고 보고 그때 받는 이율(%).
     *
     * <p>중도해지는 언제 일어날지 모르는 사건이라 대표 시점을 하나 골라야 한다. 절반을 쓰는 것은
     * 구간표(`1개월 미만` / `1~6개월` / `6개월 이상`)의 가운데 구간을 집어 상품 간 차이를 가장 잘
     * 드러내기 때문이다. 앞이나 뒤 끝을 쓰면 대부분 상품이 같은 값으로 뭉쳐 동점 처리가 무의미해진다.
     *
     * <p>못 구한 상품은 {@code null}이고 정렬에서 <b>맨 뒤</b>다. 0으로 바꿔 읽지 않는다 —
     * 모르는 것과 `연 0%`는 다르다.
     */
    private static Double earlyTerminationRate(SavingsMatch m) {
        var early = m.product().earlyTermination();
        if (early == null) return null;
        int term = m.product().termMonths();
        int held = Math.max(1, term / 2);
        return early.rateAt(held, term, m.effectiveRate());
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
     * @param effectiveRate     M6 실수령 금리(%).
     * @param unmetConditions   M8 <b>확정 미충족</b> 조건. 화면은 `미충족`이라고 말해도 된다.
     * @param unknownConditions M6 <b>판정 불가</b> 조건. 화면은 `확인하지 못했어요`라고 말해야 하며
     *                          <b>`미충족`으로 바꿔 쓰면 안 된다.</b> 실측상 이쪽이 다수(83%)라 예외가
     *                          아니라 기본 상태다.
     * @param labeled           우대조건을 라벨링했는지. false면 두 목록이 비어 있어도 `조건 없음`이 아니라
     *                          <b>`아직 못 읽음`</b>이다.
     * @param amountCapped      M5 — 이 사람의 월 저축액이 상품 상한을 넘는가. true면 화면에
     *                          `월 ○○원까지 담을 수 있어요` 사실만 적는다(빼지 않는다).
     */
    public record SavingsMatch(ProductCandidate product, double effectiveRate,
                               List<PreferentialCondition> unmetConditions,
                               List<PreferentialCondition> unknownConditions,
                               boolean labeled, boolean amountCapped) {

        /** M8 표시용 — 최고금리. 실수령과 나란히 노출한다. */
        public double maxRate() {
            return product().maxRate();
        }

        /**
         * 우대조건을 <b>끝까지 판정했는가</b>. 라벨링됐고 판정 불가가 하나도 없을 때만 true다.
         * false면 화면이 `미충족 없음`으로 읽으면 안 된다.
         */
        public boolean conditionsKnown() {
            return labeled && unknownConditions.isEmpty();
        }

        /** 우대조건이 실제로 <b>없는</b> 상품인가(`spcl_cnd`가 `없음`). 화면은 조건 영역을 통째로 비운다. */
        public boolean noConditions() {
            return labeled && unmetConditions.isEmpty() && unknownConditions.isEmpty()
                    && product().requiredConditions() != null
                    && product().requiredConditions().isEmpty();
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
