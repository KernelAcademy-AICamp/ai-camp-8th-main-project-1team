package com.finntech.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 혜택 묶음의 <b>구간별</b> 월 한도.
 *
 * <p>한도가 구간 행({@link CardPerformanceTier})을 가리키므로 <b>"tiers 에 없는 키의 한도"가
 * 못 들어온다</b>. 지시서 게이트 3 의 검산 하나가 여기서는 규칙이 아니라 스키마다.
 *
 * <p>한도가 없는 혜택은 이 행이 <b>0개</b>다 — 0원 한도와 다르다.
 */
@Entity
@Table(name = "card_benefit_cap")
public class CardBenefitCap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "benefit_id", nullable = false)
    private CardBenefit benefit;

    // 구간을 함께 저장한다 — 카드 한 장이 통째로 들어오므로 구간도 아직 저장 전이다.
    // 없으면 flush 에서 TransientPropertyValueException 이 난다(2026-08-11 실측).
    @ManyToOne(fetch = FetchType.LAZY, optional = false, cascade = CascadeType.PERSIST)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "tier_id", nullable = false)
    private CardPerformanceTier tier;

    @Column(name = "cap_krw", nullable = false)
    private int capKrw;

    protected CardBenefitCap() {
    }

    public CardBenefitCap(CardPerformanceTier tier, int capKrw) {
        this.tier = tier;
        this.capKrw = capKrw;
    }

    void attachTo(CardBenefit benefit) {
        this.benefit = benefit;
    }

    public Long getId() { return id; }
    public CardBenefit getBenefit() { return benefit; }
    public CardPerformanceTier getTier() { return tier; }
    public int getCapKrw() { return capKrw; }
}
