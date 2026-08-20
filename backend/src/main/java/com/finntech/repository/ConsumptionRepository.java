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

    /**
     * 결제 한 건에서 생긴 소비를 되찾는다 — 분류를 고칠 때 짝을 맞추기 위해서다.
     *
     * <p>{@code sourcePaymentId} 는 적재할 때 달아 둔 결제 키다(V15 주석). 원장이 나중에 이 소비의
     * 가맹점을 되찾는 유일한 길이고, 분류 확정이 리포트까지 닿는 길이기도 하다.
     */
    java.util.List<Consumption> findBySourcePaymentId(String sourcePaymentId);

    /**
     * 사람별 기간 지출 합계 — 또래 비교가 중앙값을 내려고 쓴다.
     *
     * <p>한 사람씩 부르지 않는 이유는 또래가 수천 명이 될 수 있기 때문이다. 합계만 받아
     * 오므로 <b>남의 결제 내역은 애플리케이션에 들어오지 않는다</b> — 비교에 필요한 것은
     * 숫자 하나뿐이고, 그보다 많이 받아 오면 그 자체가 개인정보 처리다.
     */
    @Query("select c.userId, sum(c.amount) from Consumption c "
            + "where c.userId in :userIds and c.occurredAt >= :from and c.occurredAt < :to "
            + "group by c.userId")
    List<Object[]> sumByUserInRange(@Param("userIds") List<Long> userIds,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to);
}
