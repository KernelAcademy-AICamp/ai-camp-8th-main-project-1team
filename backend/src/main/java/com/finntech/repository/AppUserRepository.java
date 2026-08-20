package com.finntech.repository;

import com.finntech.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /**
     * 이 사용자가 <b>실제 사람</b>인가 — 유료 모델(Gemini)을 부르기 전에 묻는다.
     *
     * <p><b>Gemini 호출은 실사용자 것만이다</b>(사용자 규칙 2026-08-08). 더미에도 모델이 필요한
     * 일이 생기면 그건 무료 통로(NVIDIA)여야 하고, 그나마도 되도록 안 부른다 — 생성기가 만든
     * 소비에 대해 문장을 지어 내는 데 돈을 쓸 이유가 없다. 실측으로 지킴이 문장 호출 14건 중
     * 13건이 더미 몫이었다(2026-08-07 재감사).
     *
     * <p>막아도 화면은 정상이다. 문장 생성기들은 <b>고정 템플릿 폴백이 먼저</b>인 구조라
     * (설계서 §5.4), AI 를 안 부르면 템플릿 문장이 그대로 나간다.
     */
    boolean existsByIdAndRealPersonTrue(Long id);
    Optional<AppUser> findByNickname(String nickname);

    /**
     * CI는 신원이다 — 한 사람은 한 계정이어야 한다.
     *
     * <p>본인인증이 계정을 '고르는' 근거로 쓴다. 클라이언트가 보낸 userId를 믿고 그 계정에 CI를
     * 쓰면, 앞사람이 쓰던 브라우저에서 다른 사람이 인증했을 때 <b>계정이 통째로 다른 사람이 된다</b>.
     * 실제로 겪었다(2026-07-31 — app_user 1이 이승진에서 원소희로 바뀌고 앞사람의 챌린지가 남았다).
     */
    Optional<AppUser> findByCi(String ci);

    /**
     * 같은 나이대의 <b>다른</b> 사람들. 리포트의 '또래 비교'가 견줄 모집단이다.
     *
     * <p>나이대를 ±N년으로 잡는 이유: 출생연도가 같은 사람만 세면 표본이 한 자리로 떨어져
     * 한 사람의 큰 결제가 중앙값을 흔든다. 넓히면 흔들림은 줄지만 '또래'가 아니게 되므로,
     * 폭은 부르는 쪽이 정한다.
     *
     * <p>자기 자신은 뺀다 — 내 소비가 또래 값에 섞이면 비교가 자기 자신과의 비교가 된다.
     */
    @org.springframework.data.jpa.repository.Query(
            "select u.id from AppUser u where u.birthYear is not null "
            + "and u.birthYear between :from and :to and u.id <> :exclude")
    java.util.List<Long> findPeerIds(@org.springframework.data.repository.query.Param("from") int from,
                                     @org.springframework.data.repository.query.Param("to") int to,
                                     @org.springframework.data.repository.query.Param("exclude") Long exclude);
}
