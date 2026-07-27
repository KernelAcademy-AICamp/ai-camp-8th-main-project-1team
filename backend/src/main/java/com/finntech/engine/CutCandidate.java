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
        /** 최근 창의 해당 category2 총 지출(원). */
        long monthlySpend,
        /** 예상 절감액(원) — REMOVABLE=전액, OPTIMIZABLE=중앙값 초과분. */
        long estimatedSaving,
        /** 코드가 생성한 근거 문장(집계 사실만). */
        String reason
) {
    public enum Type { REMOVABLE, OPTIMIZABLE }
}
