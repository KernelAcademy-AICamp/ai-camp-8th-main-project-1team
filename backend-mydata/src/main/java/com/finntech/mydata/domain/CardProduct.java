package com.finntech.mydata.domain;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

/** 카드 상품 (card). 카드사가 발급하는 카드 종류 — 카테고리별 혜택(실적구간)을 갖는다. */
@Entity
@Table(name = "card")
public class CardProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "card_code")
    private Long code;

    @Column(name = "card_name", nullable = false, length = 60)
    private String name;

    @Column(name = "card_img_url", length = 255)
    private String imgUrl;

    /** 카드 페이스 색(프론트 표시용). */
    @Column(name = "card_color", length = 20)
    private String color;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_company_id", nullable = false)
    private CardCompany cardCompany;

    @OneToMany(mappedBy = "cardProduct", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<CardBenefit> benefits = new ArrayList<>();

    protected CardProduct() {}

    public CardProduct(String name, String imgUrl, String color, CardCompany cardCompany) {
        this.name = name;
        this.imgUrl = imgUrl;
        this.color = color;
        this.cardCompany = cardCompany;
    }

    public void addBenefit(CardBenefit benefit) { benefits.add(benefit); }

    public Long getCode() { return code; }
    public String getName() { return name; }
    public String getImgUrl() { return imgUrl; }
    public String getColor() { return color; }
    public CardCompany getCardCompany() { return cardCompany; }
    public List<CardBenefit> getBenefits() { return benefits; }
}
