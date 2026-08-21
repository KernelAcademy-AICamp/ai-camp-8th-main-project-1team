package com.finntech.service;

import com.finntech.engine.IndustryCategoryMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>프롬프트가 우리 축을 알려 주면 안 된다.</b>
 *
 * <p>예전 프롬프트는 업종을 {@code [카페/간식] 커피 전문점 · 제과점업} 처럼 중분류로 묶어
 * 보여줬다. 그러면 모델이 업종이 아니라 <b>축</b>을 보고 답하고, 실제로 상위 모델의 오답
 * 대부분이 거기서 나왔다(스타필드 → '대형 마트'). 마스터 §4 원칙 1 은 축 배정을 우리 표가
 * 한다고 정하는데 프롬프트가 그것을 어기고 있었다.
 *
 * <p>여기서 잠그는 것은 셋이다 — ① 중분류가 프롬프트에 안 나가는가 ② 가맹점명을 앞뒤로
 * 두 번 말하는가(목록 5,600자에 묻히지 않게) ③ 목록 밖의 답을 걸러 내는가.
 */
class IndustryPromptTest {

    private final IndustryCategoryMapper mapper = new IndustryCategoryMapper(new ObjectMapper());
    private final String list = IndustryPrompt.industryList(mapper);

    @Test
    @DisplayName("업종 목록에 중분류 이름이 섞이지 않는다 — 이것이 이 클래스의 존재 이유다")
    void 중분류가_안_나간다() {
        String prompt = IndustryPrompt.of("스타벅스 포항공대점", list);

        for (String mid : mapper.midCategories()) {
            assertThat(prompt).as("중분류 '%s' 가 프롬프트에 있으면 모델이 축을 보고 답한다", mid)
                    .doesNotContain("[" + mid + "]");
        }
        assertThat(prompt).doesNotContain("소비 분류").doesNotContain("대괄호");
    }

    @Test
    @DisplayName("업종 이름은 그대로 담긴다 — 목록이 비면 모델이 아무 말이나 한다")
    void 업종은_담긴다() {
        assertThat(list).contains("커피 전문점").contains("체인화 편의점").contains("택시 운송업");
        assertThat(list.split(", ")).hasSizeGreaterThan(300);
    }

    /** 목록이 5,600자라 가맹점명이 한 번만 나오면 그 안에 묻힌다. */
    @Test
    @DisplayName("가맹점명을 앞뒤로 두 번 말한다")
    void 가맹점명을_두_번_말한다() {
        String prompt = IndustryPrompt.of("올리브영 홍대점", list);

        assertThat(prompt.split("올리브영 홍대점", -1).length - 1).isEqualTo(2);
        assertThat(prompt.indexOf("올리브영 홍대점"))
                .as("첫 번째는 목록보다 앞에").isLessThan(prompt.indexOf(list));
        assertThat(prompt.lastIndexOf("올리브영 홍대점"))
                .as("두 번째는 목록보다 뒤에").isGreaterThan(prompt.indexOf(list));
    }

    @Test
    @DisplayName("한 번에 한 가맹점만 묻는다 — 번호 목록도 JSON 요구도 없다")
    void 한_곳만_묻는다() {
        String prompt = IndustryPrompt.of("GS25 강남역점", list);

        assertThat(prompt).doesNotContain("JSON").doesNotContain("각 가맹점");
        assertThat(prompt).contains("단답");
    }

    @Test
    @DisplayName("목록에 있는 이름을 그대로 답하면 그대로 받는다")
    void 그대로_답하면_받는다() {
        assertThat(IndustryPrompt.pickIndustry("커피 전문점", mapper)).isEqualTo("커피 전문점");
    }

    @Test
    @DisplayName("군말이 붙어도 업종 이름을 뽑아낸다")
    void 군말을_걷어낸다() {
        assertThat(IndustryPrompt.pickIndustry("**커피 전문점**", mapper)).isEqualTo("커피 전문점");
        assertThat(IndustryPrompt.pickIndustry("답: 체인화 편의점 입니다.", mapper))
                .isEqualTo("체인화 편의점");
    }

    /**
     * 짧은 이름이 긴 이름 안에 들어 있는 일이 흔하다. 짧은 쪽을 먼저 집으면 엉뚱한 업종이
     * 되므로 <b>가장 긴 것</b>을 고른다.
     */
    @Test
    @DisplayName("이름이 겹치면 더 긴 쪽을 고른다")
    void 겹치면_긴_쪽() {
        String answer = "기타 대형 종합 소매업";
        assertThat(IndustryPrompt.pickIndustry(answer, mapper)).isEqualTo(answer);
    }

    @Test
    @DisplayName("목록 밖의 말은 버린다 — 지어낸 업종이 원장에 들어가면 안 된다")
    void 목록_밖은_버린다() {
        assertThat(IndustryPrompt.pickIndustry("우주선 정비업", mapper)).isNull();
        assertThat(IndustryPrompt.pickIndustry("모름", mapper)).isNull();
        assertThat(IndustryPrompt.pickIndustry("", mapper)).isNull();
        assertThat(IndustryPrompt.pickIndustry(null, mapper)).isNull();
    }

    /** 중분류를 곧장 답해도 받지 않는다 — 축은 우리 표가 정한다. */
    @Test
    @DisplayName("중분류를 답하면 버린다")
    void 중분류_답은_버린다() {
        assertThat(IndustryPrompt.pickIndustry("카페/간식", mapper)).isNull();
        assertThat(IndustryPrompt.pickIndustry("대형마트", mapper)).isNull();
    }
}
