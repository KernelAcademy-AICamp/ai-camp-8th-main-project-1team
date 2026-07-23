package com.finntech.mydata.generation;

import java.util.Random;

/** 페르소나 enum(LOW/MID/HIGH·MID_SLOW 등) → 수치 매핑. personas.json의 정성값을 분포로 변환. */
public final class PersonaResolver {
    private PersonaResolver() {}

    /** 충동성 요인 배수(nightImpulse·deliveryOveruse·subscriptionLeak). */
    public static double impulseMult(String level) {
        return switch (level == null ? "MID" : level) {
            case "LOW" -> 0.5;
            case "LOW_MID" -> 0.75;
            case "MID_HIGH" -> 1.3;
            case "HIGH" -> 1.6;
            default -> 1.0; // MID
        };
    }

    /** 초기 낭비 강도 배수(initialWasteLevel) — 곡선 시작 진폭. */
    public static double wasteLevel(String level) {
        return switch (level == null ? "MID" : level) {
            case "LOW" -> 0.6;
            case "LOW_MID" -> 0.8;
            case "MID_HIGH" -> 1.2;
            case "HIGH" -> 1.4;
            default -> 1.0; // MID
        };
    }

    /** 개선 속도 배수(improvementSpeed) — 하강 곡선 기울기. RANDOM은 rng로 표본. */
    public static double declineSpeed(String speed, Random r) {
        return switch (speed == null ? "MID" : speed) {
            case "FAST" -> 1.30;
            case "MID_FAST" -> 1.10;
            case "MID_SLOW" -> 0.70;
            case "SLOW" -> 0.50;
            case "RANDOM" -> GenSeed.uniform(r, 0.5, 1.3);
            default -> 0.90; // MID
        };
    }

    /** 취미 지출 강도 배수(hobbyIntensity) — 월 취미 지출 건수 스케일. */
    public static double hobbyIntensity(String level) {
        return switch (level == null ? "MID" : level) {
            case "LOW" -> 0.6;
            case "HIGH" -> 1.5;
            default -> 1.0; // MID
        };
    }

    /** 차량 보유 여부(hasVehicle: YES/NO/MIXED) → 사용자별 boolean. */
    public static boolean hasVehicle(String mode, Random r) {
        return switch (mode == null ? "MIXED" : mode) {
            case "YES" -> true;
            case "NO" -> false;
            default -> r.nextBoolean(); // MIXED
        };
    }
}
