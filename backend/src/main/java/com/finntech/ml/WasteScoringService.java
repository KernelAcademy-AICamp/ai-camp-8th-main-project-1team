package com.finntech.ml;

import com.finntech.domain.UserPayment;
import com.finntech.domain.UserSpendingOverride;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.UserPaymentRepository;
import com.finntech.repository.UserSpendingOverrideRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 낭비/필수 ML 판정 서비스 (W8 주 판정) — 사용자의 마이데이터 결제(UserPayment)를 EBM으로 분류하고
 * "왜 낭비인지"(특징 기여)를 함께 낸다. 규칙 FDS(§12)는 baseline으로 병존(AlertService 유지).
 * 모델 미배치({@link SpendingClassifier#isReady()}=false)면 빈 결과 → 상위는 규칙 baseline으로 폴백.
 *
 * <p>개인화(W8-5, 요구 10): 사용자가 category2를 "본인엔 필수/낭비"로 지정하면 그 사용자에 한해 라벨을
 * 덮어쓴다("통념상 낭비여도 본인 취미/필수면 보호"). override는 파기 흐름에 포함(PrivacyService).
 */
@Service
public class WasteScoringService {

    /**
     * 무엇을 샀는지 알 수 없는 결제의 카테고리. ML은 이를 낭비/필수로 판단하지 않고(사용자가 직접 결정),
     * 학습에서도 제외한다(§13-11).
     *
     * <p>이름을 여기에 박지 않고 {@link IndustryCategoryMapper}에서 가져온다. 예전에는 {@code "미분류"}가
     * 박혀 있었는데 업종코드 체계로 옮기며 미분류 이름이 {@code "카테고리없음"}이 되었고, 그 결과
     * <b>알 수 없는 PG 결제가 전부 ML 판정에 들어갔다</b> — 문자열이 안 맞을 뿐이라 크래시가 없었다.
     */
    private static final String UNCLASSIFIED = IndustryCategoryMapper.UNCLASSIFIED;

    private final SpendingClassifier classifier;
    private final UserPaymentRepository userPaymentRepository;
    private final UserSpendingOverrideRepository overrideRepository;
    private final Clock clock;
    /** category1이 '줄이면 좋은 소비'가 되는 낭비금액 비율 하한. 설계 원칙 4 — 임계치는 application.yml. */
    private final double wasteCategoryRatioThreshold;

    public WasteScoringService(SpendingClassifier classifier, UserPaymentRepository userPaymentRepository,
                               UserSpendingOverrideRepository overrideRepository, Clock clock,
                               @Value("${finntech.ml.waste-category-ratio-threshold:0.35}")
                               double wasteCategoryRatioThreshold) {
        this.classifier = classifier;
        this.userPaymentRepository = userPaymentRepository;
        this.overrideRepository = overrideRepository;
        this.clock = clock;
        this.wasteCategoryRatioThreshold = wasteCategoryRatioThreshold;
    }

    /** 거래별 낭비 판정 + 설명. */
    public record WasteJudgment(String paymentId, String category2, int amount, LocalDateTime date,
                                double wasteProbability, boolean waste, String explanation) {}

    public boolean modelReady() { return classifier.isReady(); }

    /** 사용자의 모든 마이데이터 결제를 낭비/필수로 분류(최신순, 개인화 override 적용). 모델 없으면 빈 리스트. */
    public List<WasteJudgment> scoreUser(Long userId) {
        List<UserPayment> payments = userPaymentRepository.findByUserIdOrderByPaymentDateDesc(userId);
        if (!classifier.isReady() || payments.isEmpty()) return List.of();
        WasteFeatureExtractor.UserStats stats = WasteFeatureExtractor.userStats(payments);
        Map<String, Boolean> overrides = new HashMap<>();
        for (UserSpendingOverride o : overrideRepository.findByUserId(userId)) {
            overrides.put(o.getCategory2(), o.isForcedWaste());
        }
        List<WasteJudgment> out = new ArrayList<>(payments.size());
        for (UserPayment p : payments) {
            if (p.getCategory2() == null || UNCLASSIFIED.equals(p.getCategory2())) continue; // 미분류(unknown-pg)는 판정 안 함
            Map<String, Object> feats = WasteFeatureExtractor.features(
                    p.getCategory2(), p.getAmount(), p.getPaymentDate(), stats);
            double prob = classifier.wasteProbability(feats);
            boolean waste;
            String explanation;
            if (overrides.containsKey(p.getCategory2())) {                 // 개인화 우선
                waste = overrides.get(p.getCategory2());
                explanation = "개인화: 사용자가 " + (waste ? "낭비" : "필수") + "로 지정";
            } else {
                waste = prob >= classifier.threshold();
                explanation = explain(classifier.contributions(feats), waste);
            }
            out.add(new WasteJudgment(p.getPaymentId(), p.getCategory2(), p.getAmount(),
                    p.getPaymentDate(), prob, waste, explanation));
        }
        return out;
    }

    /**
     * 다운스트림 판정 소스 전환용 요약(W8) — 리포트·소비건강점수가 규칙(overspending·planned) 대신 ML 판정을 쓰게 한다.
     * 마이데이터 결제를 ML로 분류해 ① 필수 금액 비율, ② 낭비가 금액의 과반인 category1 집합을 낸다.
     * 모델 미배치·결제 없음이면 empty → 상위는 규칙 baseline으로 폴백(규칙 FDS §12는 그대로 병존).
     */
    public java.util.Optional<MlSummary> summarize(Long userId) {
        if (!classifier.isReady()) return java.util.Optional.empty();
        List<UserPayment> payments = userPaymentRepository.findByUserIdOrderByPaymentDateDesc(userId);
        if (payments.isEmpty()) return java.util.Optional.empty();
        WasteFeatureExtractor.UserStats stats = WasteFeatureExtractor.userStats(payments);
        Map<String, Boolean> overrides = new HashMap<>();
        for (UserSpendingOverride o : overrideRepository.findByUserId(userId)) {
            overrides.put(o.getCategory2(), o.isForcedWaste());
        }
        double thr = classifier.threshold();
        long essentialAmt = 0, totalAmt = 0;
        Map<String, long[]> byCat1 = new java.util.TreeMap<>(); // category1 -> [낭비금액, 총금액]
        for (UserPayment p : payments) {
            if (p.getCategory2() == null || UNCLASSIFIED.equals(p.getCategory2())) continue; // 미분류(unknown-pg) 집계 제외
            boolean waste = overrides.containsKey(p.getCategory2())
                    ? overrides.get(p.getCategory2())
                    : classifier.wasteProbability(WasteFeatureExtractor.features(
                            p.getCategory2(), p.getAmount(), p.getPaymentDate(), stats)) >= thr;
            int amt = p.getAmount();
            totalAmt += amt;
            if (!waste) essentialAmt += amt;
            // 낭비 비율을 중분류 단위로 굴린다. 판정(cat2)과 집계 축이 같아져 설명이 이어진다.
            long[] c = byCat1.computeIfAbsent(p.getCategory2(), k -> new long[2]);
            if (waste) c[0] += amt;
            c[1] += amt;
        }
        if (totalAmt == 0) return java.util.Optional.empty();
        java.util.Set<String> wasteCategories = new java.util.TreeSet<>();
        Map<String, Double> ratioByCat1 = new java.util.TreeMap<>();
        for (var e : byCat1.entrySet()) {
            double ratio = (double) e.getValue()[0] / e.getValue()[1];
            ratioByCat1.put(e.getKey(), ratio);
            // 이 카테고리에 쓴 돈 중 낭비가 차지하는 비율이 임계 이상이면 '줄이면 좋은 소비'.
            // 예전엔 0.5(과반)가 코드에 박혀 있었는데, 실측하면 절약형 사용자는 최대 카테고리가 35~41%에
            // 그쳐 후보가 0개가 됐다 — 절약 리포트가 "줄일 게 없다"고 하고 챌린지 추천도 비었다.
            // 판정 소스를 규칙(전체 대비 30% 쏠림)에서 ML로 옮기며 기준이 훨씬 엄해진 것이 원인이라,
            // 값을 application.yml로 빼고 규칙 시절과 비슷한 강도로 맞췄다(설계 원칙 4).
            if (ratio >= wasteCategoryRatioThreshold) wasteCategories.add(e.getKey());
        }
        return java.util.Optional.of(new MlSummary((double) essentialAmt / totalAmt, wasteCategories, ratioByCat1));
    }

    /** 리포트·점수용 ML 요약(W8 다운스트림). */
    public record MlSummary(double essentialRatio, java.util.Set<String> wasteCategories,
                            Map<String, Double> wasteRatioByCategory1) {}

    /** 개인화 재분류 지정(같은 category2는 갱신). 이후 실시간 유입 결제에도 이 기준이 적용된다. */
    public void setOverride(Long userId, String category2, boolean forcedWaste) {
        overrideRepository.deleteByUserIdAndCategory2(userId, category2);
        overrideRepository.save(new UserSpendingOverride(userId, category2, forcedWaste, LocalDateTime.now(clock)));
    }

    /** 상위 기여 특징으로 "왜"를 만든다(원칙 1 설명가능성). */
    private static String explain(Map<String, Double> contributions, boolean waste) {
        if (!waste) return "필수·계획 소비";
        return contributions.entrySet().stream()
                .filter(e -> !e.getKey().equals("(기준)") && e.getValue() > 0.05)
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(2)
                .map(e -> label(e.getKey()))
                .distinct()
                .reduce((a, b) -> a + "·" + b)
                .map(s -> s + " 요인으로 낭비 판정")
                .orElse("충동·과다 소비");
    }

    private static String label(String feature) {
        return switch (feature) {
            case "cat2" -> "소비 유형";
            case "night" -> "심야 결제";
            case "amt_vs_typical" -> "평소보다 큰 금액";
            case "log_amount" -> "고액 결제";
            case "hour_sin", "hour_cos" -> "결제 시간대";
            case "dow_sin", "dow_cos", "weekend" -> "요일 패턴";
            case "user_disc_ratio" -> "재량 지출 성향";
            case "user_mean_log_amount" -> "소비 규모 성향";
            default -> feature;
        };
    }
}
