package com.finntech.service;

import com.finntech.service.ParkingAccountSource.ParkingAccount;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 파킹통장 응답 → {@link ParkingAccount} 변환의 순수 로직만 검증한다(외부 호출 없음).
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
    void 회사코드와_상품코드로_키를_만든다() {
        ParkingAccount a = ParkingAccountSource.toAccount(
                row("KJ", "P100", "광주은행", "매일이자Wa파킹통장", "2.50", "5.10"));

        assertThat(a.company()).isEqualTo("광주은행");
        assertThat(a.name()).isEqualTo("매일이자Wa파킹통장");
        assertThat(a.baseRate()).isEqualTo(2.50);
        assertThat(a.primeRate()).isEqualTo(5.10);
        // 이름은 흔들려도 코드는 안 흔들린다 — 정렬의 마지막 동점 처리를 이 키로 잠근다
        assertThat(a.productKey()).isEqualTo("KJ:P100");
    }

    /**
     * 최고금리는 <b>조건부</b>다(예치금 구간·첫거래). 광주 상품은 기본 2.50%인데 최고가 5.10%로
     * 두 배가 넘는다 — 이 값으로 줄을 세우면 조건을 못 채운 사람에게 못 받을 금리를 앞세우게 된다.
     */
    @Test
    void 최고금리는_기본금리와_따로_담긴다() {
        ParkingAccount a = ParkingAccountSource.toAccount(
                row("KJ", "P100", "광주은행", "매일이자Wa파킹통장", "2.50", "5.10"));

        assertThat(a.primeRate()).isGreaterThan(a.baseRate() * 2);
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
