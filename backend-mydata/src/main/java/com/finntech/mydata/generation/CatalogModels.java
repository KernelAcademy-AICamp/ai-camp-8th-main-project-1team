package com.finntech.mydata.generation;

import java.util.List;
import java.util.Map;

/** 카탈로그 리소스({@code generation/catalog/*.json})의 타입. 데이터일 뿐 판단 로직 아님(원칙4). */
public final class CatalogModels {
    private CatalogModels() {}

    /**
     * 소비맥락(contexts.json) 1건 = category2 → 7대분류 매핑 + 빈도·재량성 가중치.
     * @param frequencyWeight   하루활동 방문확률 base(0..1)
     * @param discretionaryBase <b>재량성</b> base(0..1) = "생존필수인가?" 척도(낮음=필수, 높음=재량). <b>낭비성향이
     *                          아니다</b> — 무대(필수/재량) 판정·금액 분포에만 쓰고, 낭비확률에 직접 넣지 않는다(재량≠낭비).
     * @param merchantSource    INDEPENDENT|BRAND|MIXED|ONLINE|OPERATOR
     * @param locationType      POI|ROUTE|VENUE_CLUSTER|NONE
     */
    public record CatalogContext(
            String category2, String category1, String channel, String locationType,
            double frequencyWeight, double discretionaryBase, String merchantSource) {}

    /** contexts.json 최상위 래퍼. */
    public record ContextsFile(List<CatalogContext> contexts) {}

    /**
     * 상품(products.json) 1건: 실 품목명·가격범위·<b>재량성</b>. amount=총액≈품목가×수량+노이즈.
     * discretionary = 재량성(생존필수 아님) 척도이며 <b>낭비성향이 아니다</b>. 취미 상품의 높은 재량성은
     * 낭비로 직결되지 않는다(라벨러가 충동·과다 기반으로 판정, 본인 취미는 보호).
     */
    public record ProductEntry(String name, int priceLow, int priceHigh, double discretionary) {}

    /**
     * 브랜드/플랫폼(merchants_brand.json) 1건.
     * @param branchable true면 생성기가 {@code name+동+"점"} 합성(가끔 forms 변형으로 명세서 노이즈)
     * @param forms       실 카드명세서 표기 변형(법인명·오타·영문 등) — 없으면 name 사용
     */
    public record BrandEntry(String name, boolean branchable, String channel, List<String> forms) {}

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
