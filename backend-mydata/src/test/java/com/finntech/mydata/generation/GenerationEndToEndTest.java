package com.finntech.mydata.generation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 생성기 엔드투엔드 — Spring 기동 시 소규모 생성이 실제 사용자·카드·결제(라벨·채널·위치 포함)를 적재하는지.
 * 시드 비활성 → 카탈로그 자동 생성, 전량 생성 결제. 격리 인메모리 H2.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:gen_e2e;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "mydata.seed.enabled=false",
        "mydata.generation.enabled=true",
        // history-days=280로 사용자당 결제가 많아 target/perUser가 사용자 수를 정한다(estimateUserCount).
        // data_split 4종(train/val/test/service)이 사용자별 확률 배정이므로, 충분한 사용자 수를 확보하려면
        // target을 넉넉히(≈12명↑) 준다 — 소규모면 split 종류가 2종으로 줄어 검증이 흔들린다(§13-11).
        "mydata.generation.target-count=24000"
})
class GenerationEndToEndTest {

    @Autowired JdbcTemplate jdbc;

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void 생성기가_라벨_채널_위치를_갖춘_결제를_적재한다() {
        int users = count("SELECT COUNT(*) FROM mydata_user WHERE mydata_user_data_split IS NOT NULL");
        int pays = count("SELECT COUNT(*) FROM mydata_payment");
        assertThat(users).isGreaterThan(0);
        assertThat(pays).isGreaterThan(500);

        // 전량 낭비/필수 라벨 + 값 유효
        assertThat(count("SELECT COUNT(*) FROM mydata_payment WHERE mydata_payment_waste_label IS NOT NULL"))
                .isEqualTo(pays);
        assertThat(count("SELECT COUNT(*) FROM mydata_payment " +
                "WHERE mydata_payment_waste_label NOT IN ('WASTE','ESSENTIAL')")).isZero();

        // 채널·상품 채워짐
        assertThat(count("SELECT COUNT(*) FROM mydata_payment WHERE mydata_payment_product_name IS NOT NULL"))
                .isEqualTo(pays);

        // 온라인(위치 null) + 오프라인(위치 있음) 공존
        assertThat(count("SELECT COUNT(*) FROM mydata_payment " +
                "WHERE mydata_payment_channel='ONLINE' AND mydata_payment_location_lat IS NULL")).isGreaterThan(0);
        assertThat(count("SELECT COUNT(*) FROM mydata_payment " +
                "WHERE mydata_payment_channel='OFFLINE' AND mydata_payment_location_lat IS NOT NULL")).isGreaterThan(0);

        // 데이터 분리 4종
        assertThat(count("SELECT COUNT(DISTINCT mydata_user_data_split) FROM mydata_user " +
                "WHERE mydata_user_data_split IS NOT NULL")).isGreaterThanOrEqualTo(3);

        // 낭비·필수 둘 다 존재(서비스 효과·충동 반영)
        assertThat(count("SELECT COUNT(*) FROM mydata_payment WHERE mydata_payment_waste_label='WASTE'"))
                .isGreaterThan(0);
        assertThat(count("SELECT COUNT(*) FROM mydata_payment WHERE mydata_payment_waste_label='ESSENTIAL'"))
                .isGreaterThan(0);
    }
}
