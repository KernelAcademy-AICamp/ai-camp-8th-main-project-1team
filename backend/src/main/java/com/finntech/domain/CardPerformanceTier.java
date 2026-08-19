package com.finntech.domain;

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
 * 실적 구간 한 단 — <b>개수가 카드마다 다르다</b>(2~4단). 그래서 배열이 아니라 행이다.
 *
 * <p>구간이 행이라 한도를 여기에 매달 수 있고({@link CardBenefitCap}), 그 덕에 <b>"없는 구간의
 * 한도"가 구조적으로 못 들어온다</b> — 지시서 게이트 3 의 <i>monthly_cap_by_tier 키 != tiers</i>
 * 검산이 여기서는 규칙이 아니라 스키마다.
 *
 * <p>실적 조건이 아예 없는 카드는 이 행이 하나도 없다.
 */
@Entity
@Table(name = "card_performance_tier")
public class CardPerformanceTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "card_id", nullable = false)
    private CardProduct card;

    /** 1부터. 정렬을 이 값으로 고정한다(원칙 3 재현성). */
    @Column(name = "tier_no", nullable = false)
    private int tierNo;

    /** 이 구간이 열리는 최소 실적(원). */
    @Column(name = "threshold_krw", nullable = false)
    private int thresholdKrw;

    protected CardPerformanceTier() {
    }

    public CardPerformanceTier(int tierNo, int thresholdKrw) {
        this.tierNo = tierNo;
        this.thresholdKrw = thresholdKrw;
    }

    void attachTo(CardProduct card) {
        this.card = card;
    }

    public Long getId() { return id; }
    public CardProduct getCard() { return card; }
    public int getTierNo() { return tierNo; }
    public int getThresholdKrw() { return thresholdKrw; }
}
