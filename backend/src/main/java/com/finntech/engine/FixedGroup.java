package com.finntech.engine;

import java.util.List;

/**
 * 고정 결제 한 묶음 — <b>요약과 그 묶음을 이룬 결제들</b>.
 *
 * <p>{@link RecurringPaymentDetector}는 판정이 끝나면 어떤 결제가 그 묶음을 이뤘는지 버렸다.
 * 화면은 "넷플릭스가 매달 22일 17,000원"만 알면 되므로 그것으로 충분했는데, 결제 한 줄마다
 * <b>고정지출인지 적어 두려면</b> 그 명단이 있어야 한다.
 *
 * <h2>왜 {@link RecurringPayment} 에 칸을 늘리지 않았나</h2>
 *
 * <p>그 record 는 {@code ConsumptionAnalysisController.AnalysisSummary} 로 <b>그대로
 * 직렬화돼 API 응답에 실린다.</b> 칸을 하나 늘리면 화면이 쓰지도 않는 결제 식별자 수백 개가
 * 매 요청에 따라 나간다. 응답에 실리는 것과 안에서만 쓰는 것을 갈라 둔다.
 *
 * <h2>여기 함께 담는 세 값</h2>
 *
 * <p>{@link #merchantKey} · {@link #periodKind} · {@link #gapCv} 는 판정 안에서 계산되고
 * <b>버려지던</b> 값이다. 밖에서 다시 만들려면 판정 규칙을 한 벌 더 적어야 하고, 그러면
 * 언젠가 한쪽만 고쳐진다. 판정이 이미 아는 것은 판정이 함께 내보낸다.
 */
public record FixedGroup(
        /** 화면·분석이 쓰는 요약. {@code RecurringPaymentDetector.detect} 가 내보내는 바로 그 값. */
        RecurringPayment summary,
        /** 이 묶음을 모은 키 — {@link RecurringPaymentDetector#merchantKeyOf} 가 만든다. */
        String merchantKey,
        /** 주간이냐 월간이냐. 판정이 두 범위 중 어느 쪽에 걸렸는지다. */
        PeriodKind periodKind,
        /** 간격 변동계수(표준편차 ÷ 평균) — 판정이 얼마나 아슬아슬했는지 말해 준다. */
        double gapCv,
        /**
         * 이 묶음에 든 결제 식별자 — 결제일 오름차순, 같은 날이면 식별자 순.
         *
         * <p>정렬을 고정하는 것은 재현성(마스터 §4 원칙 3) 때문이다. 저장소가 내주는 순서
         * (결제일 내림차순)를 그대로 흘리면 같은 입력에 같은 출력이라는 보장이 없다.
         */
        List<String> paymentIds
) {

    /** 고정형이 걸릴 수 있는 두 주기. 어느 쪽이냐에 따라 금액 게이트가 걸리고 안 걸린다. */
    public enum PeriodKind { WEEKLY, MONTHLY }
}
