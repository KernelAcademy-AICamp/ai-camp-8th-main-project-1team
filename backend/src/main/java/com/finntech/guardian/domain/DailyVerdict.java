package com.finntech.guardian.domain;

import com.finntech.guardian.domain.GuardianEnums.DailyResult;
import com.finntech.guardian.domain.GuardianEnums.Grade;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

/**
 * 일 판정 — <b>반드시 스냅샷과 함께 저장한다</b> (설계서 §3.5).
 *
 * <p>스냅샷이 없으면 "그날 왜 사물을 못 받았지"를 나중에 답할 수 없다. 챌린지의 현재 상태는
 * 계속 변하므로, 판정 시점의 지출·비율·페이스를 그 자리에서 박제해야 추적이 가능하다.
 *
 * <p>등급 가중치는 설계서에서 jsonb였으나 Postgres 종속을 피하려고 세 컬럼으로 폈다.
 * 값이 세 개로 고정이라 잃는 것이 없고, 오히려 집계 쿼리가 가능해진다.
 */
@Entity
@Table(name = "guardian_daily_verdict",
        uniqueConstraints = @UniqueConstraint(name = "uk_gverdict_challenge_date",
                columnNames = {"challenge_id", "verdict_date"}),
        indexes = @Index(name = "idx_gverdict_user_date", columnList = "user_id, verdict_date"))
public class DailyVerdict {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "challenge_id", nullable = false)
    private Long challengeId;

    @Column(name = "verdict_date", nullable = false)
    private LocalDate verdictDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DailyResult result;

    @Column(name = "grant_object", nullable = false)
    private boolean grantObject;

    @Column(name = "weight_common")
    private Double weightCommon;

    @Column(name = "weight_rare")
    private Double weightRare;

    @Column(name = "weight_epic")
    private Double weightEpic;

    /** 예: NO_SPEND_STREAK_4 — 사물 획득 사유 문구의 근거가 된다. */
    @Column(name = "reason_code", length = 40)
    private String reasonCode;

    // ---- 판정 당시 스냅샷 (추적용) ----------------------------------------

    @Column(name = "spent_at_date", nullable = false)
    private long spentAtDate;

    @Column(name = "spent_ratio", nullable = false)
    private double spentRatio;

    @Column(name = "pace_ratio", nullable = false)
    private double paceRatio;

    @Column(name = "allowed_ratio", nullable = false)
    private double allowedRatio;

    @Column(name = "no_spend_streak", nullable = false)
    private int noSpendStreak;

    // ---- 추첨 결과 --------------------------------------------------------

    @Column(name = "granted_object_id", length = 60)
    private String grantedObjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "granted_grade", length = 10)
    private Grade grantedGrade;

    @Column(nullable = false)
    private boolean rerolled;

    /**
     * 세리머니 모달을 자동으로 띄울지 (스펙 v1.5 §5.3).
     * 매일 띄우면 연출이 광고처럼 읽힌다 — 처음 일주일과 희귀 이상일 때만 자동이고,
     * 나머지는 미개봉 뱃지로 쌓아 두고 사용자가 열게 한다.
     */
    @Column(name = "ceremony_auto_open", nullable = false)
    private boolean ceremonyAutoOpen;

    // ---- 아침 세리머니 (푸시 아님 — 앱을 열면 뜨는 모달) --------------------

    @Column(name = "ceremony_message", length = 200)
    private String ceremonyMessage;

    @Column(name = "ceremony_seen_at")
    private LocalDateTime ceremonySeenAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected DailyVerdict() {}

    public DailyVerdict(Long userId, Long challengeId, LocalDate verdictDate, DailyResult result,
                        boolean grantObject, Map<Grade, Double> gradeWeights, String reasonCode,
                        long spentAtDate, double spentRatio, double paceRatio, double allowedRatio,
                        int noSpendStreak, LocalDateTime createdAt) {
        this.userId = userId;
        this.challengeId = challengeId;
        this.verdictDate = verdictDate;
        this.result = result;
        this.grantObject = grantObject;
        setGradeWeights(gradeWeights);
        this.reasonCode = reasonCode;
        this.spentAtDate = spentAtDate;
        this.spentRatio = spentRatio;
        this.paceRatio = paceRatio;
        this.allowedRatio = allowedRatio;
        this.noSpendStreak = noSpendStreak;
        this.createdAt = createdAt;
    }

    public final void setGradeWeights(Map<Grade, Double> w) {
        this.weightCommon = w == null ? null : w.get(Grade.COMMON);
        this.weightRare = w == null ? null : w.get(Grade.RARE);
        this.weightEpic = w == null ? null : w.get(Grade.EPIC);
    }

    /** 저장된 가중치를 다시 맵으로. 미지급이면 null. */
    @Transient
    public Map<Grade, Double> getGradeWeights() {
        if (weightCommon == null) return null;
        Map<Grade, Double> m = new EnumMap<>(Grade.class);
        m.put(Grade.COMMON, weightCommon);
        m.put(Grade.RARE, weightRare);
        m.put(Grade.EPIC, weightEpic);
        return m;
    }

    public void grant(String objectId, Grade grade) {
        this.grantedObjectId = objectId;
        this.grantedGrade = grade;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getChallengeId() { return challengeId; }
    public LocalDate getVerdictDate() { return verdictDate; }
    public DailyResult getResult() { return result; }
    public boolean isGrantObject() { return grantObject; }
    public String getReasonCode() { return reasonCode; }
    public long getSpentAtDate() { return spentAtDate; }
    public double getSpentRatio() { return spentRatio; }
    public double getPaceRatio() { return paceRatio; }
    public double getAllowedRatio() { return allowedRatio; }
    public int getNoSpendStreak() { return noSpendStreak; }
    public String getGrantedObjectId() { return grantedObjectId; }
    public Grade getGrantedGrade() { return grantedGrade; }
    public boolean isRerolled() { return rerolled; }
    public void setRerolled(boolean v) { this.rerolled = v; }
    public boolean isCeremonyAutoOpen() { return ceremonyAutoOpen; }
    public void setCeremonyAutoOpen(boolean v) { this.ceremonyAutoOpen = v; }
    public String getCeremonyMessage() { return ceremonyMessage; }
    public void setCeremonyMessage(String v) { this.ceremonyMessage = v; }
    public LocalDateTime getCeremonySeenAt() { return ceremonySeenAt; }
    public void setCeremonySeenAt(LocalDateTime v) { this.ceremonySeenAt = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
