package com.finntech.mydata.generation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>상호와 품목의 짝</b>이 맞는지 본다.
 *
 * <p><b>실제로 밟은 버그.</b> 상호와 품목을 따로 뽑아서 명세서에 이런 줄이 찍혔다 —
 * {@code 지하철 · 시내버스 1,500원}, {@code 지하철 · 광역버스 장거리 3,167원}.
 * 한 맥락(대중교통)에 도시철도와 버스·충전 사업자가 섞여 있는데 짝을 맞추지 않았기 때문이다.
 * 1,600만 건이 그렇게 생성됐고, 사용자가 명세서를 눈으로 보고 찾아냈다.
 *
 * <p>덤으로 잡은 것: {@code 지하철}·{@code 시내버스}·{@code 마을버스}가 <b>상호로</b> 등록돼
 * 있었다. 그건 교통수단이지 사업자가 아니다 — 실제 명세서에는 서울교통공사·한국철도공사·
 * 티머니·캐시비가 찍힌다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:merchant_match;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "mydata.seed.enabled=false", "mydata.generation.enabled=false",
})
class MerchantProductMatchTest {

    @Autowired CatalogSampler sampler;
    @Autowired CatalogLoader catalog;

    @Test
    @DisplayName("대중교통 상호는 교통수단 이름이 아니라 사업자다")
    void transitMerchantsAreOperators() {
        List<String> names = catalog.brands().get("대중교통").stream()
                .map(CatalogModels.BrandEntry::name).toList();

        assertThat(names)
                .as("교통수단 이름은 상호가 아니다")
                .doesNotContain("지하철", "시내버스", "마을버스", "광역버스");
        assertThat(names).contains("서울교통공사", "한국철도공사");
    }

    @Test
    @DisplayName("뽑힌 상호는 그 품목을 파는 사업자다 — 지하철 요금을 버스 회사가 받지 않는다")
    void merchantSellsTheProduct() {
        Random r = new Random(20260730);
        var mismatched = new TreeSet<String>();

        // 명세서에 찍히는 이름은 `forms` 의 표기 변형일 수 있다(한국철도공사 → 코레일·KORAIL).
        // 그래서 이름 하나가 아니라 그 사업자가 쓸 수 있는 표기 전부를 본다.
        var railNames = new java.util.HashSet<String>();
        for (var b : catalog.brands().get("대중교통")) {
            if (b.serves().contains("지하철")) {
                railNames.add(b.name());
                railNames.addAll(b.forms());
            }
        }
        assertThat(railNames).as("도시철도 사업자 표기").isNotEmpty();

        for (int i = 0; i < 4000; i++) {
            var product = sampler.resolveProduct("대중교통", r);
            var merchant = sampler.resolveMerchant("대중교통", null, product.name(), r);

            boolean rail = product.name().startsWith("지하철");
            boolean railOperator = railNames.contains(merchant.name());
            if (rail != railOperator) {
                mismatched.add(merchant.name() + " · " + product.name());
            }
        }

        assertThat(mismatched).as("상호와 품목이 어긋난 조합").isEmpty();
    }

    @Test
    @DisplayName("serves 를 비워 두면 아무 품목이나 판다 — 기존 맥락은 영향받지 않는다")
    void emptyServesMatchesEverything() {
        var open = new CatalogModels.BrandEntry("아무데나", false, "OFFLINE", List.of(), List.of());
        assertThat(open.canSell("무엇이든")).isTrue();
        assertThat(open.canSell(null)).isTrue();

        var rail = new CatalogModels.BrandEntry("서울교통공사", false, "OFFLINE",
                List.of(), List.of("지하철"));
        assertThat(rail.canSell("지하철 기본구간(~10km)")).isTrue();
        assertThat(rail.canSell("시내버스")).isFalse();
    }
}
