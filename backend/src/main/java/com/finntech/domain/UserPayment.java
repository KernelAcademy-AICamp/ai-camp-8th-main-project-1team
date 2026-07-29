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

    /**
     * 적재 키 = {@code 앱사용자id + ":" + 제공자 결제id}. {@link #rowId} 로만 만든다.
     *
     * <p><b>왜 제공자 id를 그대로 쓰지 않는가.</b> 예전에는 제공자가 준 결제 id가 그대로 PK였다.
     * 그러면 <b>앱 사용자가 달라도 같은 신원(CI)이면 행이 하나뿐이라</b>, 두 번째 계정이 연동하는
     * 순간 {@code save()}가 기존 행의 {@code user_id}를 덮어써 먼저 연동한 사람의 화면이 통째로 빈다.
     * 실제로 운영에서 재현했다 — 한 계정의 결제 2,404건이 다른 계정으로 옮겨갔다.
     *
     * <p>데모 신원은 5개뿐이고 보는 사람마다 브라우저가 달라 앱 계정이 따로 생긴다. 두 사람이 같은
     * 페르소나를 고르는 일은 예외가 아니라 <b>기본값</b>이므로, 키를 계정별로 분리한다.
     */
    @Id
    @Column(name = "payment_id", length = 40)
    private String paymentId;

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

    /**
     * 적재 키를 만든다. 적재하는 쪽과 중복을 확인하는 쪽이 <b>같은 함수</b>를 써야 한다 —
     * 한쪽만 규칙이 다르면 이미 있는 행을 못 찾아 같은 결제가 두 번 쌓인다.
     */
    public static String rowId(Long userId, String providerPaymentId) {
        return userId + ":" + providerPaymentId;
    }

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
