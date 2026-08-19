package com.finntech.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 전월 실적을 어떻게 세는가 — 카드당 한 행.
 *
 * <p>절감액 계산 1단계가 읽는 곳이다: <i>전달 승인내역 → 실적 제외 빼기 → 전월실적</i>.
 * <b>이 단계를 빼먹으면 실적이 과대 계산된다</b> — 총소비 45만이어도 아파트관리비·공과금·
 * 대중교통이 13만이면 실적은 32만이다.
 *
 * <p><b>{@code basisException} 이 있는 이유.</b> 대개는 승인일 기준인데 해외결제와
 * 무승인결제(대중교통·통신요금·자동납부·기내판매)는 매입일 기준이라 <b>월 귀속이 달라진다</b>.
 * 같은 결제가 다음 달 실적으로 밀린다는 뜻이라, 하한을 말할 때 알고 있어야 한다.
 */
@Entity
@Table(name = "card_performance_rule")
public class CardPerformanceRule {

    /** 승인일 / 매입일. */
    public enum Basis { APPROVAL, PURCHASE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "card_id", nullable = false)
    private CardProduct card;

    /** '전월 1일~말일'. 자연어 그대로 둔다 — 표준화하면 원문과 대조가 안 된다. */
    @Column(name = "period_label", nullable = false, length = 40)
    private String periodLabel;

    @Column(name = "basis", nullable = false, length = 10)
    private String basis;

    @Column(name = "basis_exception", length = 10)
    private String basisException;

    @Column(name = "basis_exception_targets", length = 300)
    private String basisExceptionTargets;

    @Column(name = "includes", length = 200)
    private String includes;

    /**
     * 가족카드 합산 여부. <b>{@code null} 은 '아니다'가 아니라 '공시에 안 적혀 있다'</b> 이다 —
     * BC 3장 중 한 장만 명시했다.
     */
    @Column(name = "includes_family_card")
    private Boolean includesFamilyCard;

    @Column(name = "new_member_grace_until", length = 160)
    private String newMemberGraceUntil;

    /** 유예기간에 적용해 주는 구간(원). 구간 행이 아니라 금액인 것은 적재 순서 때문이다. */
    @Column(name = "new_member_applied_tier_krw")
    private Integer newMemberAppliedTierKrw;

    @Column(name = "new_member_note", length = 300)
    private String newMemberNote;

    protected CardPerformanceRule() {
    }

    public CardPerformanceRule(String periodLabel, Basis basis,
                               Basis basisException, String basisExceptionTargets,
                               String includes, Boolean includesFamilyCard) {
        this.periodLabel = periodLabel;
        this.basis = basis.name();
        this.basisException = basisException == null ? null : basisException.name();
        this.basisExceptionTargets = basisExceptionTargets;
        this.includes = includes;
        this.includesFamilyCard = includesFamilyCard;
    }

    void attachTo(CardProduct card) {
        this.card = card;
    }

    public void graceForNewMember(String until, Integer appliedTierKrw, String note) {
        this.newMemberGraceUntil = until;
        this.newMemberAppliedTierKrw = appliedTierKrw;
        this.newMemberNote = note;
    }

    public Long getId() { return id; }
    public CardProduct getCard() { return card; }
    public String getPeriodLabel() { return periodLabel; }
    public String getBasis() { return basis; }
    public String getBasisException() { return basisException; }
    public String getBasisExceptionTargets() { return basisExceptionTargets; }
    public String getIncludes() { return includes; }
    public Boolean getIncludesFamilyCard() { return includesFamilyCard; }
    public String getNewMemberGraceUntil() { return newMemberGraceUntil; }
    public Integer getNewMemberAppliedTierKrw() { return newMemberAppliedTierKrw; }
    public String getNewMemberNote() { return newMemberNote; }
}
