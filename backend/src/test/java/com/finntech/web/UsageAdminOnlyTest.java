package com.finntech.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 행태 통계를 <b>보는</b> 문은 admin 뒤에, <b>쓰는</b> 문은 사용자 토큰 뒤에.
 *
 * <h2>둘을 가르는 이유</h2>
 *
 * <p>통계에서 나가는 것은 <b>남의 발자취</b>다 — 누가 언제 어느 화면에 얼마나 머물렀는지.
 * {@code /api/admin/} 접두라야 {@code AuthFilter} 가 admin 쿠키를 요구하고, 사용자 토큰으로는
 * 403 이다. 소비 원장 문을 {@code /api/ops} 에 뒀다가 옮긴 것과 같은 이유다 — 그 자리는
 * 운영에서 기본으로 켜져 있고 <b>로그인한 아무나</b> 부를 수 있다.
 *
 * <p>반대로 <b>기록을 남기는</b> 문({@code /api/usage/track})은 admin 뒤에 두면 안 된다.
 * 그 문을 부르는 것은 사용자의 브라우저다.
 */
@SpringBootTest
@ActiveProfiles("test")
class UsageAdminOnlyTest {

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("통계를 보는 문은 전부 /api/admin/ 아래에만 있다")
    void 통계는_admin_뒤에_있다() {
        assertThat(patternsOf("UsageAdminController"))
                .as("남의 발자취를 내는 문이 admin 밖에 나와 있다")
                .isNotEmpty()
                .allSatisfy(p -> assertThat(p).startsWith("/api/admin/"));
    }

    @Test
    @DisplayName("네 문이 그대로 있다 — 옮기다 흘리지 않았는지")
    void 문이_그대로_있다() {
        assertThat(patternsOf("UsageAdminController")).containsExactlyInAnyOrder(
                "/api/admin/usage/overview",
                "/api/admin/usage/realtime",
                "/api/admin/usage/glossary",
                "/api/admin/usage/trail/{userId}");
    }

    @Test
    @DisplayName("기록을 받는 문은 admin 밖이다 — 부르는 것은 사용자의 브라우저다")
    void 수집_문은_사용자_쪽이다() {
        assertThat(patternsOf("UsageController"))
                .containsExactly("/api/usage/track")
                .allSatisfy(p -> assertThat(p).doesNotStartWith("/api/admin/"));
    }

    private List<String> patternsOf(String controller) {
        return handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> declaringClassOf(e.getValue()).endsWith(controller))
                .flatMap(e -> e.getKey().getPathPatternsCondition() == null ? java.util.stream.Stream.<String>empty()
                        : e.getKey().getPathPatternsCondition().getPatterns().stream()
                                .map(Object::toString))
                .sorted()
                .toList();
    }

    private static String declaringClassOf(HandlerMethod method) {
        return method.getBeanType().getName();
    }
}
