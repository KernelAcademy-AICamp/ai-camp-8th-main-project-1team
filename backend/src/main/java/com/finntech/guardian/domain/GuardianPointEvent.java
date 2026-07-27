package com.finntech.guardian.domain;

import com.finntech.guardian.domain.GuardianEnums.PointType;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 지킴이 포인트 적립 (설계서 §3.7).
 *
 * <p>기존 {@code com.finntech.domain.PointEvent}(게임화 저축 루프의 DEPOSIT/WITHDRAWAL)와 <b>다른 축</b>이라
 * 이름을 나눴다. 저쪽은 "아낀 돈이 목표 버킷으로 이동"이고, 이쪽은 "절약 행동의 증명으로 받는 포인트"다.
 *
 * <p>{@code amount}는 규칙상 금액, {@code cappedAmount}는 주간 상한을 적용한 뒤 실제 적립분이다.
 * 둘을 나눠 저장해야 "상한에 얼마나 걸렸는지"를 나중에 볼 수 있다.
 */
@Entity
@Table(name = "guardian_point_event", indexes = {
        @Index(name = "idx_gpoint_week", columnList = "user_id, week_start")
})
public class GuardianPointEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "challenge_id")
    private Long challengeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PointType type;

    /** 규칙에 적힌 금액. */
    @Column(nullable = false)
    private int amount;

    /** 주간 상한 적용 후 실제 적립분. */
    @Column(name = "capped_amount", nullable = false)
    private int cappedAmount;

    /** 주간 상한 계산 키(월요일). 상한 밖 이벤트는 null. */
    @Column(name = "week_start")
    private LocalDate weekStart;

    /** 라벨링이면 거래 id 등. */
    @Column(name = "source_ref")
    private Long sourceRef;

    @Column(name = "confirmed_at", nullable = false)
    private LocalDateTime confirmedAt;

    protected GuardianPointEvent() {}

    public GuardianPointEvent(Long userId, Long challengeId, PointType type, int amount, int cappedAmount,
                              LocalDate weekStart, Long sourceRef, LocalDateTime confirmedAt) {
        this.userId = userId;
        this.challengeId = challengeId;
        this.type = type;
        this.amount = amount;
        this.cappedAmount = cappedAmount;
        this.weekStart = weekStart;
        this.sourceRef = sourceRef;
        this.confirmedAt = confirmedAt;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getChallengeId() { return challengeId; }
    public PointType getType() { return type; }
    public int getAmount() { return amount; }
    public int getCappedAmount() { return cappedAmount; }
    public LocalDate getWeekStart() { return weekStart; }
    public Long getSourceRef() { return sourceRef; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
}
