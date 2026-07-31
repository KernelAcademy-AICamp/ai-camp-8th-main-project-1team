package com.finntech.repository;

import com.finntech.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByNickname(String nickname);

    /**
     * CI는 신원이다 — 한 사람은 한 계정이어야 한다.
     *
     * <p>본인인증이 계정을 '고르는' 근거로 쓴다. 클라이언트가 보낸 userId를 믿고 그 계정에 CI를
     * 쓰면, 앞사람이 쓰던 브라우저에서 다른 사람이 인증했을 때 <b>계정이 통째로 다른 사람이 된다</b>.
     * 실제로 겪었다(2026-07-31 — app_user 1이 이승진에서 원소희로 바뀌고 앞사람의 챌린지가 남았다).
     */
    Optional<AppUser> findByCi(String ci);
}
