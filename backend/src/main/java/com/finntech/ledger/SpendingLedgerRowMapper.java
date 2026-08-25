package com.finntech.ledger;

import com.finntech.config.AnalysisProperties;
import com.finntech.domain.MerchantCategory;
import com.finntech.domain.SpendingLedger;
import com.finntech.domain.UserMerchantStance;
import com.finntech.domain.UserPayment;
import com.finntech.engine.FixedGroup;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.engine.RecurringPayment;
import com.finntech.engine.RecurringPaymentDetector;
import com.finntech.ml.WasteScoringService;

import java.time.YearMonth;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 소비 원장 <b>한 줄을 만드는 규칙</b> — 순수 함수만 있다.
 *
 * <p>저장소도 Spring 도 모른다. 그래서 시험이 값 하나하나를 붙들고 볼 수 있고, 대조 점검
 * ({@code SpendingLedgerVerifier})이 <b>쓰지 않고 다시 만들어</b> 저장된 줄과 견줄 수 있다.
 * 만드는 일과 쓰는 일을 갈라 둔 값어치가 거기서 나온다.
 *
 * <h2>여기서 지키는 것 셋</h2>
 *
 * <ol>
 *   <li><b>확정과 추정을 가른다.</b> 원장은 {@code category2_source} 한 칸이 둘을 겸한다 —
 *       {@code confirmCategory2} 는 DICT/USER/REGISTRY/LLM_LOCAL/GIVE_UP 를,
 *       {@code suggestCategory2} 는 LLM/TEMP 를 <b>같은 칸에</b> 적는다. 그대로 옮기면
 *       읽는 쪽이 무심코 추정을 판정에 쓴다(마스터 §4 원칙 1).
 *   <li><b>오늘 날짜를 봐야 아는 값은 안 적는다.</b> 고정지출의 진행/종료가 그것이다.
 *   <li><b>임계를 다시 계산하지 않는다.</b> 성향별 임계는 {@link WasteScoringService} 가
 *       내주는 값을 그대로 받는다(원칙 2).
 * </ol>
 */
public final class SpendingLedgerRowMapper {

    /** {@code suggestCategory2} 가 적는 출처 — 이 둘만 추정이다. */
    /**
     * 추정으로 보는 출처.
     *
     * <p>{@code TEMP} 는 <b>더 이상 쓰지 않는다</b>(2026-08-21 — 무료·유료를 가르던 두 층을
     * 없애고 둘 다 {@code LLM} 으로 적는다). 그래도 목록에 남긴다 — 그 이전에 쓰인 줄이 DB 에
     * 있고, 빼면 그 줄들이 <b>추정이 아니라 확정</b>으로 읽힌다.
     */
    private static final Set<String> ESTIMATE_SOURCES = Set.of("LLM", "TEMP");

    /** 확정 출처를 잃은 줄. 값은 있는데 원장이 그 출처를 덮어써 되찾을 수 없을 때. */
    static final String SOURCE_UNKNOWN = "UNKNOWN";

    /** 아직 아무 확정도 없는 줄. */
    static final String SOURCE_NONE = "NONE";

    /** 실제 사람의 명세서에서 온 결제. */
    static final String ORIGIN_REAL = "REAL";

    /** 생성기가 만든 결제 — 실사용자 계정에도 섞일 수 있다(데모 신원이 다섯뿐이다). */
    static final String ORIGIN_SYNTHETIC = "SYNTHETIC";

    private SpendingLedgerRowMapper() {}

    /**
     * 가맹점 부가정보 — 사전({@link MerchantCategory})과 브랜드 대기표에서 온다.
     *
     * <p>세 값 다 <b>판정에 참여하지 않는 장식</b>이라 없어도 줄이 만들어진다. 그래서 이것들만
     * 바뀌었을 때는 표를 다시 쓰지 않는다 — 그러자고 5분마다 실사용자 전원을 재작성하는 것은
     * 값을 못 한다.
     */
    /**
     * @param brand      사전·대기 장소에 저장된 브랜드. <b>모델이 지어낸 것일 수 있다</b>
     * @param confirmedBrand 표기표가 확정한 브랜드 — <b>소분류는 이것만 쓴다</b>
     * @param formBrand  표기표가 상호에서 읽어 낸 브랜드 — <b>화면에 적을 때 이것이 먼저다</b>
     */
    public record MerchantFacts(String brand, String address, String registryIndustryName,
                                String confirmedBrand, String formBrand) {

        public MerchantFacts(String brand, String address, String registryIndustryName) {
            this(brand, address, registryIndustryName, null, null);
        }

        /** 표기표가 읽어 낸 브랜드를 실어 되돌린다 — 저장된 값은 그대로 둔다. */
        public MerchantFacts withFormBrands(String forSub, String forDisplay) {
            return new MerchantFacts(brand, address, registryIndustryName, forSub, forDisplay);
        }

        /**
         * <b>원장에 적을 브랜드</b> — 표기표가 답하면 그것이, 아니면 저장된 값이 나간다.
         *
         * <p>저장된 값은 모델이 지어낸 것일 수 있고 <b>한 번 붙으면 스스로 안 고쳐진다</b>.
         * 그래서 표기표가 아는 상호는 표기표를 믿는다. 표기표가 모르는 개인 상호는
         * 저장된 값이 유일한 답이라 그대로 쓴다.
         */
        public String brandForLedger() {
            return formBrand != null && !formBrand.isBlank() ? formBrand : brand;
        }

        public static final MerchantFacts EMPTY = new MerchantFacts(null, null, null);

        /** 사전 행에서 뽑는다. 브랜드가 사전에 아직 없으면 대기표({@code merchant_brand})의 값을 쓴다. */
        public static MerchantFacts of(MerchantCategory dictionaryRow, String pendingBrand) {
            if (dictionaryRow == null) {
                return pendingBrand == null ? EMPTY : new MerchantFacts(pendingBrand, null, null);
            }
            String brand = dictionaryRow.getBrand() != null ? dictionaryRow.getBrand() : pendingBrand;
            return new MerchantFacts(brand, dictionaryRow.getAddress(), dictionaryRow.getRegistryIndustry());
        }
    }

    // ── 1층: 사실 ────────────────────────────────────────────────────────────

    /** 결제 한 건의 사실 칸. 계산은 없고, 유도되는 값(월·요일·시간대)만 여기서 만든다. */
    public static SpendingLedger.Facts factsOf(UserPayment payment, MerchantFacts merchant,
                                               AnalysisProperties.Daypart daypart,
                                               Predicate<String> isPaymentAgency,
                                               IndustryCategoryMapper industries) {
        var paidAt = payment.getPaymentDate();
        String rawSource = payment.getCategory2Source();
        boolean estimate = rawSource != null && ESTIMATE_SOURCES.contains(rawSource);
        String businessNumber = MerchantCategory.normalize(payment.getBusinessNumber());

        return new SpendingLedger.Facts(
                payment.getUserId(),
                YearMonth.from(paidAt).toString(),
                paidAt,
                paidAt.toLocalDate(),
                paidAt.getDayOfMonth(),
                paidAt.getDayOfWeek().getValue(),
                // 시간대는 이 함수 한 곳에서만 나온다 — 경계값을 여기 박으면 yml 을 고쳐도 표가 안 따라온다.
                daypart.bucketOf(paidAt.getHour()),
                payment.isFromRealPerson() ? ORIGIN_REAL : ORIGIN_SYNTHETIC,
                businessNumber,
                isPaymentAgency.test(businessNumber),
                payment.getMerchantName(),
                merchant.brandForLedger(),
                merchant.address(),
                // 묶는 쪽과 **같은 함수**를 쓴다. 갈라지면 표의 묶음과 화면의 정기결제가 다른 것을 가리킨다.
                RecurringPaymentDetector.merchantKeyOf(
                        payment.getBusinessNumber(), payment.getMerchantName(), isPaymentAgency),
                payment.getAmount(),
                payment.getKsicCode(),
                merchant.registryIndustryName(),
                payment.getCategory2(),
                confirmedSourceOf(payment.getCategory2(), rawSource, estimate),
                payment.getCategory2Llm(),
                estimate ? rawSource : null,
                subOf(merchant, industries),
                subSourceOf(merchant, industries));
    }

    /**
     * <b>소분류를 정한다 — 브랜드가 업종 이름보다 먼저다.</b>
     *
     * <p>브랜드 표는 <b>사람이 검수한 확정 지식</b>이고 업종 이름은 <b>가맹점 한 곳씩의
     * 추론</b>(등록 조회거나 LLM)이다. 확정이 추론을 이긴다 — 사전 확정(①)이 업종코드(②)를
     * 이기는 것과 같은 원리라 순위에 새 원칙이 들어가지 않는다.
     *
     * <p>그리고 이 순서가 <b>같은 브랜드가 갈리는 것을 막는다</b>. 사전 키는 (사업자번호,
     * 가맹점명)이라 같은 브랜드의 두 지점이 서로 다른 통로로 들어온다 — 한 곳은 등록 조회가
     * {@code 한식 육류 요리 전문점} 을 주고 다른 곳은 LLM 이 {@code 치킨 전문점} 을 준다.
     * 브랜드가 먼저 답하면 둘 다 같은 소분류가 되고, 소분류는 한 중분류에만 속하므로
     * 중분류도 자동으로 같아진다.
     *
     * <p><b>회사명·결제수단에는 표가 답하지 않는다</b>({@code subOfBrand} 가 빈 값을 준다).
     * 그때는 업종 이름으로 내려간다.
     *
     * <p><b>사전에 적힌 브랜드가 아니라 표기표가 확정한 브랜드를 본다</b>({@code confirmedBrand}).
     * 저장된 값은 무료 통로가 답한 추정일 수 있어서다 — 운영 사전 845행 중 269행이 그랬고,
     * {@code (주)카카오} 는 <b>멜론</b>으로 적혀 있었다(2026-08-25 실측).
     */
    private static String subOf(MerchantFacts merchant, IndustryCategoryMapper industries) {
        String byBrand = industries.subOfBrand(merchant.confirmedBrand());
        if (!byBrand.isEmpty()) return byBrand;
        String byName = industries.subOfIndustryName(merchant.registryIndustryName());
        return byName.isEmpty() ? null : byName;
    }

    /** 소분류를 무엇으로 알아냈나. {@link #subOf} 와 같은 순서를 본다. */
    private static String subSourceOf(MerchantFacts merchant, IndustryCategoryMapper industries) {
        if (!industries.subOfBrand(merchant.confirmedBrand()).isEmpty()) return SUB_BRAND;
        if (!industries.subOfIndustryName(merchant.registryIndustryName()).isEmpty()) return SUB_NAME;
        return SOURCE_NONE;
    }

    /** 소분류를 브랜드 표에서 얻었다 — 사람이 검수한 확정. */
    static final String SUB_BRAND = "BRAND";
    /** 소분류를 업종 이름에서 얻었다. */
    static final String SUB_NAME = "NAME";

    /**
     * 확정 출처를 가려낸다 — 원장의 한 칸에서 두 뜻을 갈라내는 자리.
     *
     * <p>마지막으로 쓴 것이 추정이면 확정 출처는 원장에 남아 있지 않다. 그때 값까지 없으면
     * {@code NONE}(아직 아무것도 없다)이고, 값은 있는데 출처만 잃었으면 {@code UNKNOWN} 이다.
     * 뒤쪽은 사실상 안 생기지만 — 추정은 확정이 없는 줄에만 칠해진다 — 안 생긴다고 단정하고
     * {@code NONE} 을 적으면 그것이 <b>거짓말</b>이 된다. 모르는 것은 모른다고 적는다.
     */
    private static String confirmedSourceOf(String category2, String rawSource, boolean estimate) {
        if (!estimate) return rawSource == null ? SOURCE_NONE : rawSource;
        boolean hasConfirmed = category2 != null && !category2.isBlank()
                && !IndustryCategoryMapper.isUnknown(category2);
        return hasConfirmed ? SOURCE_UNKNOWN : SOURCE_NONE;
    }

    // ── 2층: 고정지출 ────────────────────────────────────────────────────────

    /**
     * 고정 묶음 하나를 칸으로 옮긴다.
     *
     * <p><b>{@code nextExpectedOn} 을 요약에서 가져오지 않는다.</b> 요약의
     * {@code nextExpected} 는 끝난 구독이면 {@code null} 인데, 그 판단은 판정이 돈 날의
     * {@code referenceTime} 에 달렸다. 적어 두면 그 줄이 쓰인 날의 답을 영원히 들고 있게 된다.
     * 여기서는 <b>마지막 결제일 + 주기</b>를 그대로 적는다 — "주기대로라면 다음"은 오늘과
     * 무관한 사실이고, 끝났는지는 읽는 쪽이 오늘과 견주어 정한다.
     */
    public static SpendingLedger.FixedFacts fixedOf(FixedGroup group, String detectorVersion) {
        RecurringPayment summary = group.summary();
        Integer periodDays = summary.periodDays();
        return new SpendingLedger.FixedFacts(
                true,
                SpendingLedger.RECURRING_FIXED,
                group.periodKind().name(),
                periodDays,
                group.gapCv(),
                group.paymentIds().size(),
                summary.occurrenceDays(),
                summary.firstSeen(),
                summary.lastSeen(),
                summary.representativeAmount(),
                summary.amountVaries(),
                summary.priorAmount(),
                periodDays == null || summary.lastSeen() == null
                        ? null : summary.lastSeen().plusDays(periodDays),
                detectorVersion);
    }

    // ── 3층: 낭비 ────────────────────────────────────────────────────────────

    /**
     * 낭비 판정 하나를 칸으로 옮긴다.
     *
     * @param judgment  판정. {@code null} 이면 분류가 없어 판정 대상이 아니었다는 뜻
     * @param stance    그 가맹점에 대한 사용자의 성향({@code null} 이면 NORMAL)
     * @param threshold {@link WasteScoringService#thresholdFor(UserMerchantStance.Stance)} 의 답.
     *                  비어 있으면 EXCLUDED — 어떤 확률도 낭비가 아니다
     * @param overridden 카테고리째로 뒤집힌 줄인가. 문구를 뜯어보지 않고 표를 직접 읽어 정한다 —
     *                   {@code explanation} 의 "개인화: …" 를 파싱하면 문구를 고치는 날 조용히 깨진다
     */
    public static SpendingLedger.WasteFacts wasteOf(WasteScoringService.WasteJudgment judgment,
                                                    UserMerchantStance.Stance stance,
                                                    OptionalDouble threshold,
                                                    boolean overridden,
                                                    double modelThreshold,
                                                    String modelFingerprint) {
        if (judgment == null) {
            return SpendingLedger.WasteFacts.unjudged(modelThreshold, modelFingerprint);
        }
        List<SpendingLedger.WasteFacts.Factor> factors = judgment.factors().stream()
                .limit(SpendingLedger.FACTOR_SLOTS)
                .map(factor -> new SpendingLedger.WasteFacts.Factor(
                        factor.label(), factor.detail(), factor.contribution()))
                .toList();
        return new SpendingLedger.WasteFacts(
                judgment.waste(),
                judgment.wasteProbability(),
                overridden ? SpendingLedger.WASTE_OVERRIDE : SpendingLedger.WASTE_MODEL,
                // EXCLUDED 는 임계가 없는 것이다. Double.MAX_VALUE 를 적으면 읽는 쪽이 그것으로 산술을 한다.
                threshold.isPresent() ? threshold.getAsDouble() : null,
                (stance == null ? UserMerchantStance.Stance.NORMAL : stance).name(),
                modelThreshold,
                modelFingerprint,
                factors);
    }
}
