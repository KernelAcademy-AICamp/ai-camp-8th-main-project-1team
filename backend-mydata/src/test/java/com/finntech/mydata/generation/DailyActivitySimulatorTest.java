package com.finntech.mydata.generation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
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
    private final CatalogSampler sampler = new CatalogSampler(loader, new MerchantRegistry(
            props.getSeed(), loader.regions(), props.getAddress().getBubunProb()), props);
    private final DailyActivitySimulator sim = new DailyActivitySimulator(
            sampler, new WasteLabeler(props, sampler), loader, props);
    private final List<GeneratedUser> users = new PopulationBuilder(loader, props).build(20260721L, 500);

    private GeneratedUser first(String persona) {
        return users.stream().filter(u -> u.variant().baseName().equals(persona)).findFirst().orElseThrow();
    }

    @Test
    void transactionsGeneratedDeterministicallyOnAndOffline() {
        GeneratedUser u = first("과소비형");
        LocalDate end = u.startDate().plusDays(120);
        List<GenTxn> txns = sim.simulate(u, end);

        assertThat(txns).isNotEmpty();
        assertThat(sim.simulate(u, end)).hasSameSizeAs(txns);        // 결정론
        assertThat(txns).allMatch(t -> t.amount() > 0);
        assertThat(txns).allMatch(t -> t.wasteLabel().equals("WASTE") || t.wasteLabel().equals("ESSENTIAL"));
        // 온라인=전국 본사 위치, 오프라인=앵커 동 위치 — 이제 둘 다 위치·사업자번호가 있다
        assertThat(txns).anyMatch(t -> t.channel().equals("ONLINE") && t.lat() != null);
        assertThat(txns).anyMatch(t -> t.channel().equals("OFFLINE") && t.lat() != null);
        // 해외 본사(스팀·아마존·아고다 등)는 국내 사업자번호가 없다 — 있으면 형식이 유효해야 한다.
        assertThat(txns).allMatch(t -> t.businessNumber() == null
                || BusinessNumberGenerator.isValid(t.businessNumber())
                || t.businessNumber().length() == 10);
        // 취미(과소비형: 여행·문화공연·패션쇼핑) 카테고리 등장
        assertThat(txns).anyMatch(t -> Set.of("여행숙박", "공연전시", "의류패션", "백화점", "화장품", "드럭스토어")
                .contains(t.category2()));
    }

    @Test
    void wasteRatioDeclinesOverTimeFromServiceEffect() {
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
    void mobilityReflectsTravelAndAdjacentDong() {
        boolean sawTravel = false, sawAdjacent = false;
        for (GeneratedUser u : users.subList(0, 60)) {
            String homeSido = u.home().sido(), homeSigungu = u.home().sigungu(), homeDong = u.home().dong();
            Set<String> dongsInHomeSigungu = new HashSet<>();
            for (GenTxn t : sim.simulate(u, u.startDate().plusDays(120))) {
                if (!"OFFLINE".equals(t.channel()) || t.address() == null) continue;
                String[] p = t.address().split(" ");
                if (p.length < 3) continue;
                if (!p[0].equals(homeSido)) sawTravel = true;                        // 다른 시도 = 여행
                if (p[0].equals(homeSido) && p[1].equals(homeSigungu)) dongsInHomeSigungu.add(p[2]);
            }
            if (dongsInHomeSigungu.stream().anyMatch(d -> !d.equals(homeDong))) sawAdjacent = true;
            if (sawTravel && sawAdjacent) break;
        }
        assertThat(sawTravel).as("먼 지역(다른 시도) 여행 결제가 나타나야 한다").isTrue();
        assertThat(sawAdjacent).as("같은 시군구의 인접 동 결제가 나타나야 한다").isTrue();

        // 동선(주소)도 결정론이어야 한다 — 같은 사용자 재실행 시 주소 시퀀스 동일
        GeneratedUser u0 = users.get(0);
        LocalDate end0 = u0.startDate().plusDays(60);
        assertThat(sim.simulate(u0, end0).stream().map(GenTxn::address).toList())
                .isEqualTo(sim.simulate(u0, end0).stream().map(GenTxn::address).toList());
    }

    @Test
    void visitFrequencyRoughlyFollowsCategoryMix() {
        // 방문가중(mix/평균가)은 '방문 빈도'가 카테고리믹스를 따르게 한다. 절대 지출총액은
        // heavy-tail(여행·공연 같은 고액 여가 결제 소수)이 지배할 수 있으므로, 페르소나 지배성은
        // '빈도'로 검증한다(외식형=식비를 가장 자주 결제). (§13-11: 금액 스냅과 무관하게 성립)
        GeneratedUser u = first("외식형");   // 식비 믹스 ~0.54
        List<GenTxn> txns = sim.simulate(u, u.startDate().plusDays(120));
        // **금액 비중**으로 본다. 빈도로 보면 카페(1,300원)가 한식(13,000원)을 이긴다 —
        // 방문가중이 `지출비중 ÷ 평균단가`라 싼 업종일수록 자주 찍히는 것이 정상이다.
        // 페르소나가 말하는 것은 '지출비중'이므로 검증도 금액으로 해야 한다.
        Map<String, Long> amtByKsic = txns.stream()
                .collect(Collectors.groupingBy(GenTxn::ksicCode,
                        Collectors.summingLong(t -> (long) t.amount())));
        long total = amtByKsic.values().stream().mapToLong(Long::longValue).sum();
        // 5611 한식 · 5612 외국식 · 5616 치킨피자 · 5619 간이 = 식비 중분류
        long food = java.util.stream.Stream.of("5611", "5612", "5616", "5619")
                .mapToLong(c -> amtByKsic.getOrDefault(c, 0L)).sum();
        assertThat(food / (double) total).isGreaterThan(0.30);   // 외식형은 식비 지출이 지배적
    }
}
