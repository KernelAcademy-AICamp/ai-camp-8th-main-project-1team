package com.finntech.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 금융회사명 정규화 — 당행 우대조건(M6 ③④) 판정의 바닥이다.
 * 상품명은 금감원 실응답 표기를, 사용자 쪽은 마이데이터 표기를 그대로 썼다.
 */
class FinancialCompanyNamesTest {

    @Test
    void 법인_표기와_업종어를_떼어낸다() {
        assertThat(FinancialCompanyNames.normalize("농협은행주식회사")).isEqualTo("농협");
        assertThat(FinancialCompanyNames.normalize("주식회사 하나은행")).isEqualTo("하나");
        assertThat(FinancialCompanyNames.normalize("주식회사 케이뱅크")).isEqualTo("케이");
        assertThat(FinancialCompanyNames.normalize("우리카드")).isEqualTo("우리");
    }

    @Test
    void 약칭은_그룹명으로_옮긴다() {
        assertThat(FinancialCompanyNames.normalize("KB국민카드")).isEqualTo("국민");
        assertThat(FinancialCompanyNames.normalize("NH농협은행")).isEqualTo("농협");
        assertThat(FinancialCompanyNames.normalize("중소기업은행")).isEqualTo("기업");
        assertThat(FinancialCompanyNames.normalize("한국스탠다드차타드은행")).isEqualTo("스탠다드차타드");
    }

    /** 은행 상품의 당행 조건을 계열 카드로 채우는 것이 실제 표기다 — 둘을 같은 그룹으로 봐야 한다. */
    @Test
    void 은행과_계열_카드사는_같은_그룹이다() {
        assertThat(FinancialCompanyNames.sameGroup("국민은행", "KB국민카드")).isTrue();
        assertThat(FinancialCompanyNames.sameGroup("우리은행", "우리카드")).isTrue();
        assertThat(FinancialCompanyNames.sameGroup("농협은행주식회사", "NH농협카드")).isTrue();
    }

    @Test
    void 다른_그룹은_다르다고_말한다() {
        assertThat(FinancialCompanyNames.sameGroup("국민은행", "신한카드")).isFalse();
        assertThat(FinancialCompanyNames.sameGroup("우리은행", "삼성카드")).isFalse();
    }

    /** 알아볼 수 없으면 `아니다`가 아니라 `모른다` — 엉뚱한 미충족을 확정하지 않기 위해서다. */
    @Test
    void 알아볼_수_없으면_null이다() {
        assertThat(FinancialCompanyNames.sameGroup("국민은행", "  ")).isNull();
        assertThat(FinancialCompanyNames.sameGroup(null, "우리카드")).isNull();
        assertThat(FinancialCompanyNames.sameGroup("은행", "우리카드")).isNull();   // 업종어만 남는다
    }

    @Test
    void 목록_안에_있는지_본다() {
        assertThat(FinancialCompanyNames.containsGroup(List.of("신한카드", "우리카드"), "우리은행")).isTrue();
        assertThat(FinancialCompanyNames.containsGroup(List.of("신한카드"), "우리은행")).isFalse();
    }

    /**
     * <b>목록이 비면 `없다`이지 `모른다`가 아니다.</b> 재료를 못 받았다는 사실은
     * {@code Preferential.known()}이 들고 있고, 여기까지 왔으면 해당 금융사가 하나도 없다는 뜻이다.
     */
    @Test
    void 목록이_비면_없다고_말한다() {
        assertThat(FinancialCompanyNames.containsGroup(List.of(), "우리은행")).isFalse();
    }

    @Test
    void 찾는_이름을_못_알아보면_null이다() {
        assertThat(FinancialCompanyNames.containsGroup(List.of("우리카드"), "")).isNull();
    }
}
