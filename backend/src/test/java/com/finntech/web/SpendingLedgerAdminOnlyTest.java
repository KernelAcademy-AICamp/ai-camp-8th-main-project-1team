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
 * 소비 원장을 <b>쓰는</b> 문은 admin 뒤에 있어야 한다.
 *
 * <h2>왜 시험으로 못박나 — 한 번 틀렸기 때문이다</h2>
 *
 * <p>처음에는 이 문들을 {@code /api/ops} 에 두었다. 그 자리는 <b>운영에서 기본으로 켜져
 * 있고</b>({@code FINNTECH_OPS_ENABLED:-true}, {@link OpsEndpointGateTest} 가 그 기본값을
 * 지킨다) 켜 두는 근거가 <i>"개인정보를 안 내고 사용자별로 쪼개지 않는다"</i> 였다.
 *
 * <p>그런데 여기 있는 것은 그 약속을 어긴다 — {@code backfill}·{@code drain} 은 실사용자
 * 전원의 판정을 돌리는 <b>쓰기</b>이고, {@code verify} 의 표본에는 결제 식별자·가맹점명·
 * 사업자번호가 담긴다. 게다가 {@code /api/ops} 는 {@code AuthFilter} 가 <b>사용자 토큰</b>만
 * 요구하고 경로에 사용자 번호가 없어 소유 확인도 안 걸려, <b>로그인한 아무나</b> 부를 수 있었다.
 *
 * <p>{@code /api/admin/} 접두라야 admin 쿠키를 요구한다({@code AuthFilter} 의 역할 분기).
 * 이 시험은 그 접두가 유지되는지만 본다 — 누가 편의로 옮기면 여기서 걸린다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SpendingLedgerAdminOnlyTest {

    // actuator 도 같은 형의 빈을 하나 더 세운다 — 이름으로 골라야 컨트롤러 매핑을 집는다.
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("소비 원장 문은 전부 /api/admin/ 아래에만 있다")
    void 소비_원장_문은_admin_뒤에_있다() {
        List<String> outsideAdmin = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> declaringClassOf(entry.getValue()).contains("SpendingLedger"))
                .flatMap(entry -> patternsOf(entry.getKey()).stream())
                .filter(pattern -> !pattern.startsWith("/api/admin/"))
                .sorted()
                .toList();

        assertThat(outsideAdmin)
                .as("소비 원장을 쓰거나 남의 개인정보를 내는 문이 admin 밖에 나와 있다")
                .isEmpty();
    }

    @Test
    @DisplayName("네 문이 실제로 붙어 있다 — 옮기다 흘리지 않았는지")
    void 문이_그대로_있다() {
        List<String> patterns = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> declaringClassOf(entry.getValue()).contains("SpendingLedger"))
                .flatMap(entry -> patternsOf(entry.getKey()).stream())
                .sorted()
                .toList();

        assertThat(patterns).containsExactlyInAnyOrder(
                "/api/admin/spending-ledger/backfill",
                "/api/admin/spending-ledger/verify",
                "/api/admin/spending-ledger/drain",
                "/api/admin/spending-ledger/refresh",
                "/api/admin/spending-ledger/health");
    }

    private static String declaringClassOf(HandlerMethod method) {
        return method.getBeanType().getSimpleName();
    }

    private static List<String> patternsOf(Object mappingInfo) {
        var info = (org.springframework.web.servlet.mvc.method.RequestMappingInfo) mappingInfo;
        var patterns = info.getPathPatternsCondition();
        if (patterns == null) return List.of();
        return patterns.getPatternValues().stream().toList();
    }
}
