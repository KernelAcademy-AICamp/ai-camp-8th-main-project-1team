package com.finntech.ml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>'평소 대비 배수'의 형상함수는 증가 단조여야 한다.</b>
 *
 * <p>이 축은 사용자에게 그대로 설명된다 — "평소보다 3배를 썼다"가 곧 판정 근거다. 그런데
 * 형상함수가 뒤집혀 있으면 <b>"15배를 쓰면 14배보다 덜 낭비"</b>라는 말이 된다. 실제로 그랬다:
 * 평소의 15.76배 구간이 직전 구간보다 낮은 점수를 받고 있었다(+2.367 → +2.081).
 *
 * <p>데이터가 그렇게 말하더라도(고액 표본이 적어 생기는 흔들림) <b>판단 축은 단조여야 한다</b> —
 * 설명할 수 없는 판정은 쓰지 않는다는 것이 이 저장소의 첫 원칙이다(마스터 §4 원칙 1).
 * 그래서 학습에 {@code monotone_constraints} 를 건다(ml/train.py). 성능 손실은 없었다
 * (PR-AUC 0.661 유지, 오히려 중요도가 0.554→0.624 로 올랐다 — 흔들림이 신호를 가리고 있었다).
 *
 * <p><b>다른 축에는 걸지 않는다.</b> {@code log_amount}·{@code user_disc_ratio} 는 방향이
 * 자명하지 않다 — 금액이 크다고 낭비가 아니고(월세·병원비), 재량성 비율도 마찬가지다.
 * 자명하지 않은 축에 단조를 강요하면 그것이 곧 편향이다.
 */
class ShapeFunctionMonotonicityTest {

    private static final String MONOTONE_FEATURE = "amt_vs_typical";

    @Test
    @DisplayName("amt_vs_typical 형상함수는 구간마다 증가하거나 같다")
    void 평소대비배수는_증가단조다() throws Exception {
        List<Double> scores = scoresOf(MONOTONE_FEATURE);
        assumeTrue(scores != null, "ebm_model.json 미배치 → skip");
        assertThat(scores).as("형상함수 구간").hasSizeGreaterThan(10);

        List<String> drops = new ArrayList<>();
        for (int i = 1; i < scores.size(); i++) {
            double prev = scores.get(i - 1), cur = scores.get(i);
            if (cur < prev - 1e-9) {
                drops.add(String.format("구간 %d: %+.4f → %+.4f", i, prev, cur));
            }
        }
        assertThat(drops)
                .as("배수가 커졌는데 낭비 점수가 내려간 구간 — 화면에 설명할 수 없는 판정이 된다")
                .isEmpty();
    }

    /** 형상함수의 점수 배열. 모델이 없으면 null(테스트는 skip). */
    private List<Double> scoresOf(String feature) throws Exception {
        ObjectMapper mapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        try (InputStream is = getClass().getResourceAsStream("/ml/ebm_model.json")) {
            if (is == null) return null;
            JsonNode root = mapper.readTree(is);
            for (JsonNode term : root.get("terms")) {
                if (feature.equals(term.path("feature").asString(""))) {
                    List<Double> out = new ArrayList<>();
                    for (JsonNode s : term.get("scores")) out.add(s.asDouble());
                    return out;
                }
            }
        }
        return null;
    }
}
