package com.finntech.engine;

import com.finntech.config.AnalysisProperties;
import com.finntech.domain.UserPayment;
import com.finntech.repository.UserPaymentRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 반복 결제 탐지(②) — 고정형·루틴형. 마이데이터 결제({@link UserPayment})만 대상.
 *
 * <p>재현성(§3): {@code referenceTime}을 주입받고 {@code now()}를 직접 읽지 않는다. 그룹핑은
 * {@link TreeMap}로 키 정렬 고정. 탐지 본체 {@link #detectFrom}은 순수 함수라 저장소 없이 단위 테스트한다.
 *
 * <p>가맹점 식별은 사업자번호(안정 식별자, 표기 노이즈에 불변)를 우선 쓰고, 없으면 표시명으로 대체한다.
 */
@Component
public class RecurringPaymentDetector {

    /** 고정형 주기의 규칙성 상한 — 간격 표준편차가 평균의 이 비율을 넘으면 '수렴 안 함'으로 제외(잠정). */
    private static final double FIXED_INTERVAL_CV_MAX = 0.30;
    private static final char SEP = '\u0001';

    private final UserPaymentRepository payments;
    private final AnalysisProperties props;

    public RecurringPaymentDetector(UserPaymentRepository payments, AnalysisProperties props) {
        this.payments = payments;
        this.props = props;
    }

    /** 사용자의 반복 결제(고정형+루틴형)를 탐지한다. */
    public List<RecurringPayment> detect(Long userId, LocalDateTime referenceTime) {
        return detectFrom(payments.findByUserIdOrderByPaymentDateDesc(userId), referenceTime,
                props.getRecurring(), props.getDaypart());
    }

    /** 순수 탐지 — 테스트 진입점(저장소·Spring 무관). */
    static List<RecurringPayment> detectFrom(List<UserPayment> txns, LocalDateTime referenceTime,
                                             AnalysisProperties.Recurring cfg, AnalysisProperties.Daypart daypart) {
        List<RecurringPayment> out = new ArrayList<>();
        out.addAll(detectFixed(txns, cfg));
        out.addAll(detectRoutine(txns, referenceTime, cfg, daypart));
        return out;
    }

    // ── 고정형: (category2, 가맹점) 그룹이 일정 금액·일정 주기(주간/월간)로 반복 ──────────────
    private static List<RecurringPayment> detectFixed(List<UserPayment> txns, AnalysisProperties.Recurring cfg) {
        TreeMap<String, List<UserPayment>> groups = new TreeMap<>();
        for (UserPayment p : txns) {
            if (p.getCategory2() == null) continue;
            String merchant = merchantIdentity(p);
            if (merchant == null) continue;
            groups.computeIfAbsent(p.getCategory2() + SEP + merchant, k -> new ArrayList<>()).add(p);
        }
        List<RecurringPayment> out = new ArrayList<>();
        for (List<UserPayment> g : groups.values()) {
            List<LocalDate> days = g.stream().map(p -> p.getPaymentDate().toLocalDate()).distinct().sorted().toList();
            if (days.size() < cfg.getFixedMinCount()) continue;

            double[] amounts = g.stream().mapToDouble(UserPayment::getAmount).toArray();
            if (Stats.coefficientOfVariation(amounts) >= cfg.getFixedCvMax()) continue; // 금액이 들쭉날쭉하면 고정형 아님

            double[] gaps = new double[days.size() - 1];
            for (int i = 1; i < days.size(); i++) gaps[i - 1] = ChronoUnit.DAYS.between(days.get(i - 1), days.get(i));
            double meanGap = Stats.mean(gaps);
            if (meanGap <= 0) continue;
            boolean weekly = inRange(meanGap, cfg.getWeeklyIntervalDays());
            boolean monthly = inRange(meanGap, cfg.getMonthlyIntervalDays());
            if (!weekly && !monthly) continue;                                  // 주간·월간 어느 주기에도 안 맞음
            if (Stats.stdDev(gaps) / meanGap > FIXED_INTERVAL_CV_MAX) continue; // 주기가 수렴하지 않음

            int periodDays = (int) Math.round(meanGap);
            UserPayment sample = g.get(0);
            out.add(new RecurringPayment(
                    RecurringPayment.Type.FIXED, sample.getCategory2(),
                    sample.getMerchantName(), sample.getBusinessNumber(), null,
                    Math.round(Stats.median(amounts)), periodDays,
                    days.get(days.size() - 1).plusDays(periodDays), days.size(),
                    round1(7.0 / meanGap)));
        }
        return out;
    }

    // ── 루틴형: (category2, 시간대) 가 최근 창에서 자주 반복(가맹점 무관, 금액 산포만 작으면 통과) ──────
    private static List<RecurringPayment> detectRoutine(List<UserPayment> txns, LocalDateTime referenceTime,
                                                        AnalysisProperties.Recurring cfg, AnalysisProperties.Daypart daypart) {
        LocalDateTime from = referenceTime.minusDays(cfg.getRoutineWindowDays());
        TreeMap<String, List<UserPayment>> groups = new TreeMap<>();
        for (UserPayment p : txns) {
            if (p.getCategory2() == null) continue;
            LocalDateTime at = p.getPaymentDate();
            if (at.isBefore(from) || at.isAfter(referenceTime)) continue;       // 최근 창 밖
            String bucket = daypart.bucketOf(at.getHour());
            groups.computeIfAbsent(p.getCategory2() + SEP + bucket, k -> new ArrayList<>()).add(p);
        }
        List<RecurringPayment> out = new ArrayList<>();
        for (var e : groups.entrySet()) {
            List<UserPayment> g = e.getValue();
            long occurrenceDays = g.stream().map(p -> p.getPaymentDate().toLocalDate()).distinct().count();
            double appearRatio = (double) occurrenceDays / cfg.getRoutineWindowDays();
            if (appearRatio < cfg.getRoutineAppearRatio() || occurrenceDays < cfg.getRoutineMinDays()) continue;

            double[] amounts = g.stream().mapToDouble(UserPayment::getAmount).toArray();
            double median = Stats.median(amounts);
            if (median <= 0 || Stats.mad(amounts, median) / median > cfg.getRoutineDispersionMax()) continue;

            int sep = e.getKey().indexOf(SEP);
            out.add(new RecurringPayment(
                    RecurringPayment.Type.ROUTINE, e.getKey().substring(0, sep), null, null,
                    e.getKey().substring(sep + 1), Math.round(median), null, null,
                    (int) occurrenceDays, round1(occurrenceDays / (cfg.getRoutineWindowDays() / 7.0))));
        }
        return out;
    }

    /** 안정 식별자(사업자번호) 우선, 없으면 표시명. 둘 다 없으면 null(그룹 제외). */
    private static String merchantIdentity(UserPayment p) {
        return p.getBusinessNumber() != null ? p.getBusinessNumber() : p.getMerchantName();
    }

    private static boolean inRange(double v, int[] range) {
        return v >= range[0] && v <= range[1];
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
