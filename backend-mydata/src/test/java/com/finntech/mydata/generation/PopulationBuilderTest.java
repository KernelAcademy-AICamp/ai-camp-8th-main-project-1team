package com.finntech.mydata.generation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 인구 생성 — 비중·시작일·지역·통근·데이터분리·결정론 검증(Spring 부팅 없이). */
class PopulationBuilderTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final PopulationBuilder pb =
            new PopulationBuilder(new CatalogLoader(mapper), new GenerationProperties());

    @Test
    void 인구가_비중_시작일_지역_분리에_맞게_결정론_생성된다() {
        List<GeneratedUser> users = pb.build(20260721L, 2000);
        assertThat(users).hasSize(2000);

        // 결정론: 같은 시드면 첫 사용자 동일
        assertThat(pb.build(20260721L, 2000).get(0).id()).isEqualTo(users.get(0).id());
        // 다른 시드면 달라짐
        assertThat(pb.build(1L, 2000).get(0).id()).isNotEqualTo(users.get(0).id());

        // 페르소나 비중(과소비형 40% ≈ 800)
        long overspend = users.stream().filter(u -> u.variant().baseName().equals("과소비형")).count();
        assertThat(overspend).isEqualTo(800);

        // 시작일 7/1~9/1 이내
        assertThat(users).allSatisfy(u -> {
            assertThat(u.startDate()).isAfterOrEqualTo(LocalDate.of(2026, 7, 1));
            assertThat(u.startDate()).isBeforeOrEqualTo(LocalDate.of(2026, 9, 1));
        });

        // 외식형 = 수도권(비서울) 거주 + 서울 통근
        var oisik = users.stream().filter(u -> u.variant().baseName().equals("외식형")).findFirst().orElseThrow();
        assertThat(Set.of("경기도", "인천광역시")).contains(oisik.home().sido());
        assertThat(oisik.work()).isNotNull();
        assertThat(oisik.work().sido()).isEqualTo("서울특별시");

        // 균형형 = 광역시 위주 거주
        var balance = users.stream().filter(u -> u.variant().baseName().equals("균형형")).findFirst().orElseThrow();
        assertThat(Set.of("서울특별시", "부산광역시", "대구광역시", "인천광역시", "광주광역시", "대전광역시", "울산광역시"))
                .contains(balance.home().sido());

        // 데이터 분리 4종 존재, service 소수
        assertThat(users.stream().map(GeneratedUser::dataSplit).distinct().toList())
                .contains("TRAIN", "VAL", "TEST", "SERVICE");
        long service = users.stream().filter(u -> u.dataSplit().equals("SERVICE")).count();
        assertThat(service).isBetween(120L, 280L); // ~10%

        // 변형이 실제로 서로 다름(모두 같은 형태 아님) — 과소비형 월지출 편차 존재
        long distinctMonthly = users.stream().filter(u -> u.variant().baseName().equals("과소비형"))
                .mapToLong(u -> u.variant().monthlyTotalMean()).distinct().count();
        assertThat(distinctMonthly).isGreaterThan(10);
    }
}
