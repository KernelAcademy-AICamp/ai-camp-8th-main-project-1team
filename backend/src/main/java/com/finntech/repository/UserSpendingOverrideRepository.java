package com.finntech.repository;

import com.finntech.domain.UserSpendingOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface UserSpendingOverrideRepository extends JpaRepository<UserSpendingOverride, Long> {

    List<UserSpendingOverride> findByUserId(Long userId);

    @Modifying
    @Transactional
    @Query("delete from UserSpendingOverride o where o.userId = :userId and o.category2 = :category2")
    void deleteByUserIdAndCategory2(@Param("userId") Long userId, @Param("category2") String category2);

    /** 파기(개인정보 삭제) 시 함께 제거. */
    @Modifying
    @Transactional
    @Query("delete from UserSpendingOverride o where o.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
