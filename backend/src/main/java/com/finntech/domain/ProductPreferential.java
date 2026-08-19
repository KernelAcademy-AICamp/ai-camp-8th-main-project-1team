package com.finntech.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 저축상품 우대조건 라벨 — 금감원 공시의 자연어 {@code spcl_cnd}("우리은행 입출식 계좌에서 각 항목별
 * 실적 월 수가 계약기간의 1/2이상인 경우 가.급여/연금 이체:연 0.7%p …")를 조건 <b>종류의 목록</b>으로
 * 옮긴 것이다. {@link ProductEligibility}(가입 자격)와 같은 자리·같은 방식의 짝이다.
 *
 * <p><b>가산폭은 담지 않는다.</b> 실측(2026-08-11 적금 58건)에서 {@code %p}가 숫자로 적힌 25건 중
 * 가산폭 합이 `(최고금리 − 기본금리)`와 맞는 것은 <b>4건(16%)</b>뿐이었다 — 조건이 배타적이거나 기간별로
 * 다르게 붙어, 파싱해도 못 쓴다. 그래서 종류와 <b>당행 한정 여부</b>만 남긴다
 * (`07_취향분석및추천_Agent_설계.md` §4.5 M6 · §8.1 D2).
 *
 * <p><b>왜 저장하나.</b> 상품 자체는 DB에 쌓지 않는다(금융상품은 금감원 API 실시간 조회). 여기 쌓는 것은
 * 상품이 아니라 <b>라벨</b>이다 — LLM 호출 결과를 공시 문구의 해시로 캐시해, 금리만 바뀌는 대부분의 날에
 * 호출을 0회로 만든다. {@code product_eligibility}가 이미 같은 이유로 존재한다.
 *
 * <p>상품 공시는 공개 정보라 개인정보가 아니며, 삭제권 파기 대상이 아니다(사용자에 매이지 않는다).
 */
@Entity
@Table(name = "product_preferential",
        uniqueConstraints = @UniqueConstraint(columnNames = {"prdt_key"}))
public class ProductPreferential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 상품 식별키 = {@code 금융회사코드:상품코드}. 금리가 바뀌어도 이 값은 유지된다. */
    @Column(name = "prdt_key", nullable = false, length = 80)
    private String prdtKey;

    /** 판정 근거가 된 {@code spcl_cnd} 원문의 SHA-256. 이 값이 달라지면 우대조건 문구가 바뀐 것이다. */
    @Column(name = "spcl_cnd_hash", nullable = false, length = 64)
    private String spclCndHash;

    /**
     * 조건 목록을 한 줄로 담은 것 — {@code CARD_PERFORMANCE@OWN,SALARY_TRANSFER@ANY} 꼴.
     *
     * <p><b>빈 문자열은 "요구 조건이 없다"는 뜻이고, 이 행이 아예 없는 것과 다르다.</b> 실측에 우대조건이
     * {@code 없음}이라고 적힌 상품이 3건 있는데(`퍼스트가계적금`), 그런 상품은 채울 조건이 없으니 곧바로
     * 최고금리다. 행이 없으면 아직 라벨링을 안 한 것이다.
     *
     * <p>표를 따로 파지 않고 한 칸에 담는 이유: 조건은 상품당 대여섯 개를 넘지 않고 <b>통째로만</b>
     * 읽고 쓴다(부분 갱신이 없다). 조인을 만들 값이 없다.
     */
    @Column(name = "conditions", nullable = false, length = 500)
    private String conditions;

    /** 판정 출처. {@code AI}=LLM 구조화, {@code RULE}=키 없거나 실패 시 규칙 폴백. */
    @Column(name = "source", nullable = false, length = 10)
    private String source;

    @Column(name = "labeled_at", nullable = false)
    private LocalDateTime labeledAt;

    protected ProductPreferential() {}

    public ProductPreferential(String prdtKey, String spclCndHash, String conditions,
                               String source, LocalDateTime labeledAt) {
        this.prdtKey = prdtKey;
        this.spclCndHash = spclCndHash;
        this.conditions = conditions;
        this.source = source;
        this.labeledAt = labeledAt;
    }

    /** 우대조건 문구가 바뀐 상품을 새 판정으로 덮어쓴다. */
    public void relabel(String spclCndHash, String conditions, String source, LocalDateTime labeledAt) {
        this.spclCndHash = spclCndHash;
        this.conditions = conditions;
        this.source = source;
        this.labeledAt = labeledAt;
    }

    public Long getId() { return id; }
    public String getPrdtKey() { return prdtKey; }
    public String getSpclCndHash() { return spclCndHash; }
    public String getConditions() { return conditions; }
    public String getSource() { return source; }
    public LocalDateTime getLabeledAt() { return labeledAt; }
}
