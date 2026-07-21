package com.finntech.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 마이데이터 서버 호출용 {@link RestClient} 빈 (§13-3). WebFlux 없이 동기 호출.
 * base-url은 {@code finntech.mydata.base-url}(기본 http://localhost:8082).
 */
@Configuration
public class MyDataClientConfig {

    @Bean
    public RestClient myDataRestClient(@Value("${finntech.mydata.base-url:http://localhost:8082}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }
}
