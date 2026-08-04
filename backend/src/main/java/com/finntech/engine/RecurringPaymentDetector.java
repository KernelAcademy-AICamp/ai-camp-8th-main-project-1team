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
 *
 * <h2>고정지출은 '금액이 같은 것'이 아니라 '주기가 같은 것'이다 (2026-08-04 개정)</h2>
 *
 * <p>예전에는 월간에도 금액 변동계수 게이트(CV &lt; 0.05)를 걸었다. 그 결과:
 *
 * <ul>
 *   <li>넷플릭스가 요금을 <b>한 번만 올려도</b>(13,500×4 → 17,000×2, CV 0.123) 6개월치가 통째로 사라졌다
 *   <li>부분환불 1건이 섞이면(CV 0.405) 역시 사라졌다
 *   <li>통신비(사용량)·공과금(계절)·해외구독(환율)은 애초에 못 넘었다 — <b>그것들이야말로 고정지출인데</b>
 * </ul>
 *
 * <p>게다가 평균·표준편차는 {@link Stats}가 존재하는 이유(이상치가 통계량을 오염시키지 않게)를
 * 정면으로 어긴다 — 루틴형은 이미 median·MAD를 쓰고 있었고 고정형만 아니었다.
 *
 * <p>운영 데이터 실측(12명·21,273건)으로 <b>오탐을 막는 것은 금액이 아니라 간격의 규칙성</b>임을 확인했다.
 * 금액 게이트를 빼고 {@code fixedMinCount} 4 · {@code fixedGapCvMax} 0.20 이면 오탐 0이고,
 * 3 · 0.30 이면 '커피빈 노은1동점'·'미니스톱 덕천1동점'이 정기결제로 올라왔다.
 *
 * <p><b>주간(6~8일)에는 금액 게이트를 남긴다.</b> 주간 반복은 습관(매주 화요일 카페)과
 * 계약(주 1회 요가원)이 섞이는데 계약 쪽은 실제로 금액이 고정이라, 그 게이트가 둘을 갈라 준다.
 * 월 단위로 같은 곳에서 빠지는 것은 그냥 계약이다.
 */
@Component
public class RecurringPaymentDetector {

    /** 그룹 키 구분자 — 가맹점명·카테고리에 절대 안 나오는 제어문자. 눈에 안 보이므로 이스케이프로 쓴다. */
    private static final char SEP = (char) 1;

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
        out.addAll(detectFixed(txns, referenceTime, cfg));
        out.addAll(detectRoutine(txns, referenceTime, cfg, daypart));
        return out;
    }

    // ── 고정형: 한 가맹점에서 일정 주기로 반복. 월간은 금액을 묻지 않는다 ──────────────────
    private static List<RecurringPayment> detectFixed(List<UserPayment> txns, LocalDateTime referenceTime,
                                                      AnalysisProperties.Recurring cfg) {
        TreeMap<String, List<UserPayment>> groups = new TreeMap<>();
        for (UserPayment p : txns) {
            String key = groupKey(p);
            if (key == null) continue;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        LocalDate today = referenceTime.toLocalDate();
        List<RecurringPayment> out = new ArrayList<>();
        for (List<UserPayment> g : groups.values()) {
            List<LocalDate> days = g.stream().map(p -> p.getPaymentDate().toLocalDate()).distinct().sorted().toList();
            if (days.size() < cfg.getFixedMinCount()) continue;

            double[] gaps = new double[days.size() - 1];
            for (int i = 1; i < days.size(); i++) gaps[i - 1] = ChronoUnit.DAYS.between(days.get(i - 1), days.get(i));
            double meanGap = Stats.mean(gaps);
            if (meanGap <= 0) continue;
            boolean weekly = inRange(meanGap, cfg.getWeeklyIntervalDays());
            boolean monthly = inRange(meanGap, cfg.getMonthlyIntervalDays());
            if (!weekly && !monthly) continue;                                    // 주간·월간 어느 주기에도 안 맞음
            if (Stats.stdDev(gaps) / meanGap > cfg.getFixedGapCvMax()) continue;  // 주기가 수렴하지 않음 — 오탐 방어선

            // 금액은 **결제일 오름차순**으로 본다. 변화점("13,500 → 17,000")을 말하려면 순서가 있어야 한다.
            double[] amounts = g.stream()
                    .sorted(java.util.Comparator.comparing(UserPayment::getPaymentDate))
                    .mapToDouble(UserPayment::getAmount).toArray();
            double median = Stats.median(amounts);
            double dispersion = median <= 0 ? 0.0 : Stats.mad(amounts, median) / median;

            // 주간만 금액 게이트 — 습관과 계약을 가른다.
            if (weekly && dispersion > cfg.getWeeklyDispersionMax()) continue;

            // 금액이 '변했다'는 데는 두 가지가 있고, 산포 하나로는 둘 다 못 본다.
            //
            //  ① 계단 변화 — 넷플릭스 13,500×4 → 17,000×2. **MAD 가 0 이다**(과반이 같으므로).
            //     이건 산포가 아니라 변화점 문제라, 끝에서부터 같은 값이 몇 개 이어지는지로 본다.
            //  ② 원래 변동 — 통신비·공과금·환율 구독. 매달 다르다. 이건 MAD 가 잡는다.
            //
            // 한 건만 튀는 것(부분환불)은 어느 쪽도 아니다 — 꼬리가 1이면 계단으로 안 보고,
            // MAD 는 애초에 이상치 한 건에 안 흔들린다. 그래서 대표금액이 환불액에 끌려가지 않는다.
            double latest = amounts[amounts.length - 1];
            int tailRun = tailRun(amounts, cfg.getAmountVariesAbove());
            boolean stepChange = tailRun >= 2 && tailRun < amounts.length
                    && differs(latest, median, cfg.getAmountVariesAbove());
            boolean varies = stepChange || dispersion > cfg.getAmountVariesAbove();

            // 흔들리면 '최근 결제액'이 답이다 — 다음 결제일을 말하면서 금액만 과거 중앙값이면 짝이 안 맞는다.
            long representative = Math.round(varies ? latest : median);
            // 이전 값은 계단 변화일 때만 의미가 있다. '원래 매달 다른' 것에는 말할 이전 요금이 없다.
            Long prior = stepChange
                    ? Math.round(Stats.median(java.util.Arrays.copyOfRange(amounts, 0, amounts.length - tailRun)))
                    : null;

            int periodDays = (int) Math.round(meanGap);
            LocalDate first = days.get(0);
            LocalDate last = days.get(days.size() - 1);
            // 마지막 결제가 주기의 N배를 넘게 지났으면 끝난 것으로 본다. 끝난 것에는 '다음'이 없다.
            boolean ended = ChronoUnit.DAYS.between(last, today) > periodDays * cfg.getEndedAfterPeriods();

            UserPayment sample = g.get(0);
            out.add(new RecurringPayment(
                    RecurringPayment.Type.FIXED,
                    ended ? RecurringPayment.Status.ENDED : RecurringPayment.Status.ACTIVE,
                    sample.getCategory2(), sample.getMerchantName(), sample.getBusinessNumber(), null,
                    representative, varies, prior,
                    periodDays, ended ? null : last.plusDays(periodDays),
                    first, last, days.size(), round1(7.0 / meanGap)));
        }
        return out;
    }

    /**
     * 끝에서부터 <b>같은 금액이 몇 건 이어지는가</b> — 계단 변화(요금 인상)를 이상치와 가르는 잣대.
     *
     * <p>요금이 오르면 그 값이 <b>계속 유지된다</b>. 부분환불은 한 건으로 끝난다. 그래서 꼬리 길이가
     * 2 이상일 때만 "요금이 바뀌었다"고 말한다. 산포(MAD)로는 이걸 못 본다 — 6건 중 4건이 같으면
     * MAD 가 0 이라 인상 자체가 안 보인다.
     */
    private static int tailRun(double[] ascending, double tolerance) {
        double last = ascending[ascending.length - 1];
        int run = 0;
        for (int i = ascending.length - 1; i >= 0 && !differs(ascending[i], last, tolerance); i--) run++;
        return run;
    }

    /** 두 금액이 '다르다'고 볼 만큼 벌어졌는가. 1원 미만 차이는 부동소수 잡음으로 본다. */
    private static boolean differs(double a, double b, double tolerance) {
        return Math.abs(a - b) > Math.max(1.0, Math.abs(b) * tolerance);
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
            List<LocalDate> days = g.stream().map(p -> p.getPaymentDate().toLocalDate()).distinct().sorted().toList();
            long occurrenceDays = days.size();
            double appearRatio = (double) occurrenceDays / cfg.getRoutineWindowDays();
            if (appearRatio < cfg.getRoutineAppearRatio() || occurrenceDays < cfg.getRoutineMinDays()) continue;

            double[] amounts = g.stream().mapToDouble(UserPayment::getAmount).toArray();
            double median = Stats.median(amounts);
            if (median <= 0) continue;
            double dispersion = Stats.mad(amounts, median) / median;
            if (dispersion > cfg.getRoutineDispersionMax()) continue;

            int sep = e.getKey().indexOf(SEP);
            out.add(new RecurringPayment(
                    RecurringPayment.Type.ROUTINE,
                    RecurringPayment.Status.ACTIVE,   // 루틴형은 최근 창에서만 뽑으므로 언제나 진행 중이다
                    e.getKey().substring(0, sep), null, null, e.getKey().substring(sep + 1),
                    Math.round(median), dispersion > cfg.getAmountVariesAbove(), null,
                    null, null,
                    days.get(0), days.get(days.size() - 1),
                    (int) occurrenceDays, round1(occurrenceDays / (cfg.getRoutineWindowDays() / 7.0))));
        }
        return out;
    }

    /**
     * 고정형 그룹 키 — <b>사업자번호가 있으면 그것만</b> 쓴다.
     *
     * <p>예전에는 {@code category2 + 가맹점}이었는데, 사업자번호가 이미 가맹점을 특정하므로
     * 카테고리는 군더더기였다. 제공자가 업종코드를 갱신하면 <b>한 구독이 두 그룹으로 쪼개져</b>
     * 각각 최소 건수에 못 미쳐 통째로 사라질 수 있다.
     *
     * <p>사업자번호가 없을 때(해외 본사 등)만 {@code category2 + 표시명}으로 물러난다 —
     * 표시명은 표기가 흔들리므로 최소한 카테고리라도 함께 묶어 둔다.
     */
    private static String groupKey(UserPayment p) {
        String biz = p.getBusinessNumber();
        if (biz != null && !biz.isBlank()) return biz;
        if (p.getCategory2() == null || p.getMerchantName() == null) return null;
        return p.getCategory2() + SEP + p.getMerchantName();
    }

    private static boolean inRange(double v, int[] range) {
        return v >= range[0] && v <= range[1];
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
