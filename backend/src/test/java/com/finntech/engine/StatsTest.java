package com.finntech.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StatsTest {

    @Test
    @DisplayName("median은 홀수/짝수 길이를 모두 처리한다")
    void median() {
        assertEquals(3.0, Stats.median(new double[]{1, 2, 3, 4, 5}));
        assertEquals(2.5, Stats.median(new double[]{1, 2, 3, 4}));
        assertEquals(0.0, Stats.median(new double[]{}));
    }

    @Test
    @DisplayName("MAD는 이상치에 오염되지 않는다 — 표준편차와의 결정적 차이")
    void madIsRobust() {
        double[] clean = {10, 10, 11, 10, 11, 10, 11, 10, 10, 11};
        double[] withOutlier = {10, 10, 11, 10, 11, 10, 11, 10, 10, 10000};

        double madClean = Stats.mad(clean, Stats.median(clean));
        double madDirty = Stats.mad(withOutlier, Stats.median(withOutlier));
        double sdClean = Stats.stdDev(clean);
        double sdDirty = Stats.stdDev(withOutlier);

        // 표준편차는 이상치 하나에 수천 배로 튄다
        assertTrue(sdDirty > sdClean * 100, "표준편차는 이상치에 크게 오염되어야 한다");
        // MAD는 거의 그대로다 — 그래서 임계 판정의 기준으로 쓴다
        assertEquals(madClean, madDirty, 1.0);
    }

    @Test
    @DisplayName("MAD가 0이어도 Infinity를 내지 않는다 — 균일 데이터에서 전건 경고 방지")
    void modifiedZHandlesZeroMad() {
        double[] identical = {100, 100, 100, 100, 100, 100, 100, 100, 100, 100};
        double z = Stats.modifiedZ(100.0, identical);
        assertTrue(Double.isFinite(z), "z는 유한해야 한다");
        assertEquals(0.0, z, 1e-9);

        double zOut = Stats.modifiedZ(500.0, identical);
        assertTrue(Double.isFinite(zOut), "이상치에서도 유한해야 한다");
    }

    @Test
    @DisplayName("명백한 이상치는 임계 3.5를 넘는다")
    void modifiedZFlagsOutlier() {
        double[] baseline = {100, 105, 98, 102, 101, 99, 103, 97, 104, 100};
        assertTrue(Stats.modifiedZ(400.0, baseline) > 3.5);
        assertFalse(Stats.modifiedZ(103.0, baseline) > 3.5);
    }

    @Test
    @DisplayName("변동계수는 평균이 0이면 0을 낸다")
    void cvHandlesZeroMean() {
        assertEquals(0.0, Stats.coefficientOfVariation(new double[]{0, 0, 0}));
    }
}
