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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 혜택 묶음 하나 — 절감액 계산에서 <b>소비를 분배받는 단위</b>다.
 *
 * <p><b>한 결제는 한 묶음에만 간다.</b> 그래서 공시가 적은 배타 관계({@link #exclusiveWith},
 * 예: 쇼픽 ↔ 카카오페이 기본적립)를 계산에 쓰지 않고 원문으로만 남긴다 — 분배가 이미 하나를
 * 고르므로 이중 계산이 구조적으로 안 난다.
 *
 * <p><b>{@link #countable} 이 이 클래스의 핵심 판단이다.</b> 공시의 혜택 중에는 금액으로
 * 옮길 수 없는 것이 섞여 있고, 셋 다 계산에서 빼되 화면에는 그대로 보인다.
 *
 * <pre>
 *   무이자할부·비금전(라운지)   금액 환산 자체가 안 된다
 *   매칭 대상이 없는 혜택        '온누리상품권 가맹점'·'해외 가맹점' — 승인내역에서 못 고른다
 *   결제수단 조건               '간편결제 경유 시 제외' — 승인내역에 결제수단 칸이 없다
 * </pre>
 *
 * 빼면 절감액이 <b>하한 방향</b>으로 틀린다. "채운 줄 알았는데 못 채웠다"가 구조적으로 안 나는
 * 쪽이라 이 방향을 고른다(07 §4.4).
 *
 * <p><b>실적 충족은 한도를 여는 것이지 한도를 받는 게 아니다.</b> 실적 30만을 채워 한도
 * 5,000원이 열려도 커피에 2만원 썼으면 받는 건 1,400원이다 — {@code min(소비 × 요율, 한도)}.
 */
@Entity
@Table(name = "card_benefit")
public class CardBenefit {

    /** 할인 / 적립 / 무이자할부 / 비금전. 뒤 둘은 {@link #countable} 이 꺼진다. */
    public enum Kind { DISCOUNT, POINT, INSTALLMENT_FREE, NON_MONETARY }

    /**
     * 대상의 성격. {@code ALL} 은 "특정 대상이 아니라 나머지 전부"라 화면에서 다르게 읽어야
     * 한다(페이북 '기본 적립 1%').
     */
    public enum Scope { BRAND, AXIS, ALL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "card_id", nullable = false)
    private CardProduct card;

    /** 공시가 쓰는 묶음 이름 그대로. 'EAT-ZONE' · '먹픽' · '기본 적립'. */
    @Column(name = "group_name", nullable = false, length = 40)
    private String groupName;

    @Column(name = "kind", nullable = false, length = 20)
    private String kind;

    /** '결제일 할인' 처럼 언제 돌려주는가. */
    @Column(name = "settle", length = 30)
    private String settle;

    @Column(name = "scope", nullable = false, length = 10)
    private String scope;

    @Column(name = "rate_percent", precision = 5, scale = 2)
    private BigDecimal ratePercent;

    /** '최대 1% (0.5% 또는 1%)' 처럼 조건에 따라 두 값이 갈리는 것. 단일 숫자로 못 담는다. */
    @Column(name = "rate_conditional", length = 60)
    private String rateConditional;

    /** 정액 할인(원). '5,000원 이상 결제 시 5,000원 할인'은 %로 표현이 안 된다. */
    @Column(name = "amount_krw")
    private Integer amountKrw;

    @Column(name = "min_amount_per_txn")
    private Integer minAmountPerTxn;

    /** 이 혜택이 열리는 최소 구간. {@code null} 이면 실적과 무관하다(해외 적립 등). */
    // 구간을 함께 저장한다 — 카드 한 장이 통째로 들어오므로 구간도 아직 저장 전이다.
    // 없으면 flush 에서 TransientPropertyValueException 이 난다(2026-08-11 실측).
    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.PERSIST)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "requires_tier_id")
    private CardPerformanceTier requiresTier;

    /** 통합한도 묶음 이름. 같은 값을 가진 혜택들이 {@link CardCombinedCap} 을 함께 쓴다. */
    @Column(name = "combined_cap_group", length = 40)
    private String combinedCapGroup;

    /** 원(현금성) / '페이북 머니' / '카카오페이포인트'. */
    @Column(name = "unit", nullable = false, length = 30)
    private String unit = "원";

    /**
     * 제3자 포인트면 그 사업자. 카카오페이포인트는 <b>카드사 밖에서 정산된다</b> —
     * 소멸·전환 조건이 우리 손 밖이라 현금과 같은 값으로 말하면 안 된다는 표시다.
     */
    @Column(name = "unit_third_party", length = 30)
    private String unitThirdParty;

    /**
     * 대상 목록이 닫혔는가. '편의점, 영화, 주유 <b>등</b>' 처럼 끝나면 {@code false} 이고,
     * 그러면 하한 계산에서 <b>나열된 것만</b> 센다.
     */
    @Column(name = "targets_complete", nullable = false)
    private boolean targetsComplete = true;

    /** '간편결제 경유 시 제외' 같은 결제수단 조건. 승인내역으로 <b>판정 불가</b>다. */
    @Column(name = "pay_channel", length = 60)
    private String payChannel;

    /** 하한 계산에 넣는가. 위 클래스 주석의 세 경우가 {@code false} 다. */
    @Column(name = "countable", nullable = false)
    private boolean countable = true;

    /** 요약에 노출할 혜택인가. 카드 상세가 혜택 여섯 중 넷만 '주요 혜택'으로 보여 준다. */
    @Column(name = "is_headline", nullable = false)
    private boolean headline;

    /** 공시의 단서들. 계산에 안 쓰고 표시·검수용이다. */
    @Column(name = "conditions_text", length = 65535)
    private String conditionsText;

    /** 공시가 적은 배타 관계. <b>계산에 쓰지 않는다</b>(위 주석). */
    @Column(name = "exclusive_with", length = 200)
    private String exclusiveWith;

    /** 화면·계산 순서를 고정한다(원칙 3 — 조회 정렬 고정). */
    @Column(name = "sort_no", nullable = false)
    private int sortNo;

    // ★ 이 둘을 한 쿼리로 같이 당기면 MultipleBagFetchException 이 난다 — 한 부모에 달린 bag 이
    //   둘이기 때문이고, @OrderBy 로는 안 없어진다. 나눠 읽되 배치로 묶는다(CardProduct 주석).
    @OneToMany(mappedBy = "benefit", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    @BatchSize(size = 500)
    private List<CardBenefitCap> caps = new ArrayList<>();

    @OneToMany(mappedBy = "benefit", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("targetGroup, kind, value")
    @BatchSize(size = 500)
    private List<CardBenefitTarget> targets = new ArrayList<>();

    protected CardBenefit() {
    }

    public CardBenefit(String groupName, Kind kind, Scope scope, int sortNo) {
        this.groupName = groupName;
        this.kind = kind.name();
        this.scope = scope.name();
        this.sortNo = sortNo;
    }

    void attachTo(CardProduct card) {
        this.card = card;
    }

    public void rate(BigDecimal ratePercent, String rateConditional,
                     Integer amountKrw, Integer minAmountPerTxn) {
        this.ratePercent = ratePercent;
        this.rateConditional = rateConditional;
        this.amountKrw = amountKrw;
        this.minAmountPerTxn = minAmountPerTxn;
    }

    public void conditions(CardPerformanceTier requiresTier, String combinedCapGroup,
                           String unit, String unitThirdParty, boolean targetsComplete,
                           String payChannel, boolean countable, boolean headline,
                           String conditionsText, String exclusiveWith, String settle) {
        this.requiresTier = requiresTier;
        this.combinedCapGroup = combinedCapGroup;
        if (unit != null) this.unit = unit;
        this.unitThirdParty = unitThirdParty;
        this.targetsComplete = targetsComplete;
        this.payChannel = payChannel;
        this.countable = countable;
        this.headline = headline;
        this.conditionsText = conditionsText;
        this.exclusiveWith = exclusiveWith;
        this.settle = settle;
    }

    public void add(CardBenefitCap cap) {
        cap.attachTo(this);
        this.caps.add(cap);
    }

    public void add(CardBenefitTarget target) {
        target.attachTo(this);
        this.targets.add(target);
    }

    /**
     * 이 구간에서 열리는 월 한도(원). 한도 행이 없으면 {@code null} — <b>한도 없음이지
     * 0원 한도가 아니다</b>(페이북 기본 적립: '적립 한도 없음').
     */
    public Integer capFor(CardPerformanceTier tier) {
        if (tier == null) return null;
        for (CardBenefitCap cap : caps) {
            // 실적 금액으로 맞춘다. id 로 맞추면 <b>아직 저장 안 된 객체에서 터진다</b>(id 가 null).
            // 같은 카드에 같은 금액의 구간은 둘일 수 없으므로(V34 의 uk_card_tier_threshold)
            // 금액이 id 만큼이나 확실한 키다.
            if (cap.getTier().getThresholdKrw() == tier.getThresholdKrw()) return cap.getCapKrw();
        }
        return null;
    }

    /** 이 실적 구간에서 혜택이 열리는가. 구간 조건이 없으면 언제나 열린다. */
    public boolean opensAt(CardPerformanceTier reached) {
        if (requiresTier == null) return true;
        return reached != null && reached.getThresholdKrw() >= requiresTier.getThresholdKrw();
    }

    public Long getId() { return id; }
    public CardProduct getCard() { return card; }
    public String getGroupName() { return groupName; }
    public String getKind() { return kind; }
    public String getSettle() { return settle; }
    public String getScope() { return scope; }
    public BigDecimal getRatePercent() { return ratePercent; }
    public String getRateConditional() { return rateConditional; }
    public Integer getAmountKrw() { return amountKrw; }
    public Integer getMinAmountPerTxn() { return minAmountPerTxn; }
    public CardPerformanceTier getRequiresTier() { return requiresTier; }
    public String getCombinedCapGroup() { return combinedCapGroup; }
    public String getUnit() { return unit; }
    public String getUnitThirdParty() { return unitThirdParty; }
    public boolean isTargetsComplete() { return targetsComplete; }
    public String getPayChannel() { return payChannel; }
    public boolean isCountable() { return countable; }
    public boolean isHeadline() { return headline; }
    public String getConditionsText() { return conditionsText; }
    public String getExclusiveWith() { return exclusiveWith; }
    public int getSortNo() { return sortNo; }
    public List<CardBenefitCap> getCaps() { return caps; }
    public List<CardBenefitTarget> getTargets() { return targets; }
}
