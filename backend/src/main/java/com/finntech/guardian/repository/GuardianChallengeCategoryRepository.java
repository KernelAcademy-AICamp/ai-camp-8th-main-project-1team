package com.finntech.guardian.repository;

import com.finntech.guardian.domain.GuardianChallengeCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GuardianChallengeCategoryRepository extends JpaRepository<GuardianChallengeCategory, Long> {

    /** 정렬 고정 — 조회 정렬은 결정론이어야 한다(마스터 §4 원칙 3). */
    @Query("select c from GuardianChallengeCategory c where c.challengeId = :challengeId "
            + "order by c.category asc")
    List<GuardianChallengeCategory> findByChallenge(@Param("challengeId") Long challengeId);
}
