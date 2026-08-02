package com.finntech.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>CI 입력 정규화</b> — 표기 차이가 신원을 가르지 않는다.
 *
 * <p>예전 산식은 받은 문자열을 그대로 이어 붙여 해시했다. 그래서 {@code 010-4814-0667} 과
 * {@code 01048140667} 이 서로 다른 CI가 됐다. 프론트가 숫자만 보내 왔기에 앱에서는 드러나지
 * 않았지만, CI가 계정 키가 된 뒤로는(tech_log §8-P) <b>같은 사람이 계정 두 개</b>가 될 수 있었다.
 *
 * <p>이 테스트가 지키는 것은 두 가지다. 하나는 <b>표기가 달라도 같은 CI</b>라는 새 성질,
 * 다른 하나는 <b>기존 CI가 안 바뀐다</b>는 것 — 후자가 깨지면 운영 데이터 전체가 신원을 잃는다.
 */
class CiNormalizationTest {

    private static final String NAME = "이승진";
    private static final String SOCIAL7 = "0507274";
    private static final String DIGITS = "01048140667";

    /**
     * 운영 DB(mydata_user PK)에 실제로 들어 있는 값이다. 이 상수가 이 테스트의 핵심 —
     * 정규화가 <b>기존 신원을 하나도 건드리지 않는다</b>는 것을 값으로 못박는다.
     */
    private static final String STORED_CI =
            "001a64d25c240f4f8cd52a442a7dc90079dea532122da57473f920cf33f0ef96";

    @Test
    @DisplayName("숫자만의 전화번호는 예전과 똑같은 CI를 낸다 — 기존 신원 불변")
    void 기존_CI가_바뀌지_않는다() {
        assertThat(Ci.of(NAME, SOCIAL7, DIGITS)).isEqualTo(STORED_CI);
    }

    @Test
    @DisplayName("하이픈·공백이 섞여도 같은 사람이다 — 예전에는 갈렸다")
    void 표기가_달라도_같은_CI() {
        assertThat(Ci.of(NAME, SOCIAL7, "010-4814-0667")).isEqualTo(STORED_CI);
        assertThat(Ci.of(NAME, SOCIAL7, "010 4814 0667")).isEqualTo(STORED_CI);
        assertThat(Ci.of(NAME, SOCIAL7, " 010-4814-0667 ")).isEqualTo(STORED_CI);
        assertThat(Ci.of("  " + NAME + " ", SOCIAL7, DIGITS)).isEqualTo(STORED_CI);
        assertThat(Ci.of(NAME, "050727-4", DIGITS)).isEqualTo(STORED_CI);
    }

    @Test
    @DisplayName("다른 사람은 여전히 다른 CI다 — 정규화가 신원을 뭉개지 않는다")
    void 다른_사람은_다르다() {
        assertThat(Ci.of("김승진", SOCIAL7, DIGITS)).isNotEqualTo(STORED_CI);
        assertThat(Ci.of(NAME, "0507273", DIGITS)).isNotEqualTo(STORED_CI);
        assertThat(Ci.of(NAME, SOCIAL7, "01048140668")).isNotEqualTo(STORED_CI);
    }

    @Test
    @DisplayName("null도 죽지 않는다 — 인증은 400을 내야지 500을 내면 안 된다")
    void null_입력() {
        assertThat(Ci.of(null, null, null)).hasSize(64);
        assertThat(Ci.of(NAME, SOCIAL7, null)).isNotEqualTo(STORED_CI);
    }
}
