package com.finntech.mydata.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 생성기가 실존하지 않는 국번을 만들지 못하게 못박는다.
 *
 * <p>총량 7,470은 본체의 {@code com.finntech.util.MsisdnTest}와 같은 수여야 한다 —
 * 두 모듈이 같은 표를 한 벌씩 들고 있으므로, 한쪽만 고치면 이 숫자가 갈라진다.
 */
class MsisdnTest {

    @Test
    @DisplayName("배정 국번은 7,470개다 — 본체 표와 같은 수여야 한다")
    void 총량() {
        assertThat(Msisdn.assignedCount()).isEqualTo(7470);
        int count = 0;
        for (int n = 0; n <= 9999; n++) if (Msisdn.isAssigned(n)) count++;
        assertThat(count).isEqualTo(7470);
    }

    @Test
    @DisplayName("미배정 구간과 경계")
    void 경계값() {
        int[] unassigned = {0, 999, 1000, 1999, 5970, 5999, 6000, 6199, 6900, 6999,
                            7000, 7099, 7800, 7899};
        for (int n : unassigned) assertThat(Msisdn.isAssigned(n)).as("국번 %04d", n).isFalse();

        int[] assigned = {2000, 5969, 6200, 6899, 7100, 7799, 7900, 9999};
        for (int n : assigned) assertThat(Msisdn.isAssigned(n)).as("국번 %04d", n).isTrue();
    }

    @Test
    @DisplayName("추첨은 언제나 배정 대역 안이고, 구간 크기에 비례해 고르게 나온다")
    void 추첨() {
        Random rnd = new Random(42);
        int[] hit = new int[4];
        for (int i = 0; i < 60_000; i++) {
            int e = Msisdn.randomAssigned(rnd);
            assertThat(Msisdn.isAssigned(e)).as("뽑힌 국번 %04d", e).isTrue();
            if (e <= 5969) hit[0]++;
            else if (e <= 6899) hit[1]++;
            else if (e <= 7799) hit[2]++;
            else hit[3]++;
        }
        // 구간 비중 3970/700/700/2100 (÷7470). 표본 6만이면 ±2%면 충분히 좁다.
        assertThat(hit[0] / 60_000.0).isCloseTo(3970 / 7470.0, org.assertj.core.data.Offset.offset(0.02));
        assertThat(hit[3] / 60_000.0).isCloseTo(2100 / 7470.0, org.assertj.core.data.Offset.offset(0.02));
    }
}
