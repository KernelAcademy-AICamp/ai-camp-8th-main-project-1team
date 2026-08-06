package com.finntech.guardian.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 챌린지 안의 카테고리 하나 — 그 카테고리의 기준 지출·지킬 돈·예산.
 *
 * <p>예전에는 예산이 챌린지에 숫자 하나뿐이라, 화면이 카테고리별로 보여줄 때 <b>균등분할</b>했다.
 * 사용자가 배달에 10만·카페에 3만을 정했는데 화면은 6.5만씩으로 보여준 셈이다.
 *
 * <p><b>여기 있는 예산은 판정에 쓰지 않는다.</b> 챌린지의 성공/실패와 잔디는 여전히 합계 기준이다
 * (사용자 결정 2026-07-31). 카테고리로 실패까지 가르면 카테고리 수만큼 실패 확률이 오르는데,
 * 이 앱은 낙인을 피하는 것을 설계 원칙으로 삼는다. 이 값의 쓰임은 <b>어디서 새는지 보여주고
 * 알리는 것</b>이다.
 *
 * <p>빌려 쓰기는 없다 — 카페가 남아도 배달 예산으로 넘기지 않는다. 넘길 수 있으면 봉투가 아니라
 * 그냥 합계 예산과 같아진다.
 */
@Entity
@Table(name = "guardian_challenge_category",
        uniqueConstraints = @UniqueConstraint(columnNames = {"challenge_id", "category"}),
        indexes = @Index(name = "idx_challenge_category", columnList = "challenge_id"))
public class GuardianChallengeCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "challenge_id", nullable = false)
    private Long challengeId;

    @Column(nullable = false, length = 40)
    private String category;

    /** 그 카테고리의 기준 지출(최근 30일 실측). */
    @Column(nullable = false)
    private long baseline;

    /** 그 카테고리에서 지키기로 한 돈. */
    @Column(nullable = false)
    private long target;

    /** 예산 = 기준 − 지킬 돈. */
    @Column(nullable = false)
    private long cap;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected GuardianChallengeCategory() {}

    public GuardianChallengeCategory(Long challengeId, String category,
                                     long baseline, long target, LocalDateTime now) {
        this.challengeId = challengeId;
        this.category = category;
        this.baseline = baseline;
        this.target = target;
        this.cap = Math.max(0L, baseline - target);
        this.createdAt = now;
    }

    public Long getId() { return id; }
    public Long getChallengeId() { return challengeId; }
    public String getCategory() { return category; }
    public long getBaseline() { return baseline; }
    public long getTarget() { return target; }
    public long getCap() { return cap; }

    /**
     * 이 카테고리에서 지킬 돈을 다시 정한다 (마이 &gt; 챌린지 관리).
     *
     * <p><b>진행 중에도 바꿀 수 있어야 한다.</b> 강도는 한 달을 견딜 수 있는지 해보기 전에는
     * 모르는 값이라, 처음에 고른 것으로 못 박으면 너무 빡빡하게 잡은 사람은 포기하고
     * 너무 헐겁게 잡은 사람은 아무것도 안 바뀐다.
     *
     * <p><b>기준 지출은 건드리지 않는다.</b> 그건 실측이라 사용자가 정할 값이 아니다.
     * 지킬 돈은 [0, 기준) 안에서만 — 기준과 같아지면 예산이 0원이 되어 첫 결제에 바로 터진다.
     */
    public void retarget(long newTarget) {
        long safe = Math.max(0L, Math.min(newTarget, Math.max(0L, baseline - 1)));
        this.target = safe;
        this.cap = Math.max(0L, baseline - safe);
    }
}
