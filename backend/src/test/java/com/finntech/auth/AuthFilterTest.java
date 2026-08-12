package com.finntech.auth;

import com.finntech.domain.UserToken;
import com.finntech.repository.UserTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인증 필터 — <b>이 앱에 없던 방어</b>를 검증한다.
 *
 * <p>도입 전 운영은 {@code GET /api/report/monthly?userId=3} 에 남의 리포트를 그대로 내줬다
 * (없는 사용자로 불러도 401 이 아니라 404 — 인증을 안 따진다는 뜻이었다).
 * 그 상태로 실제 사람 750건이 올라가 있었다.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthFilterTest {

    @org.springframework.beans.factory.annotation.Autowired AuthFilter filter;
    @org.springframework.beans.factory.annotation.Autowired AuthTokenService tokens;
    @org.springframework.beans.factory.annotation.Autowired UserTokenRepository repository;

    private String userToken;
    private String adminToken;

    @BeforeEach
    void issueTokens() {
        repository.deleteAll();
        userToken = tokens.issue(UserToken.Role.USER, 7L, new MockHttpServletRequest()).raw();
        adminToken = tokens.issue(UserToken.Role.ADMIN, 1L, new MockHttpServletRequest()).raw();
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    @DisplayName("토큰이 없으면 401 — 도입 전에는 그냥 통과했다")
    void rejectsMissingToken() throws Exception {
        MockHttpServletRequest request = get("/api/report/monthly");
        request.setParameter("userId", "7");
        assertThat(run(request).getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("자기 userId 는 통과한다")
    void allowsOwnUserId() throws Exception {
        MockHttpServletRequest request = get("/api/report/monthly");
        request.setParameter("userId", "7");
        request.addHeader(AuthFilter.USER_HEADER, userToken);
        assertThat(run(request).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("남의 userId 는 403 — 이것이 이 필터를 만든 이유다")
    void rejectsOtherUserId() throws Exception {
        MockHttpServletRequest request = get("/api/report/monthly");
        request.setParameter("userId", "3");
        request.addHeader(AuthFilter.USER_HEADER, userToken);
        assertThat(run(request).getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("경로변수로 온 남의 번호도 막는다 — /api/score/{userId} · /api/users/{userId}")
    void rejectsOtherUserIdInPath() throws Exception {
        MockHttpServletRequest score = get("/api/score/3");
        score.addHeader(AuthFilter.USER_HEADER, userToken);
        assertThat(run(score).getStatus()).isEqualTo(403);

        MockHttpServletRequest consent = get("/api/users/3/consent");
        consent.addHeader(AuthFilter.USER_HEADER, userToken);
        assertThat(run(consent).getStatus()).isEqualTo(403);

        MockHttpServletRequest own = get("/api/score/7");
        own.addHeader(AuthFilter.USER_HEADER, userToken);
        assertThat(run(own).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("사용자 토큰으로는 admin 경로에 못 간다")
    void userCannotReachAdmin() throws Exception {
        MockHttpServletRequest request = get("/api/admin/intake");
        request.addHeader(AuthFilter.USER_HEADER, userToken);
        assertThat(run(request).getStatus()).isEqualTo(401);   // 쿠키가 없으니 인증 자체가 안 된다
    }

    @Test
    @DisplayName("admin 토큰으로는 사용자 API 를 대신 부르지 못한다 — 권한 경계를 지킨다")
    void adminCannotReachUserApi() throws Exception {
        MockHttpServletRequest request = get("/api/report/monthly");
        request.setParameter("userId", "7");
        request.addHeader(AuthFilter.USER_HEADER, adminToken);
        assertThat(run(request).getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("admin 은 쿠키로 admin 경로에 들어간다")
    void adminReachesAdminPath() throws Exception {
        MockHttpServletRequest request = get("/api/admin/intake");
        request.setCookies(new Cookie(AuthFilter.ADMIN_COOKIE, adminToken));
        assertThat(run(request).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("인증 전에 불러야 하는 경로는 토큰 없이 통과한다 — 빠지면 온보딩이 막힌다")
    void publicPathsPass() throws Exception {
        for (String path : new String[] {
                "/api/privacy/terms", "/api/mydata/verify", "/api/mydata/companies",
                "/api/apply", "/api/admin/login", "/actuator/health" }) {
            assertThat(run(get(path)).getStatus())
                    .as("공개 경로 %s", path)
                    .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("폐기한 토큰은 더 못 쓴다 — 로그아웃이 실제로 끊는다")
    void revokedTokenRejected() throws Exception {
        tokens.revoke(userToken);
        MockHttpServletRequest request = get("/api/report/monthly");
        request.setParameter("userId", "7");
        request.addHeader(AuthFilter.USER_HEADER, userToken);
        assertThat(run(request).getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("사용자 번호를 말하지 않는 요청은 토큰만 유효하면 된다")
    void requestWithoutUserIdOnlyNeedsToken() throws Exception {
        MockHttpServletRequest request = get("/api/mydata/banks");
        request.addHeader(AuthFilter.USER_HEADER, userToken);
        assertThat(run(request).getStatus()).isEqualTo(200);
    }
}
