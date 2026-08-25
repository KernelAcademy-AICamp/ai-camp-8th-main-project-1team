package com.finntech.service;

import com.finntech.engine.IndustryCategoryMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>최초 연동에서만 묶어 묻는다.</b>
 *
 * <p>평소에는 한 곳씩이다({@link IndustryPrompt#of}) — 묶으면 모델이 중간에서 흘리기 때문이다.
 * 그런데 최초 연동은 <b>사람이 로딩 화면 앞에서 기다린다</b>. 110종을 한 곳씩 물으면 호출
 * 예산(분당 40)에 걸려 3분이고, 40곳씩 묶으면 세 번이라 30초다. 그 한 자리에서만 예외를 둔다.
 *
 * <p>여기서 잠그는 것은 셋이다 — ① 축(중분류)은 여전히 안 보여 주는가 ② 번호가 이름으로
 * 되돌아오는가 ③ 한 줄이 깨져도 나머지를 건지는가.
 */
class BulkPromptTest {

    private final IndustryCategoryMapper mapper = new IndustryCategoryMapper(new ObjectMapper());
    private final String list = IndustryPrompt.industryList(mapper);

    private final List<String> three = List.of("스타벅스 포항공대점", "GS25 강남역점", "CGV");

    @Test
    @DisplayName("묶어 물어도 중분류는 안 나간다 — 축은 우리 표가 정한다")
    void 중분류가_안_나간다() {
        String prompt = IndustryPrompt.ofMany(three, list);

        for (String mid : mapper.midCategories()) {
            assertThat(prompt).as("중분류 '%s' 가 보이면 모델이 축을 보고 답한다", mid)
                    .doesNotContain("[" + mid + "]");
        }
    }

    @Test
    @DisplayName("가맹점이 번호와 함께 전부 담긴다")
    void 번호와_함께_담긴다() {
        String prompt = IndustryPrompt.ofMany(three, list);

        assertThat(prompt).contains("1. 스타벅스 포항공대점")
                .contains("2. GS25 강남역점")
                .contains("3. CGV");
        assertThat(prompt).as("몇 곳인지 두 번 말한다 — 빠뜨리지 말라는 뜻").contains("3개");
    }

    /** 실측으로 얻은 규칙 여섯 줄은 묶음에서도 그대로다 — 그것이 정확도를 만든 근거다. */
    @Test
    @DisplayName("한 곳씩 묻던 규칙을 그대로 가져간다")
    void 규칙을_그대로_가져간다() {
        String prompt = IndustryPrompt.ofMany(three, list);

        assertThat(prompt).contains("가장 가까운 업종");
        assertThat(prompt).contains("해외 가맹점도 마찬가지");
        assertThat(prompt).contains("결제대행사 상호");
        assertThat(prompt).contains("한 브랜드의 자체 결제 수단");
        assertThat(prompt).contains("글자 그대로");
    }

    @Test
    @DisplayName("번호 답을 자리로 되돌린다")
    void 번호를_자리로_되돌린다() {
        Map<Integer, String> got = IndustryPrompt.pickMany("""
                1. 커피 전문점
                2. 체인화 편의점
                3. 영화관 운영업
                """, mapper);

        assertThat(got).containsEntry(0, "커피 전문점")
                .containsEntry(1, "체인화 편의점")
                .containsEntry(2, "영화관 운영업");
    }

    /**
     * <b>한 줄이 깨졌다고 묶음을 버리면 40곳이 날아간다.</b> 묶어 묻기의 위험이 이것이라
     * 파서가 그 자리에서 막는다.
     */
    @Test
    @DisplayName("깨진 줄은 건너뛰고 나머지를 건진다")
    void 깨진_줄은_건너뛴다() {
        Map<Integer, String> got = IndustryPrompt.pickMany("""
                1. 커피 전문점
                여기서 모델이 딴소리를 한다
                3. 영화관 운영업
                4. 우주선 정비업
                """, mapper);

        assertThat(got).containsEntry(0, "커피 전문점").containsEntry(2, "영화관 운영업");
        assertThat(got).as("목록 밖의 답은 안 담는다").doesNotContainKey(3);
        assertThat(got).as("서식이 아닌 줄은 건너뛴다").hasSize(2);
    }

    @Test
    @DisplayName("번호 표기가 달라도 받는다")
    void 번호_표기가_달라도() {
        Map<Integer, String> got = IndustryPrompt.pickMany("""
                1) 커피 전문점
                2 - 체인화 편의점
                3: 영화관 운영업
                """, mapper);

        assertThat(got).hasSize(3).containsEntry(0, "커피 전문점").containsEntry(2, "영화관 운영업");
    }

    @Test
    @DisplayName("빈 답·null 은 빈 지도다")
    void 빈_답은_빈_지도() {
        assertThat(IndustryPrompt.pickMany(null, mapper)).isEmpty();
        assertThat(IndustryPrompt.pickMany("", mapper)).isEmpty();
        assertThat(IndustryPrompt.pickMany("모름", mapper)).isEmpty();
    }
}
