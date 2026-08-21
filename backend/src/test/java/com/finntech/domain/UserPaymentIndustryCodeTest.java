package com.finntech.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>카드추천이 실사용자에게 답을 못 하고 있었다.</b>
 *
 * <p>카드 혜택 축은 중분류가 아니라 <b>업종코드</b>로 정해진다({@code IndustryCategoryMapper.cardAxisOf}).
 * 그런데 실 명세서에는 업종코드가 없어 적재기가 자리채움값 {@code 642004} 를 넣고, 그 코드는
 * 대조표에 <b>아예 없다</b>(카드축도 중분류도 {@code null}).
 *
 * <p>운영 실측(2026-08-21): 실사용자 결제 <b>1,579건 전부</b>가 {@code 642004} 였다. 즉 실사용자
 * 전원이 카드추천에서 축 없음으로 빠지고 있었다. 모델이 업종을 알아내면 그 코드를 여기 적어
 * 그 자리를 메운다.
 *
 * <p>여기서 잠그는 것은 <b>사실을 안 덮는다</b>는 것이다 — 제공자가 준 진짜 코드가 있으면
 * 추정으로 갈아 끼우지 않는다. 자리채움일 때만 채운다.
 */
class UserPaymentIndustryCodeTest {

    private UserPayment payment(String industryCode) {
        return new UserPayment("PAY-1", 1L, "CARD-1", 1L,
                LocalDateTime.now(), industryCode, null,
                10_000, "가맹점", "1234567890");
    }

    @Test
    @DisplayName("자리채움값이면 알아낸 코드로 갈아 끼운다 — 이것이 이 메서드의 목적이다")
    void 자리채움은_갈아_끼운다() {
        UserPayment p = payment(UserPayment.PLACEHOLDER_INDUSTRY);

        assertThat(p.learnIndustryCode("552101")).isTrue();
        assertThat(p.getKsicCode()).isEqualTo("552101");
    }

    /** 제공자가 준 코드는 사실이다. 추정이 사실을 덮으면 안 된다. */
    @Test
    @DisplayName("진짜 코드가 있으면 안 덮는다")
    void 사실은_안_덮는다() {
        UserPayment p = payment("521912");

        assertThat(p.learnIndustryCode("552101")).isFalse();
        assertThat(p.getKsicCode()).isEqualTo("521912");
    }

    @Test
    @DisplayName("줄 것이 없으면 아무 일도 안 한다")
    void 빈_값은_무시한다() {
        UserPayment p = payment(UserPayment.PLACEHOLDER_INDUSTRY);

        assertThat(p.learnIndustryCode(null)).isFalse();
        assertThat(p.learnIndustryCode("")).isFalse();
        assertThat(p.learnIndustryCode("   ")).isFalse();
        assertThat(p.getKsicCode()).isEqualTo(UserPayment.PLACEHOLDER_INDUSTRY);
    }

    /**
     * 적재기와 이 상수가 어긋나면 조용히 안 채워진다 — 두 곳이 같은 값을 알아야 한다.
     * (적재기는 제공자 모듈이라 여기서 직접 못 부른다. 값으로 잠근다.)
     */
    @Test
    @DisplayName("자리채움값은 적재기가 넣는 것과 같아야 한다")
    void 자리채움값이_적재기와_같다() {
        assertThat(UserPayment.PLACEHOLDER_INDUSTRY).isEqualTo("642004");
    }
}
