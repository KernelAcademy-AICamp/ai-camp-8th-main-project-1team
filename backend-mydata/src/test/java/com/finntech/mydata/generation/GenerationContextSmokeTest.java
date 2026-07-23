package com.finntech.mydata.generation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스키마 확장(라벨·채널·상품·위치·data_split) + generation 설정 바인딩이 컨텍스트 부팅을
 * 깨지 않는지 확인. 격리 인메모리 H2 + 시드 비활성(dev ./data/mydata 파일 미접촉).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:gen_smoke;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "mydata.seed.enabled=false"
})
class GenerationContextSmokeTest {

    @Autowired GenerationProperties props;
    @Autowired CatalogLoader catalogLoader;

    @Test
    void 설정바인딩과_카탈로그가_정상() {
        // split 비율 합 = 1.0 (요구11)
        double sum = props.getSplitRatios().getTrain() + props.getSplitRatios().getVal()
                + props.getSplitRatios().getTest() + props.getSplitRatios().getService();
        assertThat(sum).isEqualTo(1.0);
        assertThat(props.isEnabled()).isFalse();       // 생성 스위치 off (자리만)
        assertThat(props.getTargetCount()).isEqualTo(11_000_000L);
        // 시작일 조건(매일 단위, 커트오프 07-21 기준 ~6개월 전부터 2025-12-15~2026-02-15) 바인딩 (§13-11)
        assertThat(props.getStartDate().getFrom()).isEqualTo(java.time.LocalDate.of(2025, 12, 15));
        assertThat(props.getStartDate().getTo()).isEqualTo(java.time.LocalDate.of(2026, 2, 15));
        assertThat(props.getStartDate().getGranularity()).isEqualTo("DAILY");
        // 페르소나 패밀리 확장(기본 5 × 변형)
        assertThat(props.getPersona().getBaseCount()).isEqualTo(5);
        assertThat(props.getPersona().getVariantsPerBase()).isGreaterThan(0);
        // 다층 랜덤성 — 곡선 편차 범위·확률이 바인딩됨(모두 같은 형태 금지)
        var curve = props.getRandomness().getCurve();
        assertThat(curve.getStartAmplitude()).hasSize(2);
        assertThat(curve.getStartAmplitude()[0]).isLessThan(curve.getStartAmplitude()[1]);
        assertThat(curve.getNoImprovementProb()).isBetween(0.0, 1.0);
        assertThat(curve.getEarlyOvershootProb()).isBetween(0.0, 1.0);
        assertThat(props.getRandomness().getDay().getCheatDayProb()).isBetween(0.0, 1.0);
        assertThat(props.getRandomness().getAmount().getSigmaLog()[1]).isLessThanOrEqualTo(0.30);
        // 취미 성향 조건 바인딩
        assertThat(props.getHobby().getPerUser()).hasSize(2);
        assertThat(props.getHobby().getPurchasesPerMonth()[0]).isGreaterThan(0.0);
        assertThat(catalogLoader.hobbies()).hasSizeGreaterThanOrEqualTo(10);
        // 낭비/필수 라벨 조건 — 재량≠낭비: 생존필수 무대·취미 보호·충동성 요인 바인딩
        var label = props.getLabel();
        assertThat(label.getEssentialCategories()).contains("대형마트", "약국", "공과금", "대중교통");
        assertThat(label.getHobbyProtection()).isBetween(0.0, 1.0);   // 취미 보호(억제)
        assertThat(label.getBaseWasteProb()).isLessThan(0.1);         // 필수 무대는 낭비 아님
        assertThat(label.getImpulse().getNightHours()).containsExactly(23, 4); // 심야 충동 신호
        assertThat(label.getImpulse().getNightWeight()).isGreaterThan(0.0);
        assertThat(catalogLoader.contexts()).hasSizeGreaterThanOrEqualTo(40);
        assertThat(catalogLoader.brands()).isNotEmpty();
        assertThat(catalogLoader.regions()).hasSizeGreaterThanOrEqualTo(3000);
    }
}
