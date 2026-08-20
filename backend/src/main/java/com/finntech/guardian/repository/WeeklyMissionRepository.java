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

    /**
     * 탈퇴·삭제요청 파기 (방침 6번).
     *
     * <p><b>지킴이 표가 파기에서 통째로 빠져 있었다</b>(2026-08-20 발견). 소비내역을 지워도
     * {@code guardian_transaction} 에 가맹점명과 금액이, {@code guardian_notification} 에
     * 그 소비를 두고 한 말이 그대로 남았다 — "삭제했다"고 해놓고 개인정보가 남는 것이
     * {@code PrivacyService} 가 처음부터 경계하던 실패 모양이다.
     */
    void deleteByUserId(Long userId);
}
