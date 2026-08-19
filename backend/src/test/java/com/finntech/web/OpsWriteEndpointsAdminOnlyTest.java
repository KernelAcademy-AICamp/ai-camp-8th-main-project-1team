package com.finntech.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>{@code /api/ops} 는 읽기 전용이다.</b> 쓰는 문은 admin 뒤에 있어야 한다.
 *
 * <h2>왜 이 시험이 있나 — 두 번 틀렸다</h2>
 *
 * <p>{@code /api/ops} 를 운영에서 켜 두는 근거는 <i>"개인정보를 안 내고 사용자별로 쪼개지
 * 않는 관측"</i>이었다({@code FINNTECH_OPS_ENABLED:true}, {@link OpsEndpointGateTest} 가 그
 * 기본값을 지킨다). 그런데 그 자리에 <b>쓰는 문</b>이 두 번 들어왔다.
 *
 * <ol>
 *   <li>소비 원장의 {@code backfill}·{@code drain}·{@code verify} — PR #202 에서 옮겼다</li>
 *   <li>{@code merchant-category/recompute?apply=true} — 2026-08-19 에 옮겼다.
 *       <b>실사용자 전원의 분류를 다시 계산하는 쓰기</b>인데 그 자리에 있었다</li>
 * </ol>
 *
 * <p>{@code AuthFilter} 는 {@code /api/ops} 에 <b>사용자 토큰</b>만 요구하고 경로에 사용자
 * 번호가 없어 소유 확인도 안 걸린다 — 즉 <b>로그인한 아무나</b> 부를 수 있었다. 같은 실수가
 * 세 번째로 들어오지 않게 이 시험이 막는다.
 *
 * <p><b>읽기는 그대로 둔다.</b> {@code ObservabilityController} 는 관측이 목적이고, 켜야 볼 수
 * 있는 관측은 결국 안 보게 된다.
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsWriteEndpointsAdminOnlyTest {

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("/api/ops 아래에는 쓰기(POST·PUT·DELETE) 문이 하나도 없다")
    void opsIsReadOnly() {
        List<String> writes = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> {
                    var methods = e.getKey().getMethodsCondition().getMethods();
                    return methods.stream().anyMatch(m ->
                            m.name().equals("POST") || m.name().equals("PUT")
                                    || m.name().equals("DELETE") || m.name().equals("PATCH"));
                })
                .flatMap(e -> patternsOf(e.getKey()).stream())
                .filter(p -> p.startsWith("/api/ops"))
                .sorted()
                .toList();

        assertThat(writes).as("""
                /api/ops 는 운영에서 기본으로 켜져 있고 사용자 토큰만 요구한다 —
                여기에 쓰기 문을 두면 로그인한 아무나 부를 수 있다.
                /api/admin/ 으로 옮겨라(admin 쿠키를 요구한다).""")
                .isEmpty();
    }

    @Test
    @DisplayName("사전 재계산은 admin 뒤에 있다")
    void recomputeIsBehindAdmin() {
        List<String> patterns = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> declaringClassOf(e.getValue()).endsWith("MerchantDictionaryOpsController"))
                .flatMap(e -> patternsOf(e.getKey()).stream())
                .sorted()
                .toList();

        assertThat(patterns)
                .isNotEmpty()
                .allSatisfy(p -> assertThat(p).startsWith("/api/admin/"));
    }

    private static List<String> patternsOf(org.springframework.web.servlet.mvc.method.RequestMappingInfo info) {
        var cond = info.getPathPatternsCondition();
        return cond == null ? List.of()
                : cond.getPatterns().stream().map(Object::toString).toList();
    }

    private static String declaringClassOf(HandlerMethod method) {
        return method.getBeanType().getName();
    }
}
