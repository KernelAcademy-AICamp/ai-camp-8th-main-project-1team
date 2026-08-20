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

    /**
     * 그 챌린지들의 카테고리를 지운다 — 파기가 <b>챌린지보다 먼저</b> 부르는 자리다.
     *
     * <p>이 표는 {@code challenge_id} 에 외래키로 매달려 있어(V13), 챌린지를 먼저 지우면
     * 제약이 막는다. 사용자 열쇠가 없으므로 챌린지 id 로 받는다.
     */
    void deleteByChallengeIdIn(List<Long> challengeIds);
}
