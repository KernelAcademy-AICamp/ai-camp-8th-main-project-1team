package com.finntech.mydata.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 통장 거래 한 줄 (mydata_account_txn) — 실제 통장의 두 칸 구조를 따른다.
 *
 * <ul>
 *   <li>{@code description}(적요) — 거래 상대나 성격. 예: {@code 뚜레쥬르 병영1동점}, {@code 이자입금}, {@code 김민준}
 *   <li>{@code note}(비고) — 취급점이나 채널. 예: {@code KB국민카드}, {@code BNK경남은행본부}, {@code 전자금융이체}
 * </ul>
 *
 * <p><b>잔액 칸이 없다.</b> 결제가 하나 들어오면 그 뒤 모든 행의 잔액이 낡기 때문이다.
 * 잔액은 조회에서 구간 시작 잔액에 시간순으로 누적해 굴린다.
 *
 * <p>커트오프({@code mydata.now}) 이후의 거래도 함께 적재된다 — 결제내역과 같은 방식으로,
 * 조회에서 {@code date <= now}로 거른다(§13-11 실시간성).
 */
@Entity
@Table(name = "mydata_account_txn")
public class MyDataAccountTxn {

    /**
     * 거래 출처. 카드 출금은 {@code mydata_payment}의 사본이라 대조하려면 출처를 알아야 한다.
     *
     * <p>{@code KPASS}는 대중교통비 환급이다(2026-07-31). {@code TRANSFER}로 뭉뚱그리지 않는 이유는
     * TRANSFER가 이 저장소에서 <b>사람 간 계좌이체</b>를 뜻하기 때문이다 — 환급을 거기 섞으면
     * 이체 통계가 조용히 틀린다.
     */
    public enum Source { TRANSFER, SALARY, INTEREST, TAX, CARD, KPASS }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mydata_account_txn_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mydata_account_id", nullable = false)
    private MyDataAccount account;

    @Column(name = "mydata_account_txn_date", nullable = false)
    private LocalDateTime date;

    /** DEPOSIT | WITHDRAWAL. amount는 부호 없는 절대액이다. */
    @Column(name = "mydata_account_txn_type", nullable = false, length = 12)
    private String type;

    @Column(name = "mydata_account_txn_amount", nullable = false)
    private long amount;

    @Column(name = "mydata_account_txn_description", nullable = false, length = 120)
    private String description;

    @Column(name = "mydata_account_txn_note", nullable = false, length = 60)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "mydata_account_txn_source", nullable = false, length = 12)
    private Source source;

    /**
     * 복제한 결제의 ID({@code source=CARD}일 때만, 그 외 null).
     *
     * <p>사본에는 원본을 가리키는 것이 있어야 한다. 생성 후 정리 단계가 해시충돌 사업자번호의 결제를
     * 지우는데, 이 값이 없으면 통장에 그 결제의 출금만 남아 결제와 통장이 갈라진다.
     */
    @Column(name = "mydata_account_txn_payment_id", length = 64)
    private String paymentId;

    protected MyDataAccountTxn() {}

    public Long getId() { return id; }
    public MyDataAccount getAccount() { return account; }
    public LocalDateTime getDate() { return date; }
    public String getType() { return type; }
    public long getAmount() { return amount; }
    public String getDescription() { return description; }
    public String getNote() { return note; }
    public Source getSource() { return source; }
    public String getPaymentId() { return paymentId; }
}
