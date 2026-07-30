package com.finntech.mydata.generation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>낭비 임계 보정</b> — 금액을 압축하면 낭비가 덜 잡힌다. 얼마나 낮춰야 제자리인가.
 *
 * <p>이론상으로는 금액과 기준액에 같은 압축을 걸면 배수가 {@code m → m^α} 로 바뀌므로 임계를
 * {@code E^α} 로 옮기면 판정 대상이 같아야 한다. 그런데 <b>기준액이 압축 임계(1만원)보다 작은
 * 맥락</b>(카페 4천원·편의점)은 기준은 그대로인데 금액만 눌려 배수가 더 줄어든다. 하나의 상수로는
 * 복원되지 않으므로 실측으로 맞춘다.
 *
 * <p><b>스프링을 띄워 실제 {@code application.yml} 을 읽는다.</b> 처음에는 스프링 없이
 * {@code new GenerationProperties()} 로 쟀는데, 그건 Java 기본값(가중치가 전부 낮고 history 120일)
 * 이라 실제 생성과 다른 세상이었다. 값을 손으로 옮겨 맞추려다 두 번 어긋났다 —
 * <b>설정을 복제하지 말고 같은 것을 읽어야 한다.</b>
 *
 * <p>DB 없이 시뮬레이터만 돌린다. 전량 재생성(40분)을 돌려 보고 고치는 것보다 훨씬 싸다.
 *
 * <p><b>생성 중인 DB와 비교하지 말 것.</b> {@link PopulationBuilder}는 페르소나 순서대로 사용자를
 * 만든다 — 절약형을 다 만든 뒤 균형형, 그다음 과소비형이다. 그래서 생성이 절반쯤 진행된 시점의
 * 표는 <b>낭비가 적은 페르소나에 치우쳐</b> 있고, 그걸 근거로 임계를 고치면 엉뚱한 값으로 간다.
 * 실제로 그렇게 두 번 재시작했다. 비교는 <b>생성이 끝난 뒤 전체</b>로만 한다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:waste_calib;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "mydata.seed.enabled=false", "mydata.generation.enabled=false",
})
class WasteCalibrationTest {

    /** 이동(통근·주유) 업종 — 필수라 낭비가 거의 안 붙는다. 희석 효과를 떼어 보려고 나눈다. */
    private static final Set<String> MOBILITY =
            Set.of("4921", "4910", "4922", "4923", "5291", "4771");

    @Autowired GenerationProperties props;
    @Autowired CatalogLoader loader;
    @Autowired CatalogSampler sampler;

    /** 주어진 임계로 시뮬레이션해 (전체 낭비율, 이동 제외 낭비율, 건수)를 낸다. */
    private double[] ratios(double excessMultiplier, int users) {
        double saved = props.getLabel().getImpulse().getExcessAmountMultiplier();
        props.getLabel().getImpulse().setExcessAmountMultiplier(excessMultiplier);
        try {
            var sim = new DailyActivitySimulator(sampler, new WasteLabeler(props, sampler), loader, props);
            long all = 0, allWaste = 0, ex = 0, exWaste = 0;
            for (var u : new PopulationBuilder(loader, props).build(props.getSeed(), users)) {
                LocalDate end = u.startDate().plusDays(props.getHistoryDays());
                for (GenTxn t : sim.simulate(u, end)) {
                    boolean waste = "WASTE".equals(t.wasteLabel());
                    all++; if (waste) allWaste++;
                    if (!MOBILITY.contains(t.ksicCode())) { ex++; if (waste) exWaste++; }
                }
            }
            return new double[]{100.0 * allWaste / all, 100.0 * exWaste / ex, all};
        } finally {
            props.getLabel().getImpulse().setExcessAmountMultiplier(saved);
        }
    }

    @Test
    @DisplayName("임계별 낭비율 — 직전 데이터(건수 32.55%)에 맞는 값을 찾는다")
    void 임계_보정값을_찾는다() {
        Map<Double, double[]> table = new LinkedHashMap<>();
        for (double m : new double[]{1.50, 1.36, 1.20, 1.00, 0.80, 0.60, 0.40}) {
            table.put(m, ratios(m, 120));
        }
        System.out.printf("%n현재 설정: excess-mult=%.2f · compress α=%.2f T=%.0f · history=%d일%n",
                props.getLabel().getImpulse().getExcessAmountMultiplier(),
                props.getAddress().getCompressAlpha(), props.getAddress().getCompressThreshold(),
                props.getHistoryDays());
        System.out.println("임계   전체낭비%   이동제외%   건수");
        table.forEach((m, r) ->
                System.out.printf("%.2f   %7.2f   %8.2f   %,.0f%n", m, r[0], r[1], r[2]));

        // 임계를 낮추면 낭비가 더 잡혀야 한다(단조). 이 성질이 깨지면 보정 자체가 성립하지 않는다.
        double prev = -1;
        for (var e : table.entrySet()) {
            if (prev >= 0) assertThat(e.getValue()[0]).isGreaterThanOrEqualTo(prev - 0.5);
            prev = e.getValue()[0];
        }
    }

    @Test
    @DisplayName("업종별 낭비율 — 실제 생성 결과와 대조해 하네스가 같은 세상인지 본다")
    void 업종별_대조() {
        var sim = new DailyActivitySimulator(sampler, new WasteLabeler(props, sampler), loader, props);
        Map<String, long[]> per = new LinkedHashMap<>();   // cat2 → [건수, 낭비]
        long all = 0, waste = 0;
        for (var u : new PopulationBuilder(loader, props).build(props.getSeed(), 120)) {
            for (GenTxn t : sim.simulate(u, u.startDate().plusDays(props.getHistoryDays()))) {
                long[] a = per.computeIfAbsent(t.category2(), k -> new long[2]);
                a[0]++; all++;
                if ("WASTE".equals(t.wasteLabel())) { a[1]++; waste++; }
            }
        }
        System.out.printf("%n[하네스] excess-mult=%.2f · 전체 %,d건 · 낭비 %.2f%%%n",
                props.getLabel().getImpulse().getExcessAmountMultiplier(), all, 100.0 * waste / all);
        per.entrySet().stream()
           .sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0])).limit(14)
           .forEach(e -> System.out.printf("  %-12s %,8d  %5.1f%%%n",
                   e.getKey(), e.getValue()[0], 100.0 * e.getValue()[1] / e.getValue()[0]));
        assertThat(all).isPositive();
    }
}
