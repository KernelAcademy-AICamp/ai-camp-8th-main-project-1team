package com.finntech.auth;

import com.finntech.domain.UserToken;
import com.finntech.repository.UserTokenRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 인증 토큰의 발급·검증·폐기.
 *
 * <p>이 앱은 <b>비밀번호를 만들지 않는다</b> — 실사용자는 이미 본인인증(가상 CI)으로 신원을
 * 확인하므로, {@code AuthService.verifyAssumed} 가 성공한 자리에서 토큰을 하나 더 돌려주면
 * 그것이 곧 로그인이다. admin 만 비밀번호를 갖는다.
 *
 * <p>발급된 원문 토큰은 <b>이 메서드가 돌려주는 그 순간에만</b> 존재한다. DB 에는 지문만 남는다.
 */
@Service
public class AuthTokenService {

    /** 사용자 토큰 수명. 매번 본인인증을 다시 시키지 않으려는 값이다. */
    private final Duration userTtl;
    /**
     * admin 세션 수명 — 사용자보다 훨씬 짧다.
     *
     * <p>승인 권한이 켜진 화면을 자리 비운 사이 방치하면 안 되기 때문이다. 활동이 있으면
     * 만료를 미룬다(슬라이딩)이라, 일하는 중에 끊기지는 않는다.
     */
    private final Duration adminTtl;

    private final UserTokenRepository repository;
    private final Clock clock;

    public AuthTokenService(UserTokenRepository repository, Clock clock,
                            @Value("${finntech.auth.user-token-ttl-days:30}") long userTtlDays,
                            @Value("${finntech.auth.admin-session-minutes:30}") long adminMinutes) {
        this.repository = repository;
        this.clock = clock;
        this.userTtl = Duration.ofDays(userTtlDays);
        this.adminTtl = Duration.ofMinutes(adminMinutes);
    }

    /** 발급 결과 — {@code raw} 는 여기서만 볼 수 있다. */
    public record Issued(String raw, LocalDateTime expiresAt) {}

    @Transactional
    public Issued issue(UserToken.Role role, Long subjectId, HttpServletRequest request) {
        String raw = Tokens.newToken();
        LocalDateTime now = LocalDateTime.now(clock);
        Duration ttl = role == UserToken.Role.ADMIN ? adminTtl : userTtl;
        LocalDateTime expiresAt = now.plus(ttl);
        repository.save(new UserToken(Tokens.hash(raw), subjectId, role, now, expiresAt,
                clientIp(request), userAgent(request)));
        return new Issued(raw, expiresAt);
    }

    /**
     * 토큰을 확인한다. 만료·미존재면 비어 있다.
     *
     * <p>admin 세션만 만료를 미룬다. 사용자 토큰까지 미루면 <b>모든 조회가 쓰기 트랜잭션</b>이
     * 되어, 읽기 전용이어야 할 화면들이 매번 DB 를 건드리게 된다.
     */
    @Transactional
    public Optional<UserToken> verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        LocalDateTime now = LocalDateTime.now(clock);
        return repository.findByTokenHash(Tokens.hash(rawToken))
                .filter(token -> !token.isExpired(now))
                .map(token -> {
                    token.touch(now, token.getRole() == UserToken.Role.ADMIN ? adminTtl : null);
                    return token;
                });
    }

    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        repository.deleteByTokenHash(Tokens.hash(rawToken));
    }

    /** 비밀번호를 바꾸거나 계정을 잠글 때 — 그 주체의 세션을 전부 끊는다. */
    @Transactional
    public int revokeAllOf(UserToken.Role role, Long subjectId) {
        return repository.deleteAllOf(role, subjectId);
    }

    /** 만료된 행을 치운다. 표가 무한히 자라는 것을 막는다. */
    @Transactional
    public int purgeExpired() {
        return repository.deleteExpired(LocalDateTime.now(clock));
    }

    /**
     * 접속 IP — 리버스 프록시 뒤라 {@code getRemoteAddr()} 은 nginx 를 가리킨다.
     *
     * <p>호스트 nginx 가 {@code X-Real-IP} 를 넣어 주므로 그것을 먼저 본다
     * ({@code frontend/nginx.conf} 의 {@code proxy_set_header X-Real-IP $remote_addr}).
     * <b>이 값은 클라이언트가 위조할 수 있다</b> — 감사 참고 자료일 뿐 인가 판단에 쓰지 않는다.
     */
    public static String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        String header = request.getHeader("X-Real-IP");
        if (header != null && !header.isBlank()) return trim(header, 45);
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return trim(forwarded.split(",")[0].trim(), 45);
        return trim(request.getRemoteAddr(), 45);
    }

    private static String userAgent(HttpServletRequest request) {
        return request == null ? null : trim(request.getHeader("User-Agent"), 255);
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
