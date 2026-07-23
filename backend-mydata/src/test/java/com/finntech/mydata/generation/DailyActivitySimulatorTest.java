package com.finntech.mydata.generation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 하루활동 시뮬레이터 — 거래 생성·결정론·온/오프라인·서비스효과 하강·취미·지출비중 검증. */
class DailyActivitySimulatorTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final CatalogLoader loader = new CatalogLoader(mapper);
    private final GenerationProperties props = new GenerationProperties();
    private final DailyActivitySimulator sim = new DailyActivitySimulator(
            new CatalogSampler(loader), new WasteLabeler(props), loader, props);
    private final List<GeneratedUser> users = new PopulationBuilder(loader, props).build(20260721L, 500);

    private GeneratedUser first(String persona) {
        return users.stream().filter(u -> u.variant().baseName().equals(persona)).findFirst().orElseThrow();
    }

    @Test
    void 거래가_결정론으로_온오프라인_실데이터로_생성된다() {
        GeneratedUser u = first("과소비형");
        LocalDate end = u.startDate().plusDays(120);
        List<GenTxn> txns = sim.simulate(u, end);

        assertThat(txns).isNotEmpty();
        assertThat(sim.simulate(u, end)).hasSameSizeAs(txns);        // 결정론
        assertThat(txns).allMatch(t -> t.amount() > 0);
        assertThat(txns).allMatch(t -> t.wasteLabel().equals("WASTE") || t.wasteLabel().equals("ESSENTIAL"));
        // 온라인=위치 null, 오프라인=위치 있음
        assertThat(txns).anyMatch(t -> t.channel().equals("ONLINE") && t.lat() == null);
        assertThat(txns).anyMatch(t -> t.channel().equals("OFFLINE") && t.lat() != null);
        // 취미(과소비형: 여행·문화공연·패션쇼핑) 카테고리 등장
        assertThat(txns).anyMatch(t -> Set.of("여행숙박", "공연전시", "의류패션", "백화점", "화장품", "드럭스토어")
                .contains(t.category2()));
    }

    @Test
    void 서비스효과로_낭비율이_시간에_따라_하강한다() {
        long earlyW = 0, earlyN = 0, lateW = 0, lateN = 0;
        List<GeneratedUser> over = users.stream()
                .filter(u -> u.variant().baseName().equals("과소비형")).limit(60).toList();
        for (GeneratedUser u : over) {
            for (GenTxn t : sim.simulate(u, u.startDate().plusDays(120))) {
                long dd = ChronoUnit.DAYS.between(u.startDate(), t.date().toLocalDate());
                boolean w = t.wasteLabel().equals("WASTE");
                if (dd < 20) { earlyN++; if (w) earlyW++; }
                else if (dd >= 60 && dd < 100) { lateN++; if (w) lateW++; }
            }
        }
        double early = (double) earlyW / earlyN, late = (double) lateW / lateN;
        assertThat(early).isGreaterThan(late);   // 서비스 효과: 초기 낭비 > 후기 낭비
    }

    @Test
    void 방문빈도가_카테고리믹스를_대체로_따른다() {
        // 방문가중(mix/평균가)은 '방문 빈도'가 카테고리믹스를 따르게 한다. 절대 지출총액은
        // heavy-tail(여행·공연 같은 고액 여가 결제 소수)이 지배할 수 있으므로, 페르소나 지배성은
        // '빈도'로 검증한다(외식형=식비를 가장 자주 결제). (§13-11: 금액 스냅과 무관하게 성립)
        GeneratedUser u = first("외식형");   // 식비 믹스 ~0.54
        List<GenTxn> txns = sim.simulate(u, u.startDate().plusDays(120));
        Map<String, Long> cntByCat1 = txns.stream()
                .collect(Collectors.groupingBy(GenTxn::category1, Collectors.counting()));
        String top = cntByCat1.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        double sikbiFreq = cntByCat1.getOrDefault("식비", 0L) / (double) txns.size();
        assertThat(top).isEqualTo("식비");           // 외식형은 식비를 가장 자주 결제
        assertThat(sikbiFreq).isGreaterThan(0.30);   // 식비 방문이 지배적
    }
}
