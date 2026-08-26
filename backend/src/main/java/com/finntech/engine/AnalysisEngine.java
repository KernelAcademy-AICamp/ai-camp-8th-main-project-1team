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

    /** {@code yyyy-MM} 키의 실제 일수(윤년 포함). 월평균을 일액으로 환산할 때 쓴다. */
    private static int daysInMonth(String yearMonth) {
        return java.time.YearMonth.parse(yearMonth).lengthOfMonth();
    }

    @Transactional(readOnly = true)
    public AnalysisResult analyze(Long userId, LocalDateTime referenceTime) {
        return analyze(userId, referenceTime, 0);
    }

    /**
     * 최근 {@code windowDays}일만 보고 분석한다. 0 이하면 전 기간이다.
     *
     * <p><b>왜 창이 필요한가.</b> 온보딩은 "이 중에서 무엇을 줄일까"를 묻는데, 그 답을 고르려면
     * 화면에 뜬 금액과 <b>실제로 훑을 수 있는 결제 목록</b>이 같은 구간이어야 한다. 전 기간
     * 월평균은 목록으로 펼칠 수가 없다 — 어느 결제를 빼야 그 금액이 줄어드는지 대응이 안 된다.
     *
     * <p>기존 호출부(리포트·점수·취향)는 전 기간을 그대로 쓴다. 창을 쓰는 곳은 온보딩과
     * 챌린지 기준 지출뿐이다.
     */
    @Transactional(readOnly = true)
    public AnalysisResult analyze(Long userId, LocalDateTime referenceTime, int windowDays) {
        List<Consumption> all = windowDays > 0
                ? consumptionRepository.findInRange(userId, referenceTime.minusDays(windowDays), referenceTime)
                : consumptionRepository.findAllForUser(userId);

        if (all.isEmpty()) {
            return empty(userId, "기록된 소비 내역이 없습니다.");
        }

        // ---- 카테고리별 집계 --------------------------------------------------
        /**
         * <b>실제로 나간 돈.</b> 무엇을 샀는지 몰라도 여기엔 남는다 — 이 값으로
         * <i>"월소득 − 월평균지출"</i> 을 구해 가용 여유자금을 낸다({@code RecommendService}).
         * 빼면 <b>없는 여유를 있다고 권하게 된다.</b>
         */
        BigDecimal total = BigDecimal.ZERO;
        /**
         * <b>카테고리로 갈린 돈</b> — 비중의 분모다.
         *
         * <p>간편결제(결제대행사 자신)는 여기서 뺀다. 무엇을 샀는지 원리적으로 모르는 돈이라
         * 어느 칸에도 못 넣는데, 총액을 분모로 쓰면 <b>도넛이 100%가 안 된다.</b>
         * 총액과 갈라 두는 이유가 그것이다.
         */
        BigDecimal categorised = BigDecimal.ZERO;
        BigDecimal planned = BigDecimal.ZERO;
        Map<String, List<Consumption>> byCategory = new TreeMap<>();
        Map<String, String> displayNames = new TreeMap<>();
        Map<String, BigDecimal> monthly = new TreeMap<>();
        // 카테고리별로 '그 카테고리에 소비가 있었던 달'을 따로 센다. 전체 관측 개월수로 나누면
        // 최근 시작한 습관이 과소평가된다(CategoryStat.observedMonths 주석 참고).
        Map<String, Set<String>> monthsByCategory = new TreeMap<>();

        for (Consumption c : all) {
            total = total.add(c.getAmount());
            if (c.isPlanned()) planned = planned.add(c.getAmount());
            String code = c.getCategory().getCode();
            String month = c.getOccurredAt().format(MONTH);
            monthly.merge(month, c.getAmount(), BigDecimal::add);
            // **간편결제는 카테고리 집계에서만 뺀다.** 총액과 월별에는 남는다 — 실제로 나간
            // 돈이고, 그 값으로 여유자금과 변동성을 구하기 때문이다.
            if (IndustryCategoryMapper.isOutsideCategories(code)) continue;
            categorised = categorised.add(c.getAmount());
            byCategory.computeIfAbsent(code, k -> new ArrayList<>()).add(c);
            displayNames.putIfAbsent(code, c.getCategory().getDisplayName());
            monthsByCategory.computeIfAbsent(code, k -> new TreeSet<>()).add(month);
        }

        Map<String, AnalysisResult.CategoryStat> stats = new TreeMap<>();
        List<String> overspending = new ArrayList<>();
        for (Map.Entry<String, List<Consumption>> e : byCategory.entrySet()) {
            BigDecimal sum = e.getValue().stream()
                    .map(Consumption::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            // 비중의 분모는 **카테고리로 갈린 돈**이다. 총액을 쓰면 간편결제만큼 모자라
            // 도넛이 100%가 안 된다.
            double ratio = categorised.signum() == 0 ? 0.0
                    : sum.divide(categorised, 10, RoundingMode.HALF_UP).doubleValue();
            // 건수는 <b>실제 소비 건만</b> 센다 — 취소 한 줄이 "이 카테고리를 한 번 더 썼다"로
            // 세어지면 표본이 부풀고, 그 표본으로 충분·불충분을 가르면 판정 근거가 흔들린다.
            // 금액(sum)은 취소를 포함해 상쇄시킨다 — 세는 것과 더하는 것은 목적이 다르다.
            int count = (int) e.getValue().stream().filter(c -> c.getAmount().signum() > 0).count();
            boolean sufficient = count >= props.getFds().getMinSamplesPerCategory();
            Set<String> catMonths = monthsByCategory.getOrDefault(e.getKey(), Set.of());
            int observedMonths = Math.max(1, catMonths.size());
            int observedMonthDays = catMonths.stream().mapToInt(AnalysisEngine::daysInMonth).sum();
            stats.put(e.getKey(), new AnalysisResult.CategoryStat(
                    e.getKey(), displayNames.get(e.getKey()), sum, ratio, count,
                    sufficient, observedMonths, Math.max(1, observedMonthDays)));
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
            // 취소·환불(음수)은 <b>소비 한 건이 아니다.</b> 합계에서는 상쇄돼야 하므로 위쪽
            // 카테고리 집계에는 그대로 들어가지만, 건별 판정의 대상은 아니다. 게다가 아래는
            // 금액에 로그를 취하는데 음수를 넣으면 NaN 이 되어 그 카테고리의 기준선이 통째로
            // 망가진다 — 예외도 경고도 없이 판정만 조용히 어긋난다.
            if (c.getAmount().signum() <= 0) continue;
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
