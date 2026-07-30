package com.finntech.ml;

import com.finntech.engine.IndustryCategoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 낭비/필수 해석가능 ML 추론기 (W8) — Python 학습 EBM(순수 GAM)의 형상함수 테이블을 읽어
 * 거래별 낭비 확률을 산출한다. 덧셈 모델: 절편 + Σ(특징별 구간 기여값) → 시그모이드.
 * Java == Python 일치(테이블 재현 오차 ~1e-16 검증). 모델 파일이 없으면 {@link #isReady()}=false →
 * 엔진은 규칙 FDS baseline으로 폴백(§12 보존). 정적 아티팩트라 추론 결정론(규칙 3).
 *
 * <p>특징 키(학습과 동일): cat2 · log_amount · hour_sin · hour_cos · night · dow_sin · dow_cos ·
 * weekend · amt_vs_typical · user_mean_log_amount · user_disc_ratio. 값은 cat2=String, 나머지=Number.
 * {@link #contributions}로 "왜 낭비인지"(특징별 기여)를 제시한다(원칙 1: 설명가능성).
 */
@Component
public class SpendingClassifier {

    private static final String MODEL_PATH = "ml/ebm_model.json";

    private record Term(String feature, boolean nominal, String[] names, double[] edges, double[] scores) {}

    private final double intercept;
    private static final Logger log = LoggerFactory.getLogger(SpendingClassifier.class);

    private final double threshold;
    private final List<Term> terms;
    private final boolean ready;

    public SpendingClassifier(ObjectMapper objectMapper, IndustryCategoryMapper categories) {
        double ic = 0, thr = 0.5;
        List<Term> ts = List.of();
        boolean ok = false;
        try (InputStream is = new ClassPathResource(MODEL_PATH).getInputStream()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = objectMapper.readValue(is, Map.class);
            ic = ((Number) root.get("intercept")).doubleValue();
            if (root.get("decision_threshold") != null) thr = ((Number) root.get("decision_threshold")).doubleValue();
            ts = parseTerms(root);
            ok = !ts.isEmpty() && categoriesMatch(ts, categories);
        } catch (Exception e) {
            ok = false;
        }
        this.intercept = ic;
        this.threshold = thr;
        this.terms = ts;
        this.ready = ok;
    }

    /**
     * 모델의 {@code cat2} 이름들이 <b>현재 카테고리 체계</b>와 맞는지 확인한다.
     *
     * <p>안 맞으면 모델을 안 쓴 것으로 친다. {@link #termScore}가 모르는 이름을 기여 0으로
     * 처리하기 때문에, 체계를 바꾸고 재학습을 잊으면 <b>전역 중요도 1위 특징이 통째로 죽은 채</b>
     * 확률이 계속 나온다 — 크래시도 로그도 없이 판정만 뭉개진다. 실제로 소비 카테고리를
     * 52개 맥락에서 15개 중분류로 옮기면서 이 상황을 만들었다.
     *
     * <p>겹치는 이름이 절반도 안 되면 다른 체계로 학습된 모델이다. 그때는 규칙 baseline이
     * 조용히 틀린 ML보다 낫다.
     */
    private static boolean categoriesMatch(List<Term> terms, IndustryCategoryMapper categories) {
        Term cat = terms.stream().filter(t -> "cat2".equals(t.feature())).findFirst().orElse(null);
        if (cat == null || cat.names() == null || cat.names().length == 0) return true;   // 명목 특징이 없으면 검사할 것도 없다
        Set<String> known = categories.midCategories();
        if (known.isEmpty()) return true;
        long hit = Arrays.stream(cat.names()).filter(known::contains).count();
        boolean match = hit * 2 >= cat.names().length;
        if (!match) {
            log.warn("ML 모델의 cat2 이름이 현재 카테고리 체계와 맞지 않는다 — 모델 {}개 중 {}개만 일치. "
                    + "재학습 전까지 규칙 baseline을 쓴다(ml/README.md 참고).", cat.names().length, hit);
        }
        return match;
    }

    /** 모델이 로드됐고 <b>현재 카테고리 체계와 맞는지</b>. false면 엔진은 규칙 FDS baseline을 쓴다. */
    public boolean isReady() { return ready; }

    /** 낭비 판정 임계값(학습 시 F1 최적). */
    public double threshold() { return threshold; }

    /** 거래 특징 → 낭비 확률(0..1). */
    public double wasteProbability(Map<String, Object> features) {
        return 1.0 / (1.0 + Math.exp(-logit(features)));
    }

    /** 특징별 기여값(로그오즈) — 설명가능성. 절편은 "(기준)" 키로 포함. 큰 값일수록 낭비를 밀어올림. */
    public Map<String, Double> contributions(Map<String, Object> features) {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("(기준)", intercept);
        for (Term t : terms) out.put(t.feature(), termScore(t, features.get(t.feature())));
        return out;
    }

    private double logit(Map<String, Object> features) {
        double s = intercept;
        for (Term t : terms) s += termScore(t, features.get(t.feature()));
        return s;
    }

    private double termScore(Term t, Object v) {
        if (v == null) return 0.0;
        if (t.nominal()) {
            int idx = indexOf(t.names(), String.valueOf(v));
            return idx >= 0 ? t.scores()[idx] : 0.0;
        }
        double x = ((Number) v).doubleValue();
        int idx = bisectRight(t.edges(), x) - 1;
        if (idx < 0) idx = 0;
        if (idx >= t.scores().length) idx = t.scores().length - 1;
        return t.scores()[idx];
    }

    @SuppressWarnings("unchecked")
    private static List<Term> parseTerms(Map<String, Object> root) {
        java.util.List<Term> out = new java.util.ArrayList<>();
        for (Object o : (List<Object>) root.get("terms")) {
            Map<String, Object> tm = (Map<String, Object>) o;
            String feature = (String) tm.get("feature");
            boolean nominal = "nominal".equals(tm.get("type"));
            List<Object> names = (List<Object>) tm.get("names");
            List<Object> scores = (List<Object>) tm.get("scores");
            double[] sc = scores.stream().mapToDouble(x -> ((Number) x).doubleValue()).toArray();
            if (nominal) {
                String[] nm = names.stream().map(String::valueOf).toArray(String[]::new);
                out.add(new Term(feature, true, nm, null, sc));
            } else {
                double[] ed = names.stream().mapToDouble(x -> ((Number) x).doubleValue()).toArray();
                out.add(new Term(feature, false, null, ed, sc));
            }
        }
        return out;
    }

    private static int indexOf(String[] a, String v) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i;
        return -1;
    }

    /** Python bisect_right: v 이하인 경계의 개수(= 우측 삽입 위치). */
    private static int bisectRight(double[] edges, double v) {
        int lo = 0, hi = edges.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (v < edges[mid]) hi = mid; else lo = mid + 1;
        }
        return lo;
    }
}
