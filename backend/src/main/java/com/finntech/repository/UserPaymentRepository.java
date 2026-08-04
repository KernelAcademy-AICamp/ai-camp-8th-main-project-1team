package com.finntech.repository;

import com.finntech.domain.UserPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface UserPaymentRepository extends JpaRepository<UserPayment, String> {
    List<UserPayment> findByUserIdOrderByPaymentDateDesc(Long userId);
    List<UserPayment> findByUserIdAndCardSerialOrderByPaymentDateDesc(Long userId, String cardSerial);

    /**
     * 아직 분류되지 않은 결제 — <b>DB 에서 걸러서</b> 가져온다.
     *
     * <p>예전에는 사용자의 결제를 전부 읽어 메모리에서 걸렀다. 데모 사용자는 결제가 4,000건이라
     * 화면에 100건을 보여 주려고 4,000건을 실어 왔다. 조건과 개수를 쿼리에 맡긴다.
     */
    List<UserPayment> findTop100ByUserIdAndCategory2OrderByPaymentDateDesc(Long userId, String category2);
    boolean existsById(String paymentId);

    /** 벌크 삭제(즉시 DML) — 재연동 delete→insert 순서 역전 방지. */
    @Modifying
    @Transactional
    @Query("delete from UserPayment p where p.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
