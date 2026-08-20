package com.finntech.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * <b>한 사람이 한 가맹점을 무엇으로 봤는가</b> — 사전의 전역 분류를 정하는 한 표(V30).
 *
 * <p>이 표가 없을 때 {@code confirm} 은 아무 조건 없이 덮었다. 마지막에 누른 사람이 이기고,
 * 그 전에 무엇이었는지는 어디에도 안 남았다 — 사람은 오분류하고 착각하는데 사전은
 * <b>전역 자산</b>이라 한 번의 실수가 모두에게 간다.
 *
 * <p><b>사람당 하나다.</b> 결제당으로 세면 티머니 17건을 고친 사람이 17표를 던진다. 세려던 것은
 * <i>"몇 명이 그렇게 보는가"</i>이지 <i>"몇 건을 눌렀는가"</i>가 아니다. 다시 확정하면 이 행이
 * <b>바뀐다</b>(늘지 않는다) — 실수를 되돌리면 반대 증거가 사라지는 것이
 * {@link BusinessNumberKind} 와 같은 이치다.
 *
 * <p><b>키는 사전과 같은 규칙이다</b> — PG 번호는 지우고 이름으로 본다. 두 곳의 키가 갈리면
 * 표는 쌓이는데 사전은 못 찾는, 오류 없는 실패가 난다.
 *
 * <p>사전 행에 FK 를 걸지 않는다. 사전 행은 재연동·재적재로 지워졌다 다시 생길 수 있는데,
 * 이 표는 <b>사람의 판단</b>이라 그때 같이 날아가면 안 된다.
 */
@Entity
@Table(name = "merchant_category_vote",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"business_number", "merchant_name", "user_id"}))
public class MerchantCategoryVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 사전과 같은 키. PG 번호는 지워져 빈 문자열이다. */
    @Column(name = "business_number", nullable = false, length = 10)
    private String businessNumber = "";

    /** <b>풀네임</b>. 사전과 같다. */
    @Column(name = "merchant_name", nullable = false, length = 120)
    private String merchantName;

    /**
     * 표를 던진 사람. <b>탈퇴·삭제요청 때 여기만 비운다</b>(V38) — 표 자체는 남긴다.
     *
     * <p>한 표는 두 가지를 동시에 담는다: "이 가맹점은 카페/간식이다"(우리 자산)와
     * "그 사람이 그 가맹점을 안다"(개인정보). 행을 지우면 앞의 것까지 잃고, 남기면 뒤의 것이
     * 남는다. 사람만 떼어내면 둘 다 지킨다 — 집계는 그대로고 연결만 끊긴다.
     */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "category2", nullable = false, length = 30)
    private String category2;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected MerchantCategoryVote() {
    }

    public MerchantCategoryVote(String businessNumber, String merchantName,
                                Long userId, String category2) {
        this.businessNumber = businessNumber == null ? "" : businessNumber;
        this.merchantName = merchantName;
        this.userId = userId;
        this.category2 = category2;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** 마음을 바꿨다 — 표가 늘지 않고 <b>바뀐다</b>. */
    public void recast(String category2) {
        this.category2 = category2;
    }

    public Long getId() { return id; }
    public String getBusinessNumber() { return businessNumber; }
    public String getMerchantName() { return merchantName; }
    public Long getUserId() { return userId; }
    public String getCategory2() { return category2; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
