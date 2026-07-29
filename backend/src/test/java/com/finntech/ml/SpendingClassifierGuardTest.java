package com.finntech.ml;

import com.finntech.engine.IndustryCategoryMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카테고리 체계와 모델이 어긋났을 때 <b>조용히 망가지지 않는지</b> 본다.
 *
 * <p>{@code cat2}는 nominal 특징이고 전역 중요도 1위다. 모르는 이름이 오면 스코어러가
 * 기여 0으로 처리하는데, 그 자체는 합리적인 방어다 — 문제는 <b>모든 이름이 낯설 때</b>다.
 * 그때는 1위 특징이 통째로 죽은 채 확률이 계속 나오고, 크래시도 로그도 없어서 아무도 모른다.
 * 실제로 소비 카테고리를 52개 맥락에서 15개 중분류로 옮기면서 이 상황을 만들었고,
 * 이 테스트가 그 재발을 막는다.
 */
class SpendingClassifierGuardTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /** 실제 파일을 읽지 않고 원하는 중분류 집합만 내놓는 매퍼. */
    private IndustryCategoryMapper categoriesOf(Set<String> mids) {
        return new IndustryCategoryMapper(mapper) {
            @Override public Set<String> midCategories() { return mids; }
        };
    }

    @Test
    @DisplayName("모델의 cat2가 현재 체계와 전혀 안 맞으면 모델을 쓰지 않는다 — 규칙 baseline으로 간다")
    void refusesModelTrainedOnAnotherTaxonomy() {
        // 현재 배포된 모델은 옛 소비맥락 52종으로 학습돼 있다. 새 중분류만 아는 체계에서는
        // 한 이름도 안 겹치므로 모델을 거부해야 한다.
        SpendingClassifier clf = new SpendingClassifier(mapper, categoriesOf(
                Set.of("식비", "카페/간식", "편의점/잡화", "대형마트", "술/유흥", "쇼핑",
                        "취미/여가", "의료", "건강/피트니스", "주거/통신", "미용",
                        "교통/자동차", "여행/숙박", "생활", "카테고리없음")));

        assertThat(clf.isReady())
                .as("체계가 다른 모델은 규칙 baseline보다 나을 것이 없다")
                .isFalse();
    }

    @Test
    @DisplayName("체계를 모르면(빈 집합) 검사를 건너뛴다 — 대조표가 없다고 모델을 버리지는 않는다")
    void skipsCheckWhenTaxonomyUnknown() {
        SpendingClassifier clf = new SpendingClassifier(mapper, categoriesOf(Set.of()));
        assertThat(clf.isReady()).isTrue();
    }

    @Test
    @DisplayName("실제 대조표로는 지금 모델이 거부된다 — 재학습 전까지 규칙 baseline")
    void currentModelIsRejectedUntilRetrained() {
        SpendingClassifier clf = new SpendingClassifier(mapper, new IndustryCategoryMapper(mapper));
        assertThat(clf.isReady())
                .as("ML 재학습이 끝나면 이 단언을 true로 뒤집는다(ml/README.md 4단계)")
                .isFalse();
    }
}
