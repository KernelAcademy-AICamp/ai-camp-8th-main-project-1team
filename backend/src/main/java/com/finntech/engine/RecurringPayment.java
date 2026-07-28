package com.finntech.engine;

import java.time.LocalDate;

/**
 * 반복 결제 1건(②) — 고정형(Fixed)·루틴형(Routine) 이원화.
 *
 * <p><b>고정형</b>: 특정 가맹점에서 일정 주기·일정 금액(통신비·구독 등) → 가맹점·주기·다음 예상일.
 * <b>루틴형</b>: 특정 category2·시간대에 반복 등장(습관, '아침 커피' 등) → 가맹점 무관·대표금액·빈도.
 */
public record RecurringPayment(
        Type type,
        String category2,
        /** 고정형 가맹점 표시명(루틴형은 null). */
        String merchantName,
        /** 고정형 가맹점 사업자번호(루틴형은 null). */
        String businessNumber,
        /** 루틴형 시간대 버킷(고정형은 null). */
        String daypart,
        /** 대표금액(원) — 중앙값. */
        long representativeAmount,
        /** 고정형 주기(일) — 루틴형은 null. */
        Integer periodDays,
        /** 고정형 다음 예상일 — 루틴형은 null. */
        LocalDate nextExpected,
        /** 서로 다른 날 등장 수. */
        int occurrenceDays,
        /** 주당 평균 빈도. */
        double perWeekFrequency
) {
    public enum Type { FIXED, ROUTINE }
}
