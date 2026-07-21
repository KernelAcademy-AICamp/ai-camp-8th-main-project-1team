package com.finntech.domain;

import jakarta.persistence.*;

/**
 * 마이데이터에서 불러온 보유 카드 (§13). 마이데이터 표준의 보유카드(user_card) 구조.
 * 실 카드번호가 아니라 마이데이터 서버가 준 serial과 상품 메타·실적만 보관한다.
 */
@Entity
@Table(name = "user_card", indexes = {
        @Index(name = "idx_user_card_user", columnList = "user_id"),
        @Index(name = "uk_user_card_serial", columnList = "user_id, serial_number", unique = true)
})
public class UserCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "serial_number", nullable = false, length = 24)
    private String serialNumber;

    @Column(name = "card_code", nullable = false)
    private Long cardCode;

    @Column(name = "card_name", nullable = false, length = 60)
    private String cardName;

    @Column(name = "card_color", length = 20)
    private String cardColor;

    @Column(name = "company_name", length = 40)
    private String companyName;

    /** 전월 실적액(마이데이터 제공). */
    @Column(name = "prev_performance", nullable = false)
    private int prevPerformance;

    /** 당월 실적액(불러온 이번달 결제 합). */
    @Column(name = "current_performance", nullable = false)
    private int currentPerformance;

    /** 혜택 실적 요건(원) — 카드 혜택 구간의 하한. 0이면 조건 없음. */
    @Column(name = "requirement", nullable = false)
    private int requirement;

    protected UserCard() {}

    public UserCard(Long userId, String serialNumber, Long cardCode, String cardName, String cardColor,
                    String companyName, int prevPerformance, int currentPerformance, int requirement) {
        this.userId = userId;
        this.serialNumber = serialNumber;
        this.cardCode = cardCode;
        this.cardName = cardName;
        this.cardColor = cardColor;
        this.companyName = companyName;
        this.prevPerformance = prevPerformance;
        this.currentPerformance = currentPerformance;
        this.requirement = requirement;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getSerialNumber() { return serialNumber; }
    public Long getCardCode() { return cardCode; }
    public String getCardName() { return cardName; }
    public String getCardColor() { return cardColor; }
    public String getCompanyName() { return companyName; }
    public int getPrevPerformance() { return prevPerformance; }
    public int getCurrentPerformance() { return currentPerformance; }
    public void setCurrentPerformance(int value) { this.currentPerformance = value; }
    public int getRequirement() { return requirement; }
}
