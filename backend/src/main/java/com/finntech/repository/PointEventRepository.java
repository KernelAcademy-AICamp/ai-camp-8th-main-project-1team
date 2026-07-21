package com.finntech.repository;

import com.finntech.domain.Enums;
import com.finntech.domain.PointEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 포인트 이벤트 조회 (문서 §5-5). 조회는 결정론적 정렬을 강제한다(재현성, §4 원칙 3). */
public interface PointEventRepository extends JpaRepository<PointEvent, Long> {

    @Query("select p from PointEvent p where p.userId = :userId "
            + "order by p.occurredAt asc, p.id asc")
    List<PointEvent> findAllForUser(@Param("userId") Long userId);

    /** 예치 순번(랜덤 목표 회전)용 — 지금까지 DEPOSIT 건수. */
    long countByUserIdAndType(Long userId, Enums.PointEventType type);

    @Query("select coalesce(sum(p.amount), 0) from PointEvent p "
            + "where p.userId = :userId and p.type = :type")
    BigDecimal sumByType(@Param("userId") Long userId, @Param("type") Enums.PointEventType type);

    @Query("select coalesce(sum(p.amount), 0) from PointEvent p "
            + "where p.userId = :userId and p.type = :type "
            + "and p.occurredAt >= :from and p.occurredAt < :to")
    BigDecimal sumByTypeInRange(@Param("userId") Long userId, @Param("type") Enums.PointEventType type,
                               @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 목표 버킷별 입금·차감 합계 (goalBalance 파생용). */
    @Query("select coalesce(sum(p.amount), 0) from PointEvent p "
            + "where p.userId = :userId and p.type = :type and p.goalId = :goalId")
    BigDecimal sumByTypeAndGoal(@Param("userId") Long userId, @Param("type") Enums.PointEventType type,
                               @Param("goalId") Long goalId);

    /** 개인정보 파기 대상 — 사용자 삭제 시 포인트 이벤트도 함께 지운다(파생 데이터 잔재 방지, §5-3). */
    void deleteByUserId(Long userId);

    /** 목표 버킷 삭제 시 그 목표의 입출금 이벤트를 함께 지운다(잔액 소멸). */
    void deleteByGoalId(Long goalId);
}
