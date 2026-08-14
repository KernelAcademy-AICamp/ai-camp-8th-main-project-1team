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
 * 제외 항목 한 줄 — <b>실적 축과 혜택 축이 한 표에 있고 {@link Axis} 로만 갈린다.</b>
 *
 * <p><b>두 축이 정말 다르다는 증거가 무이자할부다.</b> BC 바로 ZONE 은 무이자할부를 혜택에서만
 * 빼고 실적에는 넣는데, BC 바로 KaPick 은 둘 다 뺀다. 한 표에 축을 두면 그 차이가 두 행으로
 * 그냥 보인다.
 *
 * <p><b>카드마다 다르므로 공통 목록으로 뭉치면 안 된다.</b> ZONE 은 대중교통을 실적에서
 * 빼는데 KaPick 은 안 뺀다 — 그리고 대중교통을 혜택 대상('생픽')으로 삼는다.
 *
 * <p>실적 제외가 5개 미만이면 게이트 3 이 '참고'로 떨어뜨린다. KaPick 의 12개를 1개로 잘못
 * 읽은 적이 있어서 둔 규칙이다(지면에 박스가 둘인데 위쪽만 읽었다).
 */
@Entity
@Table(name = "card_exclusion")
public class CardExclusion {

    /** 전월실적에서 빼는가({@code PERFORMANCE}), 혜택을 안 주는가({@code BENEFIT}). */
    public enum Axis { PERFORMANCE, BENEFIT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "card_id", nullable = false)
    private CardProduct card;

    @Column(name = "axis", nullable = false, length = 12)
    private String axis;

    /** {@code CASH_ADVANCE} · {@code PUBLIC_DUES} · {@code TRANSIT} · {@code INSTALLMENT_FREE} … */
    @Column(name = "code", nullable = false, length = 30)
    private String code;

    /** 원문 문구. 길다 — 공과금 항목 하나가 87자다. */
    @Column(name = "label", nullable = false, length = 300)
    private String label;

    protected CardExclusion() {
    }

    public CardExclusion(Axis axis, String code, String label) {
        this.axis = axis.name();
        this.code = code;
        this.label = label;
    }

    void attachTo(CardProduct card) {
        this.card = card;
    }

    public Long getId() { return id; }
    public CardProduct getCard() { return card; }
    public String getAxis() { return axis; }
    public String getCode() { return code; }
    public String getLabel() { return label; }
}
