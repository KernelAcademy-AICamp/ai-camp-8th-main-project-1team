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
 * 확정 분류 사전 한 줄 — <b>(사업자번호, 가맹점 풀네임) → 중분류</b>.
 *
 * <p>실제 명세서에는 업종코드가 없어 그대로 넣으면 전부 '카테고리없음'이 된다. 사람이 확실하게
 * 정해 준 것을 여기 쌓아 두고 <b>같은 가맹점을 두 번 묻지 않는다.</b>
 *
 * <p><b>키가 풀네임인 것이 설계의 전부다.</b> {@code GS25} 가 아니라 {@code GS25 강남역점} 이
 * 들어간다. 그래서 PG(전자지급결제대행)를 거친 결제도 안전하다 —
 * {@code KG모빌리언스 번호 + 삼성물산리조트(주)에버랜드} 와 {@code 같은 번호 + 다른 가맹점} 은
 * 서로 다른 행이라, 한 PG 에 업종 하나가 박히는 사고가 구조적으로 안 난다.
 *
 * <p>사람마다 나누지 않는다. {@link UserMerchantStance}(<i>"이 가게가 <b>나에게</b> 낭비인가"</i>)와
 * 달리 여기 담기는 것은 <i>"이 점포의 업종이 무엇인가"</i> 라 <b>사람에 따라 달라지지 않는 사실</b>이다.
 * 그래서 전역이고 만료도 없다 — 캐시가 아니라 자산이다.
 */
@Entity
@Table(name = "merchant_category")
public class MerchantCategory {

    /** 이 분류가 어디서 왔나. <b>LLM 추정만으로는 들어오지 못한다</b> — 그래서 둘뿐이다. */
    public enum Source {
        /** 사용자가 업종코드를 직접 준 것. 국세청 등록 정보라 추정이 아니라 사실이다. */
        USER_CSV,
        /** LLM 추정을 사람이 "맞다"고 확인한 것. */
        USER_CONFIRMED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 하이픈 없는 10자리. 해외 본사처럼 번호가 없으면 <b>빈 문자열</b>이다 —
     * {@code null} 로 두면 UNIQUE 가 NULL 끼리 다르다고 봐서 같은 가맹점이 여러 번 쌓인다.
     */
    @Column(name = "business_number", nullable = false, length = 10)
    private String businessNumber = "";

    /** <b>풀네임</b>. 'GS25' 가 아니라 'GS25 강남역점'. */
    @Column(name = "merchant_name", nullable = false, length = 120)
    private String merchantName;

    @Column(name = "category2", nullable = false, length = 30)
    private String category2;

    @Column(name = "source", nullable = false, length = 20)
    private String source;

    /** 오입력을 되돌릴 때 근거가 된다. CSV 적재분은 사람이 없어 null 이다. */
    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected MerchantCategory() {
    }

    public MerchantCategory(String businessNumber, String merchantName,
                            String category2, Source source, Long confirmedBy) {
        this.businessNumber = normalize(businessNumber);
        this.merchantName = merchantName;
        this.category2 = category2;
        this.source = source.name();
        this.confirmedBy = confirmedBy;
    }

    /** 원장이 하이픈을 넣어 보관하기도 한다. 키가 갈라지지 않게 숫자만 남긴다. */
    public static String normalize(String businessNumber) {
        return businessNumber == null ? "" : businessNumber.replaceAll("\\D", "");
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

    /** 사람이 다시 확인해 분류를 바꾼다 — 오입력을 되돌릴 길이다. */
    public void reclassify(String category2, Source source, Long confirmedBy) {
        this.category2 = category2;
        this.source = source.name();
        this.confirmedBy = confirmedBy;
    }

    public Long getId() { return id; }
    public String getBusinessNumber() { return businessNumber; }
    public String getMerchantName() { return merchantName; }
    public String getCategory2() { return category2; }
    public String getSource() { return source; }
    public Long getConfirmedBy() { return confirmedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
