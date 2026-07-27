package com.finntech.guardian.repository;

import com.finntech.guardian.domain.WeeklyMission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 조회는 전부 결정론적 정렬을 강제한다 (마스터 §4 원칙 3). */
public interface WeeklyMissionRepository extends JpaRepository<WeeklyMission, Long> {

    @Query("select m from WeeklyMission m where m.userId = :userId and m.periodStart = :periodStart "
            + "order by m.id desc")
    List<WeeklyMission> findByUserAndPeriod(@Param("userId") Long userId,
                                            @Param("periodStart") LocalDate periodStart);

    default Optional<WeeklyMission> findCurrent(Long userId, LocalDate periodStart) {
        List<WeeklyMission> found = findByUserAndPeriod(userId, periodStart);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** 평가되지 않은 채 기간이 끝난 미션 — 일요일 배치가 정산한다. */
    @Query("select m from WeeklyMission m where m.achieved is null and m.periodEnd <= :date "
            + "order by m.id asc")
    List<WeeklyMission> findUnevaluated(@Param("date") LocalDate date);
}
