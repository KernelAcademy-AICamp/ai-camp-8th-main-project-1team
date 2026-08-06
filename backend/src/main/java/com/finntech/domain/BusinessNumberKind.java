package com.finntech.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 사업자번호의 성격 — <b>한 사업인가, 여러 사업인가</b>(V16).
 *
 * <p>사전은 (사업자번호, 가맹점 풀네임) 이 키인데, 번호만으로 붙이는 <b>완화</b>가 하나 있다.
 * 한 번호에 상호가 38,690종 붙은 곳이 있어서다(택시 — 표시명 뒤에 차량번호가 붙는다).
 * 그 완화가 <b>닿으면 안 되는 번호</b>가 있다 — 백화점 하나에 무인양품·러쉬·식품관이 들어 있고,
 * 울릉크루즈 한 번호에 여객선과 배 안의 GS25 가 붙는다.
 *
 * <p><b>"상호가 여럿인가"로는 못 가른다.</b> 택시도 KTX 도 상호가 여럿이지만 전부 같은 사업이고
 * 거기서는 완화가 꼭 필요하다. 가르는 것은 <i>"그 상호들이 같은 것을 파는가"</i> 이고, 그건
 * 분류해 봐야 안다. 그래서 <b>관측하며 판정하고 그 결과를 여기 남긴다</b> — 남기지 않으면
 * 연동할 때마다 같은 것을 다시 묻게 되고, 그게 곧 재질문이다.
 */
@Entity
@Table(name = "business_number_kind")
public class BusinessNumberKind {

    /**
     * 판정 상태. <b>의심 쪽이 기본이다</b> — 복합인데 단일로 보면 오염이라 조용히 틀리지만,
     * 단일인데 복합으로 보면 미분류가 늘 뿐이고 그건 눈에 보이며 LLM·사용자가 채운다.
     */
    public enum Kind {
        /** 상호가 여럿인 것을 봤지만 아직 판단 못 함 — 완화 <b>보류</b>. */
        UNKNOWN,
        /** 서로 다른 상호를 충분히 봤고 전부 같은 중분류 — 완화 <b>허용</b>. 택시가 여기로 온다. */
        SINGLE,
        /** 중분류가 갈렸다 — 완화 <b>금지</b>. 정확일치만 인정한다. */
        MULTI
    }

    @Id
    @Column(name = "business_number", length = 10)
    private String businessNumber;

    @Column(name = "kind", nullable = false, length = 10)
    private String kind;

    /** {@code SINGLE} 일 때 그 하나의 중분류. 왜 그렇게 정했는지 되짚을 수 있게 남긴다. */
    @Column(name = "settled_category2", length = 30)
    private String settledCategory2;

    /** 지금까지 본 <b>서로 다른 가맹점명</b> 수. 굳히는 근거이자, 왜 아직 UNKNOWN 인지의 답. */
    @Column(name = "merchant_names", nullable = false)
    private int merchantNames;

    @Column(name = "observed_at", nullable = false)
    private LocalDateTime observedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected BusinessNumberKind() {
    }

    public BusinessNumberKind(String businessNumber, Kind kind, String settledCategory2,
                              int merchantNames, LocalDateTime observedAt) {
        this.businessNumber = businessNumber;
        this.kind = kind.name();
        this.settledCategory2 = settledCategory2;
        this.merchantNames = merchantNames;
        this.observedAt = observedAt;
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

    /** 관측 결과로 판정을 고쳐 쓴다. */
    public void observe(Kind kind, String settledCategory2, int merchantNames, LocalDateTime at) {
        this.kind = kind.name();
        this.settledCategory2 = settledCategory2;
        this.merchantNames = merchantNames;
        this.observedAt = at;
    }

    public String getBusinessNumber() { return businessNumber; }
    public String getKind() { return kind; }
    public String getSettledCategory2() { return settledCategory2; }
    public int getMerchantNames() { return merchantNames; }
    public LocalDateTime getObservedAt() { return observedAt; }

    public boolean isMulti() { return Kind.MULTI.name().equals(kind); }
    public boolean isSingle() { return Kind.SINGLE.name().equals(kind); }
}
