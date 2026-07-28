package com.finntech.guardian.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 가상 시계 — 데모에서 "다음 날로 이동"을 가능하게 하는 장치 (설계서 §0).
 *
 * <p>지킴이 코드 어디서도 {@code LocalDateTime.now()}를 직접 부르지 않는다. 반드시
 * {@code GuardianClock.now(userId)}를 거친다. 이걸 처음부터 정해두면 30일짜리 챌린지를
 * 발표 5분 안에 시연할 수 있고, 나중에 고치려면 전부 뒤져야 한다.
 *
 * <p>주입된 {@link java.time.Clock} 빈(마스터 §4 원칙 3) 위에 <b>사용자별 오프셋</b>을 더하는
 * 구조라, 테스트의 고정 Clock과 데모의 시간 전진이 서로를 방해하지 않는다.
 */
@Entity
@Table(name = "guardian_demo_clock", indexes = {
        @Index(name = "idx_gclock_user", columnList = "user_id", unique = true)
})
public class DemoClock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "virtual_offset_seconds", nullable = false)
    private long virtualOffsetSeconds;

    @Column(name = "demo_mode", nullable = false)
    private boolean demoMode;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected DemoClock() {}

    public DemoClock(Long userId, boolean demoMode, LocalDateTime now) {
        this.userId = userId;
        this.demoMode = demoMode;
        this.updatedAt = now;
    }

    /** 가상 시계를 앞으로 민다. 되감기는 지원하지 않는다(원장이 이미 그 시각으로 확정됐다). */
    public void advanceDays(int days, LocalDateTime now) {
        if (days <= 0) throw new IllegalArgumentException("전진 일수는 1 이상이어야 해요");
        this.virtualOffsetSeconds += days * 86_400L;
        this.demoMode = true;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public long getVirtualOffsetSeconds() { return virtualOffsetSeconds; }
    public boolean isDemoMode() { return demoMode; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
