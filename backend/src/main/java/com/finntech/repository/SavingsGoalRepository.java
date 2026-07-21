package com.finntech.repository;

import com.finntech.domain.SavingsGoal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 저축 목표 버킷 조회 (문서 §5-5). 정렬 고정으로 재현성 유지. */
public interface SavingsGoalRepository extends JpaRepository<SavingsGoal, Long> {

    List<SavingsGoal> findByUserIdOrderBySortOrderAscIdAsc(Long userId);

    long countByUserId(Long userId);

    /** 개인정보 파기 대상 — 사용자 삭제 시 목표도 함께 지운다(§5-3 잔재 방지). */
    void deleteByUserId(Long userId);
}
