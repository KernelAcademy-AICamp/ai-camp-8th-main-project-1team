package com.finntech.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 사용자가 연동한 은행 (§13 자산연결).
 *
 * <p>카드사({@link UserCardCompany})와 달리 <b>증분 동기화 시각을 두지 않는다</b>. 통장의 잔액과
 * 입출금 내역은 저장된 행이 아니라 조회 시점에 계산되기 때문이다
 * (잔액 = 초기잔액 + 월급누적 + 이자 − 세금 − 카드출금). 저장해 두면 결제가 하나 들어올 때마다
 * 즉시 낡는다. 그래서 여기에는 '무엇을 연동했는가'만 남기고 값은 매번 제공자에서 받아온다.
 *
 * <p>{@code (userId, bankId)} 유니크.
 */
@Entity
@Table(name = "user_bank",
        uniqueConstraints = @UniqueConstraint(name = "uq_user_bank", columnNames = {"user_id", "bank_id"}))
public class UserBank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 제공자의 은행 목록 순번(이름순). 제공자가 결정론이라 이 값도 안정적이다. */
    @Column(name = "bank_id", nullable = false)
    private Long bankId;

    @Column(name = "bank_name", length = 40)
    private String bankName;

    @Column(name = "linked_at", nullable = false)
    private LocalDateTime linkedAt;

    protected UserBank() {}

    public UserBank(Long userId, Long bankId, String bankName, LocalDateTime linkedAt) {
        this.userId = userId;
        this.bankId = bankId;
        this.bankName = bankName;
        this.linkedAt = linkedAt;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getBankId() { return bankId; }
    public String getBankName() { return bankName; }
    public LocalDateTime getLinkedAt() { return linkedAt; }
}
