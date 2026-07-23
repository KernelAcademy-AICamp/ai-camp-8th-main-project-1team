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
 */
public record GeneratedUser(
        String id, PersonaVariant variant, LocalDate startDate,
        RegionEntry home, RegionEntry work, boolean hasVehicle, int cardCount,
        String dataSplit, long userSeed) {
}
