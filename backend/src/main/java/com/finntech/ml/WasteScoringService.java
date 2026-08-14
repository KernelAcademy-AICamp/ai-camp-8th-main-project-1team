package com.finntech.ml;

import com.finntech.domain.UserMerchantStance;
import com.finntech.domain.UserPayment;
import com.finntech.domain.UserSpendingOverride;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.UserMerchantStanceRepository;
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

    /*
     * 무엇을 샀는지 알 수 없는 결제는 ML이 낭비/필수로 판단하지 않고(사용자가 직접 결정),
     * 학습에서도 제외한다(§13-11). 판정은 {@link IndustryCategoryMapper#isUnknown} 한 곳에서 한다.
     *
     * 이름을 여기 박지 않는 이유가 있다. 예전에는 {@code "미분류"}가 박혀 있었는데 업종코드
     * 체계로 옮기며 미분류 이름이 {@code "카테고리없음"}이 되었고, 그 결과 <b>알 수 없는 PG
     * 결제가 전부 ML 판정에 들어갔다</b> — 문자열이 안 맞을 뿐이라 크래시가 없었다. 그래서
     * 같은 사고가 '기타'(종결 표시)로 반복되지 않게, 상수를 복사해 두지 않고 판정 함수를 부른다.
     */

    private final SpendingClassifier classifier;
    private final UserPaymentRepository userPaymentRepository;
    private final UserSpendingOverrideRepository overrideRepository;
    private final UserMerchantStanceRepository stanceRepository;
    private final Clock clock;
    /** 관대(LENIENT) 가맹점에 더할 임계 폭. 클수록 '확실할 때만' 낭비로 본다. */
    private final double lenientThresholdShift;
    /** category1이 '줄이면 좋은 소비'가 되는 낭비금액 비율 하한. 설계 원칙 4 — 임계치는 application.yml. */
    private final double wasteCategoryRatioThreshold;

    public WasteScoringService(SpendingClassifier classifier, UserPaymentRepository userPaymentRepository,
                               UserSpendingOverrideRepository overrideRepository,
                               UserMerchantStanceRepository stanceRepository,
                               Clock clock,
                               @Value("${finntech.ml.waste-category-ratio-threshold:0.35}")
                               double wasteCategoryRatioThreshold,
                               @Value("${finntech.ml.lenient-threshold-shift:0.20}")
                               double lenientThresholdShift) {
        this.classifier = classifier;
        this.userPaymentRepository = userPaymentRepository;
        this.overrideRepository = overrideRepository;
        this.stanceRepository = stanceRepository;
        this.clock = clock;
        this.wasteCategoryRatioThreshold = wasteCategoryRatioThreshold;
        this.lenientThresholdShift = lenientThresholdShift;
    }

    /**
     * 그 가맹점에 적용할 임계 — 사용자가 쌓아 온 판단을 반영한다.
     *
     * <p>NORMAL은 전역 임계 그대로, LENIENT는 δ만큼 올려 '확실할 때만' 낭비로 보고,
     * EXCLUDED는 아예 낭비로 보지 않는다(1.0 이상이면 어떤 확률도 넘지 못한다).
     */
    private double thresholdFor(Map<String, UserMerchantStance.Stance> stances, String bizNo) {
        UserMerchantStance.Stance st = bizNo == null ? null : stances.get(bizNo);
        return thresholdFor(st).orElse(Double.MAX_VALUE);
    }

    /**
     * 그 성향에 적용될 임계 — <b>없으면 어떤 확률도 낭비가 아니다</b>({@code EXCLUDED}).
     *
     * <p>{@code Double.MAX_VALUE} 를 밖으로 내보내지 않는다. 그것은 임계가 아니라
     * "판정하지 않는다"는 뜻인데, 숫자로 받은 쪽은 그것으로 산술을 하거나 그대로 저장한다.
     * 없음은 없음으로 낸다.
     *
     * <p>이 규칙을 밖에서 한 벌 더 적지 않게 하려고 연다 — 정리된 소비 원장이 "이 줄에 실제로
     * 적용된 임계"를 적는데, 같은 계산을 저쪽에도 두면 {@code lenient-threshold-shift} 를
     * 고쳤을 때 둘이 갈라진다(마스터 §4 원칙 2: 서비스는 임계치를 재계산하지 않는다).
     */
    public java.util.OptionalDouble thresholdFor(UserMerchantStance.Stance stance) {
        if (stance == null) return java.util.OptionalDouble.of(classifier.threshold());
        return switch (stance) {
            case EXCLUDED -> java.util.OptionalDouble.empty();
            case LENIENT -> java.util.OptionalDouble.of(classifier.threshold() + lenientThresholdShift);
            case NORMAL -> java.util.OptionalDouble.of(classifier.threshold());
        };
    }

    /** 전역 임계 — 성향을 뺀 값. 원장이 집계 쪽 답을 되살릴 수 있게 함께 적는다. */
    public double modelThreshold() { return classifier.threshold(); }

    /** 지금 판정을 낸 모델 파일의 지문. 재학습을 알아보는 유일한 수단이다. */
    public String modelFingerprint() { return classifier.fingerprint(); }

    /** 거래별 낭비 판정 + 설명. */
    /**
     * 판정을 밀어올린 축 하나 — <b>이름·실제 값·기여도(로그오즈)</b>.
     *
     * <p>기여도는 EBM이 원래 낼 수 있는 값인데(§8-O에서 이 축들에 단조 제약까지 걸었다),
     * 지금까지는 그것을 <b>"평소보다 큰 금액" 한 마디로 뭉개고 숫자를 버렸다.</b>
     * 마스터 §4 원칙 1이 "판단은 설명가능한 모델이"라고 한 이유의 절반만 쓰고 있던 셈이다 —
     * 모델은 설명할 수 있는데 화면이 설명을 안 했다.
     *
     * <p>{@code detail} 이 그 숫자다. "평소보다 큰 금액"이 아니라
     * <b>"평소 23,000원 → 78,000원(3.4배)"</b>. 사용자가 반박하려면 무엇에 반박하는지
     * 알아야 하고, 그 반박이 성향(§8-S)의 교정 신호가 된다.
     *
     * @param contribution 로그오즈 기여. 양수면 낭비 쪽으로 민 것이다.
     */
    public record Factor(String label, String detail, double contribution) {}

    public record WasteJudgment(String paymentId, String category2, int amount, LocalDateTime date,
                                double wasteProbability, boolean waste, String explanation,
                                List<Factor> factors) {

        /** 근거 없이 만드는 옛 호출부용. */
        public WasteJudgment(String paymentId, String category2, int amount, LocalDateTime date,
                             double wasteProbability, boolean waste, String explanation) {
            this(paymentId, category2, amount, date, wasteProbability, waste, explanation, List.of());
        }
    }

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
        // 가맹점별 성향 — 사용자가 "이건 낭비 아님"을 반복한 곳은 임계가 올라가 있다.
        Map<String, UserMerchantStance.Stance> stances = new HashMap<>();
        for (UserMerchantStance st : stanceRepository.findByUserId(userId)) {
            stances.put(st.getBusinessNumber(), st.getStance());
        }
        List<WasteJudgment> out = new ArrayList<>(payments.size());
        for (UserPayment p : payments) {
            // 미분류(unknown-pg)와 종결(기타)는 판정 안 함 — 무엇을 샀는지 모르는 것은 같다.
            if (IndustryCategoryMapper.isUnknown(p.getCategory2())) continue;
            Map<String, Object> feats = WasteFeatureExtractor.features(
                    p.getCategory2(), p.getAmount(), p.getPaymentDate(), stats);
            double prob = classifier.wasteProbability(feats);
            boolean waste;
            String explanation;
            List<Factor> factors = List.of();
            if (overrides.containsKey(p.getCategory2())) {                 // 개인화 우선
                waste = overrides.get(p.getCategory2());
                explanation = "개인화: 사용자가 " + (waste ? "낭비" : "필수") + "로 지정";
            } else {
                double thr = thresholdFor(stances, p.getBusinessNumber());
                waste = prob >= thr;
                Map<String, Double> contrib = classifier.contributions(feats);
                explanation = thr > classifier.threshold() && !waste
                        ? "이 가게는 낭비가 아니라고 하셔서, 확실할 때만 알려드려요"
                        : explain(contrib, waste);
                // 근거는 낭비로 본 것에만 붙인다 — 필수 판정에 "왜 필수인지"를 캐물을 사람은 없다.
                if (waste) factors = factorsOf(contrib, feats, p, stats);
            }
            out.add(new WasteJudgment(p.getPaymentId(), p.getCategory2(), p.getAmount(),
                    p.getPaymentDate(), prob, waste, explanation, factors));
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
            if (IndustryCategoryMapper.isUnknown(p.getCategory2())) continue;  // 미분류·기타 집계 제외
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

    /**
     * 기여도 상위 축을 <b>사람이 검증할 수 있는 문장</b>으로 바꾼다.
     *
     * <p>규칙 하나: <b>사용자가 사실 여부를 확인할 수 있는 것만 말한다.</b> "재량 지출 성향 0.62"는
     * 반박할 수 없지만 "평소 23,000원 → 78,000원"은 반박할 수 있다. 검증 불가능한 근거는
     * 설명이 아니라 권위이고, 그건 블랙박스와 다르지 않다.
     *
     * <p>그래서 {@code user_mean_log_amount}·{@code user_disc_ratio}(사용자 전반의 성향)와
     * 삼각함수로 인코딩된 축은 <b>수치를 붙이지 않는다</b> — 이름만으로 충분하거나,
     * 숫자를 보여줘 봐야 사용자가 확인할 방법이 없다.
     *
     * <p>품목이 있으면 맨 앞에 놓는다. 모델은 아직 품목을 안 보지만(그건 다음 단계다),
     * <b>사용자가 판단하는 데는 이게 제일 크다</b> — "편의점 12,000원"과 "맥주 4캔"은 다르다.
     */
    private static List<Factor> factorsOf(Map<String, Double> contributions,
                                          Map<String, Object> feats, UserPayment p,
                                          WasteFeatureExtractor.UserStats stats) {
        List<Factor> out = new ArrayList<>(3);
        contributions.entrySet().stream()
                .filter(e -> !e.getKey().equals("(기준)") && e.getValue() > 0.05)
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(3)
                .forEach(e -> out.add(new Factor(label(e.getKey()), detailOf(e.getKey(), feats, p, stats),
                        Math.round(e.getValue() * 1000) / 1000.0)));
        return List.copyOf(out);
    }

    /** 축의 실제 값 — 확인할 수 없는 축은 빈 문자열을 준다(화면이 이름만 쓴다). */
    private static String detailOf(String feature, Map<String, Object> feats, UserPayment p,
                                   WasteFeatureExtractor.UserStats stats) {
        return switch (feature) {
            case "amt_vs_typical" -> {
                Object v = feats.get("amt_vs_typical");
                double ratio = v instanceof Number n ? n.doubleValue() : 0;
                long typical = ratio > 0 ? Math.round(p.getAmount() / ratio) : 0;
                yield typical <= 0 ? "" : String.format("평소 %,d원 → %,d원 (%.1f배)",
                        typical, p.getAmount(), ratio);
            }
            case "log_amount" -> String.format("%,d원", p.getAmount());
            case "night" -> p.getPaymentDate().getHour() + "시 결제";
            case "hour_sin", "hour_cos" -> p.getPaymentDate().getHour() + "시";
            case "dow_sin", "dow_cos", "weekend" -> switch (p.getPaymentDate().getDayOfWeek()) {
                case MONDAY -> "월요일"; case TUESDAY -> "화요일"; case WEDNESDAY -> "수요일";
                case THURSDAY -> "목요일"; case FRIDAY -> "금요일";
                case SATURDAY -> "토요일"; case SUNDAY -> "일요일";
            };
            case "cat2" -> p.getCategory2() == null ? "" : p.getCategory2();
            // 사용자 전반의 성향 — 숫자를 보여줘도 확인할 방법이 없다. 이름만 쓴다.
            default -> "";
        };
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
