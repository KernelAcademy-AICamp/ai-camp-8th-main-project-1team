package com.finntech.repository;

import com.finntech.domain.SpendingLedgerDirty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 소비 원장 재작성 대기열 (V34). */
public interface SpendingLedgerDirtyRepository extends JpaRepository<SpendingLedgerDirty, Long> {

    /**
     * 다음에 쓸 사용자 — <b>정렬 고정</b>(마스터 §4 원칙 3)이라 같은 대기열이면 같은 순서다.
     *
     * <p>{@code attempts} 상한을 넘긴 사용자는 건너뛴다. 안 그러면 계속 터지는 한 사용자가
     * 배수를 통째로 붙잡아 뒤에 선 사람들이 한 번도 안 써진다.
     */
    @Query("""
            select min(d.userId) from SpendingLedgerDirty d
            where d.attempts < :maxAttempts
            """)
    Long findNextUserId(@Param("maxAttempts") int maxAttempts);

    /** 아직 못 쓴 사용자들 — 운영 점검이 보여 준다. 정렬 고정. */
    @Query("select distinct d.userId from SpendingLedgerDirty d order by d.userId")
    List<Long> findDistinctUserIds();

    /** 상한에 걸려 멈춘 사용자들 — 사람이 봐야 할 목록이다. */
    @Query("""
            select distinct d.userId from SpendingLedgerDirty d
            where d.attempts >= :maxAttempts order by d.userId
            """)
    List<Long> findStuckUserIds(@Param("maxAttempts") int maxAttempts);

    /**
     * 이 사용자에게 지금 걸린 표시 중 가장 나중 것 — <b>수위 표시(watermark)</b>.
     *
     * <p>재작성을 시작하기 전에 이 값을 손에 쥐고, 끝난 뒤 <b>이 값 이하만</b> 지운다.
     * 그러면 재작성이 도는 동안 들어온 표시는 번호가 더 커서 살아남고, 다음 회차가 그 사용자를
     * 한 번 더 쓴다. 재작성이 멱등이라 한 번 더 도는 것은 손해가 아니지만, 놓치는 것은 손해다.
     */
    @Query("select max(d.id) from SpendingLedgerDirty d where d.userId = :userId")
    Long findWatermark(@Param("userId") Long userId);

    /** 수위 표시 이하만 치운다 — 그 사이 들어온 표시는 남긴다. */
    @Modifying
    @Transactional
    @Query("delete from SpendingLedgerDirty d where d.userId = :userId and d.id <= :watermark")
    int clearUpTo(@Param("userId") Long userId, @Param("watermark") Long watermark);

    /** 실패를 기록한다 — 상한을 넘으면 그 사용자는 배수에서 빠진다. */
    @Modifying
    @Transactional
    @Query("update SpendingLedgerDirty d set d.attempts = d.attempts + 1 where d.userId = :userId")
    int noteFailure(@Param("userId") Long userId);

    /** 파기 — 지워진 사용자의 표시를 남겨 두면 배수가 헛걸음하며 로그를 흐린다. */
    @Modifying
    @Transactional
    @Query("delete from SpendingLedgerDirty d where d.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    /** 가장 오래 기다린 표시 — 배수가 멈췄는지 보는 계기판. */
    @Query("select min(d.markedAt) from SpendingLedgerDirty d")
    LocalDateTime findOldestMarkedAt();
}
