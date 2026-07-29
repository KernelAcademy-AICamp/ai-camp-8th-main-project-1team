package com.finntech.mydata.repository;

import com.finntech.mydata.domain.MyDataPayment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface MyDataPaymentRepository extends JpaRepository<MyDataPayment, String> {

    /**
     * 카드의 결제내역 중 <b>현재시각(now)까지</b>의 것만. 최신순 정렬 고정(재현성).
     * 미래 날짜로 미리 생성해둔 결제는 now가 그 시점을 지나기 전엔 반환되지 않는다(§13-11 실시간 시뮬레이션).
     */
    @Query("select p from MyDataPayment p "
            + "where p.card.id = :cardId and p.paymentDate <= :now order by p.paymentDate desc")
    List<MyDataPayment> findByCardUpTo(@Param("cardId") String cardId,
                                       @Param("now") LocalDateTime now);

    /** 증분 조회: 마지막 동기화 이후 ~ 현재시각(now)까지의 결제만. */
    @Query("select p from MyDataPayment p "
            + "where p.card.id = :cardId and p.paymentDate > :after and p.paymentDate <= :now "
            + "order by p.paymentDate desc")
    List<MyDataPayment> findByCardBetween(@Param("cardId") String cardId,
                                          @Param("after") LocalDateTime after,
                                          @Param("now") LocalDateTime now);

    /** 통장(§13-11): 사용자의 모든 카드 결제 합계(≤now) — 잔액 = 초기잔액 + 월급입금 − 이 합계. */
    @Query("select coalesce(sum(p.amount),0) from MyDataPayment p "
            + "where p.card.user.id = :userId and p.paymentDate <= :now")
    long sumByUserUpTo(@Param("userId") String userId, @Param("now") LocalDateTime now);

    /** 통장 내역용: 지정 구간의 카드 결제 전부(오름차순). 잔액을 시간순으로 굴려야 해서 정렬을 고정한다. */
    @Query("select p from MyDataPayment p "
            + "where p.card.user.id = :userId and p.paymentDate >= :from and p.paymentDate <= :now "
            + "order by p.paymentDate asc")
    List<MyDataPayment> findByUserBetween(@Param("userId") String userId,
                                          @Param("from") LocalDateTime from,
                                          @Param("now") LocalDateTime now);

    /**
     * 통장 이자 계산용: 사용자의 카드 결제를 <b>월별로</b> 합산(≤now).
     *
     * <p>이자는 '그 시점 실잔액'에 붙으므로 월을 따라 걸으며 잔액을 알아야 한다. 매달 한 번씩
     * 합계를 묻는 대신 한 번에 월별로 받아 메모리에서 걷는다 — 결제가 1,120만 행이라 왕복 수를
     * 줄이는 편이 낫다.
     */
    @Query("select year(p.paymentDate), month(p.paymentDate), coalesce(sum(p.amount),0) "
            + "from MyDataPayment p where p.card.user.id = :userId and p.paymentDate <= :now "
            + "group by year(p.paymentDate), month(p.paymentDate)")
    List<Object[]> sumByUserPerMonth(@Param("userId") String userId, @Param("now") LocalDateTime now);

    /** 통장 입출금 내역용: 사용자의 최근 카드 결제(≤now, 최신순). Pageable로 상위 N개만 로드. */
    @Query("select p from MyDataPayment p "
            + "where p.card.user.id = :userId and p.paymentDate <= :now order by p.paymentDate desc")
    List<MyDataPayment> findByUserUpTo(@Param("userId") String userId, @Param("now") LocalDateTime now,
                                       Pageable pageable);
}
