package com.finntech.mydata.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 마이데이터 카드 (mydata_card). 사용자가 실제로 발급받은 카드 인스턴스. */
@Entity
@Table(name = "mydata_card")
public class MyDataCard {

    @Id
    @Column(name = "mydata_card_id", length = 24)
    private String id; // 카드 serial (NNNN-NNNN-NNNN-NNNN)

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mydata_user_id", nullable = false)
    private MyDataUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_code", nullable = false)
    private CardProduct cardProduct;

    @Column(name = "mydata_card_expiration_date", nullable = false)
    private LocalDate expirationDate;

    // 전월 실적액은 두지 않는다 — 실 마이데이터 카드 API 에 그런 필드가 없다.
    // 여기서 값을 내주면 실적을 승인내역에서 계산하는 로직을 아무도 짜지 않게 되고,
    // 실데이터로 넘어가는 날 그 계산이 통째로 비어 있다. 더미는 제약을 완화하는 것이
    // 아니라 재현하는 것이다.

    /**
     * 실사용자가 붙인 표시 이름 (설계서 F3).
     *
     * <p>카드사별로 명세서를 따로 받으므로 카드가 여러 장이 되고, 본인이 알아볼 이름이 필요하다.
     * <b>그 이름을 카탈로그에 새 상품으로 만들지 않는다</b> — 카탈로그는 실제 상품 115종이 든
     * 기준 데이터라, 한 사람의 표기가 섞이면 다음 사람에게도 보이는 가짜 상품이 된다.
     * 비어 있으면 화면은 상품명을 그대로 쓴다.
     */
    @Column(name = "mydata_card_display_name", length = 60)
    private String displayName;

    @OneToMany(mappedBy = "card", fetch = FetchType.LAZY)
    private List<MyDataPayment> payments = new ArrayList<>();

    protected MyDataCard() {}

    public MyDataCard(String id, MyDataUser user, CardProduct cardProduct,
                      LocalDate expirationDate) {
        this.id = id;
        this.user = user;
        this.cardProduct = cardProduct;
        this.expirationDate = expirationDate;
    }

    public String getId() { return id; }
    public MyDataUser getUser() { return user; }
    public CardProduct getCardProduct() { return cardProduct; }
    public LocalDate getExpirationDate() { return expirationDate; }
    public List<MyDataPayment> getPayments() { return payments; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
}
