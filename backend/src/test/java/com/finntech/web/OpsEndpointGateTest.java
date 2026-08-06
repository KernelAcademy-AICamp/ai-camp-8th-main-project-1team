package com.finntech.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>관측 경로는 스위치로 끌 수 있어야 한다</b> — 다만 기본은 켜 둔다.
 *
 * <p>{@code /api/ops/health} 는 개인정보를 안 내고 사용자별로 쪼개지 않는다. 나가는 것은
 * 알림 건수·침묵률·LLM 폴백률·모델 임계뿐이고, 그 임계(0.495)는 <b>이미 application.yml 에
 * 커밋돼 있어</b> 숨겨서 얻는 게 없다.
 *
 * <p>그래서 운영에서도 켜 둔다 — <b>켜야 볼 수 있는 관측은 안 보게 된다.</b> 이상을 눈치채라고
 * 만든 것이 이상할 때만 켜러 가는 물건이 되면 순서가 뒤집힌다.
 *
 * <p>그래도 끄는 길은 남긴다. 이 테스트가 지키는 것은 <b>그 스위치가 실제로 문을 닫는가</b>다.
 * 설정만 넣고 확인하지 않으면 그 설정은 주석과 다르지 않다.
 *
 * <p>nginx가 막아 주기를 기대하지 않는다 — nginx는 {@code /api/} 아래를 경로별 구분 없이
 * 통째로 백엔드에 넘긴다. 운영에서 {@code /api/dev/seed} 가 404인 것도 nginx 덕이 아니라
 * <b>속성 게이트로 빈이 없기 때문</b>이다(2026-08-02 실측).
 */
class OpsEndpointGateTest {

    @SpringBootTest
@ActiveProfiles("test")   // 인메모리 H2 — 파일 DB 를 쓰면 낡은 스키마가 남는다
    @TestPropertySource(properties = "finntech.ops.enabled=false")
    @DisplayName("끄면 빈이 없다")
    static class 꺼짐 {
        @Autowired ApplicationContext ctx;

        @Test
        @DisplayName("스위치를 내리면 매핑 자체가 사라진다")
        void 닫힘() {
            assertThat(ctx.getBeanNamesForType(ObservabilityController.class)).isEmpty();
        }
    }

    @SpringBootTest
    @DisplayName("기본값이면 켜져 있다")
    static class 기본 {
        @Autowired ApplicationContext ctx;

        @Test
        @DisplayName("아무것도 안 정하면 열려 있다 — 관측은 늘 볼 수 있어야 한다")
        void 기본은_켜짐() {
            assertThat(ctx.getBeanNamesForType(ObservabilityController.class)).isNotEmpty();
        }
    }
}
