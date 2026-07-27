package com.finntech.engine;

import com.finntech.config.AnalysisProperties;
import com.finntech.domain.UserPayment;
import com.finntech.repository.UserPaymentRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 소비 프로필 조립(④) — ②(반복결제)·③(패턴)과 원거래를 모아 이상소비지수를 낸다. 마이데이터 결제만 대상.
 *
 * <p>지수는 4개 성분(낭비비율·과소비집중·지출변동성·심야충동)의 해석가능 가중합이며, 각 성분은 [0,1]로
 * 정규화돼 성분별 기여 점수가 그대로 드러난다(마스터 §4). 낭비비율은 규칙(제거가능 카테고리 지출/총지출)으로
 * 산출하며, 후속에 EBM 낭비확률로 교체 가능(성분 정의만 바꾸면 됨).
 *
 * <p>재현성(§3): {@code referenceTime} 주입, 집계는 {@link TreeMap}로 키 정렬 고정, 본체 {@link #buildFrom}은 순수.
 */
@Component
public class ProfileBuilder {

    private final UserPaymentRepository payments;
    private final AnalysisProperties props;

    public ProfileBuilder(UserPaymentRepository payments, AnalysisProperties props) {
        this.payments = payments;
        this.props = props;
    }

    /** 최근 {@code windowDays}일 소비 프로필(④). ②③를 같은 창으로 계산해 합산한다. */
    public UserProfile build(Long userId, LocalDateTime referenceTime, int windowDays) {
        List<UserPayment> all = payments.findByUserIdOrderByPaymentDateDesc(userId);
        LocalDateTime from = referenceTime.minusDays(windowDays);
        List<UserPayment> window = all.stream()
                .filter(p -> !p.getPaymentDate().isBefore(from) && !p.getPaymentDate().isAfter(referenceTime))
                .toList();
        List<RecurringPayment> recurring = RecurringPaymentDetector.detectFrom(
                all, referenceTime, props.getRecurring(), props.getDaypart());
        SpendingPattern pattern = PatternAnalyzer.analyzeFrom(all, from, referenceTime, props.getDaypart());
        return buildFrom(window, recurring, pattern, props.getProfile(),
                props.getCutCandidate(), props.getVolatility());
    }

    /** 순수 조립 — 테스트 진입점. {@code window}는 이미 창 필터된 결제, {@code recurring}·{@code pattern}은 같은 창 산출물. */
    static UserProfile buildFrom(List<UserPayment> window, List<RecurringPayment> recurring, SpendingPattern pattern,
                                 AnalysisProperties.Profile pf, AnalysisProperties.CutCandidate cut,
                                 AnalysisProperties.Volatility vol) {
        Set<String> removable = Set.copyOf(cut.getRemovable());

        // category1별 지출 → 총지출·최대비중(집중도)
        Map<String, Long> byCat1 = new TreeMap<>();
        long removableSpend = 0;
        for (UserPayment p : window) {
            byCat1.merge(p.getCategory1(), (long) p.getAmount(), Long::sum);
            if (removable.contains(p.getCategory2())) removableSpend += p.getAmount();
        }
        long total = byCat1.values().stream().mapToLong(Long::longValue).sum();

        String topCategory1 = null;
        long maxCat = 0;
        for (Map.Entry<String, Long> e : byCat1.entrySet()) { // TreeMap 순회 → 동점 시 사전순 앞 카테고리 고정
            if (e.getValue() > maxCat) { maxCat = e.getValue(); topCategory1 = e.getKey(); }
        }

        double wasteRatio = ratio(removableSpend, total);
        double concentration = ratio(maxCat, total);
        double volatility = monthlyVolatility(window, vol.getMinMonths(), vol.getCvCap());

        long nightSpend = pattern.amountByDaypart().getOrDefault("심야", 0L);
        long routineWasteSpend = recurring.stream()
                .filter(r -> r.type() == RecurringPayment.Type.ROUTINE && removable.contains(r.category2()))
                .mapToLong(r -> r.representativeAmount() * r.occurrenceDays())
                .sum();
        double nightImpulse = total > 0 ? Stats.clamp((double) (nightSpend + routineWasteSpend) / total, 0, 1) : 0;

        // 성분별 지수 기여(합 = 지수) → 설명가능 분해
        int pWaste = points(pf.getWasteWeight(), wasteRatio);
        int pConc = points(pf.getConcentrationWeight(), concentration);
        int pVol = points(pf.getVolatilityWeight(), volatility);
        int pNight = points(pf.getNightImpulseWeight(), nightImpulse);
        Map<String, Integer> contribution = new LinkedHashMap<>();
        contribution.put("낭비", pWaste);
        contribution.put("집중", pConc);
        contribution.put("변동", pVol);
        contribution.put("심야충동", pNight);

        int fixedCount = (int) recurring.stream().filter(r -> r.type() == RecurringPayment.Type.FIXED).count();
        int routineCount = (int) recurring.stream().filter(r -> r.type() == RecurringPayment.Type.ROUTINE).count();

        return new UserProfile(pWaste + pConc + pVol + pNight, wasteRatio, concentration, volatility, nightImpulse,
                contribution, total, topCategory1, fixedCount, routineCount, pattern.peak());
    }

    /** 월별 지출 총액의 변동계수 ÷ cvCap (clamp 0~1). 관측 월수가 minMonths 미만이면 판단 보류(0). */
    private static double monthlyVolatility(List<UserPayment> window, int minMonths, double cvCap) {
        Map<YearMonth, Long> byMonth = new TreeMap<>();
        for (UserPayment p : window) byMonth.merge(YearMonth.from(p.getPaymentDate()), (long) p.getAmount(), Long::sum);
        if (byMonth.size() < minMonths || cvCap <= 0) return 0;
        double[] totals = byMonth.values().stream().mapToDouble(Long::doubleValue).toArray();
        return Stats.clamp(Stats.coefficientOfVariation(totals) / cvCap, 0, 1);
    }

    private static double ratio(long part, long total) {
        return total > 0 ? (double) part / total : 0;
    }

    private static int points(double weight, double component) {
        return (int) Math.round(100 * weight * component);
    }
}
