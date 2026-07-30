package com.finntech.mydata.generation;

import com.finntech.mydata.generation.CatalogModels.RegionEntry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** 가맹점/상품 리솔버 — 실상호·브랜드 동점 합성·온라인 무위치·상품가격 검증. */
class CatalogSamplerTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final CatalogLoader loader = new CatalogLoader(mapper);
    private final GenerationProperties props = new GenerationProperties();
    private final CatalogSampler sampler = new CatalogSampler(loader,
            new MerchantRegistry(20260721L, loader.regions(), 0.35), props);

    @Test
    void merchantProductLocationResolvedFromRealData() {
        RegionEntry anchor = loader.regions().get(0); // 서울 종로구 사직동
        assertThat(anchor.dong()).isNotBlank();

        // 업종코드 안에서 맥락 선택 — 예전에는 대분류였다.
        String c2 = sampler.pickCategory2("5611", new Random(1));
        assertThat(sampler.context(c2).ksicCode()).isEqualTo("5611");

        // 소상공인(한식) → 오프라인 실상호 + 앵커 동 지번주소·좌표 + 유효 사업자번호
        var korean = sampler.resolveMerchant("한식", anchor, new Random(2));
        assertThat(korean.channel()).isEqualTo("OFFLINE");
        assertThat(korean.lat()).isNotNull();
        assertThat(korean.address()).contains(anchor.dong());
        assertThat(korean.address()).endsWith("번지");
        assertThat(korean.name()).isNotBlank();
        assertThat(BusinessNumberGenerator.isValid(korean.businessNumber())).isTrue();

        // 프랜차이즈(편의점, branchable) → "브랜드 {동}점" 합성(가상 동)
        var conv = sampler.resolveMerchant("편의점", anchor, new Random(3));
        assertThat(conv.name()).endsWith("점");
        assertThat(conv.name()).contains(anchor.dong());
        assertThat(BusinessNumberGenerator.isValid(conv.businessNumber())).isTrue();

        // 온라인(이커머스) → **실제 본사 주소**. 예전에는 브랜드명 해시로 전국 행정동 하나를 뽑아
        // "쿠팡 · 경상북도 성주군"이 찍혔다. 이제 카탈로그의 실주소를 그대로 쓰므로 지번 형식이 아닐 수
        // 있고(도로명·건물명), 해외 본사는 사업자번호도 좌표도 없다.
        var online = sampler.resolveMerchant("이커머스", anchor, new Random(4));
        assertThat(online.channel()).isEqualTo("ONLINE");
        assertThat(online.address()).isNotBlank();
        if (online.businessNumber() != null) {
            assertThat(online.businessNumber()).hasSize(10);
        }

        // 상품 가격 유효
        var prod = sampler.resolveProduct("치킨", new Random(5));
        assertThat(prod.unitPrice()).isGreaterThan(0);
        assertThat(prod.name()).isNotBlank();
    }
}
