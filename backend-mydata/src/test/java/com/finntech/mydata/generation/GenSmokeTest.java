package com.finntech.mydata.generation;

import com.finntech.mydata.domain.MyDataPayment;
import com.finntech.mydata.repository.MyDataPaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 재생성 전 점검 — 생성된 결제가 새 축을 제대로 쓰는지 실제 데이터로 본다. */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ksic_smoke;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "mydata.seed.enabled=false",
        "mydata.generation.enabled=true",
        "mydata.generation.target-count=60000",
})
class GenSmokeTest {

    /** 알 수 없는 결제(간편결제·PG)의 업종코드 — 대조표에 일부러 없다. */
    private static final String UNKNOWN_INDUSTRY = "642004";

    @Autowired MyDataPaymentRepository payments;
    @Autowired IndustryCategoryMap ksicToMid;
    @Autowired CatalogSampler sampler;

    @Test
    @DisplayName("생성 결과 점검 — 업종코드 유효 · 중분류 커버 · 미분류 5% 미만 · 고시요금 보존")
    void generatedDataIsSound() {
        List<MyDataPayment> all = payments.findAll();
        assertThat(all).isNotEmpty();

        // ① 모든 결제가 유효한 6자리 국세청 업종코드를 갖는가
        Set<String> codes = all.stream().map(MyDataPayment::getKsicCode).collect(Collectors.toSet());
        assertThat(codes).allMatch(c -> c != null && c.matches("\\d{6}"));

        // ② 대조표에 없는 코드는 '알 수 없는 결제'뿐이어야 한다.
        //
        // 642004(포털 및 기타 인터넷 정보 매개 서비스업)는 **일부러 빼 둔다** — 간편결제·PG 를 거친
        // 결제는 무엇을 샀는지 모르므로 분류하면 안 된다. 그 밖의 코드가 여기 뜨면 대조표 누락이고,
        // 그 업종의 소비가 통째로 '카테고리없음'으로 새는데 크래시가 안 나서 알아채기 어렵다.
        List<String> unmapped = codes.stream().filter(c -> ksicToMid.midOf(c) == null).sorted().toList();
        System.out.println("  대조표에 없는 코드: " + unmapped);
        assertThat(unmapped).containsAnyOf(UNKNOWN_INDUSTRY).isSubsetOf(UNKNOWN_INDUSTRY);

        // ③ 중분류 분포와 미분류 비율
        Map<String, Long> byMid = all.stream().collect(Collectors.groupingBy(
                p -> {
                    String m = ksicToMid.midOf(p.getKsicCode());
                    return m == null ? "카테고리없음" : m;
                }, TreeMap::new, Collectors.counting()));
        long total = all.size();
        System.out.println("  결제 " + total + "건 · 중분류 " + byMid.size() + "종");
        byMid.forEach((m, n) -> System.out.printf("    %-14s%7d  %5.1f%%%n", m, n, n * 100.0 / total));
        long unclassified = byMid.getOrDefault("카테고리없음", 0L);
        System.out.printf("  미분류 %.1f%%%n", unclassified * 100.0 / total);
        assertThat(unclassified * 100.0 / total).isLessThan(5.0);

        // ④ 고시요금이 보존되는가.
        //
        // **택시(4923)는 미터 요금이라 범위형이 맞다** — 여기 섞으면 검사가 무의미해진다.
        // 실제로 처음엔 4921·4923을 한 묶음으로 보고 `a < 3100` 예외를 뒀는데,
        // 그 조건이 검사 대상을 거의 다 걸러 내 아무것도 못 잡는 테스트가 됐다.
        // 대중교통(4921)만 본다 — 이건 고시 요금표가 있는 축이다.
        Set<Integer> ladder = Set.of(
                1550, 1650, 1750, 1850, 1950, 2050, 2150,   // 지하철 기본 + 거리비례
                1500, 1200, 3000);                          // 시내·마을·광역버스
        List<Integer> subway = all.stream()
                .filter(p -> "4921".equals(p.getKsicCode()))
                .map(MyDataPayment::getAmount).distinct().sorted().toList();
        // 광역버스 장거리(3,100~3,400)와 교통카드 충전(10,000~50,000)은 범위형이라 사다리 밖이다.
        List<Integer> offLadder = subway.stream()
                .filter(a -> !ladder.contains(a))
                .filter(a -> !(a >= 3100 && a <= 3400) && !(a >= 10000 && a <= 50000))
                .toList();
        System.out.println("  대중교통 금액 " + subway.size() + "종 · 사다리 밖 " + offLadder);
        assertThat(offLadder).as("요금표에 없는 대중교통 요금이 나왔다").isEmpty();

        // 지하철 기본요금이 실제로 가장 흔한가 — 가중치가 먹었는지 본다.
        Map<Integer, Long> subwayFreq = all.stream()
                .filter(p -> "4921".equals(p.getKsicCode()))
                .collect(Collectors.groupingBy(MyDataPayment::getAmount, Collectors.counting()));
        System.out.println("  대중교통 상위 금액: " + subwayFreq.entrySet().stream()
                .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed()).limit(6).toList());

        // ⑤ 상호가 업종과 맞는가 — 표본 출력(육안)
        System.out.println("  상호 표본:");
        for (String mid : List.of("식비", "술/유흥", "카페/간식", "의료", "미용", "생활")) {
            all.stream().filter(p -> mid.equals(ksicToMid.midOf(p.getKsicCode()))).limit(4)
                    .forEach(p -> System.out.printf("    %s %-10s %s%n",
                            p.getKsicCode(), mid, p.getMerchantName()));
        }
    }
}
