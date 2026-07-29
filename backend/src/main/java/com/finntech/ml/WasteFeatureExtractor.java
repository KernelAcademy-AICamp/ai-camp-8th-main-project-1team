package com.finntech.ml;

import com.finntech.domain.UserPayment;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 낭비/필수 ML 추론 특징 추출 — Python 학습과 동일 정의(누수·불일치 금지). 순수 함수(테스트 용이).
 * 백엔드 실가용 데이터(UserPayment: category2·amount·date + 사용자 이력)로만 구성한다.
 */
public final class WasteFeatureExtractor {
    private WasteFeatureExtractor() {}

    /**
     * 생존필수 소비 중분류 — 이 무대는 낭비가 아니다. 재량성 = 이 집합 밖.
     *
     * <p><b>대조표에서 읽는다.</b> 예전에는 이 목록이 네 곳(여기 · {@code ml/train.py} ·
     * {@code backend-mydata/application.yml} · {@code WasteLabeler})에 손으로 복사돼 있었다.
     * 한 곳만 고치면 학습과 추론의 {@code user_disc_ratio} 특징이 갈라지는데, 크래시가 안 나서
     * 아무도 모른다. 이제 {@code scripts/ksic}가 카탈로그의 재량성에서 유도해 한 파일에 쓴다.
     *
     * <p>정적 컨텍스트라 주입을 못 받아 직접 읽는다. 읽기 실패는 빈 집합 — 그러면
     * 모든 소비가 재량으로 잡혀 {@code user_disc_ratio}가 1.0이 되지만, 예외로 죽는 것보다 낫다.
     */
    static final Set<String> ESSENTIAL = loadEssential();

    private static Set<String> loadEssential() {
        try (java.io.InputStream is = WasteFeatureExtractor.class
                .getResourceAsStream("/ksic-mid.json")) {
            if (is == null) return Set.of();
            @SuppressWarnings("unchecked")
            Map<String, Object> root = new tools.jackson.databind.ObjectMapper().readValue(is, Map.class);
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) root.get("essentialCategories");
            return list == null ? Set.of() : Set.copyOf(list);
        } catch (Exception e) {
            return Set.of();
        }
    }

    /** 사용자 단위 집계(라벨 미사용 → 누수 아님): 카테고리별 중앙값 · 평균 log금액 · 재량지출 비율. */
    public record UserStats(Map<String, Double> categoryMedian, double meanLogAmount, double discRatio) {}

    public static UserStats userStats(List<UserPayment> payments) {
        Map<String, List<Integer>> byCat = new HashMap<>();
        double sumLog = 0;
        long disc = 0;
        for (UserPayment p : payments) {
            byCat.computeIfAbsent(p.getCategory2(), k -> new ArrayList<>()).add(p.getAmount());
            sumLog += Math.log1p(p.getAmount());
            if (!ESSENTIAL.contains(p.getCategory2())) disc++;
        }
        Map<String, Double> median = new HashMap<>();
        for (var e : byCat.entrySet()) {
            List<Integer> v = e.getValue();
            Collections.sort(v);
            int n = v.size();
            median.put(e.getKey(), n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2.0);
        }
        int total = Math.max(1, payments.size());
        return new UserStats(median, sumLog / total, (double) disc / total);
    }

    /** 거래 1건 → 11개 특징 맵(모델 특징명과 동일 키). */
    public static Map<String, Object> features(String category2, int amount, LocalDateTime when, UserStats s) {
        int hour = when.getHour();
        int dow = when.getDayOfWeek().getValue() - 1; // 월=0..일=6 (pandas dayofweek 일치)
        double med = s.categoryMedian().getOrDefault(category2, (double) amount);
        double amtVsTypical = Math.min(20.0, amount / Math.max(1.0, med));
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("cat2", category2);
        f.put("log_amount", Math.log1p(amount));
        f.put("hour_sin", Math.sin(2 * Math.PI * hour / 24));
        f.put("hour_cos", Math.cos(2 * Math.PI * hour / 24));
        f.put("night", (hour >= 23 || hour <= 4) ? 1 : 0);
        f.put("dow_sin", Math.sin(2 * Math.PI * dow / 7));
        f.put("dow_cos", Math.cos(2 * Math.PI * dow / 7));
        f.put("weekend", dow >= 5 ? 1 : 0);
        f.put("amt_vs_typical", amtVsTypical);
        f.put("user_mean_log_amount", s.meanLogAmount());
        f.put("user_disc_ratio", s.discRatio());
        return f;
    }
}
