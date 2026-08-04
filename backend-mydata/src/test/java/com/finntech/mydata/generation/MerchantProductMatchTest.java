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
    @DisplayName("계약 맥락도 상호와 요금제의 짝이 맞는다 — serves 가 비면 이 단정이 무너진다")
    void contractMerchantsSellTheirOwnPlans() {
        // 대중교통을 고칠 때 만든 `productName` 인자를 계약 셋(구독·통신비·공과금)은 안 쓰고 있었다.
        // 게다가 **`serves` 가 비어 있으면 `canSell` 이 무조건 true** 라, 인자를 넘기도록 고쳐도
        // 그것만으로는 아무 효과가 없다. 그래서 여기서 두 가지를 같이 못박는다 —
        // ① serves 가 채워져 있을 것 ② 뽑힌 짝이 실제로 맞을 것.
        //
        // 2026-08-04 실측으로 이런 명세서가 있었다:
        //   애플티비플러스 · 넷플릭스 광고형 / 서울시상수도사업본부 · 정수기렌탈
        for (String cat : List.of("스트리밍", "통신비", "공과금")) {
            var brands = catalog.brands().get(cat);
            assertThat(brands).as(cat + " 브랜드").isNotEmpty();
            assertThat(brands).as(cat + " — serves 가 빈 상호가 있으면 아무 요금제나 받게 된다")
                    .allMatch(b -> !b.serves().isEmpty(), "serves 가 비어 있지 않다");

            Random r = new Random(20260804);
            var mismatched = new TreeSet<String>();
            for (int i = 0; i < 2000; i++) {
                var p = sampler.resolveProduct(cat, r);
                var m = sampler.resolveMerchant(cat, null, p.name(), r);
                boolean ok = brands.stream()
                        .filter(b -> b.name().equals(m.name()) || b.forms().contains(m.name()))
                        .anyMatch(b -> b.canSell(p.name()));
                if (!ok) mismatched.add(m.name() + " · " + p.name());
            }
            assertThat(mismatched).as(cat + " — 상호가 팔지 않는 요금제를 받았다").isEmpty();
        }
    }

    @Test
    @DisplayName("알뜰폰 사업자는 5G·인터넷결합 요금제를 팔지 않는다")
    void mvnoDoesNotSellFlagshipPlans() {
        // MVNO 는 망을 빌려 쓰는 재판매 사업자다. 5G 프리미엄 요금제나 인터넷+TV 결합은
        // 망을 가진 MNO 의 상품이라, 알뜰폰 명세서에 그게 찍히면 거짓 데이터다.
        for (var b : catalog.brands().get("통신비")) {
            boolean mvno = List.of("KT엠모바일", "SK세븐모바일", "U+유모바일", "헬로모바일")
                    .contains(b.name());
            if (!mvno) continue;
            assertThat(b.canSell("5G 6~7만원대")).as(b.name() + " 는 5G 요금제를 팔지 않는다").isFalse();
            assertThat(b.canSell("인터넷+TV 결합")).as(b.name() + " 는 결합상품을 팔지 않는다").isFalse();
            assertThat(b.canSell("알뜰폰 요금제")).as(b.name() + " 는 알뜰폰을 판다").isTrue();
        }
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
