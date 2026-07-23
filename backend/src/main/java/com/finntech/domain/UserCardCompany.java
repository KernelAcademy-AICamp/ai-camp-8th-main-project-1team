package com.finntech.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 사용자가 연동한 카드사와 마지막 동기화 시각 (§13-11 실시간 증분, W2).
 * 마이데이터 재조회는 카드사별로 {@code lastRenewalTime} 이후의 결제만 증분으로 당겨오므로,
 * 카드사마다 마지막 동기화 시각을 따로 보관한다(부분 실패 시 누락·중복 방지). {@code (userId, companyId)} 유니크.
 */
@Entity
@Table(name = "user_card_company",
        uniqueConstraints = @UniqueConstraint(name = "uq_user_company", columnNames = {"user_id", "company_id"}))
public class UserCardCompany {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "company_name", length = 40)
    private String companyName;

    @Column(name = "linked_at", nullable = false)
    private LocalDateTime linkedAt;

    /** 이 시각 이후의 결제만 다음 동기화에서 증분으로 당겨온다. */
    @Column(name = "last_renewal_time", nullable = false)
    private LocalDateTime lastRenewalTime;

    protected UserCardCompany() {}

    public UserCardCompany(Long userId, Long companyId, String companyName,
                           LocalDateTime linkedAt, LocalDateTime lastRenewalTime) {
        this.userId = userId;
        this.companyId = companyId;
        this.companyName = companyName;
        this.linkedAt = linkedAt;
        this.lastRenewalTime = lastRenewalTime;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getCompanyId() { return companyId; }
    public String getCompanyName() { return companyName; }
    public LocalDateTime getLinkedAt() { return linkedAt; }
    public LocalDateTime getLastRenewalTime() { return lastRenewalTime; }
    public void setLastRenewalTime(LocalDateTime v) { this.lastRenewalTime = v; }
}
