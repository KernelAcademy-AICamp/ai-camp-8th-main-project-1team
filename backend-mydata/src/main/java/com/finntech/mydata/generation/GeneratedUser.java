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
        String dataSplit, long userSeed, int transitCard) {

    /** 교통카드를 따로 정하지 않던 시절의 표기 — 첫 카드를 교통카드로 본다. */
    public GeneratedUser(String id, PersonaVariant variant, LocalDate startDate,
                         RegionEntry home, RegionEntry work, boolean hasVehicle, int cardCount,
                         String dataSplit, long userSeed) {
        this(id, variant, startDate, home, work, hasVehicle, cardCount, dataSplit, userSeed, 0);
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
