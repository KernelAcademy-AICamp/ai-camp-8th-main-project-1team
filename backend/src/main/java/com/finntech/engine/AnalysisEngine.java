package com.finntech.engine;

import com.finntech.config.AnalysisProperties;
import com.finntech.domain.Consumption;
import com.finntech.domain.Enums;
import com.finntech.repository.ConsumptionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 공유 분석 엔진. 세 서비스가 이 결과 하나를 재사용한다 (문서 §4 원칙 2).
 *
 * <p><b>재현성 보장 규칙</b> (문서 §4 원칙 3):
 * <ul>
 *   <li>모든 조회는 정렬이 고정돼 있다 ({@code order by occurredAt, id})</li>
 *   <li>모든 Map은 TreeMap이라 키 순회 순서가 결정적이다</li>
 *   <li>"현재 시각"을 내부에서 읽지 않고 {@code referenceTime}으로 주입받는다 —
 *       {@code LocalDateTime.now()}를 쓰면 같은 입력이 다른 출력을 내어 재현성이 깨진다</li>
 * </ul>
 *
 * <p><b>세그먼트 비의존 규칙</b> (문서 §8): 카테고리 이름이 이 클래스에 등장하지 않는다.
 * 판단은 전부 {@link AnalysisProperties}의 설정값과 데이터로만 이뤄진다.
 */
@Component
public class AnalysisEngine {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final ConsumptionRepository consumptionRepository;
    private final AnalysisProperties props;

    public AnalysisEngine(ConsumptionRepository consumptionRepository, AnalysisProperties props) {
        this.consumptionRepository = consumptionRepository;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public AnalysisResult analyze(Long userId, LocalDateTime referenceTime) {
        List<Consumption> all = consumptionRepository.findAllForUser(userId);

        if (all.isEmpty()) {
            return empty(userId, "기록된 소비 내역이 없습니다.");
        }

        // ---- 카테고리별 집계 --------------------------------------------------
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal planned = BigDecimal.ZERO;
        Map<String, List<Consumption>> byCategory = new TreeMap<>();
        Map<String, String> displayNames = new TreeMap<>();
        Map<String, BigDecimal> monthly = new TreeMap<>();

        for (Consumption c : all) {
            total = total.add(c.getAmount());
            if (c.isPlanned()) planned = planned.add(c.getAmount());
            String code = c.getCategory().getCode();
            byCategory.computeIfAbsent(code, k -> new ArrayList<>()).add(c);
            displayNames.putIfAbsent(code, c.getCategory().getDisplayName());
            monthly.merge(c.getOccurredAt().format(MONTH), c.getAmount(), BigDecimal::add);
        }

        Map<String, AnalysisResult.CategoryStat> stats = new TreeMap<>();
        List<String> overspending = new ArrayList<>();
        for (Map.Entry<String, List<Consumption>> e : byCategory.entrySet()) {
            BigDecimal sum = e.getValue().stream()
                    .map(Consumption::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            double ratio = total.signum() == 0 ? 0.0
                    : sum.divide(total, 10, RoundingMode.HALF_UP).doubleValue();
            boolean sufficient = e.getValue().size() >= props.getFds().getMinSamplesPerCategory();
            stats.put(e.getKey(), new AnalysisResult.CategoryStat(
                    e.getKey(), displayNames.get(e.getKey()), sum, ratio, e.getValue().size(), sufficient));
            if (ratio > props.getOverspending().getRatioThreshold()) {
                overspending.add(e.getKey());
            }
        }

        // 지출 비중 내림차순. 동률이면 코드 오름차순으로 깨서 순서를 결정적으로 만든다.
        List<String> bySpendDesc = new ArrayList<>(stats.keySet());
        bySpendDesc.sort((a, b) -> {
            int cmp = Double.compare(stats.get(b).spendRatio(), stats.get(a).spendRatio());
            return cmp != 0 ? cmp : a.compareTo(b);
        });

        // ---- 장기 변동성 -----------------------------------------------------
        // 관측 월수가 최소치 미만이면 '측정 불가'다. 0.0을 그대로 내보내면 소비자 쪽에서
        // '완벽히 안정적'으로 읽혀 데이터가 적을수록 점수가 높아진다.
        double cv = 0.0;
        boolean volatilityMeasured = monthly.size() >= props.getVolatility().getMinMonths();
        if (volatilityMeasured) {
            double[] monthTotals = monthly.values().stream().mapToDouble(BigDecimal::doubleValue).toArray();
            cv = Stats.coefficientOfVariation(monthTotals);
        }

        // ---- 단기 이탈 (FDS) --------------------------------------------------
        List<AnalysisResult.Deviation> deviations =
                computeDeviations(all, referenceTime);

        // ---- 데이터 출처 모드 --------------------------------------------------
        long userInputCount = consumptionRepository.countByUserIdAndSource(userId, Enums.DataSource.USER_INPUT);
        long dummyCount = all.size() - userInputCount;
        Enums.DataSourceMode mode;
        String reason = null;

        if (dummyCount > 0 && userInputCount == 0) {
            // 더미 시드만 있는 계정 — 엔진 검증용이므로 CONFIRMED로 취급한다.
            mode = Enums.DataSourceMode.CONFIRMED;
        } else {
            LocalDateTime earliest = consumptionRepository.findEarliest(userId, Enums.DataSource.USER_INPUT);
            long days = earliest == null ? 0 : Duration.between(earliest, referenceTime).toDays();
            int needRecords = props.getConfirmation().getMinRecords();
            int needDays = props.getConfirmation().getMinDays();
            if (userInputCount >= needRecords && days >= needDays) {
                mode = Enums.DataSourceMode.CONFIRMED;
            } else {
                mode = Enums.DataSourceMode.ESTIMATED;
                long lackRecords = Math.max(0, needRecords - userInputCount);
                long lackDays = Math.max(0, needDays - days);
                reason = "분석에 필요한 데이터가 아직 부족합니다. "
                        + (lackRecords > 0 ? lackRecords + "건 " : "")
                        + (lackDays > 0 ? lackDays + "일 " : "")
                        + "더 기록하면 정확한 리포트를 받아보실 수 있어요.";
            }
        }

        return new AnalysisResult(userId, stats, total, overspending, bySpendDesc,
                cv, volatilityMeasured, deviations, monthly, planned, mode, userInputCount, reason);
    }

    /**
     * 최근 1개월 거래를 직전 3개월 분포에 대해 평가한다.
     * <b>두 구간은 겹치지 않는다</b> — 최근 1개월이 기준 분포에 섞이면 이상치가 자기 기준을
     * 끌어올려 탐지가 무뎌진다 (문서 §4 원칙 2 윈도우 확정).
     *
     * <p><b>희소 카테고리 처리</b>: 카테고리 표본이 임계치 미달이면 사용자 전체 분포로 대체한다.
     * 그냥 건너뛰면 신규 카테고리는 z를 못 내고, 그러면 "z 플래그 AND 룰" 구조에서
     * 룰 ②(신규 카테고리 급증)가 <b>영원히 발화하지 못한다</b>.
     */
    private List<AnalysisResult.Deviation> computeDeviations(List<Consumption> all, LocalDateTime ref) {
        LocalDateTime evalFrom = ref.minusMonths(props.getFds().getEvaluationWindowMonths());
        LocalDateTime baselineFrom = evalFrom.minusMonths(props.getFds().getBaselineWindowMonths());

        Map<String, List<Double>> baselineLogByCategory = new TreeMap<>();
        Map<String, List<Double>> baselineRawByCategory = new TreeMap<>();
        Map<String, Long> recentCountByCategory = new TreeMap<>();
        List<Double> baselineLogGlobal = new ArrayList<>();
        List<Consumption> evaluated = new ArrayList<>();

        for (Consumption c : all) {
            LocalDateTime t = c.getOccurredAt();
            String code = c.getCategory().getCode();
            if (!t.isBefore(evalFrom) && !t.isAfter(ref)) {
                evaluated.add(c);
                recentCountByCategory.merge(code, 1L, Long::sum);
            } else if (!t.isBefore(baselineFrom) && t.isBefore(evalFrom)) {
                baselineLogByCategory.computeIfAbsent(code, k -> new ArrayList<>())
                        .add(logAmount(c.getAmount()));
                baselineRawByCategory.computeIfAbsent(code, k -> new ArrayList<>())
                        .add(c.getAmount().doubleValue());
                baselineLogGlobal.add(logAmount(c.getAmount()));
            }
        }

        List<AnalysisResult.Deviation> out = new ArrayList<>();
        double threshold = props.getFds().getModifiedZThreshold();
        int minSamples = props.getFds().getMinSamplesPerCategory();
        int baselineMonths = Math.max(1, props.getFds().getBaselineWindowMonths());
        double[] globalArr = baselineLogGlobal.stream().mapToDouble(Double::doubleValue).toArray();

        for (Consumption c : evaluated) {
            String code = c.getCategory().getCode();
            List<Double> catLog = baselineLogByCategory.get(code);
            long baselineCount = (catLog == null) ? 0L : catLog.size();

            double[] arr;
            AnalysisResult.BaselineSource src;
            if (catLog != null && catLog.size() >= minSamples) {
                arr = catLog.stream().mapToDouble(Double::doubleValue).toArray();
                src = AnalysisResult.BaselineSource.CATEGORY;
            } else if (globalArr.length >= minSamples) {
                arr = globalArr;
                src = AnalysisResult.BaselineSource.GLOBAL;
            } else {
                // 전체 표본조차 미달이면 통계 판정을 시도하지 않는다.
                continue;
            }

            double z = Stats.modifiedZ(logAmount(c.getAmount()), arr);
            List<Double> catRaw = baselineRawByCategory.get(code);
            double medianRaw = (catRaw == null || catRaw.isEmpty()) ? 0.0
                    : Stats.median(catRaw.stream().mapToDouble(Double::doubleValue).toArray());

            out.add(new AnalysisResult.Deviation(
                    c.getId(), code, c.getAmount(), c.getOccurredAt(),
                    z, z > threshold, src,
                    medianRaw,
                    baselineCount,
                    recentCountByCategory.getOrDefault(code, 0L),
                    (double) baselineCount / baselineMonths));
        }
        return out;
    }

    /** 금액은 로그정규 분포에 가까우므로 log를 취한 뒤 z-score를 건다 (문서 §5 ①). */
    private static double logAmount(BigDecimal amount) {
        double v = amount.doubleValue();
        return Math.log(Math.max(v, 1.0));
    }

    private AnalysisResult empty(Long userId, String reason) {
        return new AnalysisResult(userId, new TreeMap<>(), BigDecimal.ZERO, List.of(), List.of(),
                0.0, false, List.of(), new TreeMap<>(), BigDecimal.ZERO,
                Enums.DataSourceMode.ESTIMATED, 0L, reason);
    }
}
