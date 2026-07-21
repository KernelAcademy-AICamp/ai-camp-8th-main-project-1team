package com.finntech.repository;

import com.finntech.domain.GoalMilestone;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 목표 마일스톤 조회 (문서 §5-5). 정렬 고정으로 재현성 유지. */
public interface GoalMilestoneRepository extends JpaRepository<GoalMilestone, Long> {

    List<GoalMilestone> findByGoalIdOrderBySortOrderAscIdAsc(Long goalId);

    long countByGoalId(Long goalId);

    /** 목표 삭제 시 그 목표의 마일스톤도 함께 지운다. */
    void deleteByGoalId(Long goalId);

    /** 개인정보 파기 대상 — 사용자 삭제 시 마일스톤도 함께 지운다(§5-3 잔재 방지). */
    void deleteByUserId(Long userId);
}
