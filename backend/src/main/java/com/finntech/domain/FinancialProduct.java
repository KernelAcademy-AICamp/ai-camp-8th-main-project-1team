package com.finntech.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * 금융상품. <b>전부 더미다</b> — RFP D18("5개 이상의 더미 금융 상품 데이터")의 근거다.
 *
 * <p>더미인 것은 <b>RFP 요구 때문</b>이지 법 때문이 아니다. 약관 정본 제7조가 운영사에게
 * 대리·중개 <b>권한이 있음</b>을 밝히고 있고(다만 수행하지 않는다), 실제로 카드는
 * 실상품을 쓴다({@code card_product}, V36). 여기가 더미로 남은 것은 이 표가 RFP 제출물
 * 쪽이기 때문이다.
 */
@Entity
@Table(name = "financial_product")
public class FinancialProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Enums.ProductType productType;

    @Column(name = "min_join_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal minJoinAmount;

    @Column(name = "min_period_months", nullable = false)
    private Integer minPeriodMonths;

    /** 연 수익률 또는 혜택률 (%) */
    @Column(name = "expected_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal expectedRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_grade", nullable = false, length = 20)
    private Enums.RiskGrade riskGrade;

    /** 타겟 카테고리 코드. null이면 범용상품(예금/적금)이며 중립값을 받는다. */
    @Column(name = "target_category_code", length = 40)
    private String targetCategoryCode;

    protected FinancialProduct() {}

    public FinancialProduct(String name, Enums.ProductType productType, BigDecimal minJoinAmount,
                            Integer minPeriodMonths, BigDecimal expectedRate,
                            Enums.RiskGrade riskGrade, String targetCategoryCode) {
        this.name = name;
        this.productType = productType;
        this.minJoinAmount = minJoinAmount;
        this.minPeriodMonths = minPeriodMonths;
        this.expectedRate = expectedRate;
        this.riskGrade = riskGrade;
        this.targetCategoryCode = targetCategoryCode;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public Enums.ProductType getProductType() { return productType; }
    public BigDecimal getMinJoinAmount() { return minJoinAmount; }
    public Integer getMinPeriodMonths() { return minPeriodMonths; }
    public BigDecimal getExpectedRate() { return expectedRate; }
    public Enums.RiskGrade getRiskGrade() { return riskGrade; }
    public String getTargetCategoryCode() { return targetCategoryCode; }
}
