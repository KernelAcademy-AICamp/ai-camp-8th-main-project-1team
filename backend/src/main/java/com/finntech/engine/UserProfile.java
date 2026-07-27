package com.finntech.engine;

import java.util.Map;

/**
 * 소비 프로필(④) — 이상소비지수와 그 해석가능 분해, ②③ 요약을 담는다.
 *
 * <p>이상소비지수({@code abnormalityIndex}, 0~100)는 4개 성분의 가중합이며, {@code contributionPoints}가
 * 각 성분이 지수에 더한 점수를 그대로 보여준다(합 = 지수). "판단은 설명가능 모델이"(마스터 §4) 원칙에 맞춰
 * 블랙박스 점수가 아니라 성분별 기여가 드러나도록 구성한다.
 */
public record UserProfile(
        /** 이상소비지수 0~100 (= contributionPoints 합). */
        int abnormalityIndex,
        /** 성분1: 낭비비율(제거가능 카테고리 지출 ÷ 총지출) [0,1]. */
        double wasteRatio,
        /** 성분2: 과소비집중도(최대 category1 지출비율) [0,1]. */
        double concentrationRatio,
        /** 성분3: 지출변동성(월지출 CV ÷ cvCap, clamp) [0,1]. */
        double volatility,
        /** 성분4: 심야·충동비중(심야 지출 + 루틴형 낭비지출 ÷ 총지출) [0,1]. */
        double nightImpulseRatio,
        /** 성분별 지수 기여 점수(낭비/집중/변동/심야충동) — 합이 abnormalityIndex. */
        Map<String, Integer> contributionPoints,
        /** 창 내 총 지출액(원). */
        long totalSpend,
        /** 최대 지출 category1(없으면 null). */
        String topCategory1,
        /** ② 고정형 반복결제 수. */
        int fixedCount,
        /** ② 루틴형 반복소비 수. */
        int routineCount,
        /** ③ 최대 지출 요일·시간대 칸(거래 없으면 null). */
        SpendingPattern.PeakCell peak
) {
}
