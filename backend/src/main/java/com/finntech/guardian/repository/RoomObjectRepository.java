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
