package com.finntech.guardian.domain;

/**
 * 지킴이 Agent 열거형 모음 (지킴이 Agent 설계서 v1.2).
 *
 * <p>설계서의 Postgres {@code create type ... as enum}을 그대로 옮긴 것이다.
 * 저장은 {@code @Enumerated(STRING)} + VARCHAR — 네이티브 ENUM은 값 추가 시
 * {@code ddl-auto=update}가 ALTER를 만들지 못해 깨진다(마스터 §13, 이 저장소가 이미 겪은 사고).
 */
public final class GuardianEnums {
    private GuardianEnums() {}

    /** 챌린지 생애주기 (설계서 §8). */
    public enum ChallengeState {
        SETUP, ACTIVE, AT_RISK, EXCEEDED, SETTLING,
        SUCCESS, PARTIAL, SHORTFALL, FAILED, ABANDONED,
        REWARD_PENDING, RESTART_OFFER, CLOSED
    }

    /**
     * 거래 판정 상태.
     * PENDING_CATEGORY = 분류 신뢰도 미달로 아직 집계하지 않음 · COUNTED = 낙관적 판정으로 집계됨 ·
     * EXCLUDED = 되돌리기(내 소비 아님) · EXEMPTED = 면제권 사용.
     */
    public enum TxState { PENDING_CATEGORY, COUNTED, EXCLUDED, EXEMPTED }

    public enum TxType { EXPENSE, REFUND, INCOME }

    /** 일 판정 결과 (설계서 §3.5). */
    public enum DailyResult { NO_SPEND_DAY, ON_PACE_DAY, OFF_PACE_DAY, NO_GRANT }

    /**
     * 알림 전달 수단. <b>SILENT도 하나의 결정이다</b> — 침묵 전환율을 지표로 올리려면
     * "보내지 않기로 했다"는 사실 자체가 기록돼야 한다(설계서 §4.1).
     */
    public enum DeliveryKind { PUSH, INAPP, MODAL, SILENT }

    /** 침묵 사유 — 왜 안 보냈는지. */
    public enum SuppressedReason { CASE_SILENT, BUDGET, COOLDOWN, NIGHT }

    /** 지킴이 포인트 적립 사유 (설계서 §3.7). 기존 {@code domain.PointEvent}(저축 루프)와 다른 축이다. */
    public enum PointType { WEEKLY_MISSION, RISK_DEFENSE, LABELING, MONTHLY_COMPLETE }

    /** 마이룸 사물 등급. */
    public enum Grade { COMMON, RARE, EPIC }

    /**
     * 화법 (설계서 §5).
     * TENTATIVE = 아직 되돌릴 수 있는 결제라 조건부로("챌린지에 넣으면 118,000원 남아요") ·
     * DEFINITIVE = 이미 확정된 사실이라 단정으로.
     */
    public enum PhrasingMode { TENTATIVE, DEFINITIVE }

    /** 문장 톤 — LLM 프롬프트의 톤 지침 키. */
    public enum Tone {
        SOFT_REMINDER, PATTERN_HINT, REWARD_WARNING, FACT_RESET,
        PRAISE, NEUTRAL_ASK, NUDGE_AHEAD, MORNING_CEREMONY, WEEKLY_RECAP;

        /** 설계서·API 응답이 쓰는 snake_case 표기. */
        public String wire() { return name().toLowerCase(); }
    }

    /** 되돌리기 사유 (설계서 §3.4.1). */
    public enum UndoReason {
        /** 내 소비가 아니다·오분류 — 무제한. */
        NOT_MINE,
        /** 인정하지만 이번은 봐달라 — 보유 면제권을 1장 소모. */
        EXEMPTION
    }

    /** 마이룸 사물 획득 경로. */
    public enum ObjectSource { DAILY, SHOP, GIFT }

    /** 주간 미션 조건 유형 (설계서 §3.7). */
    public enum MissionCondition { CATEGORY_COUNT_MAX, NO_SPEND_STREAK_MIN, LABELING_COUNT_MIN }

    /** 알림 피드백 — 별점보다 이 태그가 프롬프트 개선 방향을 정한다(설계서 §API 5). */
    public enum Feedback { USEFUL, NOT_USEFUL }

    public enum FeedbackReason { TIMING, TONE, ALREADY_KNEW, NOT_MINE, TOO_OFTEN }
}
