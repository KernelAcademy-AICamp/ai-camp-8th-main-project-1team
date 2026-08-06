package com.finntech.mydata.generation;

import com.finntech.mydata.generation.CatalogModels.RegionEntry;

import java.time.LocalDate;

/**
 * 생성된 합성 사용자 1명 — 페르소나 변형 + 시작일(가입) + 거주/직장 지역 + 파생 속성.
 * 거래는 이 사용자 컨텍스트에서 하루활동 시뮬레이터가 만든다(userSeed로 결정론).
 *
 * @param home     거주 행정동(실좌표 앵커)
 * @param work     통근 시 직장 행정동(없으면 null)
 * @param dataSplit TRAIN|VAL|TEST|SERVICE (사용자 단위 disjoint, 요구11)
 * @param userSeed 이 사용자 하위 생성(일별·거래별)의 결정론 시드
 * @param transitCard 교통 결제에만 쓰는 카드의 인덱스.
 *        <b>사람은 교통카드를 한 장으로 쓴다.</b> 예전에는 모든 결제가 {@code nextInt(cardCount)}로
 *        카드를 골라, 같은 사람의 지하철 요금이 날마다 다른 카드에서 빠졌다. 실제 명세서는 그렇지 않다.
 */
public record GeneratedUser(
        String id, PersonaVariant variant, LocalDate startDate,
        RegionEntry home, RegionEntry work, boolean hasVehicle, int cardCount,
        String dataSplit, long userSeed, int transitCard,
        String name, String social7, String phone) {

    /**
     * 신원 없이 만들던 시절의 표기 — 시험·옛 호출부용.
     *
     * <p><b>이 생성자로 만든 사용자는 로그인할 수 없다.</b> 본인인증은 이름·주민번호·전화번호로
     * CI 를 계산하는데({@code Ci.of}) 여기서는 그 셋이 비어 있어 어떤 입력으로도 닿지 않는다.
     * 실제 생성 경로({@code PopulationBuilder})는 신원을 채우는 쪽을 쓴다.
     */
    public GeneratedUser(String id, PersonaVariant variant, LocalDate startDate,
                         RegionEntry home, RegionEntry work, boolean hasVehicle, int cardCount,
                         String dataSplit, long userSeed, int transitCard) {
        this(id, variant, startDate, home, work, hasVehicle, cardCount, dataSplit, userSeed,
                transitCard, null, null, null);
    }

    /** 교통카드를 따로 정하지 않던 시절의 표기 — 첫 카드를 교통카드로 본다. */
    public GeneratedUser(String id, PersonaVariant variant, LocalDate startDate,
                         RegionEntry home, RegionEntry work, boolean hasVehicle, int cardCount,
                         String dataSplit, long userSeed) {
        this(id, variant, startDate, home, work, hasVehicle, cardCount, dataSplit, userSeed, 0);
    }

    /** 주민등록번호 13자리 — 뒤 6자리는 시드에서 채운다(실제로 쓰이지 않는 자리다). */
    public String fullSocial() {
        if (social7 == null) return null;
        java.util.Random r = GenSeed.rng(userSeed, 77);
        StringBuilder sb = new StringBuilder(social7);
        for (int i = 0; i < 6; i++) sb.append(r.nextInt(10));
        return sb.toString();
    }

    /**
     * 반려동물을 기르는가 — <b>펫보험은 이 사람만 든다</b>(사용자 결정 2026-07-31).
     *
     * <p>페르소나에는 없는 속성이라 사용자 시드에서 결정론으로 유도한다. 2022 농림부 조사 기준
     * 반려동물 양육 가구가 약 25%다. 시드에서 뽑으므로 몇 번을 돌려도 같은 사람이 같은 답을 준다.
     */
    public boolean hasPet() {
        return Math.floorMod(Long.hashCode(userSeed ^ 0x9E3779B97F4A7C15L), 100) < 25;
    }
}
