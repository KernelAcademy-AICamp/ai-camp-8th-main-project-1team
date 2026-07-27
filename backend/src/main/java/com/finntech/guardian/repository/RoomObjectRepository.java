package com.finntech.guardian.repository;

import com.finntech.guardian.domain.RoomObject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** 조회는 전부 결정론적 정렬을 강제한다 (마스터 §4 원칙 3). */
public interface RoomObjectRepository extends JpaRepository<RoomObject, Long> {

    @Query("select o from RoomObject o where o.userId = :userId "
            + "order by o.acquiredDate asc, o.id asc")
    List<RoomObject> findByUser(@Param("userId") Long userId);

    boolean existsByUserIdAndObjectId(Long userId, String objectId);

    /** 이미 쓰고 있는 슬롯 — 새 사물을 빈 자리에 놓기 위해 본다. */
    @Query("select o.slotIndex from RoomObject o where o.userId = :userId and o.slotIndex is not null "
            + "order by o.slotIndex asc")
    List<Integer> findOccupiedSlots(@Param("userId") Long userId);
}
