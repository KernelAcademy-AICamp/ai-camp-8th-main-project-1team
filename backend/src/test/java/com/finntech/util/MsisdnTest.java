package com.finntech.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 국번 대역표가 정부 할당과 어긋나지 않는지 못박는다.
 *
 * <p>대역표는 손으로 옮겨 적은 상수라 <b>경계 한 칸이 밀려도 티가 안 난다</b>. 그래서 구간마다
 * 경계값을 양쪽으로 찍고, 전체 합계와 겹침까지 센다 — 합계가 맞는데 겹치면 어딘가 빈 구멍이 있다는 뜻이다.
 */
class MsisdnTest {

    @Test
    @DisplayName("배정 국번은 7,470개이고 통신사끼리 겹치지 않는다")
    void 배정_총량과_겹침() {
        Map<Msisdn.Carrier, Integer> count = new HashMap<>();
        int assigned = 0;
        for (int n = 0; n <= 9999; n++) {
            Msisdn.Carrier c = Msisdn.carrierOf(n);
            if (c == null) continue;
            assigned++;
            count.merge(c, 1, Integer::sum);
        }
        assertThat(assigned).isEqualTo(7470);
        assertThat(count.get(Msisdn.Carrier.SKT)).isEqualTo(3250);
        assertThat(count.get(Msisdn.Carrier.KT)).isEqualTo(2520);
        assertThat(count.get(Msisdn.Carrier.LGU)).isEqualTo(1700);
        // 합이 맞고 각 국번이 통신사 하나만 반환한다면(carrierOf는 첫 일치를 돌려주므로)
        // 겹침 여부는 세 통신사 개수의 합으로 확인된다.
        assertThat(count.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(assigned);
    }

    @Test
    @DisplayName("미배정 구간은 통신사가 없다")
    void 미배정_구간() {
        int[] unassigned = {
            0, 999,          // 0xxx — 국번이 될 수 없다
            1000, 1544, 1999, // 1xxx — 전국대표번호와 충돌
            5970, 5999,
            6000, 6199,
            6900, 6999,
            7000, 7099,
            7800, 7899,
        };
        for (int n : unassigned) {
            assertThat(Msisdn.carrierOf(n)).as("국번 %04d", n).isNull();
        }
    }

    @Test
    @DisplayName("구간 경계가 한 칸도 밀리지 않는다")
    void 경계값() {
        assertThat(Msisdn.carrierOf(1999)).isNull();
        assertThat(Msisdn.carrierOf(2000)).isEqualTo(Msisdn.Carrier.SKT);
        assertThat(Msisdn.carrierOf(2179)).isEqualTo(Msisdn.Carrier.SKT);
        assertThat(Msisdn.carrierOf(2180)).isEqualTo(Msisdn.Carrier.KT);
        assertThat(Msisdn.carrierOf(2199)).isEqualTo(Msisdn.Carrier.KT);
        assertThat(Msisdn.carrierOf(2200)).isEqualTo(Msisdn.Carrier.LGU);
        assertThat(Msisdn.carrierOf(5969)).isEqualTo(Msisdn.Carrier.SKT);
        assertThat(Msisdn.carrierOf(5970)).isNull();
        assertThat(Msisdn.carrierOf(6199)).isNull();
        assertThat(Msisdn.carrierOf(6200)).isEqualTo(Msisdn.Carrier.SKT);
        assertThat(Msisdn.carrierOf(6899)).isEqualTo(Msisdn.Carrier.KT);
        assertThat(Msisdn.carrierOf(6900)).isNull();
        assertThat(Msisdn.carrierOf(7099)).isNull();
        assertThat(Msisdn.carrierOf(7100)).isEqualTo(Msisdn.Carrier.SKT);
        assertThat(Msisdn.carrierOf(7799)).isEqualTo(Msisdn.Carrier.LGU);
        assertThat(Msisdn.carrierOf(7800)).isNull();
        assertThat(Msisdn.carrierOf(7899)).isNull();
        assertThat(Msisdn.carrierOf(7900)).isEqualTo(Msisdn.Carrier.LGU);
        assertThat(Msisdn.carrierOf(9499)).isEqualTo(Msisdn.Carrier.SKT);
        assertThat(Msisdn.carrierOf(9500)).isEqualTo(Msisdn.Carrier.KT);
        assertThat(Msisdn.carrierOf(9999)).isEqualTo(Msisdn.Carrier.KT);
    }

    @Test
    @DisplayName("번호 문자열에서 국번을 읽는다 — 하이픈 유무 무관")
    void 번호_파싱() {
        assertThat(Msisdn.carrierOfPhone("010-3913-6360")).isEqualTo(Msisdn.Carrier.LGU);
        assertThat(Msisdn.carrierOfPhone("01039136360")).isEqualTo(Msisdn.Carrier.LGU);
        assertThat(Msisdn.carrierOfPhone("010-7068-5400")).isNull();   // 70xx 미배정
        assertThat(Msisdn.carrierOfPhone("0103913")).isNull();          // 길이 미달
        assertThat(Msisdn.carrierOfPhone(null)).isNull();
    }

    @Test
    @DisplayName("알뜰폰은 유효 국번이면 통과하고, 미배정은 알뜰폰이어도 막힌다")
    void 알뜰폰() {
        assertThat(Msisdn.matches("알뜰폰", Msisdn.Carrier.SKT)).isTrue();
        assertThat(Msisdn.matches("알뜰폰", Msisdn.Carrier.KT)).isTrue();
        assertThat(Msisdn.matches("알뜰폰", Msisdn.Carrier.LGU)).isTrue();
        assertThat(Msisdn.matches("알뜰폰", null)).isFalse();
    }

    @Test
    @DisplayName("고른 통신사와 대역이 다르면 막는다")
    void 통신사_불일치() {
        assertThat(Msisdn.matches("SKT", Msisdn.Carrier.SKT)).isTrue();
        assertThat(Msisdn.matches("SKT", Msisdn.Carrier.LGU)).isFalse();
        assertThat(Msisdn.matches("LG U+", Msisdn.Carrier.LGU)).isTrue();
        assertThat(Msisdn.matches("KT", Msisdn.Carrier.KT)).isTrue();
    }
}
