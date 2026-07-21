package com.finntech.mydata.domain;

import jakarta.persistence.*;

/** 카드사 (card_company). 마이데이터 연동 시 사용자가 고르는 '기관' 목록. */
@Entity
@Table(name = "card_company")
public class CardCompany {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "card_company_id")
    private Long id;

    @Column(name = "card_company_name", nullable = false, length = 40)
    private String name;

    @Column(name = "card_company_img_url", length = 255)
    private String imgUrl;

    protected CardCompany() {}

    public CardCompany(String name, String imgUrl) {
        this.name = name;
        this.imgUrl = imgUrl;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getImgUrl() { return imgUrl; }
}
