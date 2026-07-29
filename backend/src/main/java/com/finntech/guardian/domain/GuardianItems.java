package com.finntech.guardian.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 보유 아이템 · 포인트 잔액 (설계서 §3.7).
 *
 * <p>서포트의 '패스(쿠폰)'와 마이룸의 '불가피 태그'는 <b>면제권 하나로 통합</b>됐다.
 * 같은 일(이 결제를 판정에서 빼기)을 하는 장치가 둘이면 사용자가 어느 쪽을 쓸지 고민하게 된다.
 */
@Entity
@Table(name = "guardian_items", indexes = {
        @Index(name = "idx_gitems_user", columnList = "user_id", unique = true)
})
public class GuardianItems {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** 면제권 — 결제 1건을 판정에서 뺀다. */
    @Column(nullable = false)
    private int exemption;

    /** 잔디 보호권 — 미지급 날의 잔디 연속을 유지시킨다(실제 무지출 연속일은 건드리지 않는다). */
    @Column(name = "grass_guard", nullable = false)
    private int grassGuard;

    /** 미션 변경권. */
    @Column(name = "mission_change", nullable = false)
    private int missionChange;

    @Column(name = "point_balance", nullable = false)
    private int pointBalance;

    @Column(name = "auto_use_grass_guard", nullable = false)
    private boolean autoUseGrassGuard;

    /**
     * 이미 받은 도감 마일스톤(종수 CSV, 예: {@code "10,15"}).
     *
     * <p><b>소유 종수로는 '받았는가'를 알 수 없다.</b> 종수는 "열렸는가"만 말한다 — 그것만 보고
     * 판정했더니 21종을 모은 사용자가 세 보상 전부 '받음'으로 표시된 채 <b>한 장도 못 받고</b>
     * 끝났고(청구 버튼이 안 뜬다), 반대로 청구 API는 부를수록 계속 지급됐다.
     */
    @Column(name = "claimed_milestones", nullable = false, length = 100)
    private String claimedMilestones = "";

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected GuardianItems() {}

    public GuardianItems(Long userId, LocalDateTime now) {
        this.userId = userId;
        this.updatedAt = now;
    }

    /** 면제권 1장 소모. 없으면 false. */
    public boolean useExemption(LocalDateTime now) {
        if (exemption <= 0) return false;
        exemption--;
        updatedAt = now;
        return true;
    }

    /** 잔디 보호권 1장 소모. 없으면 false. */
    public boolean useGrassGuard(LocalDateTime now) {
        if (grassGuard <= 0) return false;
        grassGuard--;
        updatedAt = now;
        return true;
    }

    public void addPoints(int delta, LocalDateTime now) {
        this.pointBalance = Math.max(0, this.pointBalance + delta);
        this.updatedAt = now;
    }

    public void grant(int exemption, int grassGuard, int missionChange, LocalDateTime now) {
        this.exemption += exemption;
        this.grassGuard += grassGuard;
        this.missionChange += missionChange;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public int getExemption() { return exemption; }
    public int getGrassGuard() { return grassGuard; }
    public int getMissionChange() { return missionChange; }
    public int getPointBalance() { return pointBalance; }
    public boolean isAutoUseGrassGuard() { return autoUseGrassGuard; }
    public void setAutoUseGrassGuard(boolean v) { this.autoUseGrassGuard = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    /** 그 마일스톤을 이미 받았는가. */
    public boolean hasClaimed(int count) {
        for (String p : claimedMilestones.split(",")) {
            if (p.trim().equals(String.valueOf(count))) return true;
        }
        return false;
    }

    /** 받았다고 표시한다. 이미 있으면 아무 일도 하지 않는다(멱등). */
    public void markClaimed(int count, LocalDateTime now) {
        if (hasClaimed(count)) return;
        claimedMilestones = claimedMilestones.isEmpty()
                ? String.valueOf(count) : claimedMilestones + "," + count;
        updatedAt = now;
    }
}
