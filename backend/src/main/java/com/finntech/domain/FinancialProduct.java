package com.finntech.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * 금융상품. <b>전부 더미다</b> — RFP D18("5개 이상의 더미 금융 상품 데이터")이자
 * D-04 규제 방어 논거("실재하지 않는 더미 상품을 쓰므로 영업이 아니다")의 근거다.
 * 실상품 데이터를 넣는 순간 금소법 중개업 판단 대상이 된다 (D-12 참조).
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
