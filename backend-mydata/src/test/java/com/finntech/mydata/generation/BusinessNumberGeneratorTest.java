package com.finntech.mydata.generation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 사업자등록번호 생성기 검증. 사용자가 제시한 검증식을 <b>참조 구현</b>으로 그대로 넣어,
 * 생성기가 만든 번호가 그 공식에 100% 부합하는지 교차 검증한다.
 */
class BusinessNumberGeneratorTest {

    /** 사용자 제시 검증식 그대로(하이픈 없는 10자리 가정). */
    private static boolean refValid(String businessNumber) {
        if (businessNumber == null || businessNumber.length() != 10) return false;
        int[] weights = {1, 3, 7, 1, 3, 7, 1, 3, 5};
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += Character.getNumericValue(businessNumber.charAt(i)) * weights[i];
        }
        sum += (Character.getNumericValue(businessNumber.charAt(8)) * 5) / 10;
        int checkDigit = (10 - (sum % 10)) % 10;
        return checkDigit == Character.getNumericValue(businessNumber.charAt(9));
    }

    @Test
    @DisplayName("생성한 모든 번호가 검증식(참조 구현)을 통과한다")
    void generatedNumbersAreValid() {
        for (long seed = 0; seed < 20_000; seed++) {
            String biz = BusinessNumberGenerator.generate(seed);
            assertEquals(10, biz.length(), "10자리여야 한다: " + biz);
            assertTrue(refValid(biz), "참조 검증식 통과 실패: " + biz + " (seed=" + seed + ")");
            assertTrue(BusinessNumberGenerator.isValid(biz), "isValid 실패: " + biz);
        }
    }

    @Test
    @DisplayName("같은 seed는 항상 같은 번호(결정론)")
    void deterministic() {
        for (long seed : new long[]{0, 1, 42, 20260721L, -1, Long.MAX_VALUE}) {
            assertEquals(BusinessNumberGenerator.generate(seed), BusinessNumberGenerator.generate(seed),
                    "seed=" + seed);
        }
    }

    @Test
    @DisplayName("자릿값 범위: XXX∈[200,899], YY∈[1,79], ZZZZ∈[1,9999]")
    void digitRanges() {
        for (long seed = 0; seed < 20_000; seed++) {
            String biz = BusinessNumberGenerator.generate(seed);
            int xxx = Integer.parseInt(biz.substring(0, 3));
            int yy = Integer.parseInt(biz.substring(3, 5));
            int zzzz = Integer.parseInt(biz.substring(5, 9));
            assertTrue(xxx >= 200 && xxx <= 899, "XXX 범위 위반: " + xxx);
            assertTrue(yy >= 1 && yy <= 79, "YY 범위 위반: " + yy);
            assertTrue(zzzz >= 1 && zzzz <= 9999, "ZZZZ 범위 위반: " + zzzz);
        }
    }

    @Test
    @DisplayName("알려진 무효 번호 1234567890은 invalid (검증숫자 1 ≠ 0)")
    void knownInvalid() {
        assertFalse(BusinessNumberGenerator.isValid("1234567890"));
        assertFalse(refValid("1234567890"));
    }

    @Test
    @DisplayName("format은 XXX-YY-ZZZZA 형태이고, isValid는 하이픈을 허용한다")
    void formatAndHyphenTolerant() {
        String biz = BusinessNumberGenerator.generate(20260721L);
        String formatted = BusinessNumberGenerator.format(biz);
        assertEquals(biz.substring(0, 3) + "-" + biz.substring(3, 5) + "-" + biz.substring(5), formatted);
        assertEquals(12, formatted.length());
        assertTrue(BusinessNumberGenerator.isValid(formatted), "하이픈 포함해도 유효해야 한다: " + formatted);
    }

    @Test
    @DisplayName("isValid는 길이·형식 불량을 거른다")
    void isValidRejectsBad() {
        assertFalse(BusinessNumberGenerator.isValid(null));
        assertFalse(BusinessNumberGenerator.isValid(""));
        assertFalse(BusinessNumberGenerator.isValid("123"));
        assertFalse(BusinessNumberGenerator.isValid("12345678901"));  // 11자리
        assertFalse(BusinessNumberGenerator.isValid("abcdefghij"));
    }
}
