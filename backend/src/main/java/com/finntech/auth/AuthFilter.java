package com.finntech.auth;

import com.finntech.domain.UserToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 인증·인가 필터 (설계서 Phase 1).
 *
 * <h2>왜 필터 한 겹인가</h2>
 *
 * <p>{@code userId} 를 받는 엔드포인트가 <b>43개</b>(컨트롤러 19개)다. 전부를 토큰 기반으로
 * 고치면 큰 작업이고 손댄 만큼 실수가 난다. 그런데 <b>고칠 필요가 없다</b> — 토큰이 가리키는
 * 사용자와 요청이 말하는 {@code userId} 를 여기서 대조해 다르면 막으면 된다.
 * 컨트롤러 시그니처는 하나도 바뀌지 않는다.
 *
 * <h2>무엇을 막는가</h2>
 *
 * <p>지금 운영은 {@code GET /api/report/monthly?userId=3} 에 <b>남의 리포트를 그대로 내준다</b>
 * (실측: 없는 사용자로 부르면 401 이 아니라 404 — 인증을 안 따진다는 뜻). 실제 사람 750건이
 * 그 상태로 올라가 있다. 이 필터가 그것을 닫는다.
 *
 * <h2>토큰이 오는 두 갈래</h2>
 *
 * <ul>
 *   <li><b>실사용자</b> — {@code X-Auth-Token} 헤더. 프론트가 localStorage 에 두고 실어 보낸다.</li>
 *   <li><b>admin</b> — {@code admin_session} <b>HttpOnly 쿠키</b>. JS 가 읽을 수 없어
 *       XSS 가 있어도 훔칠 수 없다. 실 개인정보 적재를 승인하는 열쇠라 급이 다르다.</li>
 * </ul>
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    /** admin 세션 쿠키 이름. {@code Path=/api/admin} 이라 사용자 요청에는 실리지 않는다. */
    public static final String ADMIN_COOKIE = "admin_session";
    public static final String USER_HEADER = "X-Auth-Token";
    /** 통과한 요청의 주체. 컨트롤러가 필요하면 꺼내 쓴다. */
    public static final String ATTR_SUBJECT_ID = "finntech.auth.subjectId";
    public static final String ATTR_ROLE = "finntech.auth.role";

    /**
     * 인증 <b>전에</b> 불러야 하는 경로.
     *
     * <p>여기서 하나라도 빠지면 온보딩이 통째로 막힌다 — 본인인증을 하려면 인증이 필요하다는
     * 순환이 생긴다. 반대로 넓게 열면 필터를 둔 의미가 없으므로 <b>정확히 필요한 것만</b> 둔다.
     */
    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/actuator/health",
            "/api/privacy/",              // 약관·방침 — 동의 전에 읽어야 한다
            "/api/mydata/verify",         // 본인인증 그 자체
            "/api/mydata/companies",      // 카드사 목록 — 연결 화면이 인증 전에 부른다
            "/api/analytics/track",       // 익명 사용성 수집
            "/api/apply",                 // 실사용자 신청 (계정이 아직 없다)
            "/api/admin/login",           // admin 로그인 그 자체
            // 개발·시연 전용 경로. **운영에서는 빈 자체가 만들어지지 않으므로**
            // (`finntech.dev.seed-enabled=false` → `@ConditionalOnProperty`) 여기 열어도
            // 운영에는 그 경로가 없다. 대신 dev-up.sh·demo-e2e.sh 가 인증 없이 그대로 돈다.
            "/api/dev/"
    );

    /**
     * 경로에 사용자 번호가 박힌 엔드포인트.
     *
     * <p>대부분은 {@code ?userId=} 라 질의 파라미터로 잡히지만, 이 둘만 경로변수를 쓴다
     * ({@code /api/score/{userId}} · {@code /api/users/{userId}} 계열). <b>여기가 빠지면
     * 그 경로만 조용히 안 막힌다</b> — 새 경로를 만들 때 이 목록을 같이 본다.
     */
    private static final Pattern PATH_USER_ID =
            Pattern.compile("^/api/(?:score|users)/(\\d+)(?:/.*)?$");

    private final AuthTokenService tokens;
    private final boolean enabled;

    public AuthFilter(AuthTokenService tokens,
                      @Value("${finntech.auth.enabled:true}") boolean enabled) {
        this.tokens = tokens;
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (!enabled || !path.startsWith("/api/") || isPublic(path)
                || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        boolean adminPath = path.startsWith("/api/admin/");
        Optional<UserToken> found = tokens.verify(adminPath ? adminCookie(request)
                                                            : request.getHeader(USER_HEADER));
        if (found.isEmpty()) {
            deny(response, HttpServletResponse.SC_UNAUTHORIZED, "로그인이 필요합니다.");
            return;
        }
        UserToken token = found.get();

        // 역할이 경로를 가른다. admin 이 사용자 API 를 대신 부르지 않는다 —
        // 부를 수 있게 두면 "admin 은 무엇이든 할 수 있다"가 되어 권한 경계가 사라진다.
        if (adminPath != (token.getRole() == UserToken.Role.ADMIN)) {
            deny(response, HttpServletResponse.SC_FORBIDDEN, "권한이 없습니다.");
            return;
        }

        if (token.getRole() == UserToken.Role.USER) {
            Long claimed = claimedUserId(request, path);
            // 요청이 사용자 번호를 말하지 않으면 대조할 것이 없다(예: 카드사 목록).
            // 토큰이 유효한 것으로 충분하다.
            if (claimed != null && !claimed.equals(token.getSubjectId())) {
                deny(response, HttpServletResponse.SC_FORBIDDEN, "권한이 없습니다.");
                return;
            }
        }

        request.setAttribute(ATTR_SUBJECT_ID, token.getSubjectId());
        request.setAttribute(ATTR_ROLE, token.getRole().name());
        chain.doFilter(request, response);
    }

    private static boolean isPublic(String path) {
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix)) return true;
        }
        return false;
    }

    private static String adminCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (ADMIN_COOKIE.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    /**
     * 요청이 주장하는 사용자 번호. 없으면 {@code null}.
     *
     * <p>질의 파라미터를 먼저 보고, 없으면 경로에서 찾는다. 숫자가 아니면 {@code null} 을
     * 주고 통과시킨다 — 형식 오류는 컨트롤러가 400 으로 답할 몫이지 인가의 몫이 아니다.
     */
    private static Long claimedUserId(HttpServletRequest request, String path) {
        String param = request.getParameter("userId");
        if (param != null && !param.isBlank()) {
            try {
                return Long.parseLong(param.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        Matcher matcher = PATH_USER_ID.matcher(path);
        return matcher.matches() ? Long.parseLong(matcher.group(1)) : null;
    }

    /**
     * 거부 응답.
     *
     * <p><b>사유를 나누지 않는다</b> — "토큰 없음"과 "남의 것"을 구분해 주면 그 자체가
     * 정보다. 401 과 403 은 클라이언트가 재로그인할지 판단해야 해서 가르되, 문구는 고정한다.
     */
    private static void deny(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
