package com.finntech.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 충동예산 절약통 상태 (마스터 §5-5, 2026-07-21 방향 전환).
 *
 * <p><b>모델.</b> 예산 = 사용자가 '충동'으로 지정한 카테고리의 월 평균 지출(=안 쓰면 모을 수 있는 돈).
 * 선물상자는 <b>시간에 따라 자동 성장</b>한다 — 하루 할당량을 방문마다 50→30→20%로 드러내고, 안 들어온 날의
 * 할당량은 다음 방문에 합산된다(여러 날 비우면 더 크게). 충동소비를 기록하면 그만큼 <b>균열</b>(잔액 차감).
 * 성장은 {@link java.time.Clock} 기준 <b>결정론적</b>이라 저장 상태(마지막 방문일·오늘 드러낸 비율·잔액)만으로 재현된다(§4).
 */
@Entity
@Table(name = "impulse_saver_state", indexes = { @Index(name = "idx_iss_user", columnList = "user_id", unique = true) })
public class ImpulseSaverState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** '충동'으로 지정한 카테고리 코드(CSV). 예: "CAFE,SHOPPING". */
    @Column(name = "impulse_categories", length = 400)
    private String impulseCategories;

    /** 자동 성장으로 쌓인(그리고 충동소비로 깎인) 현재 잔액. */
    @Column(name = "gift_balance", nullable = false, precision = 15, scale = 2)
    private BigDecimal giftBalance = BigDecimal.ZERO;

    /** 절약통을 시작한 날(성장 기준점). */
    @Column(name = "start_date")
    private LocalDate startDate;

    /** 마지막으로 성장시킨 날(방문일). */
    @Column(name = "last_visit_date")
    private LocalDate lastVisitDate;

    /** 오늘 하루 할당량 중 이미 드러낸 비율(0·0.5·0.8·1.0). */
    @Column(name = "today_fraction", nullable = false)
    private double todayFraction = 0.0;

    protected ImpulseSaverState() {}

    public ImpulseSaverState(Long userId) { this.userId = userId; }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getImpulseCategories() { return impulseCategories; }
    public void setImpulseCategories(String v) { this.impulseCategories = v; }
    public BigDecimal getGiftBalance() { return giftBalance; }
    public void setGiftBalance(BigDecimal v) { this.giftBalance = v; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate v) { this.startDate = v; }
    public LocalDate getLastVisitDate() { return lastVisitDate; }
    public void setLastVisitDate(LocalDate v) { this.lastVisitDate = v; }
    public double getTodayFraction() { return todayFraction; }
    public void setTodayFraction(double v) { this.todayFraction = v; }
}
