package com.finntech.mydata.generation;

import java.util.List;
import java.util.Map;

/** 카탈로그 리소스({@code generation/catalog/*.json})의 타입. 데이터일 뿐 판단 로직 아님(원칙4). */
public final class CatalogModels {
    private CatalogModels() {}

    /**
     * 소비맥락(contexts.json) 1건 = 거래를 현실적으로 만들기 위한 '무대'.
     *
     * <p><b>{@code ksicCode}가 소비 카테고리를 대신한다.</b> 예전에는 여기에 {@code category1}
     * (7대분류)이 있어 그 값이 그대로 앱의 소비 카테고리가 됐다. 그러다 보니 한 축이
     * "가맹점 업종"과 "사용자 소비 종류"를 겸해, 지하철이 '온라인' 대분류에 들어가는 왜곡이 났다.
     * 이제 마이데이터는 <b>업종코드만</b> 넘기고, 소비 카테고리는 앱이 결정론 1:1 표로 붙인다.
     *
     * @param category2         맥락 이름. 상품·가맹점 풀을 고르는 키일 뿐, 앱에는 나가지 않는다
     * @param ksicCode          KSIC 세분류 4자리. 이 맥락의 결제가 어느 업종에서 일어나는가
     * @param frequencyWeight   하루활동 방문확률 base(0..1)
     * @param discretionaryBase <b>재량성</b> base(0..1) = "생존필수인가?" 척도(낮음=필수, 높음=재량). <b>낭비성향이
     *                          아니다</b> — 무대(필수/재량) 판정·금액 분포에만 쓰고, 낭비확률에 직접 넣지 않는다(재량≠낭비).
     * @param merchantSource    INDEPENDENT|BRAND|MIXED|ONLINE|OPERATOR
     * @param locationType      POI|ROUTE|VENUE_CLUSTER|NONE
     * @param fixedTariff       요금이 고시로 정해진 맥락(운임·요금제·고지서). true면 금액에 지터를
     *                          걸지 않는다 — 지하철은 1,550원이지 1,400원이나 1,700원이 아니다.
     */
    public record CatalogContext(
            String category2, String ksicCode, String channel, String locationType,
            double frequencyWeight, double discretionaryBase, String merchantSource,
            boolean fixedTariff) {}

    /** contexts.json 최상위 래퍼. */
    public record ContextsFile(List<CatalogContext> contexts) {}

    /**
     * 상품(products.json) 1건: 실 품목명·가격범위·<b>재량성</b>. amount=총액≈품목가×수량+노이즈.
     * discretionary = 재량성(생존필수 아님) 척도이며 <b>낭비성향이 아니다</b>. 취미 상품의 높은 재량성은
     * 낭비로 직결되지 않는다(라벨러가 충동·과다 기반으로 판정, 본인 취미는 보호).
     */
    public record ProductEntry(String name, int priceLow, int priceHigh, double discretionary,
                               double weight) {

        /** 가중치 없는 4원소 표기(대다수 품목) — 균등 추출. */
        public ProductEntry(String name, int priceLow, int priceHigh, double discretionary) {
            this(name, priceLow, priceHigh, discretionary, 1.0);
        }
    }

    /**
     * 브랜드/플랫폼(merchants_brand.json) 1건.
     * @param branchable true면 생성기가 {@code name+동+"점"} 합성(가끔 forms 변형으로 명세서 노이즈)
     * @param forms       실 카드명세서 표기 변형(법인명·오타·영문 등) — 없으면 name 사용
     */
    /**
     * 본사(또는 단일 시설) 소재지 — 실제 주소다.
     *
     * <p>해외 본사는 국내 지번이 없어 {@code lat}·{@code lon}이 {@code null}이다(스팀·아마존·아고다 등).
     * 좌표를 쓰는 화면이 없으므로 무해하다 — 앱은 주소 문자열만 보여 준다.
     *
     * @param businessNumber 실제 사업자등록번호. 주면 생성 번호 대신 이것을 쓴다.
     */
    public record HqEntry(String address, Double lat, Double lon, String businessNumber) {}

    /**
     * 브랜드/사업자 1건.
     *
     * @param serves 이 사업자가 파는 품목의 이름 접두사. 비어 있으면 그 맥락의 아무 품목이나 판다.
     *               <b>왜 필요한가.</b> 예전에는 상호와 품목을 따로 뽑아서 `지하철`이 `시내버스`
     *               요금(1,500원)을 받고 `광역버스 장거리`(3,167원)까지 받았다. 한 맥락에 서로 다른
     *               운영주체가 섞여 있으면(도시철도·버스·충전사업자) 짝을 맞춰야 명세서가 말이 된다.
     *
     * @param channel      이 사업자의 결제 채널. <b>맥락보다 우선한다.</b> 예전에는 이 필드가 정의만
     *                     되고 한 번도 읽히지 않아, 애플·스팀·예스24가 '디지털가전'(OFFLINE) 맥락에
     *                     묶여 동네 지번주소를 받았다. 비어 있으면 맥락의 값을 쓴다.
     * @param locationType <b>주소를 어떻게 정하는가</b> — 결제 채널과는 다른 축이다. 이 둘을 하나로
     *                     묶었더니 "온라인 결제인데 실물 시설"(공연장)과 "오프라인 결제인데 지점이
     *                     없음"(교통 사업자)을 표현할 수 없었다. 비어 있으면 맥락의 값을 쓴다.
     *                     <ul>
     *                       <li>{@code POI} — 앵커 동의 지번(지점이 있는 브랜드. 기존 동작)
     *                       <li>{@code NONE}·{@code ROUTE} — {@code hq} 고정 주소 하나
     *                       <li>{@code VENUE_CLUSTER} — 그 시설의 실주소({@code hq})
     *                       <li>{@code DISTRICT} — <b>시군구마다 다른 사업자</b>. 이름은 시군구에서
     *                           합성하고 주소는 그 시군구 안에서 결정론으로 뽑는다(ㅇㅇ시설공단)
     *                     </ul>
     * @param regions      담당 광역시도. 비어 있으면 전국. 앵커 시도와 겹치는 사업자만 후보가 된다 —
     *                     부산에서 지하철을 타고 서울교통공사가 찍히면 안 된다.
     * @param hq           본사·시설의 실주소. {@code locationType}이 {@code POI}·{@code DISTRICT}가
     *                     아니면 있어야 한다({@code CatalogConsistencyTest}가 검사).
     */
    public record BrandEntry(String name, boolean branchable, String channel,
                             List<String> forms, List<String> serves,
                             String locationType, List<String> regions, HqEntry hq) {
        public BrandEntry {
            forms = forms == null ? List.of() : forms;
            serves = serves == null ? List.of() : serves;
            regions = regions == null ? List.of() : regions;
        }

        /** 예전 5원소 표기(주소·지역이 없던 시절) — 테스트와 옛 카탈로그용. */
        public BrandEntry(String name, boolean branchable, String channel,
                          List<String> forms, List<String> serves) {
            this(name, branchable, channel, forms, serves, null, List.of(), null);
        }

        /** 이 사업자가 그 품목을 파는가. serves 가 비어 있으면 가리지 않는다. */
        public boolean canSell(String productName) {
            if (serves.isEmpty() || productName == null) return true;
            for (String pre : serves) if (productName.startsWith(pre)) return true;
            return false;
        }

        /** 이 사업자가 그 시도를 담당하는가. regions 가 비어 있으면 전국이라 가리지 않는다. */
        public boolean servesRegion(String sido) {
            return regions.isEmpty() || sido == null || regions.contains(sido);
        }

        /** 담당 지역이 정해진 사업자인가 — 전국 사업자와 섞을 때 비율을 나누는 기준. */
        public boolean isRegional() { return !regions.isEmpty(); }
    }

    /**
     * 전국 행정동(regions.json) 1건 — 실 중심좌표(WGS84) + 사용자 분포 가중.
     * 생성기: weight로 거주 동 추출 → (lat,lon) 앵커+지터로 동선/POI 배치, 프랜차이즈는 dong으로 {동}점 합성.
     * @param weight 시도 실인구 기반 사용자 분포 가중(전국 합=1)
     */
    public record RegionEntry(String sido, String sigungu, String dong, double lat, double lon, double weight) {}

    /** regions.json 최상위 래퍼. */
    public record RegionsFile(List<RegionEntry> regions) {}

    /**
     * 취미 성향(hobbies.json) 1건 — 그 취미가 '명백히' 드러나는 category2 집합.
     * 생성기: 사용자에 1~3개 취미 배정 → signatureCategories에서 가끔 지출 주입 → 성향이 식별됨.
     */
    public record HobbyType(String type, List<String> signatureCategories) {}

    /** hobbies.json 최상위 래퍼. */
    public record HobbiesFile(List<HobbyType> hobbies) {}

    /**
     * 기본 페르소나(personas.json) 1종 — Stage B 확정. 생성기가 variantsPerBase개 변형으로 확장.
     * enum 필드(nightImpulse·initialWasteLevel·improvementSpeed 등)는 생성기가 분포로 매핑.
     * @param categoryMix 7대분류 비중(합 100). 세부 category2는 생성기가 방문빈도로 자동배분.
     * @param impulsivity 라벨 모델 impulse에 곱하는 페르소나 충동성 배수.
     * @param traits      하루활동 모델이 존중할 행동 특성(자유서술).
     */
    public record PersonaProfile(
            String name, double populationShare, long monthlyTotalMean, double monthlyCV,
            Map<String, Double> categoryMix, int txPerMonthMean, String ticketTendency, double onlineRatio,
            int[] activeHours, String dayBias, double plannedRatio, double impulsivity, String nightImpulse,
            String deliveryOveruse, int[] subscriptionCount, String subscriptionLeak, List<String> hobbies,
            String hobbyIntensity, String initialWasteLevel, String improvementSpeed, double noImprovementPct,
            int[] cards, String hasVehicle, PersonaRegion region, List<String> traits) {}

    /** 페르소나 거주·이동 성향. mode=POP_WEIGHTED|METRO|CAPITAL_SUBURB|ALL. */
    public record PersonaRegion(String mode, String workCity, Boolean commute, Boolean wideMovement) {}

    /** personas.json 최상위 래퍼. */
    public record PersonasFile(List<PersonaProfile> personas) {}
}
