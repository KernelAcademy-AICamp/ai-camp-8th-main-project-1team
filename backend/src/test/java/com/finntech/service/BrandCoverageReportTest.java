package com.finntech.service;

import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.freechannel.FreeChannelQueue;
import com.finntech.repository.MerchantBrandRepository;
import com.finntech.repository.MerchantCategoryRepository;
import com.finntech.repository.SpendingLedgerRepository;
import com.finntech.repository.UserPaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>회사명이 서비스를 가린 자리를 스스로 찾아내는가.</b>
 *
 * <p>브랜드 표는 회사명과 서비스명을 갈라 두고 <b>회사명에는 소분류를 안 붙인다</b>. 그래서
 * 어떤 상호가 회사명에만 걸리면 소분류를 영원히 못 얻는다 — 운영에서 {@code 카카오스타일}
 * (지그재그 운영사)이 {@code 카카오} 에 걸려 그랬고, {@code 티머니지하철} 도 그랬다
 * (2026-08-25 실측 144건).
 *
 * <p>이 구멍은 <b>표를 고칠 때마다 새로 생긴다</b> — 회사를 하나 넣을 때마다 그 회사의
 * 서비스들이 같이 들어와야 하기 때문이다. 손으로 찾으면 놓친다. 그래서 되묻는 문을 두고,
 * 그 문이 정말 답하는지를 여기서 잠근다.
 */
class BrandCoverageReportTest {

    private final IndustryCategoryMapper industries = new IndustryCategoryMapper(new ObjectMapper());

    private final MerchantBrandService brands = new MerchantBrandService(
            mock(MerchantBrandRepository.class), mock(MerchantCategoryRepository.class),
            mock(TempClassifierService.class), mock(UserPaymentRepository.class),
            new ObjectMapper(), null, mock(FreeChannelQueue.class));

    private BrandCoverageReport reportOf(List<String> names) {
        SpendingLedgerRepository ledger = mock(SpendingLedgerRepository.class);
        when(ledger.findDistinctMerchantNamesByOrigin(anyString())).thenReturn(names);
        return new BrandCoverageReport(ledger, brands, industries);
    }

    @Test
    @DisplayName("회사명에만 걸린 상호를 그 회사 이름 아래 모은다")
    void 회사명_아래_모은다() {
        var result = reportOf(List.of(
                "주식회사 카카오",           // 회사명뿐 — 가려진다
                "카카오(상품권)_다날",        // 회사명뿐 — 가려진다
                "카카오T일반택시_0",          // 서비스 표기가 있다 — 답한다
                "동네개인식당")).scan("REAL");

        assertThat(result.merchants()).isEqualTo(4);
        assertThat(result.withSub()).as("카카오T 는 소분류를 얻어야 한다").isEqualTo(1);
        assertThat(result.shadowed()).containsEntry("카카오", 2);
        assertThat(result.unmatched()).as("어떤 브랜드에도 안 걸린 개인 상호").isEqualTo(1);
        assertThat(result.samples()).anyMatch(s -> s.startsWith("카카오 ← "));
    }

    /**
     * <b>차량번호가 붙어도 서비스 표기가 이긴다.</b> 같은 자리에서 시작하면 긴 쪽이 이기고,
     * 짧은 표기가 긴 표기 안에 통째로 들면 버려진다 — 그 둘이 여기서 함께 걸린다.
     */
    @Test
    @DisplayName("카카오택시-차량번호는 회사명에 안 먹힌다")
    void 차량번호가_붙어도_택시다() {
        for (String name : List.of("카카오택시-서울33바2592", "카카오택시-경북15바7708",
                "카카오T일반택시(법인)_4", "카카오 T_바이크-주식회사 카카오모빌리티")) {
            String brand = brands.subBrandOf(name, industries::hasSub).orElse("");
            assertThat(industries.midOfSub(industries.subOfBrand(brand)))
                    .as("'%s' 가 교통으로 안 간다", name).isEqualTo("교통/자동차");
        }
        assertThat(industries.subOfBrand(
                brands.subBrandOf("카카오택시-서울33바2592", industries::hasSub).orElse("")))
                .isEqualTo("택시");
    }

    /** 운영에서 실제로 가려져 있던 것들 — 표기를 세워 풀었다. 되돌아가지 않게 잠근다. */
    @Test
    @DisplayName("가려져 있던 서비스들이 이제 답한다")
    void 가려졌던_것이_답한다() {
        var result = reportOf(List.of("카카오스타일", "주식회사 카카오스타일",
                "03월티머니지하철 0008건", "03월티머니버스 0001건", "(주)티머니시외버스",
                "LGUPLUS 통신요금자동이체")).scan("REAL");

        assertThat(result.withSub()).as("여섯 다 소분류를 얻어야 한다").isEqualTo(6);
        assertThat(result.shadowed()).isEmpty();
    }
}
