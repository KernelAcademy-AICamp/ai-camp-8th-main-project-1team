package com.finntech.repository;

import com.finntech.domain.Enums;
import com.finntech.domain.WishlistItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 고민 목록 조회 (문서 §5-5). 정렬 고정으로 재현성 유지. */
public interface WishlistItemRepository extends JpaRepository<WishlistItem, Long> {

    List<WishlistItem> findByUserIdOrderByIdDesc(Long userId);

    List<WishlistItem> findByUserIdAndStatusOrderByIdDesc(Long userId, Enums.WishlistStatus status);

    /** 개인정보 파기 대상 — 사용자 삭제 시 고민 목록도 함께 지운다(§5-3 잔재 방지). */
    void deleteByUserId(Long userId);
}
