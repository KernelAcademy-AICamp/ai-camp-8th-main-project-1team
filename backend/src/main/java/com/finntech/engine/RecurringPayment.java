package com.finntech.engine;

import java.time.LocalDate;

/**
 * 반복 결제 1건(②) — 고정형(Fixed)·루틴형(Routine) 이원화.
 *
 * <p><b>고정형</b>: 특정 가맹점에서 일정 주기(통신비·구독 등) → 가맹점·주기·다음 예상일.
 * <b>루틴형</b>: 특정 category2·시간대에 반복 등장(습관, '아침 커피' 등) → 가맹점 무관·대표금액·빈도.
 *
 * <p><b>금액이 같아야 고정형인 것은 아니다</b>(2026-08-04). 통신비는 사용량에, 해외 구독은 환율에,
 * 공과금은 계절에 따라 매달 다르다 — 그래도 계약이다. 그래서 {@link #amountVaries}·{@link #priorAmount}로
 * <b>변했다는 사실 자체를 표현</b>한다. 판정 근거는
 * {@link com.finntech.config.AnalysisProperties.Recurring} 참조.
 */
public record RecurringPayment(
        Type type,
        /** 이 반복이 아직 살아 있는가. 루틴형은 최근 창에서만 뽑으므로 언제나 {@link Status#ACTIVE}. */
        Status status,
        String category2,
        /** 고정형 가맹점 표시명(루틴형은 null). */
        String merchantName,
        /** 고정형 가맹점 사업자번호(루틴형은 null). */
        String businessNumber,
        /** 루틴형 시간대 버킷(고정형은 null). */
        String daypart,
        /**
         * 대표금액(원).
         *
         * <p>금액이 안정적이면 중앙값(=고정액), {@link #amountVaries}면 <b>최근 결제액</b>이다.
         * 다음 결제일을 말하면서 금액만 과거 중앙값을 말하면 짝이 맞지 않는다.
         */
        long representativeAmount,
        /** 금액이 흔들리는가 — 화면이 "최근 124,000원"처럼 정직하게 쓸 수 있게. */
        boolean amountVaries,
        /**
         * 마지막 변화점 <b>이전</b> 구간의 금액(변한 적이 없으면 null).
         *
         * <p>"13,500 → 17,000이 됐어요"를 말하려면 이전 금액이 필요하다. 그 판단은 판정 로직
         * 안에서만 할 수 있으므로 여기서 함께 내보낸다.
         */
        Long priorAmount,
        /** 고정형 주기(일) — 루틴형은 null. */
        Integer periodDays,
        /**
         * 고정형 다음 예상일 — 루틴형이거나 {@link Status#ENDED}면 null.
         *
         * <p>끝난 구독에 "다음 예상일"을 주면 <b>과거 날짜가 미래인 척</b> 뜬다(2026-08-04 운영에서 발견).
         */
        LocalDate nextExpected,
        /** 첫 등장일 — "언제부터 구독했나". */
        LocalDate firstSeen,
        /** 마지막 등장일 — "언제까지 구독했나". */
        LocalDate lastSeen,
        /** 서로 다른 날 등장 수. */
        int occurrenceDays,
        /** 주당 평균 빈도. */
        double perWeekFrequency
) {
    public enum Type { FIXED, ROUTINE }

    /**
     * 진행 중인가, 끝났는가.
     *
     * <p>탐지는 <b>전 기간</b>을 본다 — 창으로 잘라 끊긴 구독을 아예 안 보이게 하면
     * "언제부터 언제까지 무엇을 구독했나"에 답할 수 없다. 보여주되 끝났다고 말한다.
     */
    public enum Status { ACTIVE, ENDED }
}
