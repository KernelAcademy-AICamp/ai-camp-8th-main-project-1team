package com.finntech.guardian.repository;

import com.finntech.guardian.domain.GuardianChallenge;
import com.finntech.guardian.domain.GuardianEnums.ChallengeState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 조회는 전부 결정론적 정렬을 강제한다 — 재현성 검증의 전제 (마스터 §4 원칙 3). */
public interface GuardianChallengeRepository extends JpaRepository<GuardianChallenge, Long> {

    /**
     * 진행 중인 챌린지. 설계서의 부분 유니크 인덱스({@code where state in (...)})는 MySQL·H2가
     * 지원하지 않으므로, 하나뿐임을 서비스 계층이 이 조회로 강제한다.
     */
    @Query("select c from GuardianChallenge c where c.userId = :userId "
            + "and c.state in :states order by c.id desc")
    List<GuardianChallenge> findByUserIdAndStateIn(@Param("userId") Long userId,
                                                   @Param("states") Collection<ChallengeState> states);

    default Optional<GuardianChallenge> findRunning(Long userId) {
        List<GuardianChallenge> found = findByUserIdAndStateIn(userId,
                List.of(ChallengeState.ACTIVE, ChallengeState.AT_RISK, ChallengeState.EXCEEDED));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    List<GuardianChallenge> findByUserIdOrderByIdDesc(Long userId);

    /** 종료일이 지난 진행 중 챌린지 — 새벽 배치가 SETTLING으로 넘긴다. */
    @Query("select c from GuardianChallenge c where c.state in :states "
            + "and c.endDate < :date order by c.id asc")
    List<GuardianChallenge> findDue(@Param("states") Collection<ChallengeState> states,
                                    @Param("date") LocalDate date);
}
