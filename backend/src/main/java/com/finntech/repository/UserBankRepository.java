package com.finntech.repository;

import com.finntech.domain.UserBank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface UserBankRepository extends JpaRepository<UserBank, Long> {

    List<UserBank> findByUserIdOrderByBankIdAsc(Long userId);

    boolean existsByUserId(Long userId);

    /** 벌크 삭제(즉시 DML) — 재연동·파기 시 순서 보장. */
    @Modifying
    @Transactional
    @Query("delete from UserBank b where b.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
