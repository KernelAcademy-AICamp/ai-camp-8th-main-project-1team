package com.finntech.repository;

import com.finntech.domain.UserMerchantStance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface UserMerchantStanceRepository extends JpaRepository<UserMerchantStance, Long> {

    List<UserMerchantStance> findByUserId(Long userId);

    Optional<UserMerchantStance> findByUserIdAndBusinessNumber(Long userId, String businessNumber);

    /** 개인정보 파기 흐름(PrivacyService)에 포함된다 — 사용자의 판단 기록도 사용자의 것이다. */
    @Modifying
    @Transactional
    @Query("delete from UserMerchantStance s where s.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
