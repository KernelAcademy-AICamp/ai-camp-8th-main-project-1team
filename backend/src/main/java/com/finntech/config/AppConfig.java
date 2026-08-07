package com.finntech.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

@Configuration
@EnableConfigurationProperties({AnalysisProperties.class, CardRecommendProperties.class,
        IndustryLookupProperties.class, com.finntech.guardian.GuardianProperties.class})
public class AppConfig {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AppConfig.class);

    /**
     * 시각을 빈으로 주입한다. 엔진이 {@code LocalDateTime.now()}를 직접 부르면
     * 같은 입력이 시간에 따라 다른 출력을 내어 <b>재현성 검증이 불가능해진다</b> (문서 §4 원칙 3).
     * 테스트는 고정 Clock을 주입해 결정론을 확보한다.
     *
     * <p><b>이 빈이 시스템 전체의 '오늘' 단일 출처다.</b> 마이데이터 커트오프
     * ({@code mydata.now})와 링크 기준일({@code finntech.mydata.reference-date})이 각자
     * 날짜를 들고 있다가 서로 어긋난 사고가 있었다 — 분석·지킴이만 실시간으로 앞서가고
     * 데이터 공급은 과거 날짜에 멈춰, 오늘 시작한 챌린지에 넣을 소비가 원천적으로 0건이 됐다.
     * 그래서 날짜를 정하는 곳을 여기 하나로 모은다.
     *
     * <p>{@code finntech.demo.today}가 비어 있으면 실시간(배포 기본), 날짜(yyyy-MM-dd)를 주면
     * 그날 자정으로 고정한다(발표 리허설·회귀 검증용). 고정해도 데이터 생성 자체는 시드 기반
     * 결정론이라 과거 구간은 항상 같은 화면이 나온다.
     */
    @Bean
    public Clock clock(@org.springframework.beans.factory.annotation.Value(
            "${finntech.demo.today:}") String demoToday) {
        if (demoToday == null || demoToday.isBlank()) {
            return Clock.systemDefaultZone();
        }
        ZoneId zone = ZoneId.systemDefault();
        Clock fixed = Clock.fixed(LocalDate.parse(demoToday.trim()).atStartOfDay(zone).toInstant(), zone);
        log.warn("데모 시계 고정 — finntech.demo.today={} (실시간 아님). 배포에서는 비워 둔다.", demoToday);
        return fixed;
    }

    /**
     * CORS 허용 오리진은 프로퍼티로 뺀다(W7-3). 운영은 env로 도메인 주입, 동일 오리진(nginx 프록시)
     * 배포 시엔 교차 출처가 사라져 사실상 무의미해진다. 기본값은 로컬 프론트(vite).
     */
    @Bean
    public WebMvcConfigurer corsConfigurer(
            @org.springframework.beans.factory.annotation.Value(
                    "${finntech.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}") String[] allowedOrigins) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(allowedOrigins)
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
            }
        };
    }

    /**
     * 절약 후보 선정에 쓸 <b>중분류별 ML 낭비 비율</b> 공급자.
     *
     * <p>엔진({@code CutCandidateSelector})이 ml 패키지를 직접 알면 의존이 순환한다
     * (engine → ml → engine). 그래서 여기서 함수로 묶어 넘긴다 — 엔진은 "숫자를 받는" 자리로 남는다.
     *
     * <p>모델이 준비되지 않았거나 판정할 결제가 없으면 <b>빈 표</b>다. 그때 선정기는 게이트를
     * 통과시키고 예전 규칙(전액 제거가능)으로 돈다 — 근거가 없을 때 조언을 지우는 것보다
     * 하던 대로 두는 편이 덜 놀랍다.
     */
    @org.springframework.context.annotation.Bean
    public java.util.function.Function<Long, java.util.Map<String, Double>> wasteRatioByCategory(
            com.finntech.ml.WasteScoringService wasteScoring) {
        return userId -> wasteScoring.summarize(userId)
                .map(com.finntech.ml.WasteScoringService.MlSummary::wasteRatioByCategory1)
                .orElseGet(java.util.Map::of);
    }
}
