package com.finntech.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * "이 사용자의 소비 원장을 다시 써야 한다"는 표시 (V34).
 *
 * <p><b>표시만 하고 계산은 나중에 한 곳에서.</b> 원장을 고치는 자리 중 가장 큰 것이
 * {@code MerchantDictionaryRecomputeService.recompute} 인데, 사전 수천 행을 훑으며 실사용자
 * 전원의 결제를 <b>한 트랜잭션에서</b> 고친다. 그 안에서 재작성을 하면 같은 사용자를 수천 번
 * 다시 쓴다. 표시는 사용자당 한 줄로 접히고, 실제 재작성은 커밋 뒤에 한 번 돈다.
 *
 * <p><b>메모리가 아니라 표인 이유</b>는 원장이 바뀌었다는 사실이 어디에도 안 남기 때문이다.
 * 분류가 조용히 바뀐 사용자를 값싼 질의로 찾을 길이 없어서, 표시를 메모리에만 두면 재기동
 * 한 번에 표가 원장과 영영 어긋난 채 남는다. 표에 적으면 표시가 <b>바꾼 트랜잭션과 같은
 * 커밋</b>에 들어가, 그 트랜잭션이 되돌려질 때 함께 되돌려진다.
 */
@Entity
@Table(name = "spending_ledger_dirty", indexes = {
        @Index(name = "idx_spending_ledger_dirty_user", columnList = "user_id, id")
})
public class SpendingLedgerDirty {

    /**
     * 무엇이 더럽혔나 — <b>처리 분기에 쓰지 않는다.</b> 재작성은 언제나 그 사용자의 사실 칸
     * 전체다. 로그와 운영 점검에서 "무슨 일이 이 표를 흔드는가"를 보기 위해 남긴다.
     */
    public enum Reason {
        /** 결제가 생기거나 지워졌다. */
        PAYMENT,
        /** 분류(확정 또는 추정)가 바뀌었다. */
        CATEGORY,
        /** 가맹점 성향(관대함)이 바뀌었다. */
        STANCE,
        /** 카테고리 단위 개인화가 바뀌었다. */
        OVERRIDE,
        /** 처음 채우기 — 운영 문이 손으로 부른 것. */
        BACKFILL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "reason", nullable = false, length = 30)
    private String reason;

    @Column(name = "marked_at", nullable = false)
    private LocalDateTime markedAt;

    /**
     * 재작성이 실패한 횟수.
     *
     * <p>없으면 한 사용자가 계속 터질 때 배수가 그 줄에 걸려 영원히 헛돈다 — 그동안 뒤에 선
     * 사용자들은 한 번도 못 써진다. 상한을 넘으면 건너뛰고 운영 점검이 그 줄을 보여 준다.
     */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    protected SpendingLedgerDirty() {}

    public SpendingLedgerDirty(Long userId, Reason reason, LocalDateTime markedAt) {
        this.userId = userId;
        this.reason = reason.name();
        this.markedAt = markedAt;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getReason() { return reason; }
    public LocalDateTime getMarkedAt() { return markedAt; }
    public int getAttempts() { return attempts; }
}
