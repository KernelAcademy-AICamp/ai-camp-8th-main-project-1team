package com.finntech.mydata.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

/**
 * 마이데이터 입출금 통장 (mydata_account) — 사용자당 1개(§13-11 경제 모델).
 * 카드 사용 = 이 통장에서 출금, 매달 월급날 = 월급 입금. 잔액은 저장하지 않고 조회 시 계산한다:
 *   잔액(now) = initialBalance + salary × (now까지의 월급날 수) − Σ(카드결제 ≤ now).
 * 입출금 내역도 파생한다(월급 입금은 월급날 계산, 카드 출금은 mydata_payment 재사용) — 별도 원장 테이블 없음.
 */
@Entity
@Table(name = "mydata_account")
public class MyDataAccount {

    @Id
    @Column(name = "mydata_account_id", length = 32)
    private String accountNumber; // 계좌번호(은행별 자리수·형식, 금융결제원 CMS 체계 참조)

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mydata_user_id", nullable = false, unique = true)
    private MyDataUser user;

    @Column(name = "mydata_account_bank", nullable = false, length = 40)
    private String bank;         // 은행명(예: 우리은행, 카카오뱅크)

    @Column(name = "mydata_account_product", nullable = false, length = 60)
    private String product;      // 통장 상품명(예: 우월한 월급 통장)

    @Column(name = "mydata_account_salary_payer", nullable = false, length = 40)
    private String salaryPayer;  // 월급 입금처(회사명, 부가통신사업자 목록에서 랜덤)

    @Column(name = "mydata_account_opened_date", nullable = false)
    private LocalDate openedDate;

    /** 월급(원, 10만원 단위) — 매달 payday에 입금돼 경제활동을 유지한다. */
    @Column(name = "mydata_account_salary", nullable = false)
    private int salary;

    /** 월급날(1~28일). */
    @Column(name = "mydata_account_payday", nullable = false)
    private int payday;

    /** 초기 잔액(원) — 랜덤(빈부차). 잔액은 여기에 월급 입금·카드 출금을 누적해 계산. */
    @Column(name = "mydata_account_initial_balance", nullable = false)
    private long initialBalance;

    protected MyDataAccount() {}

    public MyDataAccount(String accountNumber, MyDataUser user, String bank, String product,
                         String salaryPayer, LocalDate openedDate, int salary, int payday, long initialBalance) {
        this.accountNumber = accountNumber;
        this.user = user;
        this.bank = bank;
        this.product = product;
        this.salaryPayer = salaryPayer;
        this.openedDate = openedDate;
        this.salary = salary;
        this.payday = payday;
        this.initialBalance = initialBalance;
    }

    public String getAccountNumber() { return accountNumber; }
    public MyDataUser getUser() { return user; }
    public String getBank() { return bank; }
    public String getProduct() { return product; }
    public String getSalaryPayer() { return salaryPayer; }
    public LocalDate getOpenedDate() { return openedDate; }
    public int getSalary() { return salary; }
    public int getPayday() { return payday; }
    public long getInitialBalance() { return initialBalance; }
}
