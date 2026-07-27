package com.finntech.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 본인인증에서 출생연도를 파생하는 순수 함수만 검증한다(외부 호출·DB 없음).
 * 출생연도는 금융상품의 나이 자격(`만 19세~만 34세` 등)을 맞춰 보는 용도로만 쓴다.
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
    }
}
