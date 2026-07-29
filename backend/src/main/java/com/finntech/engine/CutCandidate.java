package com.finntech.engine;

/**
 * 절약 후보 1건(⑤) — 줄일 수 있는 소비 하나. category2 단위, 3등급 카테고리 규칙으로 뽑는다.
 *
 * <p><b>제거가능(REMOVABLE)</b>: 대표 소비(커피·배달·구독 등) → 전액이 절감 대상.
 * <b>최적화가능(OPTIMIZABLE)</b>: 필수에 가까운 소비(식비·교통) → 중앙값 초과분만 절감 대상.
 * {@code reason}은 코드가 만든 사실 문장이다(원칙 1: 판단은 코드가). LLM은 이를 다듬어 표현만 한다(⑥).
 */
public record CutCandidate(
        String category2,
        Type type,
        /**
         * 해당 category2의 <b>월 환산</b> 지출(원).
         *
         * <p>예전에는 관측 창(기본 90일)의 <b>합계</b>를 그대로 담고 이름만 {@code monthlySpend}였다.
         * 화면은 그 값을 "월 N원"으로 표시했으므로 실제 월 지출의 약 3배가 나갔고, 다음 화면인
         * 온보딩 2단계는 진짜 월평균을 보여줘 <b>같은 카테고리 금액이 화면을 넘길 때마다 10배씩
         * 뛰었다.</b> 이제 창 길이로 나눠 한 달치로 환산한다 — 이름과 값이 일치한다.
         */
        long monthlySpend,
        /** 예상 <b>월</b> 절감액(원) — REMOVABLE=전액, OPTIMIZABLE=중앙값 초과분. 위와 같은 기준으로 환산한다. */
        long estimatedSaving,
        /** 코드가 생성한 근거 문장(집계 사실만). */
        String reason
) {
    public enum Type { REMOVABLE, OPTIMIZABLE }
}
