package com.finntech.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(AnalysisProperties.class)
public class AppConfig {

    /**
     * 시각을 빈으로 주입한다. 엔진이 {@code LocalDateTime.now()}를 직접 부르면
     * 같은 입력이 시간에 따라 다른 출력을 내어 <b>재현성 검증이 불가능해진다</b> (문서 §4 원칙 3).
     * 테스트는 고정 Clock을 주입해 결정론을 확보한다.
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
            }
        };
    }
}
