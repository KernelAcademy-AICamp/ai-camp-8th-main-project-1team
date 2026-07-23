package com.finntech.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 마이데이터 서버 호출용 {@link RestClient} 빈 (§13-3). WebFlux 없이 동기 호출.
 * base-url은 {@code finntech.mydata.base-url}(기본 http://localhost:8082).
 *
 * <p><b>서버간 인증(W7-2)</b>: 제공자(마이데이터)-사업자(본체) 간 공유 시크릿을 모든 요청에
 * {@code X-MyData-Token} 헤더로 실어 보낸다. 8082 격리(단층 방어)가 뚫려도 토큰 없는 직접 호출은
 * 마이데이터가 401로 막는다. 시크릿은 {@code finntech.mydata.shared-secret}(env MYDATA_SHARED_SECRET).
 * 미설정(빈 값)이면 헤더를 붙이지 않는다 — dev(마이데이터 h2)는 강제하지 않으므로 로컬 개발이 통과한다.
 */
@Configuration
public class MyDataClientConfig {

    @Bean
    public RestClient myDataRestClient(
            @Value("${finntech.mydata.base-url:http://localhost:8082}") String baseUrl,
            @Value("${finntech.mydata.shared-secret:}") String sharedSecret) {
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        if (sharedSecret != null && !sharedSecret.isBlank()) {
            builder.defaultHeader("X-MyData-Token", sharedSecret);
        }
        return builder.build();
    }
}
