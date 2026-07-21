package com.finntech.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 게임화 저축 루프 회계·쿠폰 검증 (문서 §5-5). 레포 없이 순수 정적 메서드를 직접 호출해
 * 저축 마이너스 불가·초과 강제차감 계산·적금 이자·쿠폰 임계치를 못박는다.
 */
class PointServiceTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    @Test
    void save_cannotExceedRemainingBudget() {
        BigDecimal remaining = new BigDecimal("100000");
        assertDoesNotThrow(() -> PointService.validateSave(new BigDecimal("100000"), remaining));
        assertThrows(IllegalArgumentException.class,
                () -> PointService.validateSave(new BigDecimal("100001"), remaining));  // 예산 초과 저축 불가
        assertThrows(IllegalArgumentException.class,
                () -> PointService.validateSave(ZERO, remaining));                       // 0 이하 불가
    }

    @Test
    void incrementalOverspend_onlyCountsThePartOverBudget() {
        BigDecimal budget = new BigDecimal("3000000");
        assertEquals(0, ZERO.compareTo(
                PointService.incrementalOverspend(new BigDecimal("2500000"), budget, new BigDecimal("500000"))));
        // 이번 소비로 30만 초과: min(이번 50만, 초과 30만) = 30만
        assertEquals(0, new BigDecimal("300000").compareTo(
                PointService.incrementalOverspend(new BigDecimal("3300000"), budget, new BigDecimal("500000"))));
        // 이미 초과 상태에서 20만 더: 이번 소비 전액이 초과분 = 20만
        assertEquals(0, new BigDecimal("200000").compareTo(
                PointService.incrementalOverspend(new BigDecimal("3600000"), budget, new BigDecimal("200000"))));
    }

    @Test
    void lumpFutureValue_zeroRateKeepsPrincipal_positiveRateGrows() {
        assertEquals(0, new BigDecimal("1000000")
                .compareTo(PointService.lumpFutureValue(new BigDecimal("1000000"), 0.0, 12)));
        assertTrue(PointService.lumpFutureValue(new BigDecimal("1000000"), 4.0, 12)
                .compareTo(new BigDecimal("1000000")) > 0);
    }

    @Test
    void acquiredCount_fillsMilestonesInOrder() {
        List<BigDecimal> costs = List.of(new BigDecimal("800000"), new BigDecimal("700000"), new BigDecimal("500000"));
        assertEquals(0, PointService.acquiredCount(new BigDecimal("500000"), costs));   // 1단계(80만) 미달
        assertEquals(1, PointService.acquiredCount(new BigDecimal("800000"), costs));   // 1단계 딱
        assertEquals(1, PointService.acquiredCount(new BigDecimal("1400000"), costs));  // 2단계 누적(150만) 미달
        assertEquals(2, PointService.acquiredCount(new BigDecimal("1500000"), costs));  // 2단계 누적 딱
        assertEquals(3, PointService.acquiredCount(new BigDecimal("2000000"), costs));  // 전부 획득
    }

    @Test
    void trailingUnnecessaryStreak_countsTailingUnplanned() {
        // 시간순 계획(true)/미계획(false) — 끝에서부터 이어지는 미계획 횟수
        assertEquals(3, PointService.trailingUnnecessaryStreak(List.of(true, true, false, false, false)));
        assertEquals(0, PointService.trailingUnnecessaryStreak(List.of(false, false, true)));  // 마지막이 계획소비
        assertEquals(2, PointService.trailingUnnecessaryStreak(List.of(false, false)));
        assertEquals(0, PointService.trailingUnnecessaryStreak(List.of()));
    }

    @Test
    void monthsToGoal_ceilingsTargetOverMonthlySaving() {
        // 180만 목표를 월 15만 절약 → ⌈12⌉ = 12개월
        assertEquals(12, PointService.monthsToGoal(new BigDecimal("1800000"), new BigDecimal("150000")));
        // 200만 목표를 월 15만 → ⌈13.3⌉ = 14개월(올림)
        assertEquals(14, PointService.monthsToGoal(new BigDecimal("2000000"), new BigDecimal("150000")));
        // 절약이 0이면 개월수 0(계획 없음)
        assertEquals(0, PointService.monthsToGoal(new BigDecimal("1000000"), ZERO));
    }

    @Test
    void cutsMonthlySaving_sumsSelectedCategories() {
        Map<String, BigDecimal> monthly = Map.of(
                "CAFE", new BigDecimal("60000"),
                "DELIVERY", new BigDecimal("90000"),
                "SHOPPING", new BigDecimal("120000"));
        // 카페+배달을 줄이면 월 15만
        assertEquals(0, new BigDecimal("150000").compareTo(
                PointService.cutsMonthlySaving(List.of("CAFE", "DELIVERY"), monthly)));
        // 데이터에 없는 코드는 0으로 무시
        assertEquals(0, new BigDecimal("60000").compareTo(
                PointService.cutsMonthlySaving(List.of("CAFE", "UNKNOWN"), monthly)));
        assertEquals(0, ZERO.compareTo(PointService.cutsMonthlySaving(List.of(), monthly)));
    }

    @Test
    void couponMilestone_crossesEveryThreshold() {
        // 임계치 10만마다 1단계
        assertEquals(0, PointService.couponMilestone(new BigDecimal("50000")));
        assertEquals(1, PointService.couponMilestone(new BigDecimal("100000")));
        assertEquals(1, PointService.couponMilestone(new BigDecimal("199999")));
        assertEquals(2, PointService.couponMilestone(new BigDecimal("250000")));
        // 90,000 → 110,000 저축 시 단계가 0→1로 올라가 쿠폰 제안 조건 성립
        assertTrue(PointService.couponMilestone(new BigDecimal("110000"))
                > PointService.couponMilestone(new BigDecimal("90000")));
    }
}
