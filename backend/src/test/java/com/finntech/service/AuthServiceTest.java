package com.finntech.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 본인인증에서 출생연도·성별을 파생하는 순수 함수만 검증한다(외부 호출·DB 없음).
 *
 * <p>둘은 주민번호 앞 7자리의 <b>같은 한 글자</b>(성별세대코드)를 나눠 쓴다 — 세기와 성별이
 * 거기서 함께 나온다. 출생연도는 금융상품의 나이 자격에, 성별은 admin 의 행태 통계에만 쓴다.
 * 둘 다 정본이 수집항목으로 적은 값이다(`legal/privacy-policy.md` 33조).
 */
class AuthServiceTest {

    @Test
    void 성별세대코드가_세기를_정한다() {
        assertThat(AuthService.birthYearOf("9501011")).isEqualTo(1995);  // 1 → 1900년대
        assertThat(AuthService.birthYearOf("8712312")).isEqualTo(1987);  // 2 → 1900년대
        assertThat(AuthService.birthYearOf("0503073")).isEqualTo(2005);  // 3 → 2000년대
        assertThat(AuthService.birthYearOf("1011014")).isEqualTo(2010);  // 4 → 2000년대
        assertThat(AuthService.birthYearOf("9912315")).isEqualTo(1999);  // 5 → 1900년대(외국인)
        assertThat(AuthService.birthYearOf("0301018")).isEqualTo(2003);  // 8 → 2000년대(외국인)
        assertThat(AuthService.birthYearOf("9906019")).isEqualTo(1899);  // 9 → 1800년대
    }

    @Test
    void 형식이_어긋나면_null이라_쓰레기값을_저장하지_않는다() {
        assertThat(AuthService.birthYearOf(null)).isNull();
        assertThat(AuthService.birthYearOf("")).isNull();
        assertThat(AuthService.birthYearOf("950101")).isNull();      // 6자리
        assertThat(AuthService.birthYearOf("95010111")).isNull();    // 8자리
        assertThat(AuthService.birthYearOf("95010a1")).isNull();     // 숫자 아님
    }

    @Test
    void 앞뒤_공백은_허용한다() {
        assertThat(AuthService.birthYearOf(" 9501011 ")).isEqualTo(1995);
        assertThat(AuthService.genderOf(" 9501011 ")).isEqualTo("MALE");
    }

    @Test
    void 성별세대코드의_홀짝이_성별을_정한다() {
        assertThat(AuthService.genderOf("9501011")).isEqualTo("MALE");     // 1 내국인 남
        assertThat(AuthService.genderOf("8712312")).isEqualTo("FEMALE");   // 2 내국인 여
        assertThat(AuthService.genderOf("0503073")).isEqualTo("MALE");     // 3 내국인 남
        assertThat(AuthService.genderOf("1011014")).isEqualTo("FEMALE");   // 4 내국인 여
        assertThat(AuthService.genderOf("9912315")).isEqualTo("MALE");     // 5 외국인 남
        assertThat(AuthService.genderOf("0301018")).isEqualTo("FEMALE");   // 8 외국인 여
        assertThat(AuthService.genderOf("9906019")).isEqualTo("MALE");     // 9 1800년대 남
        assertThat(AuthService.genderOf("9906010")).isEqualTo("FEMALE");   // 0 1800년대 여
    }

    @Test
    void 성별도_형식이_어긋나면_null이다() {
        assertThat(AuthService.genderOf(null)).isNull();
        assertThat(AuthService.genderOf("950101")).isNull();
        assertThat(AuthService.genderOf("95010a1")).isNull();
    }
}
