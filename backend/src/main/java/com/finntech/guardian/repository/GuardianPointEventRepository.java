package com.finntech.guardian.repository;

import com.finntech.guardian.domain.GuardianPointEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/** 조회는 전부 결정론적 정렬을 강제한다 (마스터 §4 원칙 3). */
public interface GuardianPointEventRepository extends JpaRepository<GuardianPointEvent, Long> {

    /** 이번 주 이미 적립한 양 — 주간 상한(기본 100P) 계산의 기준. */
    @Query("select coalesce(sum(e.cappedAmount), 0) from GuardianPointEvent e "
            + "where e.userId = :userId and e.weekStart = :weekStart")
    int sumCappedInWeek(@Param("userId") Long userId, @Param("weekStart") LocalDate weekStart);

    @Query("select e from GuardianPointEvent e where e.userId = :userId "
            + "order by e.confirmedAt desc, e.id desc")
    List<GuardianPointEvent> findByUserRecent(@Param("userId") Long userId);

    @Query("select coalesce(sum(e.cappedAmount), 0) from GuardianPointEvent e where e.userId = :userId")
    int sumAll(@Param("userId") Long userId);

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
