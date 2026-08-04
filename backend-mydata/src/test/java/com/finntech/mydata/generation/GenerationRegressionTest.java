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
    @DisplayName("같은 사업자번호는 같은 주소다 — 어기면 그 브랜드의 결제가 통째로 지워진다")
    void 같은_번호는_같은_주소다() {
        // 실제로 밟았다. 같은 법인이 여러 브랜드로 등록돼 있는데(티머니=티머니택시=온다택시,
        // 애플=애플앱스토어=애플티비플러스) 자료를 두 파일에서 따로 채우는 바람에 주소 표기가
        // 갈렸다. gen-mydata.sh 의 충돌 정리는 "한 사업자번호에 주소 2개"를 해시 충돌로 보고
        // **그 번호의 결제·통장거래를 전부 삭제**한다. 그래서 17개 브랜드가 데이터에서 사라졌다.
        Map<String, Set<String>> addrByBiz = new HashMap<>();
        Map<String, Set<String>> namesByBiz = new HashMap<>();
        for (var e : loader.brands().entrySet()) {
            for (BrandEntry b : e.getValue()) {
                var hq = b.hq();
                if (hq == null || hq.businessNumber() == null || hq.businessNumber().isBlank()) continue;
                addrByBiz.computeIfAbsent(hq.businessNumber(), k -> new HashSet<>()).add(hq.address());
                namesByBiz.computeIfAbsent(hq.businessNumber(), k -> new HashSet<>()).add(b.name());
            }
        }
        List<String> offenders = new ArrayList<>();
        addrByBiz.forEach((bn, addrs) -> {
            if (addrs.size() > 1) offenders.add(bn + " " + namesByBiz.get(bn) + " → " + addrs);
        });
        assertThat(offenders).isEmpty();
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

    // ── 택시 번호판 (2026-07-31 추가) ─────────────────────────────────────────

    @Test
    @DisplayName("택시 표시명 뒤에 차량 번호판이 붙는다 — 지역2 + 31~36 + 아바사자 + 네자리")
    void 택시_번호판_형식() {
        Random r = new Random(7);
        var regions = loader.regions();
        for (int i = 0; i < 300; i++) {
            RegionEntry anchor = regions.get(r.nextInt(regions.size()));
            var m = sampler.resolveMerchant("택시", anchor, r);
            assertThat(m.name())
                    .as("택시 표시명 (앵커 %s)", anchor.sido())
                    .matches(".+[가-힣]{2}3[1-6][아바사자]\\d{4}$");
            // 앞 두 글자는 결제한 곳의 시도다 — 서울에서 잡은 택시가 제주 번호판이면 어색하다.
            String plate = m.name().substring(m.name().length() - 9);
            assertThat(plate.substring(0, 2))
                    .isEqualTo(CatalogSampler.plateRegion(anchor.sido()));
        }
    }

    @Test
    @DisplayName("번호판이 달라도 결제하는 가맹점은 하나다 — 사업자번호·주소가 흔들리면 안 된다")
    void 택시_가맹점은_하나다() {
        Random r = new Random(11);
        RegionEntry anchor = loader.regions().get(0);
        Map<String, Set<String>> addrByBiz = new HashMap<>();
        Set<String> plates = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            var m = sampler.resolveMerchant("택시", anchor, r);
            addrByBiz.computeIfAbsent(m.businessNumber(), k -> new HashSet<>()).add(m.address());
            plates.add(m.name());
        }
        assertThat(plates.size()).as("번호판은 여러 개여야 한다").isGreaterThan(1);
        addrByBiz.forEach((biz, addrs) ->
                assertThat(addrs).as("사업자번호 %s 의 주소", biz).hasSize(1));
        assertThat(addrByBiz.size())
                .as("택시 브랜드 수만큼만 사업자가 있어야 한다 — 번호판마다 가맹점이 생기면 안 된다")
                .isLessThanOrEqualTo(8);
    }

    @Test
    @DisplayName("번호판 지역 표기 — 충청·전라·경상만 방위로 줄인다")
    void 번호판_지역표기() {
        assertThat(CatalogSampler.plateRegion("서울특별시")).isEqualTo("서울");
        assertThat(CatalogSampler.plateRegion("경기도")).isEqualTo("경기");
        assertThat(CatalogSampler.plateRegion("강원도")).isEqualTo("강원");
        assertThat(CatalogSampler.plateRegion("세종특별자치시")).isEqualTo("세종");
        assertThat(CatalogSampler.plateRegion("제주특별자치도")).isEqualTo("제주");
        assertThat(CatalogSampler.plateRegion("충청북도")).isEqualTo("충북");
        assertThat(CatalogSampler.plateRegion("충청남도")).isEqualTo("충남");
        assertThat(CatalogSampler.plateRegion("전라남도")).isEqualTo("전남");
        assertThat(CatalogSampler.plateRegion("경상북도")).isEqualTo("경북");
        assertThat(CatalogSampler.plateRegion("경상남도")).isEqualTo("경남");
    }

    // ── K-패스 환급 (2026-07-31 추가) ─────────────────────────────────────────

    @Test
    @DisplayName("한 달 대중교통비의 5만원 초과분이 다음 달 20일에 환급된다")
    void 케이패스_환급() {
        var transit = new java.util.TreeMap<java.time.YearMonth, Long>();
        transit.put(java.time.YearMonth.of(2026, 3), 30_000L);    // 문턱 아래 — 환급 없음
        transit.put(java.time.YearMonth.of(2026, 4), 50_000L);    // 딱 문턱 — 환급 없음
        transit.put(java.time.YearMonth.of(2026, 5), 88_000L);    // 38,000원 환급
        transit.put(java.time.YearMonth.of(2026, 6), 120_000L);   // 70,000원 환급

        var rows = AccountTxnGenerator.kpassRefunds(transit, LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 7, 31).atTime(23, 59));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).date().toLocalDate()).isEqualTo(LocalDate.of(2026, 6, 20));
        assertThat(rows.get(0).amount()).isEqualTo(38_000);
        assertThat(rows.get(0).description()).isEqualTo("5월K패스환급");
        assertThat(rows.get(0).type()).isEqualTo("DEPOSIT");
        assertThat(rows.get(1).date().toLocalDate()).isEqualTo(LocalDate.of(2026, 7, 20));
        assertThat(rows.get(1).amount()).isEqualTo(70_000);
        assertThat(rows.get(1).description()).isEqualTo("6월K패스환급");
    }

    // ── 보험 (2026-07-31 추가) ────────────────────────────────────────────────

    @Test
    @DisplayName("보험사는 자기가 파는 상품만 판다 — 삼성화재가 DB의 펫블리를 팔면 안 된다")
    void 보험사와_상품이_짝이다() {
        var brands = loader.brands().get("보험");
        assertThat(brands).as("보험 브랜드").isNotEmpty();
        Map<String, String> owner = new HashMap<>();
        for (BrandEntry b : brands) {
            assertThat(b.serves()).as("%s 의 취급 상품", b.name()).isNotEmpty();
            assertThat(b.hq()).as("%s 의 본사", b.name()).isNotNull();
            for (String s : b.serves()) {
                assertThat(owner.put(s, b.name()))
                        .as("상품 '%s' 를 두 보험사가 판다", s).isNull();
            }
        }
        // 명세서 표기는 forms 변형이 섞인다(삼성화재 → 삼성화재다이렉트). 사업자번호로 본다.
        Map<String, String> bizOf = new HashMap<>();
        for (BrandEntry b : brands) bizOf.put(b.name(), b.hq().businessNumber());
        Random r = new Random(3);
        for (int i = 0; i < 400; i++) {
            var product = sampler.resolveProduct("보험", r);
            var m = sampler.resolveMerchant("보험", null, product.name(), r);
            assertThat(m.businessNumber())
                    .as("상품 '%s' 를 파는 보험사 (표기: %s)", product.name(), m.name())
                    .isEqualTo(bizOf.get(owner.get(product.name())));
        }
    }

    // ── 계약(구독·통신비·공과금) — 2026-08-04 추가 ─────────────────────────────

    /**
     * 구독은 <b>계약</b>이다 — 매달 같은 서비스에서 같은 요금이 같은 날 빠진다.
     *
     * <p>예전에는 매달 {@code resolveMerchant}+{@code resolveProduct} 를 다시 불러
     * 한 사람이 스트리밍 26곳을 구독하고 금액이 7,000~29,000원으로 튀었다. 그 결과
     * 반복결제 탐지에서 <b>정기결제가 하나도 안 잡혔다</b>(운영 실측: 고정 15건이 전부 보험).
     */
    @Test
    @DisplayName("구독은 매달 같은 서비스·같은 요금·같은 날 — 표시명까지 고정")
    void 구독은_계약이다() {
        assertContract("스트리밍", true);
    }

    @Test
    @DisplayName("통신비는 통신사 하나·요금제 하나 — 한 사람이 7곳에 내지 않는다")
    void 통신비는_계약이다() {
        assertContract("통신비", true);
    }

    /** 공과금은 사업자·출금일만 고정이다. 전기요금이 매달 같을 수 없다. */
    @Test
    @DisplayName("공과금은 사업자·날짜만 고정이고 금액은 사용량따라 변한다")
    void 공과금은_금액이_변한다() {
        assertContract("공과금", false);
        var sim = new DailyActivitySimulator(sampler, new WasteLabeler(props, sampler), loader, props);
        var users = new PopulationBuilder(loader, props).build(props.getSeed(), 200);
        boolean varied = false;
        for (var u : users) {
            var amounts = sim.simulate(u, u.startDate().plusDays(240)).stream()
                    .filter(t -> "공과금".equals(t.category2())).map(GenTxn::amount).distinct().toList();
            if (amounts.size() > 1) { varied = true; break; }
        }
        assertThat(varied).as("공과금이 전원 정액이면 사용량 변동이 죽은 것이다").isTrue();
    }

    /**
     * 계약 맥락은 <b>계약으로만</b> 나간다 — 추첨으로 한 건도 새지 않는다.
     *
     * <p><b>{@link #assertContract} 로는 못 잡는다.</b> 그것은 요금제별로 묶어서 보는데,
     * 추첨으로 새어 든 결제는 저마다 다른 요금제라 "요금제 하나에 상호 하나"를 그대로 통과한다.
     * 실제로 통과한 채 1,090만 건이 생성됐다.
     *
     * <p>새는 자리는 {@code isContractIndustry} 필터가 빠진 곳이었다. 일상 추첨과 페르소나 믹스에는
     * 있었는데 <b>취미 주입</b>에는 없었고, {@code 영화관람}·{@code 디지털게임} 의 signature 에
     * 스트리밍이 들어 있어 구독과다형(두 취미를 다 가진다) <b>451명 전원</b>이 계약 위에 랜덤한
     * 날짜의 스트리밍 결제를 더 받았다 — 가맹점 15~26곳, 다른 페르소나는 0명(2026-08-04 실측).
     *
     * <p>그래서 여기서는 <b>날짜</b>를 본다. 한 사람의 구독은 출금일이 하나이므로(말일 보정 제외),
     * 다른 날짜가 섞이면 그건 계약이 아닌 경로로 들어온 것이다.
     */
    @Test
    @DisplayName("계약 맥락은 추첨으로 새지 않는다 — 취미 signature 로 들어오던 구멍")
    void 계약은_추첨으로_새지_않는다() {
        var sim = new DailyActivitySimulator(sampler, new WasteLabeler(props, sampler), loader, props);
        var users = new PopulationBuilder(loader, props).build(props.getSeed(), 300);
        int checked = 0;
        for (var u : users) {
            var txns = sim.simulate(u, u.startDate().plusDays(240)).stream()
                    .filter(t -> "스트리밍".equals(t.category2())).toList();
            if (txns.size() < 4) continue;

            // 한 사람의 구독은 전부 같은 날 빠진다. 말일 보정(2월 28일 등)만 예외라 28 이상은 허용한다.
            Set<Integer> days = new HashSet<>();
            for (var t : txns) days.add(t.date().getDayOfMonth());
            assertThat(days).as("%s 의 구독 출금일 — 여러 날이면 추첨이 끼어든 것이다",
                            u.userSeed())
                    .allMatch(dd -> dd >= 28 || days.size() == 1);

            // 페르소나가 정한 구독 개수를 넘을 수 없다(최대 10). 넘으면 추첨분이 얹힌 것이다.
            long merchants = txns.stream().map(GenTxn::merchant).distinct().count();
            assertThat(merchants).as("%s 의 구독 서비스 수 — 페르소나 상한(10)을 넘었다", u.userSeed())
                    .isLessThanOrEqualTo(10);
            if (++checked >= 40) break;
        }
        assertThat(checked).as("구독을 가진 사용자가 하나도 없다").isPositive();
    }

    /**
     * 계약 불변식 — 사용자마다 상호·표시명·출금일이 하나이고, 요금제도 하나다.
     *
     * @param amountFixed 금액까지 고정인가(구독·통신비 true, 공과금 false)
     */
    private void assertContract(String category2, boolean amountFixed) {
        var sim = new DailyActivitySimulator(sampler, new WasteLabeler(props, sampler), loader, props);
        var users = new PopulationBuilder(loader, props).build(props.getSeed(), 200);
        int checked = 0;
        for (var u : users) {
            var txns = sim.simulate(u, u.startDate().plusDays(240)).stream()
                    .filter(t -> category2.equals(t.category2())).toList();
            if (txns.size() < 2) continue;

            // 상품(요금제)마다 상호·표시명이 하나여야 한다 — 표기 변형이 결제마다 바뀌면
            // 사업자번호 없는 해외 가맹점이 반복결제에서 여러 그룹으로 쪼개진다.
            Map<String, Set<String>> merchByProduct = new HashMap<>();
            Map<String, Set<Integer>> amtByProduct = new HashMap<>();
            for (var t : txns) {
                merchByProduct.computeIfAbsent(t.productName(), k -> new HashSet<>()).add(t.merchant());
                amtByProduct.computeIfAbsent(t.productName(), k -> new HashSet<>()).add(t.amount());
            }
            merchByProduct.forEach((p, m) ->
                    assertThat(m).as("%s / '%s' 의 표시 상호", category2, p).hasSize(1));
            if (amountFixed) {
                amtByProduct.forEach((p, a) ->
                        assertThat(a).as("%s / '%s' 의 요금", category2, p).hasSize(1));
            }

            // 상품(계약) 하나당 출금일이 하나 — 말일 보정으로 28/29/30/31 이 섞이는 것은 허용한다.
            Map<String, Set<Integer>> dayByProduct = new HashMap<>();
            for (var t : txns) {
                dayByProduct.computeIfAbsent(t.productName(), k -> new HashSet<>())
                        .add(t.date().getDayOfMonth());
            }
            dayByProduct.forEach((p, days) -> assertThat(days)
                    .as("%s / '%s' 출금일(말일 보정만 허용)", category2, p)
                    .allMatch(dd -> dd >= 28 || days.size() == 1));
            if (++checked >= 25) break;
        }
        assertThat(checked).as("%s 계약을 가진 사용자가 하나도 없다", category2).isPositive();
    }

    @Test
    @DisplayName("보험료는 매달 같은 날 같은 금액이다 — 계약은 달마다 바뀌지 않는다")
    void 보험료는_고정이다() {
        var sim = new DailyActivitySimulator(sampler, new WasteLabeler(props, sampler), loader, props);
        var users = new PopulationBuilder(loader, props).build(props.getSeed(), 120);
        int checked = 0;
        for (var u : users) {
            var ins = sim.simulate(u, u.startDate().plusDays(120)).stream()
                    .filter(t -> "보험".equals(t.category2()))
                    // 여행보험은 계약이 아니라 여행 건당이라 고정일 규칙 밖이다.
                    .filter(t -> !DailyActivitySimulator.isTravelInsurance(t.productName())).toList();
            if (ins.isEmpty()) continue;
            // 날짜는 매달 같은 '일'
            assertThat(ins.stream().map(t -> t.date().getDayOfMonth()).distinct())
                    .as("%s 의 보험료 출금일", u.id()).hasSize(1);
            // 같은 상품이면 상호·금액이 늘 같다
            Map<String, Set<Integer>> amtByProduct = new HashMap<>();
            Map<String, Set<String>> merchByProduct = new HashMap<>();
            for (var t : ins) {
                amtByProduct.computeIfAbsent(t.productName(), k -> new HashSet<>()).add(t.amount());
                merchByProduct.computeIfAbsent(t.productName(), k -> new HashSet<>()).add(t.merchant());
            }
            amtByProduct.forEach((p, a) -> assertThat(a).as("'%s' 보험료", p).hasSize(1));
            merchByProduct.forEach((p, m) -> assertThat(m).as("'%s' 보험사", p).hasSize(1));
            if (++checked >= 25) break;
        }
        assertThat(checked).as("보험을 든 사용자가 하나도 없다").isPositive();
    }

    @Test
    @DisplayName("차가 없으면 자동차·운전자보험이 안 나가고, 차가 있으면 운전자보험이 반드시 있다")
    void 차량과_보험이_맞는다() {
        var sim = new DailyActivitySimulator(sampler, new WasteLabeler(props, sampler), loader, props);
        var users = new PopulationBuilder(loader, props).build(props.getSeed(), 150);
        int withCar = 0, without = 0;
        for (var u : users) {
            var products = sim.simulate(u, u.startDate().plusDays(70)).stream()
                    .filter(t -> "보험".equals(t.category2()))
                    .map(t -> t.productName()).collect(java.util.stream.Collectors.toSet());
            if (products.isEmpty()) continue;
            // 펫보험은 반려동물이 있는 사람만 든다.
            if (!u.hasPet()) {
                assertThat(products).as("반려동물 없는 %s 의 보험", u.id())
                        .noneMatch(DailyActivitySimulator::isPetInsurance);
            }
            if (u.hasVehicle()) {
                assertThat(products).as("차 있는 %s 의 보험", u.id())
                        .anyMatch(n -> n.contains("운전자"));
                withCar++;
            } else {
                assertThat(products).as("차 없는 %s 의 보험", u.id())
                        .noneMatch(DailyActivitySimulator::isVehicleInsurance);
                without++;
            }
        }
        assertThat(withCar).as("차 있는 표본").isPositive();
        assertThat(without).as("차 없는 표본").isPositive();
    }

    @Test
    @DisplayName("보험료를 전력·가스회사가 받지 않는다 — 공과금 맥락에 얹혀 있던 자리")
    void 보험료는_보험사가_받는다() {
        var utilities = loader.brands().get("공과금").stream().map(BrandEntry::name).toList();
        Random r = new Random(5);
        for (int i = 0; i < 200; i++) {
            var product = sampler.resolveProduct("보험", r);
            var m = sampler.resolveMerchant("보험", null, product.name(), r);
            assertThat(utilities).as("'%s' 를 받은 상호", product.name()).doesNotContain(m.name());
        }
        // 그리고 공과금 품목에 보험료가 남아 있으면 안 된다.
        assertThat(loader.products().get("공과금")).extracting(p -> p.name())
                .as("공과금 품목").noneMatch(n -> n.contains("보험"));
    }

    @Test
    @DisplayName("관측 창 밖의 환급은 만들지 않는다 — 아직 못 받은 돈이다")
    void 케이패스_창밖은_없다() {
        var transit = new java.util.TreeMap<java.time.YearMonth, Long>();
        transit.put(java.time.YearMonth.of(2026, 7), 200_000L);   // 입금일은 8/20 → 창 밖
        var rows = AccountTxnGenerator.kpassRefunds(transit, LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 7, 31).atTime(23, 59));
        assertThat(rows).isEmpty();
    }
}
