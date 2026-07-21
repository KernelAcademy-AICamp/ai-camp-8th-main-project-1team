package com.finntech.mydata.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 마이데이터 결제내역 (mydata_payment). 카드 사용 1건 = 소비내역 1건. */
@Entity
@Table(name = "mydata_payment", indexes = {
        @Index(name = "idx_mydata_payment_card_date", columnList = "mydata_card_id, mydata_payment_date")
})
public class MyDataPayment {

    @Id
    @Column(name = "mydata_payment_id", length = 40)
    private String id; // uuid

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mydata_card_id", nullable = false)
    private MyDataCard card;

    @Column(name = "mydata_payment_date", nullable = false)
    private LocalDateTime paymentDate;

    @Column(name = "mydata_payment_category1", nullable = false, length = 30)
    private String category1;

    @Column(name = "mydata_payment_category2", length = 30)
    private String category2;

    @Column(name = "mydata_payment_amount", nullable = false)
    private int amount;

    @Column(name = "mydata_payment_merchant_name", length = 60)
    private String merchantName;

    /** 이 결제로 받은 혜택 금액(원) — 생성 시 실적구간 대조로 계산해 저장. */
    @Column(name = "mydata_payment_received_benefit_amount", nullable = false)
    private int receivedBenefitAmount;

    protected MyDataPayment() {}

    public MyDataPayment(String id, MyDataCard card, LocalDateTime paymentDate,
                         String category1, String category2, int amount,
                         String merchantName, int receivedBenefitAmount) {
        this.id = id;
        this.card = card;
        this.paymentDate = paymentDate;
        this.category1 = category1;
        this.category2 = category2;
        this.amount = amount;
        this.merchantName = merchantName;
        this.receivedBenefitAmount = receivedBenefitAmount;
    }

    public String getId() { return id; }
    public MyDataCard getCard() { return card; }
    public LocalDateTime getPaymentDate() { return paymentDate; }
    public String getCategory1() { return category1; }
    public String getCategory2() { return category2; }
    public int getAmount() { return amount; }
    public String getMerchantName() { return merchantName; }
    public int getReceivedBenefitAmount() { return receivedBenefitAmount; }
}
