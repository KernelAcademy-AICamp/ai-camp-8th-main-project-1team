package com.finntech.repository;

import com.finntech.domain.Coupon;
import com.finntech.domain.Enums;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 치팅데이 쿠폰 조회 (문서 §5-5 Phase 3). 정렬 고정으로 재현성 유지. */
public interface CouponRepository extends JpaRepository<Coupon, Long> {

    long countByUserId(Long userId);

    /** 아직 결정하지 않은(제안 상태) 쿠폰 — 하나만 노출한다. */
    Optional<Coupon> findFirstByUserIdAndStatusOrderByIdDesc(Long userId, Enums.CouponStatus status);

    List<Coupon> findByUserIdAndStatus(Long userId, Enums.CouponStatus status);

    /** 개인정보 파기 대상 — 사용자 삭제 시 쿠폰도 함께 지운다(§5-3 잔재 방지). */
    void deleteByUserId(Long userId);
}
