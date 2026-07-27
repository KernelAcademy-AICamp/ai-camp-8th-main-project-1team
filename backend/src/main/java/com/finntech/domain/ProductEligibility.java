package com.finntech.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 금융상품 가입 자격 라벨 — 금감원 공시의 자연어 {@code join_member}("만 19세~만 34세 개인",
 * "미성년 자녀를 둔 부모")를 기계가 쓸 수 있는 구조로 옮긴 것이다.
 *
 * <p><b>왜 필요한가.</b> 금감원의 {@code join_deny}(가입제한 코드)는 은행 신고가 부실해 믿을 수 없다.
 * 실측(2026-07-24 은행 적금 58개)에서 `마이키즈 적금`(만 17세 미만)·`우리아이적금`이 전부
 * {@code join_deny=1}(제한없음)으로 올라왔다. 상품명 키워드로 거르면 이번엔 반대로 멀쩡한 상품을
 * 지운다(`MZ 플랜적금`은 이름만 MZ고 실제 가입대상은 "개인 및 개인사업자"였다).
 * 자격은 자연어에만 있으므로 LLM으로 구조화한다.
 *
 * <p><b>수명 관리.</b> 금감원 공시는 고정 갱신일이 없고 "수시 변경"된다(공식 안내 문구). 실제로
 * 대부분은 <b>금리만</b> 바뀌고 상품코드와 자격 문구는 그대로다. 그래서 시간 기반 만료를 쓰지 않고
 * {@code joinMemberHash}(자격 문구의 지문)로 판단한다 — 해시가 같으면 재판정하지 않고, 문구가
 * 실제로 바뀐 상품만 다시 라벨링한다. 금리가 매일 바뀌어도 LLM 호출은 0회다.
 *
 * <p>상품 공시는 공개 정보라 개인정보가 아니며, 삭제권 파기 대상이 아니다(사용자에 매이지 않는다).
 */
@Entity
@Table(name = "product_eligibility",
        uniqueConstraints = @UniqueConstraint(columnNames = {"prdt_key"}))
public class ProductEligibility {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 상품 식별키 = {@code 금융회사코드:상품코드}. 금리가 바뀌어도 이 값은 유지된다. */
    @Column(name = "prdt_key", nullable = false, length = 80)
    private String prdtKey;

    /** 판정 근거가 된 {@code join_member} 원문의 SHA-256. 이 값이 달라지면 자격 문구가 바뀐 것이다. */
    @Column(name = "join_member_hash", nullable = false, length = 64)
    private String joinMemberHash;

    /** 가입 가능 최소 나이(포함). 제한 없으면 null. */
    @Column(name = "min_age")
    private Integer minAge;

    /** 가입 가능 최대 나이(포함). 제한 없으면 null. */
    @Column(name = "max_age")
    private Integer maxAge;

    /**
     * 나이 말고 별도 신분·상황이 필요하면 그 사유(예: `군 장병 전용`, `미성년 자녀를 둔 부모`).
     * null이면 나이만 맞으면 되는 범용 상품이다.
     * <p>우리가 아는 정보는 출생연도뿐이라 이 값이 있으면 <b>비교 대상에서 제외</b>한다.
     */
    @Column(name = "special_status", length = 120)
    private String specialStatus;

    /** 판정 출처. {@code AI}=LLM 구조화, {@code RULE}=키 없거나 실패 시 규칙 폴백. */
    @Column(name = "source", nullable = false, length = 10)
    private String source;

    @Column(name = "labeled_at", nullable = false)
    private LocalDateTime labeledAt;

    protected ProductEligibility() {}

    public ProductEligibility(String prdtKey, String joinMemberHash, Integer minAge, Integer maxAge,
                              String specialStatus, String source, LocalDateTime labeledAt) {
        this.prdtKey = prdtKey;
        this.joinMemberHash = joinMemberHash;
        this.minAge = minAge;
        this.maxAge = maxAge;
        this.specialStatus = specialStatus;
        this.source = source;
        this.labeledAt = labeledAt;
    }

    /** 자격 문구가 바뀐 상품을 새 판정으로 덮어쓴다. */
    public void relabel(String joinMemberHash, Integer minAge, Integer maxAge,
                        String specialStatus, String source, LocalDateTime labeledAt) {
        this.joinMemberHash = joinMemberHash;
        this.minAge = minAge;
        this.maxAge = maxAge;
        this.specialStatus = specialStatus;
        this.source = source;
        this.labeledAt = labeledAt;
    }

    public Long getId() { return id; }
    public String getPrdtKey() { return prdtKey; }
    public String getJoinMemberHash() { return joinMemberHash; }
    public Integer getMinAge() { return minAge; }
    public Integer getMaxAge() { return maxAge; }
    public String getSpecialStatus() { return specialStatus; }
    public String getSource() { return source; }
    public LocalDateTime getLabeledAt() { return labeledAt; }
}
