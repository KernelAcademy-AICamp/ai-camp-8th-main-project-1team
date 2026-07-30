package com.finntech.mydata.generation;

import com.finntech.mydata.domain.MyDataPayment;
import com.finntech.mydata.repository.MyDataPaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결제 금액이 <b>카탈로그 가격대</b>를 크게 벗어나지 않는지 본다.
 *
 * <p><b>실제로 밟은 버그.</b> 금액 배율(`flexScale`)에 하한만 있고 상한이 없어서, 월급이 큰
 * 사용자는 모든 금액이 몇 배로 부풀었다. 1,000만 건 실측에서 수량 1건 결제의 <b>27.7%</b>가
 * 카탈로그 상한을 넘었고 최대 <b>14.2배</b>까지 갔다 — `클라이밍 1일권 181,000원`
 * (카탈로그 20,000~30,000), `헬스 1개월 623,000원`(50,000~90,000) 같은 값이 나왔다.
 *
 * <p>부유함은 같은 물건을 몇 배 주고 사는 것이 아니다. 더 비싼 품목을 고르거나 더 자주 쓰는
 * 것으로 나타나야 하고, 그 둘은 페르소나가 이미 한다.
 *
 * <p><b>수량은 뺀다.</b> 편의점·대형마트·이커머스는 한 번에 1~3개를 사므로 단가의 3배까지가
 * 정상이다 — 그 맥락은 이 검사에서 제외하지 않고 수량으로 나눠 단가로 되돌려 비교한다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:price_range;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "mydata.seed.enabled=false",
        "mydata.generation.enabled=true",
        "mydata.generation.target-count=300000",
})
class PriceRangeTest {

    /**
     * 단가가 카탈로그 상한의 이 배수를 넘으면 '존재하지 않는 가격'으로 본다.
     *
     * <p>1.0으로 두지 않는 이유: 금액에는 로그정규 지터(sigma ≤ 0.30)와 100원 단위 스냅이 붙어
     * 상한을 조금 넘는 것이 정상이다. 배율 상한(1.6)과 지터를 합쳐도 2.2배를 넘을 일은 없다.
     */
    private static final double MAX_OVER = 2.2;
    /** 이 비율을 넘게 벗어나면 배율이나 지터 어딘가가 풀린 것이다. */
    private static final double MAX_OFFENDER_RATIO = 0.02;

    @Autowired MyDataPaymentRepository payments;
    @Autowired CatalogSampler sampler;
    @Autowired CatalogLoader catalog;

    @Test
    @DisplayName("결제 단가가 카탈로그 가격대를 크게 벗어나지 않는다")
    void unitPriceStaysWithinCatalogRange() {
        // 품목명 → 카탈로그 상한(같은 이름이 여러 맥락에 있으면 가장 넓은 쪽)
        Map<String, Integer> ceiling = new HashMap<>();
        for (var c : catalog.contexts()) {
            for (var p : sampler.productsOf(c.category2())) {
                ceiling.merge(p.name(), p.priceHigh(), Math::max);
            }
        }
        assertThat(ceiling).as("카탈로그 품목").isNotEmpty();

        List<MyDataPayment> all = payments.findAll();
        assertThat(all).isNotEmpty();

        int checked = 0, over = 0;
        double worst = 1.0;
        String worstItem = null;
        for (MyDataPayment p : all) {
            Integer hi = ceiling.get(p.getProductName());
            if (hi == null || hi <= 0) continue;          // 고시요금·미상 품목은 다른 검사가 본다
            int qty = Math.max(1, p.getQuantity() == null ? 1 : p.getQuantity());
            double unit = p.getAmount() / (double) qty;   // 수량을 되돌려 단가로 본다
            checked++;
            double ratio = unit / hi;
            if (ratio > MAX_OVER) {
                over++;
                if (ratio > worst) { worst = ratio; worstItem = p.getProductName(); }
            }
        }

        double ratio = checked == 0 ? 0 : (double) over / checked;
        System.out.printf("  검사 %,d건 · 상한 %.1f배 초과 %,d건 (%.2f%%) · 최악 %.1f배 %s%n",
                checked, MAX_OVER, over, ratio * 100, worst, worstItem);
        assertThat(ratio).as("카탈로그 상한을 %.1f배 넘는 결제 비율", MAX_OVER)
                .isLessThan(MAX_OFFENDER_RATIO);
    }
}
