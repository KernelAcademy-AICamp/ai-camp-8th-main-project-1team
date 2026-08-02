package com.finntech.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AdminEndpointOffTest} 의 짝 — <b>켜면 실제로 열린다</b>.
 *
 * <p>닫히는 것만 확인하면 "스위치가 동작한다"와 "그 빈이 애초에 없다"를 구별할 수 없다.
 * 양쪽을 다 봐야 스위치가 스위치임이 증명된다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "finntech.realdata.enabled=true",
        "finntech.ops.enabled=true"
})
class AdminEndpointOnTest {

    @Autowired ApplicationContext ctx;

    @Test
    @DisplayName("켜면 두 경로 모두 등록된다")
    void 열림() {
        assertThat(ctx.getBeanNamesForType(RealDataController.class)).isNotEmpty();
        assertThat(ctx.getBeanNamesForType(ObservabilityController.class)).isNotEmpty();
    }
}
