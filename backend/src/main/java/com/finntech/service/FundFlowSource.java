package com.finntech.service;

/**
 * 자금 흐름 입력({@link FundFlowInputs})을 실어 오는 <b>seam</b>. {@link FundFlowService}는 이 인터페이스에만
 * 의존하고, 재료가 실제로 어디서 오는지는 구현이 결정한다.
 *
 * <p><b>현재 구현</b> — {@link AccountFundFlowSource}: 잔액·급여를 마이데이터 계좌에서 직접 읽어(B안) L1·L3를
 * 채운다. 소비(월평균지출)는 카드로만 세므로 이중 계산이 없다(R7). L2·L4·L5는 아직 재료가 없어 UNKNOWN이다.
 *
 * <p><b>다음</b> — ①의 (B)(`fixed_expense_summary`·`recurring_payments`·`preferential_material`)가 나오면
 * 이 소스에서 함께 채우거나 별도 소스로 합친다. {@link FundFlowService}·{@link FundFlowInputs}(계약)는 그대로 둔다.
 */
public interface FundFlowSource {

    /** 사용자의 (B) 자금흐름 재료. 받지 못한 축은 해당 서브레코드를 null로 둔다. */
    FundFlowInputs load(Long userId);
}
