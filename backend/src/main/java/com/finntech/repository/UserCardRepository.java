package com.finntech.repository;

import com.finntech.domain.UserCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface UserCardRepository extends JpaRepository<UserCard, Long> {
    List<UserCard> findByUserIdOrderByIdAsc(Long userId);
    Optional<UserCard> findByUserIdAndSerialNumber(Long userId, String serialNumber);

    /**
     * 벌크 삭제(즉시 DML). 재연동 시 delete→insert 순서가 뒤바뀌어(Hibernate가 insert를 먼저 flush)
     * unique 위반이 나는 것을 막기 위해 파생 delete가 아닌 벌크 쿼리로 즉시 실행한다.
     */
    @Modifying
    @Transactional
    @Query("delete from UserCard c where c.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
