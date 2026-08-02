package com.finntech.mydata.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>CI 산식은 본체와 한 글자도 다르면 안 된다.</b>
 *
 * <p>두 모듈에 같은 클래스가 두 벌 있다(공유 모듈이 없어서다). 한쪽만 고치면 조회가 조용히
 * 전부 빈 결과가 된다 — 예외도 안 나고 로그도 안 남는다. 그래서 <b>기대값을 상수로 박아</b>
 * 양쪽 테스트가 같은 숫자를 지키게 한다. 한쪽이 어긋나는 순간 그쪽 CI 테스트가 깨진다.
 *
 * <p>{@code backend/src/test/java/com/finntech/util/CiNormalizationTest} 와 짝이다.
 */
class CiNormalizationTest {

    private static final String NAME = "이승진";
    private static final String SOCIAL7 = "0507274";
    private static final String DIGITS = "01048140667";

    /** 본체 테스트와 <b>같은 값</b>이어야 한다. 운영 mydata_user PK에 실제로 있는 값이다. */
    private static final String STORED_CI =
            "001a64d25c240f4f8cd52a442a7dc90079dea532122da57473f920cf33f0ef96";

    @Test
    @DisplayName("본체와 같은 CI를 낸다 — 두 벌의 산식이 어긋나면 여기서 깨진다")
    void 본체와_같다() {
        assertThat(Ci.of(NAME, SOCIAL7, DIGITS)).isEqualTo(STORED_CI);
    }

    @Test
    @DisplayName("표기 차이는 신원을 가르지 않는다")
    void 정규화() {
        assertThat(Ci.of(NAME, SOCIAL7, "010-4814-0667")).isEqualTo(STORED_CI);
        assertThat(Ci.of(" " + NAME, "050727-4", " 010 4814 0667 ")).isEqualTo(STORED_CI);
    }
}
