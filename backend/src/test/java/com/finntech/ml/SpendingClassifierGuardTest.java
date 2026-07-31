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
        // 배포된 모델은 우리 중분류로 학습돼 있다. 전혀 다른 체계(영문 코드)만 아는 앱에서는
        // 한 이름도 안 겹치므로 모델을 거부해야 한다.
        //
        // 이 검사를 '현재 모델 vs 현재 체계'로 두면 안 된다 — 재학습하면 통과해 버려서
        // 가드가 살아 있는지 아닌지를 더는 알 수 없다. 겹칠 리 없는 체계로 시험한다.
        SpendingClassifier clf = new SpendingClassifier(mapper, categoriesOf(
                Set.of("FOOD", "CAFE", "SHOPPING", "TRANSPORT", "HOUSING", "MEDICAL")));

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
    @DisplayName("배포된 모델이 현재 대조표와 같은 체계로 학습돼 있다")
    void deployedModelMatchesCurrentTaxonomy() {
        // 2026-07-30 재학습으로 cat2가 우리 중분류가 되었다(2026-07-31 금융/보험 추가로 16종). 이 단언이 깨지면
        // 데이터 체계를 바꾸고 재학습을 잊은 것이다 — 그러면 ML 판정이 통째로 규칙 baseline이 된다.
        SpendingClassifier clf = new SpendingClassifier(mapper, new IndustryCategoryMapper(mapper));
        assertThat(clf.isReady())
                .as("모델의 cat2 이름이 현재 중분류와 겹쳐야 한다")
                .isTrue();
    }
}
