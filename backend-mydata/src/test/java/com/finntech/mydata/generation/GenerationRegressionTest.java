package com.finntech.mydata.generation;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.finntech.mydata.generation.CatalogModels.BrandEntry;
import com.finntech.mydata.generation.CatalogModels.RegionEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>과거에 실제로 터진 오류들</b>을 하나씩 못박는다.
 *
 * <p>이 저장소에서 데이터 사고는 늘 같은 방식으로 났다 — 생성기의 한 축을 고치면서 다른 축이
 * 조용히 어긋나고, 1,600만 건이 그렇게 만들어진 뒤 사람이 명세서를 눈으로 보고 찾아냈다.
 * 그래서 "무엇이 틀렸었나"를 테스트로 남긴다. 재생성 전에 이 파일이 초록이어야 한다.
 *
 * <p>여기 있는 항목은 전부 <b>겪은 것</b>이다. 상상한 위험이 아니다.
 */
class GenerationRegressionTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final CatalogLoader loader = new CatalogLoader(mapper);
    private final GenerationProperties props = new GenerationProperties();
    private final MerchantRegistry registry = new MerchantRegistry(
            props.getSeed(), loader.regions(), props.getAddress().getBubunProb());
    private final CatalogSampler sampler = new CatalogSampler(loader, registry, props);

    // ── 상호 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("교통수단 이름이 상호로 등록돼 있지 않다 — 지하철은 상호가 아니라 탈것이다")
    void 교통수단은_상호가_아니다() {
        Set<String> vehicles = Set.of("지하철", "시내버스", "마을버스", "광역버스", "고속버스", "택시", "기차");
        List<String> offenders = new ArrayList<>();
        for (var e : loader.brands().entrySet()) {
            for (BrandEntry b : e.getValue()) if (vehicles.contains(b.name())) offenders.add(b.name());
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("업종 일반명사가 브랜드로 등록돼 있지 않다 — '동물병원 역삼동점'이 찍히던 자리")
    void 일반명사는_브랜드가_아니다() {
        Set<String> generic = Set.of("동물병원", "휴게소", "캠핑장", "볼링장", "화방", "공방",
                "문화센터", "개인택시", "알뜰주유소", "스크린야구", "풋살파크", "클라이밍파크");
        List<String> offenders = new ArrayList<>();
        for (var e : loader.brands().entrySet()) {
            for (BrandEntry b : e.getValue()) if (generic.contains(b.name())) offenders.add(b.name());
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("맥락마다 상호를 얻을 수 있다 — 없으면 생성이 NPE로 죽는다")
    void 모든_맥락에_상호가_있다() {
        Random r = new Random(20260730);
        RegionEntry anchor = loader.regions().get(0);
        for (var c : loader.contexts()) {
            var m = sampler.resolveMerchant(c.category2(), anchor, null, r);
            assertThat(m.name()).as("맥락 %s 의 상호", c.category2()).isNotBlank();
        }
    }

    // ── 상호와 품목의 짝 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("지하철 요금을 버스 회사가 받지 않는다 — 실제로 1,600만 건이 그렇게 생성됐었다")
    void 상호와_품목의_짝이_맞는다() {
        Random r = new Random(20260730);
        Set<String> railOperators = new HashSet<>();
        for (BrandEntry b : loader.brands().get("대중교통")) {
            if (b.serves().contains("지하철")) {
                railOperators.add(b.name());
                railOperators.addAll(b.forms());
            }
        }
        assertThat(railOperators).as("도시철도 사업자").isNotEmpty();

        RegionEntry anchor = loader.regions().get(0);
        List<String> mismatched = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            var p = sampler.resolveProduct("대중교통", r);
            var m = sampler.resolveMerchant("대중교통", anchor, p.name(), r);
            boolean rail = p.name().startsWith("지하철");
            if (rail != railOperators.contains(m.name())) mismatched.add(m.name() + " · " + p.name());
        }
        assertThat(mismatched).isEmpty();
    }

    @Test
    @DisplayName("지역 교통공사는 담당 시도에서만 나온다 — 부산에서 서울교통공사가 찍히면 안 된다")
    void 지역사업자는_담당_시도에서만() {
        Map<String, List<String>> regionsOf = new HashMap<>();
        for (BrandEntry b : loader.brands().get("대중교통")) {
            if (b.isRegional()) regionsOf.put(b.name(), b.regions());
        }
        assertThat(regionsOf).as("담당 지역이 정해진 사업자").isNotEmpty();

        Random r = new Random(4921);
        List<String> offenders = new ArrayList<>();
        for (RegionEntry anchor : loader.regions()) {
            if (r.nextInt(40) != 0) continue;                 // 표본
            for (int i = 0; i < 20; i++) {
                var m = sampler.resolveMerchant("대중교통", anchor, "지하철 기본구간(~10km)", r);
                List<String> served = regionsOf.get(m.name());
                if (served != null && !served.contains(anchor.sido())) {
                    offenders.add(m.name() + " @ " + anchor.sido());
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    // ── 주소·사업자번호 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("본사·시설 브랜드는 전국 어디서 결제해도 주소·번호가 하나다")
    void 본사는_주소가_하나다() {
        Random r = new Random(1);
        List<RegionEntry> spots = List.of(loader.regions().get(0),
                loader.regions().get(loader.regions().size() / 3),
                loader.regions().get(loader.regions().size() - 1));
        for (var e : loader.brands().entrySet()) {
            for (BrandEntry b : e.getValue()) {
                if (b.hq() == null) continue;
                Set<String> addrs = new HashSet<>(), bizes = new HashSet<>();
                for (RegionEntry spot : spots) {
                    var m = registry.resolveFixed(b.name(), b.hq());
                    addrs.add(m.address());
                    bizes.add(String.valueOf(m.businessNumber()));
                }
                assertThat(addrs).as("%s 의 주소", b.name()).hasSize(1);
                assertThat(bizes).as("%s 의 사업자번호", b.name()).hasSize(1);
            }
        }
    }

    @Test
    @DisplayName("사업자등록번호는 10자리이거나 없다 — 하이픈이 섞이면 적재가 죽는다")
    void 사업자번호는_10자리다() {
        List<String> bad = new ArrayList<>();
        for (var e : loader.brands().entrySet()) {
            for (BrandEntry b : e.getValue()) {
                String bn = b.hq() == null ? null : b.hq().businessNumber();
                if (bn != null && !bn.matches("\\d{10}")) bad.add(b.name() + "=" + bn);
            }
        }
        assertThat(bad).isEmpty();
    }

    @Test
    @DisplayName("hq 가 필요한 브랜드는 전부 주소를 갖고 있다")
    void 고정주소가_필요한_브랜드는_주소가_있다() {
        Set<String> needsHq = Set.of("NONE", "ROUTE", "VENUE_CLUSTER");
        List<String> missing = new ArrayList<>();
        for (var e : loader.brands().entrySet()) {
            for (BrandEntry b : e.getValue()) {
                if (needsHq.contains(String.valueOf(b.locationType())) && b.hq() == null) {
                    missing.add(e.getKey() + "/" + b.name());
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("시군구 사업자 이름은 접미사 없이 붙는다 — 강남시설공단, 양평시설공단")
    void 시군구_사업자_이름() {
        Map<String, String> expect = Map.of(
                "강남구", "강남", "양평군", "양평", "강릉시", "강릉", "중구", "중구");
        for (var e : expect.entrySet()) {
            RegionEntry rg = loader.regions().stream()
                    .filter(x -> x.sigungu().equals(e.getKey())).findFirst().orElse(null);
            if (rg == null) continue;
            assertThat(CatalogSampler.districtName(rg)).as(e.getKey()).isEqualTo(e.getValue());
        }
    }

    // ── 금액 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("고시요금은 흔들지 않는다 — 지하철은 1,550원이지 1,085원이 아니다")
    void 고시요금은_그대로다() {
        var props2 = new GenerationProperties();
        var sim = new DailyActivitySimulator(sampler, new WasteLabeler(props2, sampler), loader, props2);
        var users = new PopulationBuilder(loader, props2).build(props2.getSeed(), 60);
        LocalDate end = users.get(0).startDate().plusDays(120);

        Map<String, int[]> range = new HashMap<>();
        for (var p : sampler.productsOf("대중교통")) range.put(p.name(), new int[]{p.priceLow(), p.priceHigh()});
        List<String> offenders = new ArrayList<>();
        for (var u : users) {
            for (GenTxn t : sim.simulate(u, end)) {
                if (!"대중교통".equals(t.category2())) continue;
                int[] rg = range.get(t.productName());
                if (rg == null) continue;
                // 카탈로그 값 **그대로**여야 한다. 반올림도 지터도 없다 —
                // 지하철 요금은 50원 단위(1,850원)라 '정리'하는 순간 틀린 요금이 된다.
                if (t.amount() < rg[0] || t.amount() > rg[1]) {
                    offenders.add(t.productName() + " = " + t.amount());
                }
            }
        }
        assertThat(offenders).as("카탈로그에 없는 교통요금").isEmpty();
    }

    @Test
    @DisplayName("고액 압축은 임계 아래를 건드리지 않고, 위에서는 단조 증가를 지킨다")
    void 고액_압축의_성질() {
        double t = props.getAddress().getCompressThreshold(), a = props.getAddress().getCompressAlpha();
        assertThat(DailyActivitySimulator.compressHigh(5_000, t, a)).isEqualTo(5_000);
        assertThat(DailyActivitySimulator.compressHigh(10_000, t, a)).isEqualTo(10_000);
        int prev = 10_000;
        for (int amt = 11_000; amt <= 2_000_000; amt += 7_000) {
            int c = DailyActivitySimulator.compressHigh(amt, t, a);
            assertThat(c).as("압축은 원금액을 넘지 않는다 (%d)", amt).isLessThanOrEqualTo(amt);
            assertThat(c).as("단조 증가 (%d)", amt).isGreaterThanOrEqualTo(prev);
            prev = c;
        }
    }

    // ── 동선 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("회사가 전원 서울이 아니다 — 부산 사람의 직장이 서울이던 결함")
    void 회사는_집_근처다() {
        var users = new PopulationBuilder(loader, props).build(props.getSeed(), 800);
        var commuters = users.stream().filter(u -> u.work() != null).toList();
        assertThat(commuters).isNotEmpty();

        // 수도권(서울·경기·인천) 거주자는 서울로 통근하는 것이 자연스러우므로 제외하고 본다.
        var nonCapital = commuters.stream()
                .filter(u -> !Set.of("서울특별시", "경기도", "인천광역시").contains(u.home().sido()))
                .toList();
        assertThat(nonCapital).as("비수도권 통근자 표본").isNotEmpty();

        long workingInSeoul = nonCapital.stream()
                .filter(u -> u.work().sido().equals("서울특별시")).count();
        long sameSido = nonCapital.stream()
                .filter(u -> u.work().sido().equals(u.home().sido())).count();

        assertThat(workingInSeoul).as("비수도권 거주인데 서울 직장 — 예전엔 전원이 그랬다").isZero();
        assertThat(sameSido).as("비수도권 통근자는 집과 같은 시도에서 일한다").isEqualTo(nonCapital.size());
    }

    @Test
    @DisplayName("교통 결제는 한 카드에서만 나간다 — 교통카드를 번갈아 쓰지 않는다")
    void 교통카드는_한_장이다() {
        var sim = new DailyActivitySimulator(sampler, new WasteLabeler(props, sampler), loader, props);
        var users = new PopulationBuilder(loader, props).build(props.getSeed(), 120).stream()
                .filter(u -> u.cardCount() > 1).toList();
        assertThat(users).isNotEmpty();

        for (var u : users) {
            LocalDate end = u.startDate().plusDays(120);
            Set<Integer> cards = new HashSet<>();
            for (GenTxn t : sim.simulate(u, end)) {
                if ("대중교통".equals(t.category2()) || "택시".equals(t.category2())) cards.add(t.cardSlot());
            }
            assertThat(cards).as("사용자 %s 의 교통카드", u.id()).hasSizeLessThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("통근자는 교통 또는 주유가 규칙적으로 찍힌다 — 예전엔 추첨의 부산물이었다")
    void 통근은_규칙적이다() {
        var sim = new DailyActivitySimulator(sampler, new WasteLabeler(props, sampler), loader, props);
        var users = new PopulationBuilder(loader, props).build(props.getSeed(), 200);
        int checked = 0;
        for (var u : users) {
            if (u.work() == null) continue;
            LocalDate end = u.startDate().plusDays(90);
            long mobility = sim.simulate(u, end).stream()
                    .filter(t -> Set.of("대중교통", "철도", "고속버스", "주유소", "통행료").contains(t.category2()))
                    .count();
            assertThat(mobility).as("통근자 %s 의 이동 결제", u.id()).isGreaterThan(5);
            if (++checked >= 30) break;
        }
        assertThat(checked).isPositive();
    }
}
