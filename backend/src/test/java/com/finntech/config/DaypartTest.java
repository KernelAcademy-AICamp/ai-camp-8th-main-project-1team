package com.finntech.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 시간대 버킷 경계 검증 — 반복결제 루틴형(②)·소비패턴(③)이 공유하는 단일 정의.
 * 심야는 ML 피처(WasteFeatureExtractor.night, [23,4])와 값이 일치해야 한다.
 */
class DaypartTest {

    private final AnalysisProperties.Daypart daypart = new AnalysisProperties.Daypart();

    @Test
    @DisplayName("심야는 23,0,1,2,3,4시 — ML night 정의와 값 일치")
    void nightBucketMatchesMlDefinition() {
        for (int h : new int[]{23, 0, 1, 2, 3, 4}) {
            assertEquals("심야", daypart.bucketOf(h), "hour " + h);
        }
    }

    @Test
    @DisplayName("아침 5~10 · 점심 11~16 · 저녁 17~22")
    void morningLunchEveningBoundaries() {
        for (int h = 5; h <= 10; h++) assertEquals("아침", daypart.bucketOf(h), "hour " + h);
        for (int h = 11; h <= 16; h++) assertEquals("점심", daypart.bucketOf(h), "hour " + h);
        for (int h = 17; h <= 22; h++) assertEquals("저녁", daypart.bucketOf(h), "hour " + h);
    }

    @Test
    @DisplayName("0~23시 전부 4개 버킷 중 하나로 분류된다")
    void allHoursCovered() {
        Set<String> buckets = Set.of("심야", "아침", "점심", "저녁");
        for (int h = 0; h <= 23; h++) {
            assertTrue(buckets.contains(daypart.bucketOf(h)), "hour " + h + " → " + daypart.bucketOf(h));
        }
    }
}
