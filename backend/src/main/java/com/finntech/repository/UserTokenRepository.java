package com.finntech.repository;

import com.finntech.domain.UserToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {

    /** 조회는 항상 해시로 한다 — 원문 토큰은 저장되어 있지 않다. */
    Optional<UserToken> findByTokenHash(String tokenHash);

    void deleteByTokenHash(String tokenHash);

    /** 그 주체의 토큰을 모두 폐기한다(비밀번호 변경·강제 로그아웃). */
    @Modifying
    @Query("delete from UserToken t where t.role = :role and t.subjectId = :subjectId")
    int deleteAllOf(@Param("role") UserToken.Role role, @Param("subjectId") Long subjectId);

    /** 만료된 것은 남겨 둘 이유가 없다. 표가 무한히 자라는 것도 막는다. */
    @Modifying
    @Query("delete from UserToken t where t.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
