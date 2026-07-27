package com.finntech.mydata.generation;

import com.finntech.mydata.generation.CatalogModels.BrandEntry;
import com.finntech.mydata.generation.CatalogModels.CatalogContext;
import com.finntech.mydata.generation.CatalogModels.ProductEntry;
import com.finntech.mydata.generation.CatalogModels.RegionEntry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Stage A 카탈로그 리소스 ↔ 레코드 매핑 + 품질(실명·실상품·대규모) 검증(Spring 부팅 없이). */
class CatalogLoaderTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private final CatalogLoader loader = new CatalogLoader(mapper);

    @Test
    void contextsMapToSevenTopCategoriesOnly() {
        List<CatalogContext> ctx = loader.contexts();
        assertThat(ctx).hasSizeGreaterThanOrEqualTo(40);
        assertThat(ctx).allSatisfy(c -> {
            assertThat(c.category1()).isIn("온라인", "쇼핑", "생활", "식비", "여가", "카페/간식", "편의점");
            assertThat(c.channel()).isIn("ONLINE", "OFFLINE");
            assertThat(c.frequencyWeight()).isBetween(0.0, 1.0);
            assertThat(c.discretionaryBase()).isBetween(0.0, 1.0);
        });
    }

    @Test
    void productsAreLargeRealMenuWithValidPriceAndDiscretionary() {
        Map<String, List<ProductEntry>> products = loader.products();
        int total = products.values().stream().mapToInt(List::size).sum();
        assertThat(total).isGreaterThanOrEqualTo(400);   // 구 149 → 대폭 확대
        products.values().forEach(list -> list.forEach(p -> {
            assertThat(p.name()).isNotBlank();
            assertThat(p.priceLow()).isLessThanOrEqualTo(p.priceHigh());
            assertThat(p.priceLow()).isGreaterThan(0);
            assertThat(p.discretionary()).isBetween(0.0, 1.0);
        }));
        // 실 메뉴 존재 확인
        assertThat(products.get("치킨")).anyMatch(p -> p.name().contains("교촌 한마리"));
    }

    @Test
    void brandsAreRealNamesAndOnlineHasNoBranchSynthesis() {
        Map<String, List<BrandEntry>> brands = loader.brands();
        int total = brands.values().stream().mapToInt(List::size).sum();
        assertThat(total).isGreaterThanOrEqualTo(200);
        // 온라인 이커머스는 지점 없음(branchable=false)
        assertThat(brands.get("이커머스")).anyMatch(b -> b.name().equals("쿠팡") && !b.branchable());
        // 프랜차이즈는 지점 합성 대상
        assertThat(brands.get("카페")).anyMatch(b -> b.name().equals("스타벅스") && b.branchable());
        // 철도 실 명세서 표기 포맷(코레일) 포함
        assertThat(brands.get("철도")).anyMatch(b -> b.forms().contains("코레일"));
    }

    @Test
    void fiveBasePersonasAreConsistent() {
        var personas = loader.personas();
        assertThat(personas).hasSize(5);
        assertThat(personas).extracting(p -> p.name())
                .containsExactly("절약형", "균형형", "과소비형", "구독과다형", "외식형");
        // 인구 비중 합 = 1.0
        double shareSum = personas.stream().mapToDouble(p -> p.populationShare()).sum();
        assertThat(shareSum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
        var validCat1 = java.util.Set.of("식비", "카페/간식", "편의점", "쇼핑", "생활", "여가", "온라인");
        var hobbyTypes = loader.hobbies().stream().map(h -> h.type()).collect(java.util.stream.Collectors.toSet());
        assertThat(personas).allSatisfy(p -> {
            // 7대분류 비중 합 = 100, 키 유효
            assertThat(p.categoryMix().keySet()).isSubsetOf(validCat1);
            assertThat(p.categoryMix().values().stream().mapToDouble(Double::doubleValue).sum())
                    .isCloseTo(100.0, org.assertj.core.data.Offset.offset(0.5));
            assertThat(p.onlineRatio()).isBetween(0.0, 1.0);
            assertThat(p.impulsivity()).isGreaterThan(0.0);
            // 대표 취미가 taxonomy(hobbies.json)에 존재
            assertThat(hobbyTypes).containsAll(p.hobbies());
        });
    }

    @Test
    void hobbySignaturesShowThroughDedicatedCategories() {
        // 취미 taxonomy: 12종, 각 취미가 드러나는 category2 매핑
        var hobbies = loader.hobbies();
        assertThat(hobbies).hasSizeGreaterThanOrEqualTo(10);
        assertThat(hobbies).allSatisfy(h -> assertThat(h.signatureCategories()).isNotEmpty());
        assertThat(hobbies).anyMatch(h -> h.type().equals("반려동물"));
        assertThat(hobbies).anyMatch(h -> h.type().equals("여행"));
        // 취미 전용 카테고리가 실제로 카탈로그(상품·브랜드)에 존재
        assertThat(loader.products()).containsKeys("공연전시", "반려동물", "여행숙박", "디지털가전", "아웃도어캠핑");
        assertThat(loader.brands()).containsKeys("반려동물", "공연전시", "서점문구");
        // 취미 상품 실 예시
        assertThat(loader.products().get("반려동물")).anyMatch(p -> p.name().contains("사료"));
        assertThat(loader.products().get("공연전시")).anyMatch(p -> p.name().contains("뮤지컬 티켓"));
    }

    @Test
    void nationwideDongsHaveValidCoordsAndPopulationWeights() {
        List<RegionEntry> regions = loader.regions();
        assertThat(regions).hasSizeGreaterThanOrEqualTo(3000);       // 전국 ~3,495 행정동
        assertThat(regions).allSatisfy(r -> {
            assertThat(r.lat()).isBetween(33.0, 39.0);               // 대한민국 위도
            assertThat(r.lon()).isBetween(124.0, 132.0);             // 경도
            assertThat(r.dong()).isNotBlank();
            assertThat(r.weight()).isGreaterThan(0.0);
        });
        // 서울·부산 등 여러 시도가 존재(전국)
        assertThat(regions.stream().map(RegionEntry::sido).distinct().count()).isGreaterThanOrEqualTo(15);
        // 가중치 합 ≈ 1
        double wsum = regions.stream().mapToDouble(RegionEntry::weight).sum();
        assertThat(wsum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.02));
    }

    @Test
    void independentsAreLargeRealNamePool() {
        @SuppressWarnings("unchecked")
        Map<String, Object> pool = (Map<String, Object>) loader.independents().get("namePoolByCategory2");
        int total = pool.values().stream().mapToInt(v -> ((List<?>) v).size()).sum();
        assertThat(total).isGreaterThanOrEqualTo(10_000);   // 서울 실상호 + KAPF
        assertThat(loader.independents()).containsKeys("dongWeights", "coordBBoxTM");
        assertThat(loader.fares()).containsKey("대중교통");
    }
}
