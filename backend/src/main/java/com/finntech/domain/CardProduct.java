package com.finntech.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 카드 한 장 — 카드사 상품공시에서 온 <b>실제 상품</b>이다.
 *
 * <p>설계의 근거는 마이그레이션 {@code V36__card_product.sql} 머리말에 있다. 여기서는 코드가
 * 알아야 할 것만 적는다.
 *
 * <p><b>실제 상품이라 방패가 하나 없다</b>(마스터 원칙 5 재개정 2026-08-10). 예적금과 달리
 * "더미라서 영업이 아니다"를 못 쓰므로 유권해석(2022.6.15) 네 요건으로 선다. 그래서 이 표에
 * <b>없는 칸이 방어다</b> — 신청 URL·CTA·제휴·광고비·노출순위 칸을 두지 않는다.
 * {@link #sourceUrl} 은 <b>공시 원문 주소</b>이지 신청 링크가 아니다.
 *
 * <p><b>혜택 개정 추적은 스코프 밖이다.</b> 이 행은 {@link #asOf}(심의필 날짜) 시점의
 * 스냅샷이고 시간이 지나면 낡는다. 화면에 기준일을 병기하는 것이 유일한 방어라,
 * {@code asOf} 가 없으면 {@link Grade#REFERENCE} 로 떨어진다.
 */
@Entity
@Table(name = "card_product")
public class CardProduct {

    /** 신용/체크 — 체크카드는 전월실적 구조가 달라 1급 구분이다. */
    public enum CardType { CREDIT, CHECK }

    /**
     * 발급 상태. 발급중단({@code STOPPED})도 <b>버리지 않는다</b> — "예전엔 이런 혜택이
     * 있었다"는 비교축이 사라진다(수집 정책 A/B/C 중 B군).
     */
    public enum Status { ACTIVE, STOPPED }

    /** 혜택의 성격. 마일리지·프리미엄은 금액 환산이 안 돼 계산에서 빠지고 표시만 된다. */
    public enum BenefitStyle { DISCOUNT_POINT, MILEAGE, PREMIUM }

    /**
     * 게이트 3(규칙 검산)을 통과했는가.
     *
     * <p><b>{@code REFERENCE} 면 화면에 숫자를 보여주지 않는다.</b> 기본값이 참고인 것은,
     * 검산을 안 거친 행이 정밀로 보이는 사고가 더 비싸기 때문이다.
     */
    public enum Grade { PRECISE, REFERENCE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issuer", nullable = false, length = 20)
    private String issuer;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** 카드사 내부 상품번호. 카드사가 바꾸지 않는 유일한 키라 재적재의 기준이다. */
    @Column(name = "product_id", nullable = false, length = 30)
    private String productId;

    @Column(name = "card_type", nullable = false, length = 10)
    private String cardType;

    @Column(name = "status", nullable = false, length = 10)
    private String status;

    @Column(name = "benefit_style", nullable = false, length = 20)
    private String benefitStyle;

    /** K패스·기후동행 등 정책 카드. 환급형이라 혜택 구조가 다르다. */
    @Column(name = "policy_card", nullable = false)
    private boolean policyCard;

    /**
     * 후불교통 기능. <b>{@code null} 은 '없다'가 아니라 '공시에 안 적혀 있다'</b> 이다 —
     * 모르는 것을 {@code false} 로 적으면 그 자리가 사실이 돼 버린다.
     */
    @Column(name = "has_transit")
    private Boolean hasTransit;

    /** 여신금융협회 심의필 날짜. <b>화면에 나가는 기준일이 이 값이다.</b> */
    @Column(name = "as_of")
    private LocalDate asOf;

    @Column(name = "review_no", length = 60)
    private String reviewNo;

    @Column(name = "posted_at")
    private LocalDate postedAt;

    /** <b>공시 원문 주소다. 신청 링크가 아니다</b> — 화면에 CTA 로 걸지 않는다. */
    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Column(name = "annual_fee_note", length = 400)
    private String annualFeeNote;

    /** 혜택 <b>전체</b>에 걸리는 단서. 계산에 못 넣지만 숨기지도 않는다. */
    @Column(name = "benefit_note", length = 600)
    private String benefitNote;

    @Column(name = "grade", nullable = false, length = 10)
    private String grade = Grade.REFERENCE.name();

    @Column(name = "grade_reason", length = 200)
    private String gradeReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ── 딸린 것들. 카드를 지우면 같이 사라진다(DB 쪽은 ON DELETE CASCADE).
    //    공시를 다시 읽으면 통째로 갈아끼우는 스냅샷이라 부분 갱신을 상정하지 않는다.
    //
    //    ★ @BatchSize 가 붙어 있는 이유 — 이것들을 한 쿼리로 같이 당기면 못 읽는다.
    //    @OrderBy 는 정렬만 정할 뿐 bag(순서 없는 List)을 없애지 않아서, 한 부모에 달린 List 가
    //    둘 이상이면 MultipleBagFetchException 이 난다. 실제로 추천 API 가 이것 때문에 500 이었다
    //    (CardProductRepository#findRecommendable 주석). 대신 나눠 읽되, 카드 한 장마다 한 번씩
    //    나가지 않도록 배치로 묶는다 — 후보가 수십 장이므로 한 배치면 끝난다.

    @OneToMany(mappedBy = "card", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("scope, brand")
    @BatchSize(size = 200)
    private List<CardAnnualFee> annualFees = new ArrayList<>();

    @OneToOne(mappedBy = "card", cascade = CascadeType.ALL, orphanRemoval = true)
    private CardPerformanceRule performanceRule;

    @OneToMany(mappedBy = "card", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("tierNo")
    @BatchSize(size = 200)
    private List<CardPerformanceTier> tiers = new ArrayList<>();

    @OneToMany(mappedBy = "card", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("axis, code")
    @BatchSize(size = 200)
    private List<CardExclusion> exclusions = new ArrayList<>();

    @OneToMany(mappedBy = "card", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortNo")
    @BatchSize(size = 200)
    private List<CardBenefit> benefits = new ArrayList<>();

    @OneToMany(mappedBy = "card", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("groupName")
    @BatchSize(size = 200)
    private List<CardCombinedCap> combinedCaps = new ArrayList<>();

    protected CardProduct() {
    }

    public CardProduct(String issuer, String name, String productId,
                       CardType cardType, Status status, BenefitStyle benefitStyle) {
        this.issuer = issuer;
        this.name = name;
        this.productId = productId;
        this.cardType = cardType.name();
        this.status = status.name();
        this.benefitStyle = benefitStyle.name();
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void describe(Boolean hasTransit, boolean policyCard, LocalDate asOf, String reviewNo,
                         LocalDate postedAt, String sourceUrl, String annualFeeNote, String benefitNote) {
        this.hasTransit = hasTransit;
        this.policyCard = policyCard;
        this.asOf = asOf;
        this.reviewNo = reviewNo;
        this.postedAt = postedAt;
        this.sourceUrl = sourceUrl;
        this.annualFeeNote = annualFeeNote;
        this.benefitNote = benefitNote;
    }

    /** 게이트 3 의 판정을 받아 적는다. 이유는 사람이 볼 단서라 함께 남긴다. */
    public void grade(Grade grade, String reason) {
        this.grade = grade.name();
        this.gradeReason = reason;
    }

    public void add(CardAnnualFee fee) {
        fee.attachTo(this);
        this.annualFees.add(fee);
    }

    public void set(CardPerformanceRule rule) {
        rule.attachTo(this);
        this.performanceRule = rule;
    }

    public void add(CardPerformanceTier tier) {
        tier.attachTo(this);
        this.tiers.add(tier);
    }

    public void add(CardExclusion exclusion) {
        exclusion.attachTo(this);
        this.exclusions.add(exclusion);
    }

    public void add(CardBenefit benefit) {
        benefit.attachTo(this);
        this.benefits.add(benefit);
    }

    public void add(CardCombinedCap cap) {
        cap.attachTo(this);
        this.combinedCaps.add(cap);
    }

    /** 이 실적 금액으로 열리는 구간. 못 채웠으면 {@code null}. */
    public CardPerformanceTier tierFor(long performanceKrw) {
        CardPerformanceTier open = null;
        for (CardPerformanceTier tier : tiers) {
            if (performanceKrw >= tier.getThresholdKrw()) open = tier;
        }
        return open;
    }

    /** 이 축의 제외 항목들 — 실적({@code PERFORMANCE})과 혜택({@code BENEFIT})은 다른 목록이다. */
    public List<CardExclusion> exclusionsOn(CardExclusion.Axis axis) {
        return exclusions.stream().filter(e -> e.getAxis().equals(axis.name())).toList();
    }

    public boolean isPrecise() { return Grade.PRECISE.name().equals(grade); }

    public Long getId() { return id; }
    public String getIssuer() { return issuer; }
    public String getName() { return name; }
    public String getProductId() { return productId; }
    public String getCardType() { return cardType; }
    public String getStatus() { return status; }
    public String getBenefitStyle() { return benefitStyle; }
    public boolean isPolicyCard() { return policyCard; }
    public Boolean getHasTransit() { return hasTransit; }
    public LocalDate getAsOf() { return asOf; }
    public String getReviewNo() { return reviewNo; }
    public LocalDate getPostedAt() { return postedAt; }
    public String getSourceUrl() { return sourceUrl; }
    public String getAnnualFeeNote() { return annualFeeNote; }
    public String getBenefitNote() { return benefitNote; }
    public String getGrade() { return grade; }
    public String getGradeReason() { return gradeReason; }
    public List<CardAnnualFee> getAnnualFees() { return annualFees; }
    public CardPerformanceRule getPerformanceRule() { return performanceRule; }
    public List<CardPerformanceTier> getTiers() { return tiers; }
    public List<CardExclusion> getExclusions() { return exclusions; }
    public List<CardBenefit> getBenefits() { return benefits; }
    public List<CardCombinedCap> getCombinedCaps() { return combinedCaps; }
}
