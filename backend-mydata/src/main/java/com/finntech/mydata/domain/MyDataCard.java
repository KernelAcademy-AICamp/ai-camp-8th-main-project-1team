package com.finntech.mydata.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 마이데이터 카드 (mydata_card). 사용자가 실제로 발급받은 카드 인스턴스. */
@Entity
@Table(name = "mydata_card")
public class MyDataCard {

    @Id
    @Column(name = "mydata_card_id", length = 24)
    private String id; // 카드 serial (NNNN-NNNN-NNNN-NNNN)

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mydata_user_id", nullable = false)
    private MyDataUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_code", nullable = false)
    private CardProduct cardProduct;

    @Column(name = "mydata_card_expiration_date", nullable = false)
    private LocalDate expirationDate;

    /** 전월 실적액(원) — 혜택 구간을 결정한다. */
    @Column(name = "mydata_card_prev_month_amount", nullable = false)
    private int prevMonthAmount;

    @OneToMany(mappedBy = "card", fetch = FetchType.LAZY)
    private List<MyDataPayment> payments = new ArrayList<>();

    protected MyDataCard() {}

    public MyDataCard(String id, MyDataUser user, CardProduct cardProduct,
                      LocalDate expirationDate, int prevMonthAmount) {
        this.id = id;
        this.user = user;
        this.cardProduct = cardProduct;
        this.expirationDate = expirationDate;
        this.prevMonthAmount = prevMonthAmount;
    }

    public String getId() { return id; }
    public MyDataUser getUser() { return user; }
    public CardProduct getCardProduct() { return cardProduct; }
    public LocalDate getExpirationDate() { return expirationDate; }
    public int getPrevMonthAmount() { return prevMonthAmount; }
    public List<MyDataPayment> getPayments() { return payments; }
}
