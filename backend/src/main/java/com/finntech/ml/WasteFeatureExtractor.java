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

    /** 생존필수 category2(학습과 동일) — 이 무대는 낭비 아님. 재량성=이 집합 밖. */
    static final Set<String> ESSENTIAL = Set.of(
            "대형마트", "편의점", "약국", "대중교통", "철도", "고속버스", "통신비", "공과금", "주유소", "통행료");

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
