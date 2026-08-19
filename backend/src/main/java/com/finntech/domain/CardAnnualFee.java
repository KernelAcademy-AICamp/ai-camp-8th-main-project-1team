package com.finntech.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 브랜드별 연회비 — 한 카드에 (국내전용 BC · 해외겸용 Mastercard) 처럼 여러 줄이 붙는다.
 *
 * <p>절감액의 <b>마지막 단계에서 빼는 값</b>이라 정확해야 한다. 연회비를 안 빼면 "아낀다"가
 * 참이 아니게 된다.
 *
 * <p><b>{@code base}·{@code affiliate} 는 없을 수 있다.</b> 총액만 적는 공시가 있어서다.
 * 그래서 {@code total = base + affiliate} 검산을 DB 제약이 아니라 게이트 3(빌드)에서 한다 —
 * 제약으로 걸면 그런 카드는 아예 못 들어온다.
 */
@Entity
@Table(name = "card_annual_fee")
public class CardAnnualFee {

    /** 국내전용 / 해외겸용. */
    public enum Scope { DOMESTIC, GLOBAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "card_id", nullable = false)
    private CardProduct card;

    @Column(name = "scope", nullable = false, length = 10)
    private String scope;

    /** 'BC' · 'Mastercard' · 'Visa'. 원문 표기 그대로. */
    @Column(name = "brand", nullable = false, length = 20)
    private String brand;

    /** 총연회비. 화면에 나가는 값이고 항상 있다. */
    @Column(name = "total", nullable = false)
    private int total;

    @Column(name = "base")
    private Integer base;

    @Column(name = "affiliate")
    private Integer affiliate;

    protected CardAnnualFee() {
    }

    public CardAnnualFee(Scope scope, String brand, int total, Integer base, Integer affiliate) {
        this.scope = scope.name();
        this.brand = brand;
        this.total = total;
        this.base = base;
        this.affiliate = affiliate;
    }

    void attachTo(CardProduct card) {
        this.card = card;
    }

    public Long getId() { return id; }
    public CardProduct getCard() { return card; }
    public String getScope() { return scope; }
    public String getBrand() { return brand; }
    public int getTotal() { return total; }
    public Integer getBase() { return base; }
    public Integer getAffiliate() { return affiliate; }
}
