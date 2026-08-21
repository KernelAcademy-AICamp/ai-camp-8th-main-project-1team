package com.finntech.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>"모름"은 추정이 아니다.</b>
 *
 * <p>모델이 답을 못 준 것을 {@code 카테고리없음} 이라는 <i>값</i>으로 적으면 둘이 망가진다
 * (2026-08-21 실측).
 *
 * <ul>
 *   <li><b>집계</b> — 미분류를 {@code category2_source='NONE'} 으로 세면 이것이 분류된 것으로
 *       잡힌다. 라진우가 그 기준으로 2건이었는데 실제 미분류는 19건(372,961원)이었다.</li>
 *   <li><b>화면</b> — {@code OnboardingController} 가 "확정이 비었는데 추정이 있다"를
 *       <i>AI 추정</i> 배지로 보여준다. 값이 '카테고리없음'인데 AI가 추정했다고 표시된다.</li>
 * </ul>
 */
class UnknownIsNotAGuessTest {

    private UserPayment payment() {
        return new UserPayment("PAY-1", 1L, "CARD-1", 1L,
                LocalDateTime.now(), "552101", null, 10_000, "어떤 가게", "1234567890");
    }

    @Test
    @DisplayName("모름은 안 적는다 — 이것이 이 수정의 이유다")
    void 모름은_안_적는다() {
        UserPayment p = payment();

        p.suggestCategory2("카테고리없음", "LLM");

        assertThat(p.getCategory2Llm()).as("추정 칸이 비어 있어야 집계가 맞는다").isNull();
        assertThat(p.getCategory2Source()).as("출처도 안 바뀐다").isNotEqualTo("LLM");
    }

    @Test
    @DisplayName("종결 표시('기타')도 추정이 아니다")
    void 기타도_안_적는다() {
        UserPayment p = payment();

        p.suggestCategory2("기타", "LLM");

        assertThat(p.getCategory2Llm()).isNull();
    }

    @Test
    @DisplayName("빈 값·null 도 안 적는다")
    void 빈_값은_안_적는다() {
        UserPayment p = payment();

        p.suggestCategory2(null, "LLM");
        p.suggestCategory2("", "LLM");
        p.suggestCategory2("   ", "LLM");

        assertThat(p.getCategory2Llm()).isNull();
    }

    @Test
    @DisplayName("진짜 추정은 그대로 적힌다 — 막는 것은 '모름'뿐이다")
    void 진짜_추정은_적힌다() {
        UserPayment p = payment();

        p.suggestCategory2("카페/간식", "LLM");

        assertThat(p.getCategory2Llm()).isEqualTo("카페/간식");
        assertThat(p.getCategory2Source()).isEqualTo("LLM");
    }
}
