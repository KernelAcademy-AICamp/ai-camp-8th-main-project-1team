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

    /**
     * 전월 실적액 — <b>불러온 지난달 승인액 합.</b>
     *
     * <p>제공자가 주던 값이었는데, 실 마이데이터 카드 API 에 그런 필드가 없어 없앴다.
     * 이제 당월 실적과 같은 방식으로 우리가 센다.
     *
     * <p><b>승인액 전액 기준이라 실제 카드사 실적과는 다르다.</b> 카드사는 세금·공과금·상품권·
     * 무이자할부·해외이용분 따위를 실적에서 빼는데, 그 목록은 카드마다 달라 카드 혜택 룰이
     * 갖춰져야 반영할 수 있다. 그때까지 이 값은 <b>상한</b>으로만 읽어야 한다 —
     * "채웠다"고 말하면 안 되고 "아직 모자라다"만 말할 수 있다.
     */
    @Column(name = "prev_performance", nullable = false)
    private int prevPerformance;

    /** 당월 실적액(불러온 이번달 결제 합). 위와 같은 한계를 갖는다. */
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
