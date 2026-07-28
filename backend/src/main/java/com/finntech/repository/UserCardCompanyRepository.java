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

    /**
     * 마이데이터를 연결한 사용자 id 목록(중복 제거). 자동 동기화 배치가 돌 대상이다.
     * 정렬을 고정해 배치 로그와 재현이 흔들리지 않게 한다(마스터 §4 원칙 3).
     */
    @Query("select distinct u.userId from UserCardCompany u order by u.userId asc")
    List<Long> findDistinctUserIds();

    /** 벌크 삭제(즉시 DML) — 재연동·파기 시 순서 보장. */
    @Modifying
    @Transactional
    @Query("delete from UserCardCompany u where u.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
