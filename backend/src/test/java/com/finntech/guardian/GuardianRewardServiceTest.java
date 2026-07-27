package com.finntech.guardian;

import com.finntech.guardian.domain.GuardianEnums.Grade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 보상 계층의 순수 계산 검증 — 추첨·시드·슬롯·주차 키.
 * 레포 없이 정적 메서드만 호출한다.
 */
class GuardianRewardServiceTest {

    private static Map<Grade, Double> weights(double common, double rare, double epic) {
        Map<Grade, Double> m = new EnumMap<>(Grade.class);
        m.put(Grade.COMMON, common);
        m.put(Grade.RARE, rare);
        m.put(Grade.EPIC, epic);
        return m;
    }

    @Test
    @DisplayName("추첨은 누적 확률 구간을 그대로 따른다")
    void drawGradeFollowsCumulativeWeights() {
        Map<Grade, Double> w = weights(0.60, 0.32, 0.08);   // 무지출 3일 연속
        assertEquals(Grade.COMMON, GuardianRewardService.drawGrade(w, 0.00));
        assertEquals(Grade.COMMON, GuardianRewardService.drawGrade(w, 0.599));
        assertEquals(Grade.RARE, GuardianRewardService.drawGrade(w, 0.60));
        assertEquals(Grade.RARE, GuardianRewardService.drawGrade(w, 0.919));
        assertEquals(Grade.EPIC, GuardianRewardService.drawGrade(w, 0.92));
        assertEquals(Grade.EPIC, GuardianRewardService.drawGrade(w, 0.999));
    }

    @Test
    @DisplayName("가중치 합이 1에 못 미쳐도 빈손으로 끝나지 않는다")
    void drawGradeHasSafetyNet() {
        assertEquals(Grade.COMMON, GuardianRewardService.drawGrade(weights(0.5, 0.2, 0.1), 0.99));
    }

    @Test
    @DisplayName("추첨 시드는 결정론 — 같은 날을 다시 판정하면 같은 결과가 나온다")
    void seedIsDeterministic() {
        LocalDate d = LocalDate.of(2026, 8, 20);
        assertEquals(GuardianRewardService.drawSeed(7L, d, 0), GuardianRewardService.drawSeed(7L, d, 0));
        // 리롤·다른 날짜·다른 챌린지는 서로 다른 시드
        assertNotEquals(GuardianRewardService.drawSeed(7L, d, 0), GuardianRewardService.drawSeed(7L, d, 1));
        assertNotEquals(GuardianRewardService.drawSeed(7L, d, 0),
                GuardianRewardService.drawSeed(7L, d.plusDays(1), 0));
        assertNotEquals(GuardianRewardService.drawSeed(7L, d, 0), GuardianRewardService.drawSeed(8L, d, 0));
    }

    @Test
    @DisplayName("주간 상한 키는 그 날짜가 속한 주의 월요일")
    void weekStartIsMonday() {
        // 2026-08-20은 목요일 → 같은 주 월요일 2026-08-17
        assertEquals(LocalDate.of(2026, 8, 17), GuardianRewardService.weekStart(LocalDate.of(2026, 8, 20)));
        // 일요일도 같은 주로 묶인다(주간 정산이 일요일에 돈다)
        LocalDate sunday = LocalDate.of(2026, 8, 23);
        assertEquals(DayOfWeek.SUNDAY, sunday.getDayOfWeek());
        assertEquals(LocalDate.of(2026, 8, 17), GuardianRewardService.weekStart(sunday));
        // 월요일은 자기 자신
        assertEquals(LocalDate.of(2026, 8, 17), GuardianRewardService.weekStart(LocalDate.of(2026, 8, 17)));
    }

    @Test
    @DisplayName("빈 슬롯은 앞에서부터 채우고, 다 차면 창고로 간다")
    void firstFreeSlot() {
        assertEquals(0, GuardianRewardService.firstFreeSlot(List.of()));
        assertEquals(2, GuardianRewardService.firstFreeSlot(List.of(0, 1, 3)));
        assertEquals(19, GuardianRewardService.firstFreeSlot(
                java.util.stream.IntStream.range(0, 19).boxed().toList()));
        assertNull(GuardianRewardService.firstFreeSlot(
                java.util.stream.IntStream.range(0, 20).boxed().toList()), "다 차면 null(창고)");
    }
}
