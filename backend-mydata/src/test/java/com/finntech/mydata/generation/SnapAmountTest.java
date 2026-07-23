package com.finntech.mydata.generation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 금액 단위 현실화(§13-11) 검증 — 실 카드내역은 1원 단위가 거의 없다.
 * 스냅 후 모든 금액은 최소 10원 단위이고(1원 단위 없음), 고액일수록 1000원 단위가 우세해야 한다.
 */
class SnapAmountTest {

    @Test
    @DisplayName("스냅된 금액은 1원 단위가 없다(최소 10원 배수) — DailyActivitySimulator·UnknownPgPaymentRunner 공통")
    void snappedAmountsHaveNoWonUnit() {
        Random r = new Random(20260721);
        for (int i = 0; i < 20_000; i++) {
            int raw = 500 + r.nextInt(400_000);
            int daily = DailyActivitySimulator.snapAmount(raw, r);
            int pg = UnknownPgPaymentRunner.snap(raw, r);
            assertEquals(0, daily % 10, "결제 금액에 1원 단위가 있으면 안 된다: " + daily);
            assertEquals(0, pg % 10, "PG 결제 금액에 1원 단위가 있으면 안 된다: " + pg);
            assertTrue(daily > 0 && pg > 0);
        }
    }

    @Test
    @DisplayName("고액은 1000원 단위로, 소액은 100원 단위로 스냅된다")
    void highAmountsSnapToThousands() {
        Random r = new Random(1);
        int thousandsHits = 0;
        for (int i = 0; i < 5_000; i++) {
            int big = DailyActivitySimulator.snapAmount(80_000 + r.nextInt(200_000), r);
            if (big % 1000 == 0) thousandsHits++;
            int small = DailyActivitySimulator.snapAmount(1_000 + r.nextInt(2_000), r);
            assertEquals(0, small % 10, "소액도 1원 단위는 없다(100/10원 단위): " + small);
        }
        assertTrue(thousandsHits > 5_000 * 0.6, "고액은 1000원 단위가 우세해야 한다: " + thousandsHits);
    }
}
