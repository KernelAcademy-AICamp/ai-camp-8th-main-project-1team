package com.finntech.repository;

import com.finntech.domain.UserCardCompany;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface UserCardCompanyRepository extends JpaRepository<UserCardCompany, Long> {
    List<UserCardCompany> findByUserIdOrderByCompanyIdAsc(Long userId);
    Optional<UserCardCompany> findByUserIdAndCompanyId(Long userId, Long companyId);

    /** 벌크 삭제(즉시 DML) — 재연동·파기 시 순서 보장. */
    @Modifying
    @Transactional
    @Query("delete from UserCardCompany u where u.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
