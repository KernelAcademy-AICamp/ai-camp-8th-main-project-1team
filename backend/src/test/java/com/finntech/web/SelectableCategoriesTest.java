package com.finntech.web;

import com.finntech.domain.Category;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.CategoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>화면이 고르는 목록과 서버가 받는 목록은 같아야 한다.</b>
 *
 * <h2>왜 시험으로 못박나 — 한 번 갈렸다</h2>
 *
 * <p>{@code /api/categories} 가 {@code category} 표를 <b>그대로</b> 내보내고 있었다. 그 표에는
 * <i>모르는 칸</i>도 행으로 들어 있다 — {@code 카테고리없음}·{@code 기타}·{@code 간편결제}.
 * 그래서 소비내역 편집 시트에 셋이 다 떠서 <b>사용자가 멀쩡한 소비를 "모름"으로 바꿀 수</b>
 * 있었고, {@code 간편결제} 행을 만들자 하나 더 늘었다(2026-08-26 화면 확인).
 *
 * <p>서버는 {@code confirm} 에서 {@code midCategories()} 로 검증하고 있어 눌러도 400 이 났다.
 * <b>목록에 보이는데 눌리지 않는 것은 고쳐진 것이 아니다</b> — 사용자는 왜 안 되는지 모른다.
 *
 * <p>이 목록을 쓰는 화면이 다섯이다(소비 기록·소비내역 편집·목표·챌린지·순위). 한 곳에서
 * 좁혀야 다섯이 함께 맞는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SelectableCategoriesTest {

    @Autowired ApiController api;
    @Autowired CategoryRepository categories;

    @Test
    @DisplayName("모르는 칸은 고를 수 없다")
    void 모르는_칸은_안_준다() {
        // 표에는 있다 — 결제가 그 값을 들고 있으므로 행 자체는 있어야 한다.
        for (String unknown : List.of(IndustryCategoryMapper.UNCLASSIFIED,
                IndustryCategoryMapper.OTHER, IndustryCategoryMapper.SIMPLE_PAY)) {
            if (categories.findByCode(unknown).isEmpty()) {
                categories.save(new Category(unknown, unknown));
            }
        }
        categories.findByCode("식비").orElseGet(() -> categories.save(new Category("식비", "식비")));

        List<String> codes = api.categories().stream().map(Category::getCode).toList();

        assertThat(codes)
                .as("모르는 칸을 고르게 하면 멀쩡한 소비가 '모름'이 된다")
                .doesNotContain(IndustryCategoryMapper.UNCLASSIFIED)
                .doesNotContain(IndustryCategoryMapper.OTHER)
                .doesNotContain(IndustryCategoryMapper.SIMPLE_PAY);
        assertThat(codes).contains("식비");
    }

    /**
     * <b>목록과 검증이 갈리면 안 된다.</b> 화면이 준 것을 서버가 거절하면 사용자는 이유를 모른다.
     *
     * <p>표에 <b>무엇이 더 들어 있든</b> 성립해야 한다. 처음에는 "모르는 칸을 뺀다"로 적었는데
     * 그것은 <i>아는 쓰레기</i>만 막는다 — 다른 시험이 남긴 가짜 행 하나가 목록에 그대로
     * 새어 나와 이 시험이 깨졌다(CI, 2026-08-26). 시험 순서에 따라 붙었다 떨어졌다 했다.
     * 그래서 <b>없는 것을 빼는</b> 대신 <b>있는 것만 넣는</b> 쪽으로 바꿨고, 여기서도 가짜 행을
     * 직접 심어 그 성질을 못박는다.
     */
    @Test
    @DisplayName("목록에 있는 것은 서버가 받는다")
    void 목록과_검증이_같다() {
        var mapper = new IndustryCategoryMapper(new tools.jackson.databind.ObjectMapper());

        // 표에 중분류가 아닌 행이 섞여도 목록에 새면 안 된다.
        String junk = "시험용_없는칸";
        categories.findByCode(junk).orElseGet(() -> categories.save(new Category(junk, junk)));

        List<String> codes = api.categories().stream().map(Category::getCode).toList();
        assertThat(codes).as("중분류가 아닌 행이 선택지에 샜다").doesNotContain(junk);

        for (String code : codes) {
            assertThat(mapper.midCategories())
                    .as("'%s' 는 목록에 있는데 confirm 이 400 을 낸다", code)
                    .contains(code);
        }
    }
}
