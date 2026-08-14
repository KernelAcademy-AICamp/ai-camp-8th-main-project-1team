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
 * 혜택이 걸리는 대상 하나 — <b>브랜드와 축이 섞인다.</b>
 *
 * <p><b>판정은 브랜드가 1순위, 축이 2순위다</b>(07 §4.4). 업종코드로 못 푸는 축이 있어서다 —
 * 배달의민족은 <i>통신판매업</i>으로 등록돼 업종으로는 '쇼핑'이 되고, 넷플릭스와 일부 PG 는
 * <i>같은 코드 724000</i> 을 쓴다.
 *
 * <p><b>축은 국세청 6자리가 아니라 '카드혜택 축' 이름이다.</b> 카드 공시도 "시내버스·지하철"
 * 처럼 축의 언어로 적지 6자리로 적지 않는다. 잇는 길은 이렇다.
 *
 * <pre>
 *   user_payment.ksic_code(6자리)
 *     → industry-mid.json 의 cardAxisByIndustry
 *     → 카드혜택 축 21종 ('대중교통' · '카페/디저트' · '편의점' …)
 *     → 이 행의 value (kind = AXIS)
 * </pre>
 *
 * <p><b>채널·제외장소가 행마다 되풀이된다.</b> 정규화하면 표가 하나 더 늘고 매칭이 조인을 한 번
 * 더 탄다. 이 표를 읽는 쪽은 "스타벅스"를 찾아 <b>그 한 행에서</b> 채널까지 받아야 한다 —
 * 되풀이가 비용이 아니라 목적이다.
 */
@Entity
@Table(name = "card_benefit_target")
public class CardBenefitTarget {

    /**
     * {@code BRAND} 브랜드명(1순위) · {@code AXIS} 카드혜택 축(2순위) ·
     * {@code ALL} 전 가맹점('국내 가맹점'·'국내/외 모든 가맹점') ·
     * {@code SCOPE} 넷 다 아닌 서술('해외 가맹점'). {@code SCOPE} 는 매칭에 안 쓰고 표시만 한다.
     *
     * <p><b>{@code ALL} 과 {@code SCOPE} 를 가르는 이유.</b> 페이북 '기본 적립 1%'처럼 전
     * 가맹점이 대상인 혜택은 <b>셀 것이 없는 게 아니라 전부가 대상</b>이다. 둘을 뭉쳐 놓으면
     * 기본 적립이 통째로 계산에서 빠진다. 반대로 '해외 가맹점'은 국내 승인내역으로 판정할 수
     * 없어 표시만 한다 — 같은 "브랜드도 축도 아님"이지만 계산에서의 자리가 정반대다.
     *
     * <p>{@code ALL} 은 {@code build_catalog.py} 가 붙인다(그 파일의 {@code ALL_MERCHANT}).
     * <b>여기 없으면 카탈로그 적재가 {@code No enum constant} 로 죽고, 기동이 통째로 막힌다</b>
     * — 2026-08-14 실제로 발생해 스프링 컨텍스트를 쓰는 시험 90개가 함께 넘어갔다.
     */
    public enum Kind { BRAND, AXIS, ALL, SCOPE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "benefit_id", nullable = false)
    private CardBenefit benefit;

    /**
     * 공시가 묶어 놓은 소분류. '커피' · '간식' · '배달' · '온쇼(온라인쇼핑)'.
     * 같은 브랜드가 두 묶음에 들 수 있어(KaPick 올리브영: 온쇼·오쇼) 키의 일부다.
     */
    @Column(name = "target_group", nullable = false, length = 40)
    private String targetGroup = "";

    @Column(name = "kind", nullable = false, length = 10)
    private String kind;

    @Column(name = "target_value", nullable = false, length = 80)
    private String value;

    /** '오프라인 현장결제, 브랜드 공식앱'. 채널이 갈리면 같은 브랜드도 혜택이 갈린다. */
    @Column(name = "channel", length = 120)
    private String channel;

    /**
     * '백화점, 면세점, 할인점, 공항, 기차역, 임대매장'. 승인내역만으로는 대개 판정이 안 되지만,
     * 안 적어 두면 우리가 <b>과대 추정</b>한 줄도 모른다.
     */
    @Column(name = "exclude_place", length = 200)
    private String excludePlace;

    @Column(name = "note", length = 300)
    private String note;

    protected CardBenefitTarget() {
    }

    public CardBenefitTarget(String targetGroup, Kind kind, String value,
                             String channel, String excludePlace, String note) {
        this.targetGroup = targetGroup == null ? "" : targetGroup;
        this.kind = kind.name();
        this.value = value;
        this.channel = channel;
        this.excludePlace = excludePlace;
        this.note = note;
    }

    void attachTo(CardBenefit benefit) {
        this.benefit = benefit;
    }

    public Long getId() { return id; }
    public CardBenefit getBenefit() { return benefit; }
    public String getTargetGroup() { return targetGroup; }
    public String getKind() { return kind; }
    public String getValue() { return value; }
    public String getChannel() { return channel; }
    public String getExcludePlace() { return excludePlace; }
    public String getNote() { return note; }
}
