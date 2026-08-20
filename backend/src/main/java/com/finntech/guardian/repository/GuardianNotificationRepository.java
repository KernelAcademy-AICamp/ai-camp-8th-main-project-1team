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

    // ======================================================================
    //  관측 (2026-08-02) — 이 시스템은 자기 상태를 성실히 기록하는데 <b>보는 눈이 없었다</b>.
    //  침묵도 남기고(delivery=SILENT + suppressedReason), LLM 폴백 여부도 남기고,
    //  프롬프트 버전도 다는데, 그걸 세는 질의가 하나도 없었다. §8-U가 배운 것과 같은 형태다 —
    //  <b>재지 않으면 통과로 보인다.</b>
    //
    //  정렬을 고정한다(마스터 §4 원칙 3). 집계도 조회이므로 같은 입력이면 같은 순서여야 한다.
    // ======================================================================

    /** 배달 유형별 건수 — 말한 것 대 침묵한 것. */
    @Query("select n.delivery, count(n) from GuardianNotification n "
            + "where n.sentAt >= :since group by n.delivery order by n.delivery asc")
    List<Object[]> countByDeliverySince(@Param("since") LocalDateTime since);

    /** 침묵 사유별 건수 — 예산 소진인가, 쿨다운인가, 야간인가, 원래 안 말하는 케이스인가. */
    @Query("select n.suppressedReason, count(n) from GuardianNotification n "
            + "where n.sentAt >= :since and n.suppressedReason is not null "
            + "group by n.suppressedReason order by n.suppressedReason asc")
    List<Object[]> countBySuppressedReasonSince(@Param("since") LocalDateTime since);

    /** 케이스별 건수 — C3·C6이 실제로 얼마나 나가는지. */
    @Query("select n.caseId, count(n) from GuardianNotification n "
            + "where n.sentAt >= :since group by n.caseId order by n.caseId asc")
    List<Object[]> countByCaseSince(@Param("since") LocalDateTime since);

    /** LLM 폴백 건수 — 목표는 5% 이하다(GuardianNarrative). 재지 않으면 알 수 없다. */
    @Query("select count(n) from GuardianNotification n "
            + "where n.sentAt >= :since and n.delivery <> :silent and n.fallback = true")
    long countFallbackSince(@Param("since") LocalDateTime since, @Param("silent") DeliveryKind silent);

    @Query("select count(n) from GuardianNotification n "
            + "where n.sentAt >= :since and n.delivery <> :silent")
    long countSpokenSince(@Param("since") LocalDateTime since, @Param("silent") DeliveryKind silent);

    default long countFallbackSince(LocalDateTime since) {
        return countFallbackSince(since, DeliveryKind.SILENT);
    }

    default long countSpokenSince(LocalDateTime since) {
        return countSpokenSince(since, DeliveryKind.SILENT);
    }

    /** 피드백 분포 — 도움이 됐다/안 됐다, 그리고 사유. 판정 품질의 유일한 사용자 신호다. */
    @Query("select n.feedback, n.feedbackReason, count(n) from GuardianNotification n "
            + "where n.sentAt >= :since and n.feedback is not null "
            + "group by n.feedback, n.feedbackReason order by n.feedback asc, n.feedbackReason asc")
    List<Object[]> countByFeedbackSince(@Param("since") LocalDateTime since);

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
