package com.finntech.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 절약 후보 선택 추적(⑤) — 사용자가 "이 소비를 줄이겠다"고 고른 후보와 월말 재검증 결과를 보관한다.
 *
 * <p>개인 소비 결정 정보이므로 삭제권(방침6) 대상이다 — {@code PrivacyService.eraseUserData}에서 함께 파기한다.
 * 재검증은 월말 스냅샷 1회(마스터 결정 3): 선택 시점 {@code baselineSpend} 대비 재검증 시점 {@code actualSpend}로
 * 개선 여부를 판정한다.
 */
@Entity
@Table(name = "cut_candidate_selection", indexes = {
        @Index(name = "idx_cut_selection_user", columnList = "user_id, status")
})
public class CutCandidateSelection {

    public enum Type { REMOVABLE, OPTIMIZABLE }

    public enum Status { ACTIVE, VERIFIED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "category2", nullable = false, length = 30)
    private String category2;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private Type type;

    /** 선택 시점 예상 절감액(원). */
    @Column(name = "target_saving", nullable = false)
    private long targetSaving;

    /** 선택 시점 해당 category2 창 지출(원) — 재검증 기준선. */
    @Column(name = "baseline_spend", nullable = false)
    private long baselineSpend;

    @Column(name = "selected_at", nullable = false)
    private LocalDateTime selectedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    /** 재검증 시점 해당 category2 창 지출(원). */
    @Column(name = "actual_spend")
    private Long actualSpend;

    /** 재검증 결과: baselineSpend보다 줄었는가. */
    @Column(name = "improved")
    private Boolean improved;

    protected CutCandidateSelection() {}

    public CutCandidateSelection(Long userId, String category2, Type type, long targetSaving,
                                 long baselineSpend, LocalDateTime selectedAt) {
        this.userId = userId;
        this.category2 = category2;
        this.type = type;
        this.targetSaving = targetSaving;
        this.baselineSpend = baselineSpend;
        this.selectedAt = selectedAt;
        this.status = Status.ACTIVE;
    }

    /** 월말 재검증 반영 — 실제 지출을 기록하고 개선 여부를 확정한다. */
    public void verify(long actualSpend, LocalDateTime at) {
        this.actualSpend = actualSpend;
        this.improved = actualSpend < baselineSpend;
        this.verifiedAt = at;
        this.status = Status.VERIFIED;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getCategory2() { return category2; }
    public Type getType() { return type; }
    public long getTargetSaving() { return targetSaving; }
    public long getBaselineSpend() { return baselineSpend; }
    public LocalDateTime getSelectedAt() { return selectedAt; }
    public Status getStatus() { return status; }
    public LocalDateTime getVerifiedAt() { return verifiedAt; }
    public Long getActualSpend() { return actualSpend; }
    public Boolean getImproved() { return improved; }
}
