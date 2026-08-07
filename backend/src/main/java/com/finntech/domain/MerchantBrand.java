package com.finntech.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 아직 사전에 못 들어간 가맹점의 <b>브랜드</b> — 대기 장소 한 줄.
 *
 * <p>실 명세서의 가맹점명에는 지점이 붙어 있다({@code GS25 강남역점}). 브랜드({@code GS25})를
 * 알면 <b>그 브랜드의 새 지점은 다시 안 물어도 되고</b>, 한 지점이 분류되면 나머지 지점에
 * 그 분류를 물려줄 수 있다.
 *
 * <p><b>사전과 왜 따로 두나.</b> 사전({@link MerchantCategory})은 <i>"이 점포의 업종이
 * 무엇인가"</i>에 대한 답만 담는다는 약속이 있다. 브랜드만 알아낸 가맹점을 그 안에 넣으면
 * 분류가 없는 행이 사전에 앉아 그 약속이 깨진다. 그래서 여기 두고, <b>그 가맹점이 사전에
 * 들어가는 순간 브랜드를 옮기고 이 행을 지운다.</b>
 *
 * <p><b>키가 가맹점명이다.</b> 브랜드는 이름에서 나온다 — 사업자번호는 브랜드를 말해 주지
 * 않는다(PG 를 거치면 번호가 남의 것이고, 프랜차이즈는 지점마다 번호가 다르다). 반대로 이름은
 * 언제나 있다. 번호 없는 해외 결제에도 있다.
 */
@Entity
@Table(name = "merchant_brand")
public class MerchantBrand {

    /** 브랜드를 누가 알아냈나. */
    public enum Source {
        /** 무료 추론 통로가 답한 것(②-c). 확정이 아니라 <b>단서</b>다. */
        TEMP_MODEL,
        /** 사람이 고친 것. */
        USER
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** <b>풀네임</b>. 'GS25' 가 아니라 'GS25 강남역점'. */
    @Column(name = "merchant_name", nullable = false, length = 120)
    private String merchantName;

    /** 뽑아낸 브랜드. 'GS25'. */
    @Column(name = "brand", nullable = false, length = 60)
    private String brand;

    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected MerchantBrand() {
    }

    public MerchantBrand(String merchantName, String brand, Source source) {
        this.merchantName = merchantName;
        this.brand = brand;
        this.source = source.name();
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

    /** 사람이 고치거나 모델이 더 나은 답을 준 경우. */
    public void rename(String brand, Source source) {
        this.brand = brand;
        this.source = source.name();
    }

    public Long getId() { return id; }
    public String getMerchantName() { return merchantName; }
    public String getBrand() { return brand; }
    public String getSource() { return source; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
