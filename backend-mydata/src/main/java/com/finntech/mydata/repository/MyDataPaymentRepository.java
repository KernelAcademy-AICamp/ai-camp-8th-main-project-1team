package com.finntech.mydata.repository;

import com.finntech.mydata.domain.MyDataPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface MyDataPaymentRepository extends JpaRepository<MyDataPayment, String> {

    /** 카드의 결제내역. 최신순 정렬 고정(재현성). */
    @Query("select p from MyDataPayment p where p.card.id = :cardId order by p.paymentDate desc")
    List<MyDataPayment> findByCard(@Param("cardId") String cardId);

    /** 증분 조회: 마지막 동기화 이후 결제만. */
    @Query("select p from MyDataPayment p "
            + "where p.card.id = :cardId and p.paymentDate > :after order by p.paymentDate desc")
    List<MyDataPayment> findByCardAfter(@Param("cardId") String cardId,
                                        @Param("after") LocalDateTime after);
}
