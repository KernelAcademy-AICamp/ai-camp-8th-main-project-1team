package com.finntech.mydata.domain;

import jakarta.persistence.*;

/**
 * 카드 혜택 (card_benefit). 카테고리(대분류)별·전월실적 구간별 할인율과 월한도.
 * 실적구간(performance band) 기반 혜택 모델.
 */
@Entity
@Table(name = "card_benefit")
public class CardBenefit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "card_benefit_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_code", nullable = false)
    private CardProduct cardProduct;

    /** 대분류 이름(온라인·쇼핑·생활·식비·여가·카페/간식·편의점). 판단 로직엔 박지 않고 데이터로만 쓴다. */
    /** 혜택 대상 — 우리 소비 중분류(식비·카페/간식 등). 예전에는 7대분류였다. */
    @Column(name = "mid_category", nullable = false, length = 30)
    private String midCategory;

    /** 1회당 할인/적립률(%). */
    @Column(name = "card_benefit_discount_percent", nullable = false)
    private int discountPercent;

    /** 전월실적 구간 [start, end] (원). end=0이면 상한 없음. */
    @Column(name = "card_benefit_performance_start", nullable = false)
    private int performanceStart;

    @Column(name = "card_benefit_performance_end", nullable = false)
    private int performanceEnd;

    /** 월 할인한도(원). */
    @Column(name = "card_benefit_limit", nullable = false)
    private int monthlyLimit;

    protected CardBenefit() {}

    public CardBenefit(CardProduct cardProduct, String midCategory, int discountPercent,
                       int performanceStart, int performanceEnd, int monthlyLimit) {
        this.cardProduct = cardProduct;
        this.midCategory = midCategory;
        this.discountPercent = discountPercent;
        this.performanceStart = performanceStart;
        this.performanceEnd = performanceEnd;
        this.monthlyLimit = monthlyLimit;
    }

    // 실적 구간 판정(coversPerformance)은 여기 두지 않는다 — 전월 실적액을 제공자가 주지
    // 않으므로 판정할 입력이 없다. 실적은 승인내역에서 계산하는 쪽의 몫이고, 이 클래스는
    // 구간의 경계값(performanceStart/End)만 들고 있는다.

    public Long getId() { return id; }
    public CardProduct getCardProduct() { return cardProduct; }
    public String getMidCategory() { return midCategory; }
    public int getDiscountPercent() { return discountPercent; }
    public int getPerformanceStart() { return performanceStart; }
    public int getPerformanceEnd() { return performanceEnd; }
    public int getMonthlyLimit() { return monthlyLimit; }
}
