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
    boolean existsById(String paymentId);

    /** 벌크 삭제(즉시 DML) — 재연동 delete→insert 순서 역전 방지. */
    @Modifying
    @Transactional
    @Query("delete from UserPayment p where p.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
