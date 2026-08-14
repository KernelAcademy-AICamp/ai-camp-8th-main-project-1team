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
 *
 * <h2>판정은 묶음 명단도 함께 낸다 (2026-08-14)</h2>
 *
 * <p>예전에는 판정이 끝나면 <b>어떤 결제가 그 묶음을 이뤘는지 버렸다.</b> 화면에는 요약만
 * 필요했기 때문이다. 그런데 결제 한 줄마다 "이것이 고정지출인가"를 적어 두려면 그 명단이
 * 있어야 한다 — 밖에서 다시 묶으려면 {@link #merchantKeyOf} 와 {@code fixedGroupFrom} 의
 * 규칙을 한 벌 더 적는 수밖에 없고, 그러면 언젠가 한쪽만 고쳐진다.
 *
 * <p>그래서 진입점을 둘로 둔다. {@link #detect} 는 예전 그대로 요약만 내고,
 * {@link #fixedGroups} 는 {@link FixedGroup}(요약 + 명단 + 묶음 키 + 주기 종류 + 간격 CV)을
 * 낸다. <b>판정 본체는 뒤쪽 하나뿐이고 앞쪽은 그것을 옮겨 담기만 한다</b> — 두 답이
 * 갈라질 자리를 만들지 않는다.
 */
@Component
public class RecurringPaymentDetector {

    /** 루틴형 그룹 키 구분자 — 카테고리·시간대에 절대 안 나오는 제어문자. 밖으로 나가지 않는다. */
    private static final char SEP = (char) 1;

    /** 고정형 묶음 키 접두어 — 사업자번호로 묶였다. {@link #merchantKeyOf} 참조. */
    public static final String BIZ_KEY_PREFIX = "BIZ:";

    /** 고정형 묶음 키 접두어 — 가맹점명으로 묶였다(번호가 없거나 PG 번호라 버렸다). */
    public static final String NAME_KEY_PREFIX = "NAME:";

    private final UserPaymentRepository payments;
    private final AnalysisProperties props;
    private final IndustryCategoryMapper industryMapper;

    public RecurringPaymentDetector(UserPaymentRepository payments, AnalysisProperties props,
                                    IndustryCategoryMapper industryMapper) {
        this.payments = payments;
        this.props = props;
        this.industryMapper = industryMapper;
    }

    /** 사용자의 반복 결제(고정형+루틴형)를 탐지한다. */
    public List<RecurringPayment> detect(Long userId, LocalDateTime referenceTime) {
        return detectFrom(payments.findByUserIdOrderByPaymentDateDesc(userId), referenceTime,
                props.getRecurring(), props.getDaypart(), industryMapper::isPaymentAgency);
    }

    /**
     * 사용자의 <b>고정형</b> 묶음을 명단과 함께 낸다 — {@link #detect} 와 같은 판정, 더 많은 값.
     *
     * <p>결제 한 줄마다 고정지출 여부를 적어 두려는 쪽이 이것을 쓴다. 루틴형은 담지 않는다:
     * 루틴형 묶음은 (category2, 시간대)라 분류가 바뀔 때마다 다시 갈리고, 최근 창
     * ({@code routineWindowDays})에 매여 있어 <b>오늘 날짜를 봐야 아는 값</b>이다.
     */
    public List<FixedGroup> fixedGroups(Long userId, LocalDateTime referenceTime) {
        return fixedGroupsFrom(payments.findByUserIdOrderByPaymentDateDesc(userId), referenceTime,
                props.getRecurring(), industryMapper::isPaymentAgency);
    }

    /** 순수 탐지 — 테스트 진입점(저장소·Spring 무관). PG 목록이 없으면 번호를 그대로 쓴다. */
    static List<RecurringPayment> detectFrom(List<UserPayment> txns, LocalDateTime referenceTime,
                                             AnalysisProperties.Recurring cfg, AnalysisProperties.Daypart daypart) {
        return detectFrom(txns, referenceTime, cfg, daypart, biz -> false);
    }

    static List<RecurringPayment> detectFrom(List<UserPayment> txns, LocalDateTime referenceTime,
                                             AnalysisProperties.Recurring cfg, AnalysisProperties.Daypart daypart,
                                             java.util.function.Predicate<String> isPaymentAgency) {
        List<RecurringPayment> out = new ArrayList<>();
        // 고정형 판정은 fixedGroupsFrom 한 곳에만 있다 — 여기서는 요약만 꺼내 담는다.
        for (FixedGroup group : fixedGroupsFrom(txns, referenceTime, cfg, isPaymentAgency)) {
            out.add(group.summary());
        }
        out.addAll(detectRoutine(txns, referenceTime, cfg, daypart));
        return out;
    }

    // ── 고정형: 한 가맹점에서 일정 주기로 반복. 월간은 금액을 묻지 않는다 ──────────────────

    /**
     * 고정형 묶음 판정의 <b>본체</b> — 요약만 필요한 쪽({@link #detectFrom})도 이것을 거친다.
     *
     * <p>순수 함수라 저장소·Spring 없이 단위 시험한다. PG 목록이 없으면 번호를 그대로 쓴다.
     *
     * <p>{@link #detectFrom} 과 달리 <b>패키지 밖에도 연다.</b> 소비 원장이 저장된 줄을 다시
     * 만들어 견주는 대조 점검을 하는데, 그때 Spring 없이 같은 판정을 돌릴 길이 있어야 한다.
     */
    public static List<FixedGroup> fixedGroupsFrom(List<UserPayment> txns, LocalDateTime referenceTime,
                                                   AnalysisProperties.Recurring cfg,
                                                   java.util.function.Predicate<String> isPaymentAgency) {
        TreeMap<String, List<UserPayment>> groups = new TreeMap<>();
        for (UserPayment p : txns) {
            String key = merchantKeyOf(p.getBusinessNumber(), p.getMerchantName(), isPaymentAgency);
            if (key == null) continue;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        LocalDate today = referenceTime.toLocalDate();
        List<FixedGroup> out = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            String merchantKey = entry.getKey();
            List<UserPayment> g = entry.getValue();
            FixedGroup whole = fixedGroupFrom(g, today, cfg, merchantKey);
            if (whole != null) { out.add(whole); continue; }

            // 한 가맹점 아래 **구독이 여럿**일 수 있다 — 앱마켓이 그렇다. 통째로 보면 날짜와
            // 금액이 뒤섞여 어느 주기에도 안 맞지만, 금액으로 나누면 각각 또렷한 월 구독이다.
            // 2026-08-05 실사용자: `Apple` 15건이 통째로는 탈락했는데 금액별로 나누니
            // **매달 5일 2,500원**과 **매달 11일 14,000원** 두 구독이 드러났다.
            //
            // **통째로 먼저 보는 순서가 중요하다.** 요금 인상(13,500→17,000)은 금액이 달라도
            // 한 구독이므로, 금액으로 먼저 나누면 그것이 둘로 찢어진다(§8-W).
            //
            // 한 결제가 두 묶음에 드는 일은 없다 — 통째로 잡히면 여기 안 오고, 여기 오면
            // 금액으로만 갈리는데 한 결제의 금액은 하나다. 표가 결제 한 줄에 고정지출 칸
            // 한 벌만 두는 것이 이 성질에 기댄다.
            java.util.Map<Integer, List<UserPayment>> byAmount = new TreeMap<>();
            for (UserPayment p : g) byAmount.computeIfAbsent(p.getAmount(), k -> new ArrayList<>()).add(p);
            if (byAmount.size() < 2) continue;
            for (List<UserPayment> sub : byAmount.values()) {
                FixedGroup part = fixedGroupFrom(sub, today, cfg, merchantKey);
                if (part != null) out.add(part);
            }
        }
        return out;
    }

    /** 한 묶음이 고정 결제인가 — 아니면 {@code null}. 묶는 방법과 판정을 갈라 둔다. */
    private static FixedGroup fixedGroupFrom(List<UserPayment> g, LocalDate today,
                                             AnalysisProperties.Recurring cfg, String merchantKey) {
        {
            List<LocalDate> days = g.stream().map(p -> p.getPaymentDate().toLocalDate()).distinct().sorted().toList();
            if (days.size() < cfg.getFixedMinCount()) return null;

            double[] gaps = new double[days.size() - 1];
            for (int i = 1; i < days.size(); i++) gaps[i - 1] = ChronoUnit.DAYS.between(days.get(i - 1), days.get(i));
            double meanGap = Stats.mean(gaps);
            if (meanGap <= 0) return null;
            boolean weekly = inRange(meanGap, cfg.getWeeklyIntervalDays());
            boolean monthly = inRange(meanGap, cfg.getMonthlyIntervalDays());
            if (!weekly && !monthly) return null;                    // 주간·월간 어느 주기에도 안 맞음
            double gapCv = Stats.stdDev(gaps) / meanGap;
            if (gapCv > cfg.getFixedGapCvMax()) return null;         // 주기가 수렴하지 않음 — 오탐 방어선

            // 금액은 **결제일 오름차순**으로 본다. 변화점("13,500 → 17,000")을 말하려면 순서가 있어야 한다.
            double[] amounts = g.stream()
                    .sorted(java.util.Comparator.comparing(UserPayment::getPaymentDate))
                    .mapToDouble(UserPayment::getAmount).toArray();
            double median = Stats.median(amounts);
            double dispersion = median <= 0 ? 0.0 : Stats.mad(amounts, median) / median;

            // 주간만 금액 게이트 — 습관과 계약을 가른다.
            if (weekly && dispersion > cfg.getWeeklyDispersionMax()) return null;

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
            RecurringPayment summary = new RecurringPayment(
                    RecurringPayment.Type.FIXED,
                    ended ? RecurringPayment.Status.ENDED : RecurringPayment.Status.ACTIVE,
                    sample.getCategory2(), sample.getMerchantName(), sample.getBusinessNumber(), null,
                    representative, varies, prior,
                    periodDays, ended ? null : last.plusDays(periodDays),
                    first, last, days.size(), round1(7.0 / meanGap));

            // 명단은 **결제일 오름차순, 같은 날이면 식별자 순**으로 고정한다. 저장소가 내주는
            // 순서(결제일 내림차순)를 그대로 흘리면 같은 입력에 같은 출력이라는 보장이 없다(원칙 3).
            List<String> paymentIds = g.stream()
                    .sorted(java.util.Comparator.comparing(UserPayment::getPaymentDate)
                            .thenComparing(UserPayment::getPaymentId))
                    .map(UserPayment::getPaymentId)
                    .toList();
            return new FixedGroup(summary, merchantKey,
                    weekly ? FixedGroup.PeriodKind.WEEKLY : FixedGroup.PeriodKind.MONTHLY,
                    gapCv, paymentIds);
        }
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
     * 같은 계약을 한 묶음으로 모으는 키.
     *
     * <p><b>PG 번호는 키로 쓰지 않는다.</b> 번호가 결제를 대행한 회사의 것이라, 같은 구독이
     * 대행사가 바뀔 때마다 다른 묶음으로 흩어진다. 실측(2026-08-05): 넷플릭스가 매달 22일
     * 22,000원씩 7회 결제됐는데 KG이니시스 5건·NHNKCP 2건으로 갈렸다. 앞은 2월이 비어 주기가
     * 수렴하지 않고 뒤는 최소 건수에 못 미쳐, <b>완벽한 월 구독이 어느 쪽에서도 안 잡혔다.</b>
     * 번호를 버리면 가맹점명으로 모여 7건이 한 묶음이 된다 —
     * {@code MerchantCategoryService.lookup} 이 PG 에서 번호를 버리는 것과 같은 이유다.
     *
     * <p>그래서 키는 <b>사업자번호(PG 가 아닐 때) 또는 가맹점명 완전일치</b> 둘 중 하나다.
     * <b>카테고리는 키에 넣지 않는다.</b> 넣으면 분류가 바뀌는 순간 묶음이 쪼개진다 —
     * 사용자가 "이건 식비예요"를 누르거나 추정이 확정으로 승격되기만 해도 그때까지 잡히던
     * 정기결제가 사라진다. 계약이 계약인 것은 <b>어디서 얼마를 언제</b> 냈느냐이지
     * 우리가 그것을 무엇으로 분류했느냐가 아니다.
     *
     * <p><b>어느 쪽으로 묶였는지 접두어로 밝힌다</b>({@code BIZ:} · {@code NAME:}).
     * 예전에는 이름 쪽에 제어문자 {@code U+0001} 을 붙였는데, 이 값이 이제 밖으로 나가
     * 표에 적히므로 눈에 보이는 형태라야 한다. 두 이름공간은 부딪히지 않는다 —
     * 사업자번호는 숫자뿐이라 {@code BIZ:} 로 시작할 수 없고, 같은 문자열이 양쪽에 걸리는
     * 일이 없다. <b>묶는 결과는 한 글자도 안 바뀐다.</b>
     *
     * @return 묶음 키, 또는 번호도 이름도 없어 어느 묶음에도 못 드는 결제면 {@code null}
     */
    public static String merchantKeyOf(String businessNumber, String merchantName,
                                       java.util.function.Predicate<String> isPaymentAgency) {
        if (businessNumber != null && !businessNumber.isBlank() && !isPaymentAgency.test(businessNumber)) {
            return BIZ_KEY_PREFIX + businessNumber;
        }
        return merchantName == null || merchantName.isBlank() ? null : NAME_KEY_PREFIX + merchantName;
    }

    private static boolean inRange(double v, int[] range) {
        return v >= range[0] && v <= range[1];
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
