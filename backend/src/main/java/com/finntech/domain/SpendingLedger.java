package com.finntech.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 정리된 소비 원장 한 줄 — 결제 1건이 여기 한 줄이다 (V34).
 *
 * <p>뒤에 붙을 별도 알고리즘 프로그램이 <b>이 표 하나만 읽고 필터링해서</b> 데이터를 뽑는다.
 * 담긴 값은 전부 다른 곳에서 이미 정해진 것의 사본이라, 표를 통째로 지워도 다시 채울 수 있다.
 *
 * <h2>세 층이 각각 다른 사건에 반응한다</h2>
 *
 * <ul>
 *   <li><b>사실</b>({@link Facts}) — 결제·가맹점·분류. 계산이 없다. 원장이 바뀌면 곧바로 따라 쓴다.
 *   <li><b>고정지출</b>({@link FixedFacts}) — {@code RecurringPaymentDetector} 가 돌 때 받아 적는다.
 *   <li><b>낭비</b>({@link WasteFacts}) — {@code WasteScoringService} 가 돌 때 받아 적는다.
 * </ul>
 *
 * <p><b>표가 계산을 일으키지 않는다.</b> 뒤 두 층은 남이 부를 때 나온 답을 옮겨 담을 뿐이라
 * 비어 있거나 낡을 수 있다. 그것을 감추지 않고 {@link #fixedRecordedAt}·{@link #wasteRecordedAt}
 * 을 {@link #factsUpdatedAt} 과 견주게 해서, 읽는 쪽이 낡음을 스스로 알게 한다.
 *
 * <h2>칸마다 세터를 두지 않는다</h2>
 *
 * <p>층 단위로 {@code applyXxx} 하나씩만 연다. 칸이 마흔 개인데 세터를 달면 갱신을 빠뜨린 칸이
 * <b>조용히 옛 값을 유지한다</b> — 그 종류의 어긋남은 아무 데서도 안 터진다. 층째로 갈아 끼우면
 * 칸이 늘 때 컴파일이 막아 준다.
 */
@Entity
@Table(name = "spending_ledger", indexes = {
        @Index(name = "idx_spending_ledger_user_month", columnList = "user_id, month_key, paid_at"),
        @Index(name = "idx_spending_ledger_user_merchant", columnList = "user_id, merchant_key"),
        @Index(name = "idx_spending_ledger_month", columnList = "month_key"),
        @Index(name = "idx_spending_ledger_brand", columnList = "brand")
})
public class SpendingLedger {

    /** 판정이 아직 안 돈 줄의 낭비 출처. "낭비가 아니다"와 다른 사실이다. */
    public static final String WASTE_UNJUDGED = "UNJUDGED";

    /** 모델이 낸 답. */
    public static final String WASTE_MODEL = "MODEL";

    /** 사용자가 카테고리째로 뒤집었다 — 확률을 무시하고 라벨이 정해진다. 근거 칸이 빈다. */
    public static final String WASTE_OVERRIDE = "OVERRIDE";

    /** 지금 담는 반복 종류는 고정형뿐이다. 루틴형은 오늘 날짜에 매인 값이라 담지 않는다. */
    public static final String RECURRING_FIXED = "FIXED";

    /** 근거로 남기는 축의 최대 개수 — 칸이 셋이다. */
    public static final int FACTOR_SLOTS = 3;

    @Id
    @Column(name = "payment_id", length = 40)
    private String paymentId;

    // ── 식별·시간 ────────────────────────────────────────────────────────────

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "month_key", nullable = false, length = 7)
    private String monthKey;

    @Column(name = "paid_at", nullable = false)
    private LocalDateTime paidAt;

    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;

    @Column(name = "day_of_month", nullable = false)
    private int dayOfMonth;

    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;

    @Column(name = "daypart", nullable = false, length = 10)
    private String daypart;

    @Column(name = "origin", nullable = false, length = 10)
    private String origin;

    // ── 가맹점 ──────────────────────────────────────────────────────────────

    @Column(name = "business_number", nullable = false, length = 10)
    private String businessNumber = "";

    @Column(name = "payment_agency", nullable = false)
    private boolean paymentAgency;

    @Column(name = "merchant_name", length = 60)
    private String merchantName;

    @Column(name = "brand", length = 60)
    private String brand;

    @Column(name = "merchant_address", length = 200)
    private String merchantAddress;

    @Column(name = "merchant_key", length = 70)
    private String merchantKey;

    // ── 금액·분류 ────────────────────────────────────────────────────────────

    @Column(name = "amount", nullable = false)
    private int amount;

    @Column(name = "nts_industry_code", nullable = false, length = 8)
    private String ntsIndustryCode;

    @Column(name = "registry_industry_name", length = 80)
    private String registryIndustryName;

    /**
     * <b>추정 업종코드</b> — 모델의 답에서 온 값. 확정({@code ntsIndustryCode})과 갈라 둔다.
     *
     * <p>실 명세서에는 업종코드가 없어 자리채움값이 들어가고, 그러면 카드 혜택축이 죽는다.
     * 채울 수 있는 것을 다 채워도 <b>확정은 40%가 한계</b>라(소분류 170개 중 62개만 업종
     * 하나를 가리킨다) 나머지는 추정할 수밖에 없다. <b>판정에는 안 쓴다.</b>
     */
    @Column(name = "nts_industry_code_llm", length = 8)
    private String ntsIndustryCodeLlm;

    @Column(name = "category2", length = 30)
    private String category2;

    @Column(name = "category2_source", nullable = false, length = 12)
    private String category2Source = "NONE";

    @Column(name = "category2_llm", length = 30)
    private String category2Llm;

    @Column(name = "category2_llm_source", length = 12)
    private String category2LlmSource;

    /**
     * <b>소분류</b> — 중분류보다 작고 브랜드보다 큰 칸(카카오T=브랜드, 택시=여기, 교통/자동차=중분류).
     *
     * <p>추정층을 따로 두지 않는다. 소분류는 정확히 한 중분류에만 속하므로
     * ({@code IndustryCategoryMapper.midOfSub}) <b>이 값이 있으면 중분류가 결정된다</b> —
     * 즉 소분류를 적을 수 있다는 것 자체가 확정을 뜻한다. 추정에 머무는 답은
     * {@code category2Llm} 쪽에 있고 소분류를 얻지 못한다.
     */
    @Column(name = "category3", length = 30)
    private String category3;

    /** 소분류를 무엇으로 알아냈나 — {@code NONE}(모름) · {@code NAME}(업종 이름) · {@code BRAND}(브랜드). */
    @Column(name = "category3_source", nullable = false, length = 12)
    private String category3Source = "NONE";

    // ── 고정지출 ─────────────────────────────────────────────────────────────

    @Column(name = "fixed")
    private Boolean fixed;

    @Column(name = "recurring_type", length = 10)
    private String recurringType;

    @Column(name = "period_kind", length = 10)
    private String periodKind;

    @Column(name = "period_days")
    private Integer periodDays;

    @Column(name = "gap_cv")
    private Double gapCv;

    @Column(name = "group_payment_count")
    private Integer groupPaymentCount;

    @Column(name = "group_occurrence_days")
    private Integer groupOccurrenceDays;

    @Column(name = "group_first_paid_on")
    private LocalDate groupFirstPaidOn;

    @Column(name = "group_last_paid_on")
    private LocalDate groupLastPaidOn;

    @Column(name = "representative_amount")
    private Long representativeAmount;

    @Column(name = "amount_varies")
    private Boolean amountVaries;

    @Column(name = "prior_amount")
    private Long priorAmount;

    @Column(name = "next_expected_on")
    private LocalDate nextExpectedOn;

    @Column(name = "fixed_recorded_at")
    private LocalDateTime fixedRecordedAt;

    @Column(name = "detector_version", length = 20)
    private String detectorVersion;

    // ── 낭비 ────────────────────────────────────────────────────────────────

    @Column(name = "waste")
    private Boolean waste;

    @Column(name = "waste_probability")
    private Double wasteProbability;

    @Column(name = "waste_label_source", length = 12)
    private String wasteLabelSource;

    @Column(name = "waste_threshold")
    private Double wasteThreshold;

    @Column(name = "stance", length = 10)
    private String stance;

    @Column(name = "model_threshold")
    private Double modelThreshold;

    @Column(name = "model_fingerprint", length = 64)
    private String modelFingerprint;

    @Column(name = "factor1_label", length = 30)
    private String factor1Label;

    @Column(name = "factor1_detail", length = 80)
    private String factor1Detail;

    @Column(name = "factor1_contribution")
    private Double factor1Contribution;

    @Column(name = "factor2_label", length = 30)
    private String factor2Label;

    @Column(name = "factor2_detail", length = 80)
    private String factor2Detail;

    @Column(name = "factor2_contribution")
    private Double factor2Contribution;

    @Column(name = "factor3_label", length = 30)
    private String factor3Label;

    @Column(name = "factor3_detail", length = 80)
    private String factor3Detail;

    @Column(name = "factor3_contribution")
    private Double factor3Contribution;

    @Column(name = "waste_recorded_at")
    private LocalDateTime wasteRecordedAt;

    // ── 관리 ────────────────────────────────────────────────────────────────

    @Column(name = "facts_updated_at", nullable = false)
    private LocalDateTime factsUpdatedAt;

    protected SpendingLedger() {}

    public SpendingLedger(String paymentId, Facts facts, LocalDateTime at) {
        this.paymentId = paymentId;
        applyFacts(facts, at);
    }

    // ── 층 셋 ────────────────────────────────────────────────────────────────

    /**
     * 결제·가맹점·분류 — 계산이 없는 사실의 사본.
     *
     * <p>확정({@code category2} + {@code category2Source})과 추정({@code category2Llm} +
     * {@code category2LlmSource})을 갈라 받는다. 원장은 한 칸이 둘을 겸하는데 그대로 옮기면
     * 읽는 쪽이 무심코 추정을 판정에 쓴다(마스터 §4 원칙 1).
     */
    public record Facts(
            Long userId, String monthKey, LocalDateTime paidAt, LocalDate paidOn,
            int dayOfMonth, int dayOfWeek, String daypart, String origin,
            String businessNumber, boolean paymentAgency, String merchantName,
            String brand, String merchantAddress, String merchantKey,
            int amount, String ntsIndustryCode, String ntsIndustryCodeLlm, String registryIndustryName,
            String category2, String category2Source,
            String category2Llm, String category2LlmSource,
            String category3, String category3Source) {}

    /**
     * 고정지출 — {@code RecurringPaymentDetector} 가 낸 묶음에서 나온다.
     *
     * <p>진행/종료 여부와 "다음 예상일"의 유무는 담지 않는다. 그것들은 오늘 날짜를 봐야 아는
     * 값이라, 적어 두면 그 줄이 쓰인 날의 답을 영원히 들고 있게 된다. 대신
     * {@code groupLastPaidOn}·{@code periodDays}·{@code nextExpectedOn}(= 마지막 + 주기)을
     * 그대로 적어 읽는 쪽이 오늘과 견주게 한다.
     */
    public record FixedFacts(
            boolean fixed, String recurringType, String periodKind, Integer periodDays, Double gapCv,
            Integer groupPaymentCount, Integer groupOccurrenceDays,
            LocalDate groupFirstPaidOn, LocalDate groupLastPaidOn,
            Long representativeAmount, Boolean amountVaries, Long priorAmount,
            LocalDate nextExpectedOn, String detectorVersion) {

        /** 어느 묶음에도 안 든 결제 — <b>판정은 돌았고 답이 '아니다'</b>라는 뜻이다(NULL 과 다르다). */
        public static FixedFacts notFixed(String detectorVersion) {
            return new FixedFacts(false, null, null, null, null, null, null,
                    null, null, null, null, null, null, detectorVersion);
        }
    }

    /**
     * 낭비 — {@code WasteScoringService} 가 낸 판정에서 나온다.
     *
     * <p>{@code threshold} 가 {@code null} 이면 성향이 {@code EXCLUDED} 라 <b>어떤 확률도
     * 낭비가 아니라는 뜻</b>이다. 코드가 쓰는 {@code Double.MAX_VALUE} 를 그대로 옮기지 않는다 —
     * 그것은 임계가 아니라 "판정하지 않는다"이고, 숫자로 받은 쪽은 그것으로 산술을 한다.
     */
    public record WasteFacts(
            Boolean waste, Double probability, String labelSource, Double threshold,
            String stance, Double modelThreshold, String modelFingerprint,
            List<Factor> factors) {

        /** 판정을 밀어올린 축 하나 — 이름·수치·로그오즈 기여. */
        public record Factor(String label, String detail, Double contribution) {}

        /** 분류가 없어 판정 대상이 아니었던 결제. */
        public static WasteFacts unjudged(Double modelThreshold, String modelFingerprint) {
            return new WasteFacts(null, null, WASTE_UNJUDGED, null, null,
                    modelThreshold, modelFingerprint, List.of());
        }
    }

    /**
     * 사실 칸을 갈아 끼운다. 고정지출·낭비 칸은 건드리지 않는다 — 그것들은 제 사건에 따라온다.
     *
     * <p><b>달라진 것이 없으면 {@link #factsUpdatedAt} 도 손대지 않는다.</b> 이유가 둘이다.
     *
     * <ul>
     *   <li>시각을 늘 새로 찍으면 Hibernate 가 <b>모든 줄에 UPDATE 를 낸다</b> — 분류 한 건을
     *       확정했을 뿐인데 그 사용자의 수천 줄이 다시 써진다(2026-08-14 예행에서 실측: 한 줄을
     *       고쳤는데 17줄이 전부 갱신됐다).
     *   <li>그러면 판정 칸이 <b>통째로 낡은 것으로 보인다</b>. 낡음의 뜻이 "사실이 바뀌었다"가
     *       아니라 "누가 재작성을 돌렸다"가 되어, 그 신호로는 아무것도 판단할 수 없다.
     * </ul>
     *
     * @return 실제로 달라진 것이 있었나
     */
    public boolean applyFacts(Facts facts, LocalDateTime at) {
        if (factsUpdatedAt != null && facts.equals(currentFacts())) return false;
        this.userId = facts.userId();
        this.monthKey = facts.monthKey();
        this.paidAt = facts.paidAt();
        this.paidOn = facts.paidOn();
        this.dayOfMonth = facts.dayOfMonth();
        this.dayOfWeek = facts.dayOfWeek();
        this.daypart = facts.daypart();
        this.origin = facts.origin();
        this.businessNumber = facts.businessNumber() == null ? "" : facts.businessNumber();
        this.paymentAgency = facts.paymentAgency();
        this.merchantName = facts.merchantName();
        this.brand = facts.brand();
        this.merchantAddress = facts.merchantAddress();
        this.merchantKey = facts.merchantKey();
        this.amount = facts.amount();
        this.ntsIndustryCode = facts.ntsIndustryCode();
        this.ntsIndustryCodeLlm = facts.ntsIndustryCodeLlm();
        this.registryIndustryName = facts.registryIndustryName();
        this.category2 = facts.category2();
        this.category2Source = facts.category2Source();
        this.category2Llm = facts.category2Llm();
        this.category2LlmSource = facts.category2LlmSource();
        this.category3 = facts.category3();
        this.category3Source = facts.category3Source();
        this.factsUpdatedAt = at;
        return true;
    }

    /** 지금 담고 있는 사실 — 들어온 것과 견주어 <b>정말 달라졌는지</b> 보려고 만든다. */
    private Facts currentFacts() {
        return new Facts(userId, monthKey, paidAt, paidOn, dayOfMonth, dayOfWeek, daypart, origin,
                businessNumber, paymentAgency, merchantName, brand, merchantAddress, merchantKey,
                amount, ntsIndustryCode, ntsIndustryCodeLlm, registryIndustryName,
                category2, category2Source, category2Llm, category2LlmSource,
                category3, category3Source);
    }

    /** 고정지출 칸을 갈아 끼운다. */
    public void applyFixed(FixedFacts values, LocalDateTime at) {
        this.fixed = values.fixed();
        this.recurringType = values.recurringType();
        this.periodKind = values.periodKind();
        this.periodDays = values.periodDays();
        this.gapCv = values.gapCv();
        this.groupPaymentCount = values.groupPaymentCount();
        this.groupOccurrenceDays = values.groupOccurrenceDays();
        this.groupFirstPaidOn = values.groupFirstPaidOn();
        this.groupLastPaidOn = values.groupLastPaidOn();
        this.representativeAmount = values.representativeAmount();
        this.amountVaries = values.amountVaries();
        this.priorAmount = values.priorAmount();
        this.nextExpectedOn = values.nextExpectedOn();
        this.detectorVersion = values.detectorVersion();
        this.fixedRecordedAt = at;
    }

    /** 낭비 칸을 갈아 끼운다. 근거 축이 셋에 못 미치면 남는 칸은 비운다. */
    public void applyWaste(WasteFacts values, LocalDateTime at) {
        this.waste = values.waste();
        this.wasteProbability = values.probability();
        this.wasteLabelSource = values.labelSource();
        this.wasteThreshold = values.threshold();
        this.stance = values.stance();
        this.modelThreshold = values.modelThreshold();
        this.modelFingerprint = values.modelFingerprint();

        List<WasteFacts.Factor> factors = values.factors() == null ? List.of() : values.factors();
        WasteFacts.Factor first = factorAt(factors, 0);
        WasteFacts.Factor second = factorAt(factors, 1);
        WasteFacts.Factor third = factorAt(factors, 2);
        this.factor1Label = first == null ? null : first.label();
        this.factor1Detail = first == null ? null : first.detail();
        this.factor1Contribution = first == null ? null : first.contribution();
        this.factor2Label = second == null ? null : second.label();
        this.factor2Detail = second == null ? null : second.detail();
        this.factor2Contribution = second == null ? null : second.contribution();
        this.factor3Label = third == null ? null : third.label();
        this.factor3Detail = third == null ? null : third.detail();
        this.factor3Contribution = third == null ? null : third.contribution();
        this.wasteRecordedAt = at;
    }

    private static WasteFacts.Factor factorAt(List<WasteFacts.Factor> factors, int index) {
        return index < factors.size() ? factors.get(index) : null;
    }

    // ── 읽기 ────────────────────────────────────────────────────────────────

    public String getPaymentId() { return paymentId; }
    public Long getUserId() { return userId; }
    public String getMonthKey() { return monthKey; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public LocalDate getPaidOn() { return paidOn; }
    public int getDayOfMonth() { return dayOfMonth; }
    public int getDayOfWeek() { return dayOfWeek; }
    public String getDaypart() { return daypart; }
    public String getOrigin() { return origin; }
    public String getBusinessNumber() { return businessNumber; }
    public boolean isPaymentAgency() { return paymentAgency; }
    public String getMerchantName() { return merchantName; }
    public String getBrand() { return brand; }
    public String getMerchantAddress() { return merchantAddress; }
    public String getMerchantKey() { return merchantKey; }
    public int getAmount() { return amount; }
    public String getNtsIndustryCode() { return ntsIndustryCode; }
    public String getRegistryIndustryName() { return registryIndustryName; }
    public String getCategory2() { return category2; }
    public String getCategory2Source() { return category2Source; }
    public String getCategory2Llm() { return category2Llm; }
    public String getCategory2LlmSource() { return category2LlmSource; }
    public String getNtsIndustryCodeLlm() { return ntsIndustryCodeLlm; }
    public String getCategory3() { return category3; }
    public String getCategory3Source() { return category3Source; }

    public Boolean getFixed() { return fixed; }
    public String getRecurringType() { return recurringType; }
    public String getPeriodKind() { return periodKind; }
    public Integer getPeriodDays() { return periodDays; }
    public Double getGapCv() { return gapCv; }
    public Integer getGroupPaymentCount() { return groupPaymentCount; }
    public Integer getGroupOccurrenceDays() { return groupOccurrenceDays; }
    public LocalDate getGroupFirstPaidOn() { return groupFirstPaidOn; }
    public LocalDate getGroupLastPaidOn() { return groupLastPaidOn; }
    public Long getRepresentativeAmount() { return representativeAmount; }
    public Boolean getAmountVaries() { return amountVaries; }
    public Long getPriorAmount() { return priorAmount; }
    public LocalDate getNextExpectedOn() { return nextExpectedOn; }
    public LocalDateTime getFixedRecordedAt() { return fixedRecordedAt; }
    public String getDetectorVersion() { return detectorVersion; }

    public Boolean getWaste() { return waste; }
    public Double getWasteProbability() { return wasteProbability; }
    public String getWasteLabelSource() { return wasteLabelSource; }
    public Double getWasteThreshold() { return wasteThreshold; }
    public String getStance() { return stance; }
    public Double getModelThreshold() { return modelThreshold; }
    public String getModelFingerprint() { return modelFingerprint; }
    public String getFactor1Label() { return factor1Label; }
    public String getFactor1Detail() { return factor1Detail; }
    public Double getFactor1Contribution() { return factor1Contribution; }
    public String getFactor2Label() { return factor2Label; }
    public String getFactor2Detail() { return factor2Detail; }
    public Double getFactor2Contribution() { return factor2Contribution; }
    public String getFactor3Label() { return factor3Label; }
    public String getFactor3Detail() { return factor3Detail; }
    public Double getFactor3Contribution() { return factor3Contribution; }
    public LocalDateTime getWasteRecordedAt() { return wasteRecordedAt; }

    public LocalDateTime getFactsUpdatedAt() { return factsUpdatedAt; }
}
