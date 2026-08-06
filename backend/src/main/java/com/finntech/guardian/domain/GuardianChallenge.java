package com.finntech.guardian.domain;

import com.finntech.guardian.domain.GuardianEnums.ChallengeState;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 지킴이 챌린지 — 설정 + 현재 상태 (지킴이 Agent 설계서 §3.3·§8).
 *
 * <p><b>예산 = 기준 지출 − 지킬 돈.</b> 설계서의 Postgres {@code generated always as} 컬럼은
 * JPA·H2·MySQL에서 이식성이 없으므로 생성 시점에 계산해 저장한다(불변값이라 안전하다).
 *
 * <p>사용자당 진행 중인 챌린지는 하나뿐이다. 설계서의 부분 유니크 인덱스
 * ({@code where state in (...)})는 MySQL·H2가 지원하지 않으므로 서비스 계층에서 강제한다.
 */
@Entity
@Table(name = "guardian_challenge", indexes = {
        @Index(name = "idx_gch_user_state", columnList = "user_id, state")
})
public class GuardianChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChallengeState state = ChallengeState.SETUP;

    // ---- 설정 ------------------------------------------------------------

    /** 줄이기로 한 카테고리 코드(CSV). 코드에 카테고리 이름을 박지 않는다(마스터 §4 원칙 4). */
    @Column(nullable = false, length = 400)
    private String categories;

    /** 성역 카테고리(CSV) — 챌린지 대상이어도 판정에서 제외하고 침묵한다. */
    @Column(name = "sanctuary_categories", length = 400)
    private String sanctuaryCategories;

    /** 기준 지출 — ① 분석이 낸 카테고리 월평균 합. */
    @Column(name = "baseline_amount", nullable = false)
    private long baselineAmount;

    /** 지킬 돈. */
    @Column(name = "target_saving", nullable = false)
    private long targetSaving;

    /** 챌린지 예산 = baselineAmount − targetSaving. 생성 시 확정. */
    @Column(name = "challenge_cap", nullable = false)
    private long challengeCap;

    /** 페이스 버퍼 = min(0.20, 평균 결제액 / 예산). */
    @Column(name = "buffer_ratio", nullable = false)
    private double bufferRatio;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    // ---- 진행 상태 --------------------------------------------------------

    @Column(name = "spent_amount", nullable = false)
    private long spentAmount;

    /** 실제 무지출 연속일 — C5·희귀 확률의 기준. */
    @Column(name = "no_spend_streak", nullable = false)
    private int noSpendStreak;

    @Column(name = "no_spend_streak_best", nullable = false)
    private int noSpendStreakBest;

    /** 잔디 표시용 연속일 — 보호권으로 유지될 수 있어 실제 연속일과 갈라진다. */
    @Column(name = "grass_streak", nullable = false)
    private int grassStreak;

    @Column(name = "grass_protected_days", nullable = false)
    private int grassProtectedDays;

    // ---- 보상 목표 --------------------------------------------------------

    @Column(name = "reward_name", length = 60)
    private String rewardName;

    @Column(name = "reward_price")
    private Long rewardPrice;

    /** 목표 재조정 시 v2, v3... */
    @Column(name = "round_no", nullable = false)
    private int roundNo = 1;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    protected GuardianChallenge() {}

    public GuardianChallenge(Long userId, List<String> categories, List<String> sanctuaryCategories,
                             long baselineAmount, long targetSaving, double bufferRatio,
                             LocalDate startDate, LocalDate endDate,
                             String rewardName, Long rewardPrice, LocalDateTime createdAt) {
        if (baselineAmount <= targetSaving) {
            throw new IllegalArgumentException("지킬 돈은 기준 지출보다 작아야 해요");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("종료일이 시작일보다 빠를 수 없어요");
        }
        this.userId = userId;
        this.categories = joinCsv(categories);
        this.sanctuaryCategories = joinCsv(sanctuaryCategories);
        this.baselineAmount = baselineAmount;
        this.targetSaving = targetSaving;
        this.challengeCap = baselineAmount - targetSaving;
        this.bufferRatio = bufferRatio;
        this.startDate = startDate;
        this.endDate = endDate;
        this.rewardName = rewardName;
        this.rewardPrice = rewardPrice;
        this.createdAt = createdAt;
        this.state = ChallengeState.ACTIVE;
    }

    /** 총 일수 = (종료일 − 시작일) + 1. 설계서의 생성 컬럼을 파생 계산으로 옮긴 것. */
    @Transient
    public int getDaysTotal() {
        return (int) (endDate.toEpochDay() - startDate.toEpochDay()) + 1;
    }

    /** 판정 대상 날짜 기준 경과일. 범위를 [0, daysTotal]로 자른다. */
    @Transient
    public int daysElapsedOn(LocalDate date) {
        long raw = date.toEpochDay() - startDate.toEpochDay() + 1;
        return (int) Math.max(0, Math.min(getDaysTotal(), raw));
    }

    @Transient
    public Set<String> getCategorySet() { return parseCsv(categories); }

    /**
     * 줄일 카테고리를 하나 더한다 (마이 &gt; 챌린지 관리 &gt; 새 챌린지 만들기).
     *
     * <p><b>진행 중인 챌린지에 붙인다.</b> 카테고리마다 챌린지를 따로 만들면 기간이 제각각이 되어
     * "이번 달"이라는 말이 뜻을 잃는다. 화면에서는 줄이 하나 늘어난 것으로 보이지만,
     * 안에서는 같은 챌린지의 카테고리가 늘어난 것이다.
     *
     * <p>기간·보상은 건드리지 않는다. 새로 더한 카테고리도 <b>같은 날 끝난다</b> — 그래야
     * 월말 결산이 한 번에 이뤄진다.
     */
    public void addCategory(String category) {
        Set<String> now = new java.util.LinkedHashSet<>(parseCsv(categories));
        now.add(category);
        this.categories = joinCsv(new java.util.ArrayList<>(now));
    }

    /** 기준 지출·지킬 돈을 늘어난 카테고리만큼 키운다. 예산은 그 차이라 함께 다시 센다. */
    public void growBaseline(long addBaseline, long addTarget) {
        this.baselineAmount += addBaseline;
        this.targetSaving += addTarget;
        this.challengeCap = Math.max(0L, this.baselineAmount - this.targetSaving);
    }

    @Transient
    public Set<String> getSanctuarySet() { return parseCsv(sanctuaryCategories); }

    /**
     * 성역을 다시 정한다 (마이 &gt; 설정 &gt; 성역 관리).
     *
     * <p><b>진행 중에도 바꿀 수 있어야 한다.</b> 성역은 "지킴이가 침묵할 곳"이라는 약속인데,
     * 챌린지를 만들 때 한 번만 정할 수 있으면 잘못 고른 사람은 한 달을 견뎌야 한다.
     *
     * <p><b>줄이기로 한 카테고리는 성역이 될 수 없다.</b> 둘 다이면 "줄이라고 하면서 침묵한다"가
     * 되어 앞뒤가 안 맞는다. 겹치는 것은 호출부가 걸러 보낸다.
     */
    public void setSanctuaryCategories(List<String> categories) {
        this.sanctuaryCategories = joinCsv(categories);
    }

    @Transient
    public boolean isRunning() {
        return state == ChallengeState.ACTIVE || state == ChallengeState.AT_RISK
                || state == ChallengeState.EXCEEDED;
    }

    private static String joinCsv(List<String> v) {
        return v == null || v.isEmpty() ? "" : String.join(",", v);
    }

    private static Set<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return new LinkedHashSet<>(Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public ChallengeState getState() { return state; }
    public void setState(ChallengeState v) { this.state = v; }
    public String getCategories() { return categories; }
    public String getSanctuaryCategories() { return sanctuaryCategories; }
    public long getBaselineAmount() { return baselineAmount; }
    public long getTargetSaving() { return targetSaving; }
    public long getChallengeCap() { return challengeCap; }
    public double getBufferRatio() { return bufferRatio; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public long getSpentAmount() { return spentAmount; }
    public void setSpentAmount(long v) { this.spentAmount = Math.max(0L, v); }
    public int getNoSpendStreak() { return noSpendStreak; }
    public void setNoSpendStreak(int v) {
        this.noSpendStreak = v;
        this.noSpendStreakBest = Math.max(this.noSpendStreakBest, v);
    }
    public int getNoSpendStreakBest() { return noSpendStreakBest; }
    public int getGrassStreak() { return grassStreak; }
    public void setGrassStreak(int v) { this.grassStreak = v; }
    public int getGrassProtectedDays() { return grassProtectedDays; }
    public void setGrassProtectedDays(int v) { this.grassProtectedDays = v; }
    public String getRewardName() { return rewardName; }
    public Long getRewardPrice() { return rewardPrice; }
    public int getRoundNo() { return roundNo; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getSettledAt() { return settledAt; }
    public void setSettledAt(LocalDateTime v) { this.settledAt = v; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime v) { this.closedAt = v; }
}
