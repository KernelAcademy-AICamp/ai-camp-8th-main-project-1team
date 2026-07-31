package com.finntech.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 가맹점 하나에 대한 그 사용자의 <b>낭비 판정 성향</b>.
 *
 * <p>온보딩에서 "이건 낭비가 아니다"로 뺀 결제는 그동안 그 챌린지 한 번에만 반영됐다. 다음 달이면
 * 같은 가게가 다시 낭비로 떠서 사용자가 같은 것을 또 빼야 했다.
 *
 * <p>그렇다고 한 번 뺐다고 가맹점을 통째로 제외하면 안 된다 — <b>같은 가게에서 낭비 목적으로 살
 * 수도 있다</b>(사용자 지적 2026-07-31). 자격증 책을 사던 서점에서 만화책을 몰아 살 수 있다.
 * 그래서 임계를 <b>올리는</b> 단계를 사이에 둔다.
 *
 * <p>되돌릴 길도 둔다. "낭비 맞음"을 한 번 누르면 한 단계 내려온다 — 한 번 새어나간 지출이
 * 영영 안 잡히면 그것이 더 나쁘다.
 */
@Entity
@Table(name = "user_merchant_stance",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "business_number"}))
public class UserMerchantStance {

    /** 판정을 얼마나 엄격하게 할 것인가. */
    public enum Stance {
        /** 전역 임계 그대로. */
        NORMAL,
        /** 임계를 δ 만큼 올린다 — 확실할 때만 낭비로 본다. */
        LENIENT,
        /** 낭비로 보지 않는다. */
        EXCLUDED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 1차 지표 — 사업자등록번호. 브랜드는 원장에 없어 나중에 2차로 붙인다(사용자 결정). */
    @Column(name = "business_number", nullable = false, length = 10)
    private String businessNumber;

    /** 화면 표시용. 판정에는 쓰지 않는다 — 상호는 표기가 흔들리기 때문이다. */
    @Column(name = "merchant_name", length = 60)
    private String merchantName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Stance stance = Stance.NORMAL;

    /** '낭비 아님'을 누른 횟수. 문턱을 넘으면 다음 단계로 간다. */
    @Column(name = "kept_count", nullable = false)
    private int keptCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected UserMerchantStance() {}

    public UserMerchantStance(Long userId, String businessNumber, String merchantName, LocalDateTime now) {
        this.userId = userId;
        this.businessNumber = businessNumber;
        this.merchantName = merchantName;
        this.stance = Stance.NORMAL;
        this.keptCount = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * "이건 낭비가 아니다" 한 번 — 횟수를 세고, 문턱을 넘으면 한 단계 올린다.
     *
     * @param toLenient  NORMAL → LENIENT 로 가는 데 필요한 횟수
     * @param toExcluded LENIENT → EXCLUDED 로 가는 데 필요한 <b>누적</b> 횟수
     */
    public void kept(int toLenient, int toExcluded, LocalDateTime now) {
        keptCount++;
        if (keptCount >= toExcluded) stance = Stance.EXCLUDED;
        else if (keptCount >= toLenient) stance = Stance.LENIENT;
        updatedAt = now;
    }

    /**
     * "역시 낭비였다" — 한 단계 내려오고 횟수를 문턱 아래로 되돌린다.
     *
     * <p>0으로 되돌리지 않는 이유: EXCLUDED에서 한 번 되돌렸는데 다시 0부터 세면, 사용자가
     * 쌓아 온 판단이 통째로 사라진다. 한 칸만 물러선다.
     */
    public void notKept(int toLenient, int toExcluded) {
        stance = switch (stance) {
            case EXCLUDED -> { keptCount = Math.max(0, toExcluded - 1); yield Stance.LENIENT; }
            case LENIENT -> { keptCount = Math.max(0, toLenient - 1); yield Stance.NORMAL; }
            case NORMAL -> { keptCount = 0; yield Stance.NORMAL; }
        };
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getBusinessNumber() { return businessNumber; }
    public String getMerchantName() { return merchantName; }
    public Stance getStance() { return stance; }
    public int getKeptCount() { return keptCount; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
