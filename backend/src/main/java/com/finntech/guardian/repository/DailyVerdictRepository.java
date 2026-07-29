package com.finntech.guardian.repository;

import com.finntech.guardian.domain.DailyVerdict;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 조회는 전부 결정론적 정렬을 강제한다 (마스터 §4 원칙 3). */
public interface DailyVerdictRepository extends JpaRepository<DailyVerdict, Long> {

    Optional<DailyVerdict> findByChallengeIdAndVerdictDate(Long challengeId, LocalDate verdictDate);

    @Query("select v from DailyVerdict v where v.challengeId = :challengeId "
            + "order by v.verdictDate asc, v.id asc")
    List<DailyVerdict> findByChallenge(@Param("challengeId") Long challengeId);

    /** 잔디 — 최근 N일. 정렬은 화면이 아니라 여기서 고정한다. */
    @Query("select v from DailyVerdict v where v.challengeId = :challengeId "
            + "and v.verdictDate >= :from order by v.verdictDate asc, v.id asc")
    List<DailyVerdict> findSince(@Param("challengeId") Long challengeId, @Param("from") LocalDate from);

    /** 한 주의 판정 — 위기 방어가 '한 주를 버텼는지' 보려면 그 주 전체가 필요하다(설계서 §6). */
    @Query("select v from DailyVerdict v where v.challengeId = :challengeId "
            + "and v.verdictDate between :from and :to order by v.verdictDate asc, v.id asc")
    List<DailyVerdict> findRange(@Param("challengeId") Long challengeId,
                                 @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 아직 안 본 세리머니. <b>아침 세리머니는 푸시로 보내지 않는다</b> — 홈의 미개봉 뱃지 하나가
     * 유일한 신호이고, 사용자가 탭하면 모달이 뜨며 {@code ceremonySeenAt}이 기록된다(설계서 §API 3).
     */
    @Query("select v from DailyVerdict v where v.userId = :userId "
            + "and v.grantObject = true and v.ceremonySeenAt is null "
            + "order by v.verdictDate desc, v.id desc")
    List<DailyVerdict> findUnseenCeremonies(@Param("userId") Long userId);
}
