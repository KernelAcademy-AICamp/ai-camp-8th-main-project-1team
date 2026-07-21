package com.finntech.mydata.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 허용 — 본체(8080)와 프론트(5173)가 마이데이터 서버(8082)를 호출할 수 있게 한다.
 * 내부 더미 서버이므로 개방한다(운영 배포 시 오리진을 좁힌다 — §13-8).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("http://localhost:5173", "http://localhost:8080")
                .allowedMethods("GET", "POST", "OPTIONS");
    }
}
