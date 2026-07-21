package com.finntech.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * 사용자. 실명·이메일·계좌·카드·주민번호 필드를 두지 않는다 (문서 §5-3, RFP D26).
 * 식별자는 닉네임 기반 익명 계정이다.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 익명 닉네임 */
    @Column(nullable = false, unique = true, length = 40)
    private String nickname;

    /** 월 소득 — 저축진행률 계산용 */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal monthlyIncome = BigDecimal.ZERO;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal goalAmount = BigDecimal.ZERO;

    /** 목표 기간(개월) */
    @Column(nullable = false)
    private Integer goalMonths = 6;

    /** 개인정보 수집 동의 여부 — 미동의 시 더미 데모 모드 */
    @Column(nullable = false)
    private boolean consentGiven = false;

    /**
     * 마이데이터 연동용 CI (§13). 실 NICE 인증값이 아니라 본인인증으로 받은 <b>가상 생성값</b>이다.
     * 마이데이터(더미) 서버 조회 키로만 쓴다. 미연동이면 null.
     * <p>전화번호는 CI 계산에만 쓰고 <b>저장하지 않는다</b>(현 스텁 단계 '전화번호 실수집 없음', §13-2).
     * 실 coolsms 도입 시 그때 전화번호 필드·처리방침을 추가한다.
     */
    @Column(length = 64)
    private String ci;

    protected AppUser() {}

    public AppUser(String nickname, BigDecimal monthlyIncome, BigDecimal goalAmount, Integer goalMonths) {
        this.nickname = nickname;
        this.monthlyIncome = monthlyIncome;
        this.goalAmount = goalAmount;
        this.goalMonths = goalMonths;
    }

    public Long getId() { return id; }
    public String getNickname() { return nickname; }
    public BigDecimal getMonthlyIncome() { return monthlyIncome; }
    public void setMonthlyIncome(BigDecimal v) { this.monthlyIncome = v; }
    public BigDecimal getGoalAmount() { return goalAmount; }
    public void setGoalAmount(BigDecimal v) { this.goalAmount = v; }
    public Integer getGoalMonths() { return goalMonths; }
    public void setGoalMonths(Integer v) { this.goalMonths = v; }
    public boolean isConsentGiven() { return consentGiven; }
    public void setConsentGiven(boolean v) { this.consentGiven = v; }
    public String getCi() { return ci; }
    public void setCi(String v) { this.ci = v; }
}
