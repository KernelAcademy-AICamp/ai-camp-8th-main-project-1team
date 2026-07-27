package com.finntech.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 마이데이터에서 불러온 카드 결제내역 (§13). 마이데이터 표준의 결제내역(user_payment) 구조.
 * '내 카드'·'내 소비' 화면의 원천이며, 동시에 {@code Consumption(source=MYDATA)}로도 투영돼 기존 엔진에 재사용된다.
 */
@Entity
@Table(name = "user_payment", indexes = {
        @Index(name = "idx_user_payment_user_date", columnList = "user_id, payment_date")
})
public class UserPayment {

    @Id
    @Column(name = "payment_id", length = 40)
    private String paymentId; // 마이데이터 서버가 준 결제 id (멱등 적재 키)

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "card_serial", nullable = false, length = 24)
    private String cardSerial;

    @Column(name = "card_code", nullable = false)
    private Long cardCode;

    @Column(name = "payment_date", nullable = false)
    private LocalDateTime paymentDate;

    @Column(name = "category1", nullable = false, length = 30)
    private String category1;

    @Column(name = "category2", length = 30)
    private String category2;

    @Column(nullable = false)
    private int amount;

    @Column(name = "merchant_name", length = 60)
    private String merchantName;

    @Column(name = "received_benefit", nullable = false)
    private int receivedBenefit;

    /** 가맹점 사업자등록번호 10자리(마이데이터에서 전달). 사용자는 이 번호로 가맹점 주소를 조회한다(§13). */
    @Column(name = "business_number", length = 10)
    private String businessNumber;

    protected UserPayment() {}

    public UserPayment(String paymentId, Long userId, String cardSerial, Long cardCode,
                       LocalDateTime paymentDate, String category1, String category2,
                       int amount, String merchantName, int receivedBenefit, String businessNumber) {
        this.paymentId = paymentId;
        this.userId = userId;
        this.cardSerial = cardSerial;
        this.cardCode = cardCode;
        this.paymentDate = paymentDate;
        this.category1 = category1;
        this.category2 = category2;
        this.amount = amount;
        this.merchantName = merchantName;
        this.receivedBenefit = receivedBenefit;
        this.businessNumber = businessNumber;
    }

    public String getPaymentId() { return paymentId; }
    public Long getUserId() { return userId; }
    public String getCardSerial() { return cardSerial; }
    public Long getCardCode() { return cardCode; }
    public LocalDateTime getPaymentDate() { return paymentDate; }
    public String getCategory1() { return category1; }
    public String getCategory2() { return category2; }
    public int getAmount() { return amount; }
    public String getMerchantName() { return merchantName; }
    public int getReceivedBenefit() { return receivedBenefit; }
    public String getBusinessNumber() { return businessNumber; }
}
