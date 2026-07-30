package com.finntech.engine;

import com.finntech.config.AnalysisProperties;
import com.finntech.domain.UserPayment;
import com.finntech.repository.UserPaymentRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 절약 후보 선정(⑤) — 최근 창의 결제를 category2로 모아 3등급 규칙으로 후보를 뽑고 절감액 순으로 정렬한다.
 *
 * <p>보호 카테고리(공과금·통신비 등)는 원천 제외한다. 제거가능은 전액을, 최적화가능은 중앙값 초과분을 절감액으로
 * 삼는다. 미분류 category2(3등급 어디에도 없음)는 보수적으로 후보에서 뺀다. 판정 근거는 코드가 만들고(원칙 1),
 * {@code wasteRatioThreshold}는 후속 EBM 낭비확률 게이트로 예약(현 단계는 3등급 규칙만).
 *
 * <p>재현성(§3): {@code referenceTime} 주입, 그룹핑 {@link TreeMap} 고정, 순수 코어 {@link #selectFrom} 단위테스트.
 */
@Component
public class CutCandidateSelector {

    private final UserPaymentRepository payments;
    private final AnalysisProperties props;

    private final IndustryCategoryMapper industryMapper;

    /**
     * 사용자 → 중분류별 ML 낭비 비율. 표가 비면 게이트를 통과시킨다.
     *
     * <p>{@code WasteScoringService}를 직접 주입하지 않고 함수로 받는 이유: 엔진이 ml 패키지를 알면
     * 의존이 engine → ml → engine 으로 돌아 순환이 된다. 함수 하나만 받으면 엔진은 여전히
     * "숫자를 받아 규칙을 적용하는" 자리로 남는다.
     */
    private final java.util.function.Function<Long, Map<String, Double>> wasteRatios;

    public CutCandidateSelector(UserPaymentRepository payments, AnalysisProperties props,
                                IndustryCategoryMapper industryMapper,
                                java.util.function.Function<Long, Map<String, Double>> wasteRatios) {
        this.payments = payments;
        this.props = props;
        this.industryMapper = industryMapper;
        this.wasteRatios = wasteRatios;
    }

    /**
     * 한 달의 평균 일수(365.2425 ÷ 12). 창 합계를 "월 얼마"로 환산할 때 쓴다.
     * 30으로 잡으면 달력 한 달보다 1.4% 짧게 나와 절감액이 그만큼 과소 표시된다.
     */
    private static final double DAYS_PER_MONTH = 30.436875;

    /** 최근 {@code windowDays}일 절약 후보(⑤). 절감액 큰 순. 금액은 <b>월 환산</b>이다. */
    public List<CutCandidate> select(Long userId, LocalDateTime referenceTime, int windowDays) {
        LocalDateTime from = referenceTime.minusDays(windowDays);
        List<UserPayment> window = payments.findByUserIdOrderByPaymentDateDesc(userId).stream()
                .filter(p -> !p.getPaymentDate().isBefore(from) && !p.getPaymentDate().isAfter(referenceTime))
                .toList();
        return selectFrom(window, props.getCutCandidate(), windowDays, industryMapper::discretionaryOf,
                wasteRatios.apply(userId));
    }

    /** 창 합계를 한 달치로 환산한다. 창이 비정상이면(0 이하) 그대로 둔다. */
    private static long toMonthly(long windowTotal, int windowDays) {
        if (windowDays <= 0) return windowTotal;
        return Math.round(windowTotal * DAYS_PER_MONTH / windowDays);
    }

    /** ML 없이 부르는 옛 호출부·테스트용 — 게이트를 통과시킨다(빈 표 = 근거 없음 = 통과). */
    static List<CutCandidate> selectFrom(List<UserPayment> window, AnalysisProperties.CutCandidate cfg,
                                         int windowDays, java.util.function.ToDoubleFunction<String> discretionary) {
        return selectFrom(window, cfg, windowDays, discretionary, Map.of());
    }

    /**
     * 순수 선정 — 테스트 진입점.
     *
     * <p><b>ML 낭비확률 게이트.</b> 등급은 재량성이 정하지만, 그 사람이 <b>실제로</b> 그 카테고리를
     * 낭비하고 있는지는 재량성이 모른다. 취미/여가는 누구에게나 재량 0.63이라 늘 '전액 제거가능'이
     * 됐다 — 취미를 아껴 쓰는 사람에게도 "월 115만원을 통째로 줄이세요"가 나갔다.
     *
     * <p>그래서 EBM이 낸 <b>중분류별 낭비 비율</b>로 두 가지를 한다.
     * <ol>
     *   <li>비율이 {@code wasteRatioThreshold} 미만이면 후보에서 뺀다 — 그 사람에겐 낭비가 아니다.</li>
     *   <li>제거가능의 절감액을 <b>낭비 비율만큼</b>으로 잡는다. 62%가 낭비인데 100%를 절약액이라
     *       적으면 그건 숫자가 아니라 과장이다.</li>
     * </ol>
     *
     * <p>모델이 준비되지 않았거나({@code SpendingClassifier.isReady()==false}) 그 카테고리에 근거가
     * 없으면 표가 비어 있고, 그때는 <b>게이트를 통과시키고 전액</b>으로 둔다 — 판정의 근거가 없을 때
     * 조언을 지우는 것이 아니라, 예전 규칙 그대로 두는 편이 덜 놀랍다.
     */
    static List<CutCandidate> selectFrom(List<UserPayment> window, AnalysisProperties.CutCandidate cfg,
                                         int windowDays, java.util.function.ToDoubleFunction<String> discretionary,
                                         Map<String, Double> wasteRatioByCategory) {
        TreeMap<String, List<Integer>> byCat2 = new TreeMap<>();
        for (UserPayment p : window) {
            String cat2 = p.getCategory2();
            // 재량성이 낮으면 생존필수 — 줄이라고 권하지 않는다(약값·통신비·교통비).
            // 무엇을 샀는지 모르는 소비(간편결제 등)도 뺀다 — "카테고리없음을 줄이세요"는
            // 사용자가 행동으로 옮길 수 없는 조언이다.
            if (cat2 == null || IndustryCategoryMapper.UNCLASSIFIED.equals(cat2)) continue;
            if (discretionary.applyAsDouble(cat2) < cfg.getProtectedBelow()) continue;
            byCat2.computeIfAbsent(cat2, k -> new ArrayList<>()).add(p.getAmount());
        }

        List<CutCandidate> out = new ArrayList<>();
        for (var e : byCat2.entrySet()) {
            String cat2 = e.getKey();
            List<Integer> amounts = e.getValue();
            // 창 합계를 월 환산해서 담는다. 근거 문장도 같은 값을 써야 숫자와 설명이 어긋나지 않는다.
            long monthlySpend = toMonthly(amounts.stream().mapToLong(Integer::longValue).sum(), windowDays);

            // ML 게이트 — 근거가 있고 그 비율이 임계 미만이면 뺀다.
            Double ratio = wasteRatioByCategory.get(cat2);
            if (ratio != null && ratio < cfg.getWasteRatioThreshold()) continue;

            if (discretionary.applyAsDouble(cat2) >= cfg.getRemovableAbove()) {
                if (ratio != null) {
                    long saving = Math.round(monthlySpend * ratio);
                    if (saving <= 0) continue;
                    out.add(new CutCandidate(cat2, CutCandidate.Type.REMOVABLE, monthlySpend, saving,
                            cat2 + " 지출 월 " + won(monthlySpend) + " 중 "
                                    + Math.round(ratio * 100) + "%가 줄일 수 있는 소비 — 월 "
                                    + won(saving) + " 절약 가능(제거가능)"));
                } else {
                    out.add(new CutCandidate(cat2, CutCandidate.Type.REMOVABLE, monthlySpend, monthlySpend,
                            cat2 + " 지출 월 " + won(monthlySpend) + "은 전액 절약 대상(제거가능)"));
                }
            } else {
                // 중앙값은 결제 한 건의 크기라 환산하지 않는다 — 초과분 합계만 월 단위로 바꾼다.
                long median = Math.round(Stats.median(amounts.stream().mapToDouble(Integer::doubleValue).toArray()));
                long excess = toMonthly(amounts.stream().mapToLong(a -> Math.max(0, a - median)).sum(), windowDays);
                if (excess > 0) {
                    out.add(new CutCandidate(cat2, CutCandidate.Type.OPTIMIZABLE, monthlySpend, excess,
                            cat2 + " 중앙값(" + won(median) + ") 초과분 월 " + won(excess) + " 절감 가능(최적화가능)"));
                }
            }
        }
        out.sort(Comparator.comparingLong(CutCandidate::estimatedSaving).reversed()
                .thenComparing(CutCandidate::category2)); // 동점은 사전순 → 결정론
        return out;
    }

    private static String won(long v) {
        return String.format("%,d원", v);
    }
}
