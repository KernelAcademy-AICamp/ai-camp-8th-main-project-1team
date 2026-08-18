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
 * 통합한도 — 개별 한도 위에 한 겹 더.
 *
 * <p><b>페이북 실측이 왜 필요한지 보여 준다.</b> 종합몰·패션몰·생활몰이 각 5,000원인데 셋을
 * 합친 '특별적립' 통합한도가 13,000원이다. <b>개별 합(15,000)이 통합(13,000)을 넘으므로
 * 절삭 순서가 결과를 바꾼다</b> — 계산은 건당 → 월(개별) → 통합 순으로 자른다(07 §4.4).
 *
 * <p>어느 혜택이 이 묶음에 드는지는 {@link CardBenefit#getCombinedCapGroup()} 가 말한다.
 */
@Entity
@Table(name = "card_combined_cap")
public class CardCombinedCap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "card_id", nullable = false)
    private CardProduct card;

    /** {@code CardBenefit.combinedCapGroup} 과 맞물리는 이름. '특별적립'. */
    @Column(name = "group_name", nullable = false, length = 40)
    private String groupName;

    // 구간을 함께 저장한다 — 카드 한 장이 통째로 들어오므로 구간도 아직 저장 전이다.
    // 없으면 flush 에서 TransientPropertyValueException 이 난다(2026-08-11 실측).
    @ManyToOne(fetch = FetchType.LAZY, optional = false, cascade = CascadeType.PERSIST)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "tier_id", nullable = false)
    private CardPerformanceTier tier;

    @Column(name = "cap_krw", nullable = false)
    private int capKrw;

    protected CardCombinedCap() {
    }

    public CardCombinedCap(String groupName, CardPerformanceTier tier, int capKrw) {
        this.groupName = groupName;
        this.tier = tier;
        this.capKrw = capKrw;
    }

    void attachTo(CardProduct card) {
        this.card = card;
    }

    public Long getId() { return id; }
    public CardProduct getCard() { return card; }
    public String getGroupName() { return groupName; }
    public CardPerformanceTier getTier() { return tier; }
    public int getCapKrw() { return capKrw; }
}
