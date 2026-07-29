package com.finntech.mydata.repository;

import com.finntech.mydata.domain.MyDataAccountTxn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface MyDataAccountTxnRepository extends JpaRepository<MyDataAccountTxn, Long> {

    /**
     * 구간 안의 거래 전부(오름차순). 잔액을 시간순으로 굴려야 해서 정렬을 고정한다.
     *
     * <p>2차 정렬로 적요를 두는 이유: 같은 초에 여러 건이 있을 수 있고(이자·소득세·지방세는
     * 5·6·7분으로 갈라 두었지만 이체는 분 단위라 겹친다), 순서가 흔들리면 잔액 열이 조회마다
     * 달라진다(마스터 §4 원칙 3).
     */
    @Query("select t from MyDataAccountTxn t "
            + "where t.account.accountNumber = :account and t.date >= :from and t.date <= :now "
            + "order by t.date asc, t.description asc, t.id asc")
    List<MyDataAccountTxn> findByAccountBetween(@Param("account") String account,
                                                @Param("from") LocalDateTime from,
                                                @Param("now") LocalDateTime now);

    /**
     * 구간 시작 시점의 잔액 변동분 — {@code Σ입금 − Σ출금} (date &lt; from).
     *
     * <p>여기에 개설 시 초기잔액을 더하면 구간 시작 잔액이다. 카드 출금도 이 표에 복제돼 있으므로
     * 결제 테이블을 따로 합산할 필요가 없다 — 예전에는 두 원천을 각각 합쳐 더했다.
     */
    @Query("select coalesce(sum(case when t.type = 'DEPOSIT' then t.amount else -t.amount end), 0) "
            + "from MyDataAccountTxn t where t.account.accountNumber = :account and t.date < :from")
    long netBefore(@Param("account") String account, @Param("from") LocalDateTime from);
}
