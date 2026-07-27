package com.finntech.guardian.repository;

import com.finntech.guardian.domain.GuardianEnums.DeliveryKind;
import com.finntech.guardian.domain.GuardianNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 조회는 전부 결정론적 정렬을 강제한다 (마스터 §4 원칙 3). */
public interface GuardianNotificationRepository extends JpaRepository<GuardianNotification, Long> {

    @Query("select count(n) from GuardianNotification n where n.userId = :userId "
            + "and n.delivery = :delivery and n.sentAt >= :from and n.sentAt < :to")
    int countByDeliveryInRange(@Param("userId") Long userId, @Param("delivery") DeliveryKind delivery,
                               @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 오늘 푸시로 나간 알림 수 — 하루 예산(기본 2건) 판정용. */
    default int countPushToday(Long userId, LocalDateTime dayStart, LocalDateTime dayEnd) {
        return countByDeliveryInRange(userId, DeliveryKind.PUSH, dayStart, dayEnd);
    }

    /**
     * 쿨다운 판정용 — <b>실제로 말한 것만</b> 센다.
     * 침묵 기록(SILENT)까지 세면 "쿨다운 때문에 침묵" → "그 침묵이 다시 쿨다운을 연장"하는
     * 자기 참조가 생겨 케이스가 영원히 안 열린다.
     */
    @Query("select n from GuardianNotification n where n.challengeId = :challengeId "
            + "and n.delivery <> :silent and n.sentAt >= :since "
            + "order by n.sentAt desc, n.id desc")
    List<GuardianNotification> findSpokenSince(@Param("challengeId") Long challengeId,
                                               @Param("silent") DeliveryKind silent,
                                               @Param("since") LocalDateTime since);

    default List<GuardianNotification> findSpokenSince(Long challengeId, LocalDateTime since) {
        return findSpokenSince(challengeId, DeliveryKind.SILENT, since);
    }

    /** 챌린지 전체에서 말한 이력 — CHALLENGE 쿨다운(1회 한정) 판정용. */
    @Query("select n from GuardianNotification n where n.challengeId = :challengeId "
            + "and n.delivery <> :silent order by n.sentAt desc, n.id desc")
    List<GuardianNotification> findAllSpoken(@Param("challengeId") Long challengeId,
                                             @Param("silent") DeliveryKind silent);

    default List<GuardianNotification> findAllSpoken(Long challengeId) {
        return findAllSpoken(challengeId, DeliveryKind.SILENT);
    }

    /** 목록은 침묵을 뺀다 — 침묵 기록은 지표 계산용이지 사용자에게 보일 것이 아니다. */
    @Query("select n from GuardianNotification n where n.userId = :userId "
            + "and n.delivery <> :silent order by n.sentAt desc, n.id desc")
    List<GuardianNotification> findVisible(@Param("userId") Long userId, @Param("silent") DeliveryKind silent);

    default List<GuardianNotification> findVisible(Long userId) {
        return findVisible(userId, DeliveryKind.SILENT);
    }

    @Query("select count(n) from GuardianNotification n where n.userId = :userId "
            + "and n.delivery <> :silent and n.readAt is null")
    int countUnread(@Param("userId") Long userId, @Param("silent") DeliveryKind silent);

    default int countUnread(Long userId) {
        return countUnread(userId, DeliveryKind.SILENT);
    }
}
