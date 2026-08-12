package com.finntech.mydata.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 서버간 인증 필터(W7-2). {@code /bank/**}(마이데이터 제공 API)는 사업자(본체)만 호출해야 한다.
 * 공유 시크릿 {@code mydata.shared-secret}(env MYDATA_SHARED_SECRET)이 설정돼 있으면 요청의
 * {@code X-MyData-Token} 헤더가 일치할 때만 통과시키고, 아니면 401을 낸다. 8082 격리(단층 방어)가
 * 설정 실수로 뚫려도 토큰 없는 직접 호출을 이 필터가 막는다("제공자-사업자 인증"은 실 마이데이터 구조의 핵심).
 *
 * <p>시크릿 미설정(빈 값)이면 강제하지 않는다 — dev(h2)는 로컬 호출이 통과한다. 운영(mysql)에서
 * 미설정이면 {@link SharedSecretRequiredGuard}가 기동을 막는다(fail-fast). {@code /bank/**} 밖 경로
 * (actuator 등)는 항상 통과 — 컨테이너 헬스체크 유지.
 */
@Component
public class MyDataSharedSecretFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-MyData-Token";
    private final String secret;

    public MyDataSharedSecretFilter(@Value("${mydata.shared-secret:}") String secret) {
        this.secret = secret == null ? "" : secret;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!secret.isBlank() && isProtected(request.getRequestURI())
                && !secret.equals(request.getHeader(HEADER))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"statusCode\":401,\"message\":\"invalid or missing " + HEADER + "\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 토큰을 요구하는 경로.
     *
     * <p>{@code /bank/**}(마이데이터 제공 API)에 더해 <b>{@code /self/**}(승인된 실사용자 적재)도
     * 검사한다.</b> 이 경로는 <b>쓰기</b>라 오히려 조회보다 무거운데, 예전 관리자 입구
     * ({@code /admin/realdata})가 필터 밖이라 무방비였다 — 8082 격리 하나에만 기대고 있었고
     * 그 격리는 설정 실수 하나면 무너지는 단층 방어다. 새 입구를 만들면서 그 빚을 여기서 갚는다.
     *
     * <p>{@code /actuator/**} 는 계속 통과 — 컨테이너 헬스체크가 토큰 없이 부른다.
     */
    private static boolean isProtected(String uri) {
        return uri.startsWith("/bank/") || uri.startsWith("/self/");
    }
}
