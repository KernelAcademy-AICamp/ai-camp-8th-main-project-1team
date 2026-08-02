package com.finntech.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>관리자용 경로는 꺼 두면 정말로 없어야 한다.</b>
 *
 * <p>{@code /api/realdata/**} 는 <b>실제 개인정보</b>를 받고 {@code DELETE} 까지 있다.
 * {@code /api/ops/**} 는 알림 예산 소진률·모델 임계 같은 내부 상태를 낸다.
 * 둘 다 호스트 nginx의 경로 차단에 기대고 싶지만 그 차단은 <b>아직 적용 전</b>이다 —
 * {@code deploy/ec2-setup.md} §7이 "걸 때는"이라고만 적어 두었고, 실측하면 운영에서
 * {@code /actuator/health} 가 공개로 200을 준다(2026-08-02 확인).
 *
 * <p>그래서 통제를 한 겹에 두지 않는다. W7-2가 8082 격리 뒤에 공유 시크릿을 둔 것과 같은 이유다 —
 * <b>격리는 설정 실수 하나면 무너지는 단층 방어</b>다. {@code @ConditionalOnProperty} 로
 * <b>빈 자체를 안 만든다</b>. 매핑이 없으니 경로도 없다.
 *
 * <p>짝은 {@link AdminEndpointOnTest} 다 — 켰을 때 열리는 것까지 봐야
 * "언제나 닫힘"과 구별된다(tech_log §8-O: 통과만 보고 믿으면 테스트가 아니라 장식이다).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "finntech.realdata.enabled=false",
        "finntech.ops.enabled=false"
})
class AdminEndpointOffTest {

    @Autowired ApplicationContext ctx;

    @Test
    @DisplayName("실데이터 입력구가 등록되지 않는다 — 실 개인정보를 받는 문은 켤 때만 열린다")
    void 실데이터_닫힘() {
        assertThat(ctx.getBeanNamesForType(RealDataController.class))
                .as("DELETE 까지 있는 경로가 기본으로 열려 있으면 안 된다").isEmpty();
    }

    @Test
    @DisplayName("운영 관측이 등록되지 않는다")
    void 관측_닫힘() {
        assertThat(ctx.getBeanNamesForType(ObservabilityController.class)).isEmpty();
    }

    @Test
    @DisplayName("서비스는 남는다 — 막는 것은 외부 입구이지 기능이 아니다")
    void 서비스는_남는다() {
        assertThat(ctx.getBeanNamesForType(com.finntech.service.RealDataService.class))
                .as("컨트롤러만 닫는다. 배치·테스트에서 쓸 길까지 없앨 이유는 없다").isNotEmpty();
    }
}
