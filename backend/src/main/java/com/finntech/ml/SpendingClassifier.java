package com.finntech.ml;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final double threshold;
    private final List<Term> terms;
    private final boolean ready;

    public SpendingClassifier(ObjectMapper objectMapper) {
        double ic = 0, thr = 0.5;
        List<Term> ts = List.of();
        boolean ok = false;
        try (InputStream is = new ClassPathResource(MODEL_PATH).getInputStream()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = objectMapper.readValue(is, Map.class);
            ic = ((Number) root.get("intercept")).doubleValue();
            if (root.get("decision_threshold") != null) thr = ((Number) root.get("decision_threshold")).doubleValue();
            ts = parseTerms(root);
            ok = !ts.isEmpty();
        } catch (Exception e) {
            ok = false;
        }
        this.intercept = ic;
        this.threshold = thr;
        this.terms = ts;
        this.ready = ok;
    }

    /** 모델이 로드됐는지. false면 엔진은 규칙 FDS baseline을 쓴다. */
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
