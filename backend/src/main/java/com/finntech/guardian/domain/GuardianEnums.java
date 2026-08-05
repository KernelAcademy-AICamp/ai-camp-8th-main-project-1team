package com.finntech.guardian.domain;

/**
 * 지킴이 Agent 열거형 모음 (지킴이 Agent 스펙 v1.5).
 *
 * <p>설계서의 Postgres {@code create type ... as enum}을 그대로 옮긴 것이다.
 * 저장은 {@code @Enumerated(STRING)} + VARCHAR — 네이티브 ENUM은 값 추가 시
 * {@code ddl-auto=update}가 ALTER를 만들지 못해 깨진다(마스터 §13, 이 저장소가 이미 겪은 사고).
 */
public final class GuardianEnums {
    private GuardianEnums() {}

    /**
     * 거래 판정 종류 (스펙 v1.5 §5.1). <b>이 하나가 뒤의 모든 판정을 좌우한다.</b>
     *
     * <p>v1.2에서는 "챌린지 카테고리인가"만 봤다. v1.5는 고정지출을 1급으로 분리한다 —
     * 통신비·구독·통근은 줄일 수 있는 소비가 아닌데 예산에서 차감하면 사용자가 손쓸 수 없는
     * 돈 때문에 한도를 넘긴다.
     *
     * <p>{@code kind}는 <b>시스템이 정하는 판정 대상 여부</b>이고 {@link SpendChip}은
     * <b>사용자가 남기는 소비 맥락</b>이다. 서로 다른 축이라 라벨을 붙여도 차감은 그대로다.
     */
    public enum TxKind {
        /** 줄이기로 한 카테고리 — 예산 차감·개입(C1 C2 C3 C8)·일 판정 모두 대상. */
        TARGET,
        /** 관리 밖 일반 지출 — 차감하지 않고 침묵(C4). */
        NORMAL,
        /** 성역(의료·경조사 등) — 영구 침묵. <b>고정지출보다 먼저 검사한다.</b> */
        SANCT,
        /** 고정지출(통신·구독·통근) — 차감도 개입도 일 판정도 없다. */
        FIXED,
        /** 분류 실패·저신뢰 — 차감을 보류하고 되묻는다(C7). */
        UNKNOWN;

        /** 예산을 깎고 일 판정에 들어가는 종류는 이것뿐이다. */
        public boolean countsAgainstCap() { return this == TARGET; }
    }

    /**
     * 소비 맥락 칩 (스펙 v1.5 §5.1). <b>라벨은 차감을 바꾸지 않는다.</b>
     *
     * <p>세 번째 칩 '불가피한'은 아직 미결(D29)이라 내보내지 않는다 — 누르면 무제한으로
     * 차감이 되돌려지는데, 뽑기 보상으로 면제권을 주면서 상시 무료 되돌리기도 있으면 모순이다.
     * 면제권 쓰기로 대체할지 정해지면 그때 추가한다.
     */
    public enum SpendChip {
        /** 계획한다 */
        PLANNED,
        /** 줄이려는 */
        REDUCING
    }

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

    /**
     * 지킴이 포인트 적립 사유 (스펙 v1.5 §5.5). 기존 {@code domain.PointEvent}(저축 루프)와 다른 축이다.
     *
     * <p>주간 상한 100P = 미션 30 + 위기 방어 20 + 라벨링 50. 완주와 중복 전환은 상한 밖이다.
     */
    public enum PointType {
        /** 주당 총 30P를 진행 중 미션이 나눠 갖는다(1개 30 · 2개 15 · 3개 10). */
        WEEKLY_MISSION,
        /** 그 주에 AT_RISK 이력이 있고 주 종료 시 EXCEEDED가 아닌 것. */
        RISK_DEFENSE,
        /** 3칩 라벨링. 건당 2P, 주 최대 50P. */
        LABELING,
        /** 완주. 중도 포기가 아니면 <b>예산을 초과해도</b> 지급한다. */
        MONTHLY_COMPLETE,
        /** 이미 가진 사물이 나왔을 때의 전환(일반 5 · 희귀 15 · 에픽 30). 상한 밖. */
        DUPLICATE_OBJECT
    }

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

    /**
     * 주간 미션 조건 유형. 내용은 보상 계층이 정하고 <b>달성 판정은 지킴이가 한다</b>.
     *
     * <p>스펙 v1.5 §5.5가 정의하는 것은 앞의 두 개({@code MAX_COUNT}·{@code AVOID_SLOT})다.
     * 뒤의 둘은 v1.5 이전부터 화면에서 돌고 있어 남겨 둔다 — 스펙에 없다고 지우면
     * 이미 사용자에게 나가고 있는 미션이 사라진다.
     */
    public enum MissionType {
        /** (v1.5) 한 주 해당 카테고리 집계 결제가 {@code threshold} 이하. */
        MAX_COUNT,
        /** (v1.5) 지정 요일·시간대에 해당 카테고리 결제가 없을 것. 판정은 <b>반드시 KST 기준</b>. */
        AVOID_SLOT,
        /** (기존) 무지출 연속 최고 기록이 {@code threshold} 이상. */
        NO_SPEND_STREAK_MIN,
        /** (기존) 소비 성격 답하기 {@code threshold}건 이상. */
        LABELING_COUNT_MIN;

        /** 요일·시간 슬롯을 쓰는 유형인가. */
        public boolean usesSlot() { return this == AVOID_SLOT; }
    }

    /** 알림 피드백 — 별점보다 이 태그가 프롬프트 개선 방향을 정한다(설계서 §API 5). */
    public enum Feedback { USEFUL, NOT_USEFUL }

    public enum FeedbackReason { TIMING, TONE, ALREADY_KNEW, NOT_MINE, TOO_OFTEN }
}
