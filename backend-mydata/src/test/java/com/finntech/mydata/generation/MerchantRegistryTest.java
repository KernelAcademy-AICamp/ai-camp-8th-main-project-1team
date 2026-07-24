package com.finntech.mydata.generation;

import com.finntech.mydata.generation.CatalogModels.RegionEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가맹점 신원 해석 검증 — 특히 전국 중복 동명('중앙동' 등)에서 중복 배정이 없는지(사용자 우려의 근본 사례).
 */
class MerchantRegistryTest {

    private static final RegionEntry BUSAN_JUNGANG =
            new RegionEntry("부산광역시", "중구", "중앙동", 35.106, 129.032, 0.01);
    private static final RegionEntry DAEGU_JUNGANG =
            new RegionEntry("대구광역시", "중구", "중앙동", 35.869, 128.606, 0.01);

    private final MerchantRegistry reg = new MerchantRegistry(20260721L, List.of(BUSAN_JUNGANG), 0.35);

    @Test
    @DisplayName("같은 상호·같은 동명이라도 도시가 다르면 다른 사업자번호·다른 주소 (중복 동명 중복배정 방지)")
    void sameNameSameDongNameDifferentCityDiffers() {
        Merchant busan = reg.resolveOffline("김밥천국", "김밥천국", BUSAN_JUNGANG);
        Merchant daegu = reg.resolveOffline("김밥천국", "김밥천국", DAEGU_JUNGANG);

        assertThat(busan.businessNumber()).isNotEqualTo(daegu.businessNumber());
        assertThat(busan.address()).startsWith("부산광역시 중구 중앙동 ").endsWith("번지");
        assertThat(daegu.address()).startsWith("대구광역시 중구 중앙동 ").endsWith("번지");
    }

    @Test
    @DisplayName("같은 전체 신원은 항상 같은 사업자번호·주소·좌표 (결정론)")
    void sameFullIdentityIsStable() {
        Merchant a = reg.resolveOffline("스타벅스", "스타벅스 중앙동점", BUSAN_JUNGANG);
        Merchant b = reg.resolveOffline("스타벅스", "스타벅스 중앙동점", BUSAN_JUNGANG);
        assertThat(a).isEqualTo(b);
        assertThat(BusinessNumberGenerator.isValid(a.businessNumber())).isTrue();
    }

    @Test
    @DisplayName("온라인 가맹점은 전국 본사(HQ) 고정 번호·주소를 갖는다")
    void onlineHasFixedHqNumberAndAddress() {
        Merchant online = reg.resolveOnline("쿠팡", "쿠팡");
        assertThat(online.online()).isTrue();
        assertThat(BusinessNumberGenerator.isValid(online.businessNumber())).isTrue();
        assertThat(online.address()).endsWith("번지");
        assertThat(reg.resolveOnline("쿠팡", "쿠팡")).isEqualTo(online);   // 결정론
    }
}
