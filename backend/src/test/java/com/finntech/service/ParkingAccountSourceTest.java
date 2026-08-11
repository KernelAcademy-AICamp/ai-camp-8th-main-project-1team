package com.finntech.service;

import com.finntech.service.SavingsMatchInputs.AccrualType;
import com.finntech.service.SavingsMatchInputs.ProductCandidate;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 파킹통장 응답 → 매칭 계약 변환의 순수 로직만 검증한다(외부 호출 없음).
 * 표본은 2026-08-04 실제 응답에서 땄다.
 */
class ParkingAccountSourceTest {

    private static Map<String, Object> row(String companyCode, String code, String company,
                                           String name, Object rate, Object primeRate) {
        Map<String, Object> m = new HashMap<>();
        m.put("companyCode", companyCode);
        m.put("code", code);
        m.put("companyName", company);
        m.put("name", name);
        m.put("interestRate", rate);
        m.put("primeInterestRate", primeRate);
        return m;
    }

    @Test
    void 파킹통장으로_분류하고_회사코드와_상품코드로_키를_만든다() {
        ProductCandidate c = ParkingAccountSource.toCandidate(
                row("KJ", "P100", "광주은행", "매일이자Wa파킹통장", "2.50", "5.10"));

        assertThat(c.accrualType()).isEqualTo(AccrualType.PARKING);
        assertThat(c.company()).isEqualTo("광주은행");
        assertThat(c.name()).isEqualTo("매일이자Wa파킹통장");
        assertThat(c.baseRate()).isEqualTo(2.50);
        assertThat(c.maxRate()).isEqualTo(5.10);
        // 이름은 흔들려도 코드는 안 흔들린다 — M9의 마지막 동점 처리가 이 키로 전순서를 보장한다
        assertThat(c.productKey()).isEqualTo("KJ:P100");
    }

    /** 파킹통장은 만기가 없다 — M9 ②(만기 짧은 것 먼저)에서 가장 앞으로 간다. */
    @Test
    void 만기가_없으므로_0이다() {
        assertThat(ParkingAccountSource.toCandidate(
                row("KB", "P1", "케이뱅크", "기분통장", "1.70", "2.20")).termMonths()).isZero();
    }

    /**
     * 우대조건을 구조로 주지 않는다 → null(확인 불가). 금감원 쪽처럼 카드실적·급여이체를 가정하지 않는다 —
     * 파킹통장의 우대는 보통 예치금 구간·첫거래라 성격이 다르다.
     */
    @Test
    void 우대조건은_확인불가로_둔다() {
        ProductCandidate c = ParkingAccountSource.toCandidate(
                row("JB", "P2", "전북은행", "씨드모아(고액우대) 통장", "1.80", "4.00"));

        assertThat(c.requiredConditions()).isNull();
        // null이면 매칭이 기본금리로 가고 `확인 불가`로 표시한다(M6) — 못 받을 금리를 띄우지 않는다
        assertThat(new SavingsMatchService(3)
                .evaluate(c, null).conditionsKnown()).isFalse();
    }

    /** 최소 가입금액이 없으므로 M5 규모 필터를 통과한다. */
    @Test
    void 최소납입금액이_없어_규모필터를_통과한다() {
        ProductCandidate c = ParkingAccountSource.toCandidate(
                row("KB", "P1", "케이뱅크", "기분통장", "1.70", "2.20"));

        assertThat(c.minMonthlyAmount()).isNull();
        assertThat(SavingsMatchService.fitsSize(c, 1_000L)).isTrue();
    }

    @Test
    void 금리가_숫자가_아니면_0으로_둔다() {
        assertThat(ParkingAccountSource.parseRate(null)).isZero();
        assertThat(ParkingAccountSource.parseRate("연 2.5%")).isZero();
        assertThat(ParkingAccountSource.parseRate("2.50")).isEqualTo(2.50);
        assertThat(ParkingAccountSource.parseRate(3.1)).isEqualTo(3.1);
    }

    /** 상품군 코드를 잘못 잡으면 목록이 통째로 달라진다 — 1001이 파킹이다(2026-08-04 실측). */
    @Test
    void 파킹통장_상품군_코드는_1001이다() {
        assertThat(ParkingAccountSource.PRODUCT_TYPE_PARKING).isEqualTo("1001");
    }
}
