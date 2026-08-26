package com.finntech.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>알 수 없는 셋은 서로 다른 사실이다</b> — 섞으면 어느 하나가 조용히 틀린다.
 *
 * <pre>
 *   카테고리없음  아직 못 했다        나중에 알 수 있다   총액 O · 카테고리합 O · 정리목록 O
 *   기타          다 물어봤는데 모른다  실재 가맹점이다     총액 O · 카테고리합 O · 정리목록 X
 *   간편결제      물어볼 대상이 아니다  영원히 알 수 없다   총액 O · 카테고리합 X · 정리목록 X
 * </pre>
 *
 * <p><b>왜 이 시험이 필요한가.</b> 셋을 섞어 두었더니 결제대행사 179건 중 142건에 카테고리가
 * 붙었고, {@code NICE_통신판매} 79건이 <b>쇼핑</b>으로 집계돼 낭비 판정까지 받았다
 * (2026-08-26 운영 실측). 무엇을 샀는지 모르는 돈이 쇼핑 지출이 됐다.
 */
class UnknownCategoriesTest {

    /** 셋 다 <b>판정의 재료가 아니다</b> — 낭비·절약 후보에서 빠진다. */
    @Test
    @DisplayName("모르는 셋은 모두 판정에서 빠진다")
    void 판정에서_빠진다() {
        assertThat(IndustryCategoryMapper.isUnknown(IndustryCategoryMapper.UNCLASSIFIED)).isTrue();
        assertThat(IndustryCategoryMapper.isUnknown(IndustryCategoryMapper.OTHER)).isTrue();
        assertThat(IndustryCategoryMapper.isUnknown(IndustryCategoryMapper.SIMPLE_PAY)).isTrue();
        assertThat(IndustryCategoryMapper.isUnknown("식비")).isFalse();
    }

    /**
     * <b>카테고리 합에서 빠지는 것은 간편결제뿐이다.</b>
     *
     * <p>{@code 기타} 는 <b>실재 가맹점의 소비</b>다 — 어디에 썼는지만 모를 뿐이라 카테고리
     * 합에는 남는다. {@code 간편결제} 는 무엇을 샀는지조차 모르므로 어느 칸에도 못 넣는다.
     */
    @Test
    @DisplayName("카테고리 합에서 빠지는 것은 간편결제뿐")
    void 카테고리합에서_빠지는_것() {
        assertThat(IndustryCategoryMapper.isOutsideCategories(IndustryCategoryMapper.SIMPLE_PAY))
                .isTrue();
        assertThat(IndustryCategoryMapper.isOutsideCategories(IndustryCategoryMapper.OTHER))
                .as("기타는 실재 가맹점의 소비다 — 카테고리 합에 남는다").isFalse();
        assertThat(IndustryCategoryMapper.isOutsideCategories(IndustryCategoryMapper.UNCLASSIFIED))
                .isFalse();
        assertThat(IndustryCategoryMapper.isOutsideCategories("식비")).isFalse();
    }

    /**
     * <b>사용자가 고를 수 있는 카테고리에 간편결제는 없다.</b>
     *
     * <p>{@code confirm} 이 이 목록으로 검증한다. 여기 있으면 사용자가 멀쩡한 소비를
     * '간편결제'로 바꿀 수 있고, 그러면 그 돈이 카테고리 합에서 통째로 빠진다.
     */
    @Test
    @DisplayName("사용자는 간편결제를 고를 수 없다")
    void 사용자가_고를_수_없다() {
        var mapper = new IndustryCategoryMapper(new tools.jackson.databind.ObjectMapper());

        assertThat(mapper.midCategories())
                .as("업종표에서 유도되지 않으므로 목록에 없어야 한다")
                .doesNotContain(IndustryCategoryMapper.SIMPLE_PAY)
                .doesNotContain(IndustryCategoryMapper.OTHER)
                .doesNotContain(IndustryCategoryMapper.UNCLASSIFIED);
        assertThat(mapper.midCategories()).contains("식비", "교통/자동차");
    }
}
