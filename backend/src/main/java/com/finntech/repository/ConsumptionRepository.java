package com.finntech.repository;

import com.finntech.domain.Consumption;
import com.finntech.domain.Enums;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 조회는 전부 결정론적 정렬을 강제한다 — 재현성 검증의 전제 (문서 §4 원칙 3). */
public interface ConsumptionRepository extends JpaRepository<Consumption, Long> {

    @Query("select c from Consumption c join fetch c.category "
            + "where c.userId = :userId order by c.occurredAt asc, c.id asc")
    List<Consumption> findAllForUser(@Param("userId") Long userId);

    @Query("select c from Consumption c join fetch c.category "
            + "where c.userId = :userId and c.occurredAt >= :from and c.occurredAt < :to "
            + "order by c.occurredAt asc, c.id asc")
    List<Consumption> findInRange(@Param("userId") Long userId,
                                  @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to);

    long countByUserIdAndSource(Long userId, Enums.DataSource source);

    /** 마이데이터 재연동 시 기존 MYDATA 투영을 정리하고 새로 적재한다(전체 동기화, §13-3). 벌크 삭제로 즉시 실행(insert 순서 역전 방지). */
    @Modifying
    @Transactional
    @Query("delete from Consumption c where c.userId = :userId and c.source = :source")
    void deleteByUserIdAndSource(@Param("userId") Long userId, @Param("source") Enums.DataSource source);

    /** 보유기간 초과분 파기용 (개인정보 처리방침 3·4번). DUMMY_SEED는 대상이 아니다. */
    List<Consumption> findBySourceAndOccurredAtBefore(Enums.DataSource source, LocalDateTime cutoff);

    @Query("select min(c.occurredAt) from Consumption c "
            + "where c.userId = :userId and c.source = :source")
    LocalDateTime findEarliest(@Param("userId") Long userId,
                               @Param("source") Enums.DataSource source);
}
