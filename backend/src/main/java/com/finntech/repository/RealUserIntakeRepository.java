package com.finntech.repository;

import com.finntech.domain.RealUserIntake;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RealUserIntakeRepository extends JpaRepository<RealUserIntake, Long> {

    Optional<RealUserIntake> findByTicket(String ticket);

    List<RealUserIntake> findByStatusOrderBySubmittedAtAsc(RealUserIntake.Status status);

    /**
     * 승인 처리용 조회 — <b>행을 잠근다.</b>
     *
     * <p>admin 둘이 같은 신청을 동시에 누르면 제공자에 두 번 들어간다. 결제 id 가 결정론이라
     * 결제는 중복되지 않지만, 감사에는 승인이 두 번 남고 두 사람 다 "내가 승인했다"가 된다.
     * 누가 승인했는지가 남아야 하는 표에서 그것은 기록이 아니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from RealUserIntake i where i.id = :id")
    Optional<RealUserIntake> findByIdForUpdate(@Param("id") Long id);

    /** 아무도 손대지 않은 신청은 만료된다 — 대기열은 창고가 아니라 통로다. */
    List<RealUserIntake> findByStatusAndExpiresAtBefore(RealUserIntake.Status status, LocalDateTime at);

    /** 같은 IP 가 하루에 몇 번 냈는가 — 대기열을 쓰레기로 채우는 것을 막는다. */
    long countBySubmittedIpAndSubmittedAtAfter(String ip, LocalDateTime after);
}
