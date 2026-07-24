package com.finntech.engine;

import java.time.DayOfWeek;
import java.util.Map;

/**
 * 소비 패턴(③) — 최근 창의 요일·시간대 분포와 최대 지출 칸(peak).
 *
 * <p>요일×시간대 히트맵으로 "언제 돈을 쓰는가"를 보여준다. 시간대 정의는 {@code AnalysisProperties.Daypart}
 * 단일 출처를 반복결제 루틴형(②)과 공유한다(모순 방지).
 */
public record SpendingPattern(
        /** 요일별 총 지출액(원). */
        Map<DayOfWeek, Long> amountByDayOfWeek,
        /** 시간대(아침/점심/저녁/심야)별 총 지출액(원). */
        Map<String, Long> amountByDaypart,
        /** "요일|시간대" 칸별 건수 — 히트맵 셀. */
        Map<String, Long> countByCell,
        /** 지출이 가장 큰 칸(거래가 없으면 null). */
        PeakCell peak
) {
    /** 지출이 가장 큰 (요일, 시간대) 칸. */
    public record PeakCell(DayOfWeek dayOfWeek, String daypart, long amount) {}
}
