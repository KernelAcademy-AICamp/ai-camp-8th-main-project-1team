package com.finntech.guardian.repository;

import com.finntech.guardian.domain.GuardianEnums.TxState;
import com.finntech.guardian.domain.GuardianTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 조회는 전부 결정론적 정렬을 강제한다 (마스터 §4 원칙 3).
 *
 * <p>상태는 JPQL 리터럴이 아니라 <b>파라미터로</b> 넘긴다 — 중첩 enum({@code GuardianEnums.TxState})의
 * 정규화 이름은 JPQL 파서마다 해석이 갈려 H2/MySQL 어느 한쪽에서 깨질 수 있다.
 * 기본값을 {@code default} 메서드로 감싸 호출부는 상태를 신경 쓰지 않는다.
 */
public interface GuardianTransactionRepository extends JpaRepository<GuardianTransaction, Long> {

    @Query("select t from GuardianTransaction t where t.challengeId = :challengeId "
            + "order by t.occurredAt asc, t.id asc")
    List<GuardianTransaction> findByChallenge(@Param("challengeId") Long challengeId);

    @Query("select t from GuardianTransaction t where t.userId = :userId "
            + "order by t.receivedAt desc, t.id desc")
    List<GuardianTransaction> findByUserRecent(@Param("userId") Long userId);

    @Query("select coalesce(sum(t.amount), 0) from GuardianTransaction t "
            + "where t.challengeId = :challengeId and t.state = :state")
    long sumByState(@Param("challengeId") Long challengeId, @Param("state") TxState state);

    /** 집계된 거래의 합. 되돌려진 건은 상태가 바뀌므로 자동으로 빠진다. */
    default long sumCounted(Long challengeId) {
        return sumByState(challengeId, TxState.COUNTED);
    }

    /**
     * 카테고리별 집계 합 — 홈의 '지킴 현황'을 카테고리로 갈라 보여주는 데 쓴다.
     *
     * <p>예전에는 챌린지 전체 한 줄만 보여줬다. 두 카테고리를 고른 사용자는 "어디서 더 썼는지"를
     * 알 수 없어, 무엇을 줄여야 할지 화면이 답해 주지 못했다(사용자 요청 2026-07-31).
     *
     * <p>정렬은 카테고리 이름으로 고정한다 — 조회 정렬은 결정론이어야 한다(마스터 §4 원칙 3).
     */
    @Query("select t.category, coalesce(sum(t.amount), 0) from GuardianTransaction t "
            + "where t.challengeId = :challengeId and t.state = :state "
            + "group by t.category order by t.category asc")
    List<Object[]> sumByCategory(@Param("challengeId") Long challengeId, @Param("state") TxState state);

    default List<Object[]> sumCountedByCategory(Long challengeId) {
        return sumByCategory(challengeId, TxState.COUNTED);
    }

    @Query("select t from GuardianTransaction t where t.challengeId = :challengeId "
            + "and t.state = :state and t.countedDate = :date order by t.occurredAt asc, t.id asc")
    List<GuardianTransaction> findByChallengeAndCountedDate(@Param("challengeId") Long challengeId,
                                                            @Param("state") TxState state,
                                                            @Param("date") LocalDate date);

    default List<GuardianTransaction> findCountedOn(Long challengeId, LocalDate date) {
        return findByChallengeAndCountedDate(challengeId, TxState.COUNTED, date);
    }

    @Query("select t from GuardianTransaction t where t.challengeId = :challengeId "
            + "and t.state = :state and t.countedDate between :from and :to "
            + "order by t.occurredAt asc, t.id asc")
    List<GuardianTransaction> findByChallengeAndCountedRange(@Param("challengeId") Long challengeId,
                                                             @Param("state") TxState state,
                                                             @Param("from") LocalDate from,
                                                             @Param("to") LocalDate to);

    /**
     * 기간 전체를 <b>한 번에</b> 읽는다. 하루씩 {@link #findCountedOn}을 도는 자리가 있었는데,
     * 주간 미션 판정은 그 방식이면 7번을 질의한다.
     */
    default List<GuardianTransaction> findCountedBetween(Long challengeId, LocalDate from, LocalDate to) {
        return findByChallengeAndCountedRange(challengeId, TxState.COUNTED, from, to);
    }

    @Query("select t from GuardianTransaction t where t.challengeId = :challengeId "
            + "and t.state = :state and t.occurredAt >= :since order by t.occurredAt asc, t.id asc")
    List<GuardianTransaction> findByChallengeAndOccurredSince(@Param("challengeId") Long challengeId,
                                                              @Param("state") TxState state,
                                                              @Param("since") LocalDateTime since);

    /**
     * <b>발생 시각</b> 기준 조회 — 시간대 습관(C9)을 재는 자리는 집계일로 세면 안 된다.
     *
     * <p>다른 질의가 {@code countedDate}를 쓰는 것은 "그날의 지출로 얼마를 셌나"를 묻기 때문이다.
     * 여기서 묻는 것은 "그 사람이 언제 결제하나"이고, 늦게 도착한 결제는 집계일이 도착일로 밀린다.
     * 그것을 세면 "금요일 19시에 3번"의 근거가 실제 습관이 아니라 전송 지연이 된다.
     */
    default List<GuardianTransaction> findCountedOccurredSince(Long challengeId, LocalDateTime since) {
        return findByChallengeAndOccurredSince(challengeId, TxState.COUNTED, since);
    }

    @Query("select coalesce(sum(t.amount), 0) from GuardianTransaction t "
            + "where t.challengeId = :challengeId and t.countedDate <= :date and t.state = :state")
    long sumUntil(@Param("challengeId") Long challengeId, @Param("date") LocalDate date,
                  @Param("state") TxState state);

    /** 집계 대상 날짜까지의 누적 — 일 판정은 '그날까지'를 봐야 페이스가 맞는다. */
    default long sumCountedUntil(Long challengeId, LocalDate date) {
        return sumUntil(challengeId, date, TxState.COUNTED);
    }

    @Query("select count(t) from GuardianTransaction t where t.challengeId = :challengeId "
            + "and t.category = :category and t.state = :state")
    int countByCategoryAndState(@Param("challengeId") Long challengeId, @Param("category") String category,
                                @Param("state") TxState state);

    default int countCountedByCategory(Long challengeId, String category) {
        return countByCategoryAndState(challengeId, category, TxState.COUNTED);
    }

    @Query("select count(t) from GuardianTransaction t where t.challengeId = :challengeId "
            + "and t.category = :category and t.state = :state "
            + "and t.countedDate >= :from and t.countedDate <= :to")
    int countByCategoryInRange(@Param("challengeId") Long challengeId, @Param("category") String category,
                               @Param("state") TxState state,
                               @Param("from") LocalDate from, @Param("to") LocalDate to);

    default int countCountedByCategoryInRange(Long challengeId, String category, LocalDate from, LocalDate to) {
        return countByCategoryInRange(challengeId, category, TxState.COUNTED, from, to);
    }

    @Query("select coalesce(sum(t.amount), 0) from GuardianTransaction t "
            + "where t.challengeId = :challengeId and t.countedDate = :date "
            + "and t.micro = true and t.state = :state")
    long sumMicroOnDateAndState(@Param("challengeId") Long challengeId, @Param("date") LocalDate date,
                                @Param("state") TxState state);

    /** 오늘 잔돈 버킷 합계 — C8 판정용. */
    default long sumMicroOnDate(Long challengeId, LocalDate date) {
        return sumMicroOnDateAndState(challengeId, date, TxState.COUNTED);
    }

    @Query("select t from GuardianTransaction t where t.challengeId = :challengeId "
            + "and t.state = :state and t.undoDeadline <= :at order by t.id asc")
    List<GuardianTransaction> findByStateAndUndoDeadlineBefore(@Param("challengeId") Long challengeId,
                                                               @Param("state") TxState state,
                                                               @Param("at") LocalDateTime at);

    /** 되돌리기 유예가 끝난 거래 — 배치가 여기서 판정을 확정한다. */
    default List<GuardianTransaction> findExpiredUndo(Long challengeId, LocalDateTime at) {
        return findByStateAndUndoDeadlineBefore(challengeId, TxState.COUNTED, at);
    }

    @Query("select t from GuardianTransaction t where t.challengeId = :challengeId "
            + "and t.state = :state order by t.id asc")
    List<GuardianTransaction> findByChallengeAndState(@Param("challengeId") Long challengeId,
                                                      @Param("state") TxState state);

    default List<GuardianTransaction> findPendingCategory(Long challengeId) {
        return findByChallengeAndState(challengeId, TxState.PENDING_CATEGORY);
    }

    /** 마이데이터 투영에서 이미 끌어온 소비인가 — 중복 적재 방지. */
    boolean existsByUserIdAndSourceConsumptionId(Long userId, Long sourceConsumptionId);
}
