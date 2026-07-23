package com.finntech.mydata.generation;

import java.util.List;
import java.util.Map;

/**
 * 페르소나 변형 프로파일 — 기본 페르소나(personas.json)를 파라미터 섭동해 만든 구체 인스턴스(수십 개/기본형).
 * enum은 수치 배수로 해석 완료. categoryMix는 합=1 분수. 여러 사용자가 한 변형을 공유(시작일·지역·일별 노이즈만 다름).
 */
public record PersonaVariant(
        String baseName, int variantIndex,
        long monthlyTotalMean, double monthlyCV,
        Map<String, Double> categoryMix,          // 7대분류 → 분수(합 1)
        int txPerMonthMean, String ticketTendency,
        double onlineRatio, int[] activeHours, String dayBias, double plannedRatio,
        double impulsivity,                        // 라벨 impulse 배수(페르소나 충동성)
        double nightImpulseMult, double deliveryOveruseMult, double subscriptionLeakMult,
        int[] subscriptionCount,
        List<String> hobbies, double hobbyIntensityMult,
        double initialWasteMult, double declineSpeedMult, double noImprovementProb,
        int[] cards, String hasVehicleMode,
        String regionMode, String workCity, boolean commute, boolean wideMovement) {
}
