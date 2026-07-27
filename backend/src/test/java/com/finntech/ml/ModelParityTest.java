package com.finntech.ml;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * W8-6: Java 스코어러 == Python EBM 예측 일치. Python이 내보낸 표본(features, proba)에 대해
 * Java {@link SpendingClassifier}가 동일 확률을 내는지 검증(수치오차 허용). 모델·표본 미배치면 skip.
 */
class ModelParityTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    @Test
    @SuppressWarnings("unchecked")
    void javaScorerMatchesPythonEbm() throws Exception {
        SpendingClassifier clf = new SpendingClassifier(mapper);
        assumeTrue(clf.isReady(), "ml/ebm_model.json 미배치 → skip");

        List<Map<String, Object>> samples;
        try (InputStream is = getClass().getResourceAsStream("/ml/parity_samples.json")) {
            assumeTrue(is != null, "parity_samples.json 미배치 → skip");
            samples = mapper.readValue(is, List.class);
        }
        assertThat(samples).isNotEmpty();

        double maxDiff = 0;
        for (Map<String, Object> s : samples) {
            Map<String, Object> feats = new HashMap<>((Map<String, Object>) s.get("features"));
            double py = ((Number) s.get("proba")).doubleValue();
            double java = clf.wasteProbability(feats);
            maxDiff = Math.max(maxDiff, Math.abs(java - py));
        }
        // 형상함수 룩업이 동일하므로 부동소수점 수준 일치
        assertThat(maxDiff).isLessThan(1e-6);
    }
}
