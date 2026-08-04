package com.finntech.service;

import java.util.List;
import java.util.Set;

/**
 * 저축 상품 매칭(FP-01, M1~M9)의 입력 재료 — `07_취향분석및추천_Agent_설계.md` §4.5.
 *
 * <p><b>왜 계약을 따로 두나(seam).</b> {@link SavingsCompareService.Account}는 금감원 오픈API 응답 모양
 * 그대로다({@code joinDeny}·{@code spclCnd}·{@code prdtKey}). 매칭 규칙이 그 DTO에 직접 붙으면 출처가
 * 바뀔 때(예금 {@code depositProductsSearch} 추가·파킹통장·카드사 공시) 규칙까지 흔들린다. 그래서
 * {@link FundFlowInputs}와 같은 방식으로 <b>규칙이 필요로 하는 것만</b> 계약으로 세우고, 출처 → 계약 변환은
 * 바깥(3단계 `SavingsCompareService` 확장)에서 한다. 의존을 구현이 아니라 계약에 둔다(DIP).
 *
 * <p>값을 아직 못 받는 항목은 {@code null}로 둔다. 규칙은 {@code null}을 <b>"모른다"</b>로 다루며
 * "없다"나 "0"으로 바꿔 읽지 않는다 — 재료 없는 축은 UNKNOWN으로 두고 숨기지 않는다(§14).
 *
 * @param candidates    후보 상품. 적립 방식(파킹·자유·정액)이 섞여 들어와도 되며 M1이 가른다.
 * @param keptMeanAmount 월 확정 지킨 돈 평균(원). <b>{@code null}이면 M5 규모 필터를 건너뛴다</b> —
 *                       ②의 이력 조회(`GET /api/guardian/challenges/history`)가 아직 없어 현재는 null이다
 *                       (§8 · §11). 없는 값으로 상품을 걸러내면 근거 없이 후보가 사라진다.
 */
public record SavingsMatchInputs(
        List<ProductCandidate> candidates,
        Long keptMeanAmount
) {

    /**
     * 적립 방식 — M1이 상품을 가르는 기준이자 <b>그룹 순서가 곧 추천 결과</b>가 되는 축이다(§4.5 M1).
     * 전체를 하나의 순위표로 합치지 않는다.
     */
    public enum AccrualType {
        /** 파킹통장 — 수시입출금. 유동성이 급할 때 상단(M2). */
        PARKING,
        /** 자유적립식 — 납입액이 매달 달라도 되는 적금. 버퍼가 두껍고 지출이 예측 가능할 때 상단(M3). */
        FLEXIBLE,
        /**
         * 정기예금 — 목돈을 한 번에 넣고 만기까지 묶는다.
         *
         * <p><b>§4.5 M1에 없던 그룹이다.</b> M1은 `파킹통장 · 자유적립식 · 정액적립식` 셋만 적어 두었는데,
         * §10 3단계의 추천 대상은 `적금 · 예금 · 파킹 전부`라 예금이 갈 자리가 없었다. 적립식(매달 조금씩)과
         * 예금(목돈 한 번에)은 납입 구조가 아예 달라 한 그룹에 섞을 수 없으므로 넷째 그룹으로 둔다.
         * <b>문서 개정 대상</b> — §4.5 M1에 반영이 필요하다.
         */
        DEPOSIT,
        /** 정액적립식 — 매달 고정 금액 납입. 판정 없이 항상 노출한다(M4). */
        FIXED
    }

    /**
     * 실수령 금리(M6)에 반영하는 우대조건. <b>카드 실적·급여이체 둘만</b> 본다 —
     * 자동이체는 더미에 해당 거래가 없어 뺐다(§4.1 L5 · R7).
     */
    public enum PreferentialCondition {
        /** 카드 실적(전월 이용금액). */
        CARD_PERFORMANCE,
        /** 급여이체. */
        SALARY_TRANSFER
    }

    /**
     * 매칭 후보 상품 한 건.
     *
     * @param productKey        상품 식별키(금융사코드:상품코드). 동률·중복 판별용.
     * @param company           금융회사명. M9 최종 동점 처리의 가나다순 기준.
     * @param name              상품명.
     * @param accrualType       적립 방식(M1).
     * @param baseRate          기본금리(%).
     * @param maxRate           최고금리(%) — 우대조건을 모두 채웠을 때.
     * @param termMonths        만기(개월). M9 2순위 동점 처리 기준.
     * @param minMonthlyAmount  최소 가입/권장 납입금액(원). <b>{@code null}이면 규모 조건 미수집</b>이라
     *                          M5가 통과시킨다. 금감원 적금 API는 이 값을 주지 않아 현재 대부분 null이다.
     * @param requiredConditions 이 상품이 최고금리를 주기 위해 요구하는 우대조건.
     *                          <b>{@code null}이면 {@code spclCnd} 미파싱</b>(D2 — 출처가 자연어로만 준다)이라
     *                          충족 여부를 확인할 수 없다는 뜻이고, 빈 집합은 <b>파싱했더니 요구 조건이 없다</b>는
     *                          뜻이다. 둘은 다르게 처리된다(M6).
     */
    public record ProductCandidate(
            String productKey,
            String company,
            String name,
            AccrualType accrualType,
            double baseRate,
            double maxRate,
            int termMonths,
            Long minMonthlyAmount,
            Set<PreferentialCondition> requiredConditions
    ) {}
}
