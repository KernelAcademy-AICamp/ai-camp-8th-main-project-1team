package com.finntech.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * 저축 목표 버킷 (Qapital 벤치마크, 문서 §5-5). 사용자가 이모지+이름+목표금액으로 <b>무제한</b> 만든다.
 *
 * <p>사진 대신 이모지를 쓴다 — 더미·로컬 환경에서 파일 저장·업로드를 피하기 위함이다.
 * 잔액은 저장하지 않고 {@link PointEvent}(TRANSFER 입금 − WITHDRAWAL 차감)에서 파생한다(단일 출처).
 */
@Entity
@Table(name = "savings_goal", indexes = { @Index(name = "idx_goal_user", columnList = "user_id") })
public class SavingsGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 40)
    private String name;

    /** 목표를 나타내는 이모지 (예: 🗼, 🖥️). 사진 대체. */
    @Column(nullable = false, length = 16)
    private String emoji;

    @Column(name = "target_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal targetAmount;

    /** 표시 순서 */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** 우선순위 목표면 자동 분배 시 목표금액까지 먼저 채운다. */
    @Column(nullable = false)
    private boolean priority;

    /** 목표 기한(일). '가는 날 N일 단축' 계산용 — fundedDays = 잔액/목표 × 기한일. */
    @Column(name = "deadline_days", nullable = false)
    private int deadlineDays = 90;

    /**
     * 이 목표를 위해 '줄이기로 한' 습관 소비 카테고리 코드(CSV). 예: "CAFE,DELIVERY".
     * 월 절약액 = 이 카테고리들의 월평균 미계획 소비 합 → 달성 개월수 = ⌈목표금액/월절약액⌉.
     * (실제 절약 판단은 코드가; 화면 표시는 이 계획을 근거로.)
     */
    @Column(name = "plan_cut_categories", length = 400)
    private String planCutCategories;

    // 이 목표를 위한 '자유입출금통장'(§13-11) — 목표에 모으는 돈을 담는 계좌(은행·통장명·계좌번호). 생성 시 발급.
    @Column(name = "account_bank", length = 40)
    private String accountBank;
    @Column(name = "account_product", length = 60)
    private String accountProduct;
    @Column(name = "account_number", length = 32)
    private String accountNumber;

    protected SavingsGoal() {}

    public SavingsGoal(Long userId, String name, String emoji, BigDecimal targetAmount,
                       int sortOrder, boolean priority) {
        this.userId = userId;
        this.name = name;
        this.emoji = emoji;
        this.targetAmount = targetAmount;
        this.sortOrder = sortOrder;
        this.priority = priority;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getEmoji() { return emoji; }
    public void setEmoji(String v) { this.emoji = v; }
    public BigDecimal getTargetAmount() { return targetAmount; }
    public void setTargetAmount(BigDecimal v) { this.targetAmount = v; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int v) { this.sortOrder = v; }
    public boolean isPriority() { return priority; }
    public void setPriority(boolean v) { this.priority = v; }
    public int getDeadlineDays() { return deadlineDays; }
    public void setDeadlineDays(int v) { this.deadlineDays = v; }
    public String getPlanCutCategories() { return planCutCategories; }
    public void setPlanCutCategories(String v) { this.planCutCategories = v; }
    public String getAccountBank() { return accountBank; }
    public String getAccountProduct() { return accountProduct; }
    public String getAccountNumber() { return accountNumber; }
    public void setAccount(String bank, String product, String number) {
        this.accountBank = bank; this.accountProduct = product; this.accountNumber = number;
    }
}
