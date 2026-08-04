package com.finntech.service;

import com.finntech.domain.UserCard;
import com.finntech.domain.UserPayment;
import com.finntech.engine.RecurringPayment;
import com.finntech.engine.RecurringPaymentDetector;
import com.finntech.engine.Stats;
import com.finntech.repository.UserCardRepository;
import com.finntech.repository.UserPaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@link FundFlowSource}의 실동 구현 — 자금 흐름 5축 재료를 <b>마이데이터에서 직접</b> 조립한다.
 * ①의 (B)를 기다리지 않기로 한 결정(B안)에 따른 소스다. 소스만 이걸로 갈아끼웠고 {@link FundFlowService}·
 * {@link FundFlowInputs}(계약)는 손대지 않는다 — seam이 한 일이 정확히 이거다.
 *
 * <p><b>이중 계산 안전.</b> 계좌에서는 <b>잔액(스냅샷 숫자)과 급여만</b> 읽고, 계좌 출금 거래는 소비로 세지 않는다.
 * 소비(월평균지출·고정비·큰 지출)는 전부 카드 결제({@link UserPayment})로만 집계한다 — ①이 소비를 카드
 * 승인내역으로만 팔로우하는 것과 같은 규칙(R7). 그래서 카드 22만원과 그 대금 계좌 출금 22만원을 겹쳐 세지 않는다.
 *
 * <p><b>다섯 축을 어디서 얻나.</b>
 * <table>
 *   <tr><td>L1 소득</td><td>계좌 급여 요약(`salary`·`payday`)</td></tr>
 *   <tr><td>L2 고정비</td><td>{@link RecurringPaymentDetector}의 <b>고정형</b> 반복 결제 — 통신비·구독</td></tr>
 *   <tr><td>L3 안정성</td><td>계좌 잔액 ÷ 카드 월평균지출(AoM)</td></tr>
 *   <tr><td>L4 유동성</td><td>반복이 아닌 <b>큰 1회성</b> 카드 결제의 주기·규칙성</td></tr>
 *   <tr><td>L5 우대조건</td><td>{@link UserCard}의 전월 실적 · 계좌 급여 수신 여부</td></tr>
 * </table>
 *
 * <p><b>재료를 못 만들면 해당 서브레코드를 {@code null}로 둔다</b> — 그러면 그 축이 UNKNOWN이 된다.
 * 0이나 false로 채우지 않는다: "고정비가 0원"과 "고정비를 모른다"는 다른 말이고, 뒤엣것을 앞엣것으로
 * 바꿔 쓰면 없는 사실을 지어내는 것이 된다(§14).
 */
@Component
public class AccountFundFlowSource implements FundFlowSource {

    private static final Logger log = LoggerFactory.getLogger(AccountFundFlowSource.class);

    /** 월평균지출 산정 창(개월). 취미보다 소비는 촘촘해 6개월이면 대표값이 안정적이다. */
    static final int WINDOW_MONTHS = 6;

    /** 한 달을 며칠로 환산할지 — 고정비 월 환산·큰 지출 주기의 개월 환산에 함께 쓴다. */
    private static final double DAYS_PER_MONTH = 30.0;

    private final MyDataLinkService myDataLinkService;
    private final UserPaymentRepository paymentRepository;
    private final UserCardRepository cardRepository;
    private final RecurringPaymentDetector recurringDetector;
    private final Clock clock;
    private final Thresholds thresholds;

    public AccountFundFlowSource(
            MyDataLinkService myDataLinkService,
            UserPaymentRepository paymentRepository,
            UserCardRepository cardRepository,
            RecurringPaymentDetector recurringDetector,
            @Value("${finntech.fund-flow.large-expense-ratio:0.5}") double largeExpenseRatio,
            @Value("${finntech.fund-flow.large-expense-predictable-cv-max:0.35}") double predictableCvMax,
            @Value("${finntech.fund-flow.card-performance-threshold:300000}") int cardPerformanceThreshold,
            Clock clock) {
        this.myDataLinkService = myDataLinkService;
        this.paymentRepository = paymentRepository;
        this.cardRepository = cardRepository;
        this.recurringDetector = recurringDetector;
        this.clock = clock;
        this.thresholds = new Thresholds(largeExpenseRatio, predictableCvMax, cardPerformanceThreshold);
    }

    @Override
    public FundFlowInputs load(Long userId) {
        MyDataResponses.AccountView account;
        try {
            // 평균잔고를 내려면 잔고가 어떻게 움직였는지가 필요하다 — 기본 조회(1개월)로는 톱니를 못 본다.
            account = myDataLinkService.account(userId, WINDOW_MONTHS);   // 은행 미연동이면 null
        } catch (RuntimeException e) {
            // 마이데이터 조회 실패(네트워크 등)는 서비스 장애로 번지지 않게 재료 없이 진행 → 전 축 UNKNOWN.
            log.warn("자금흐름 계좌 조회 실패 userId={} — 재료 없이 진행: {}", userId, e.toString());
            return FundFlowInputs.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<UserPayment> payments = paymentRepository.findByUserIdOrderByPaymentDateDesc(userId);
        List<UserCard> cards = cardRepository.findByUserIdOrderByIdAsc(userId);
        List<RecurringPayment> recurring = recurringDetector.detect(userId, now);
        return assemble(account, payments, cards, recurring, now, thresholds);
    }

    // ── 순수 조립 (테스트 진입점) ──────────────────────────────────────────────

    /** 계좌·카드·반복결제에서 자금 흐름 재료를 조립한다. 계좌가 없으면(은행 미연동) 빈 입력. */
    static FundFlowInputs assemble(MyDataResponses.AccountView account, List<UserPayment> payments,
                                   List<UserCard> cards, List<RecurringPayment> recurring,
                                   LocalDateTime now, Thresholds th) {
        if (account == null) return FundFlowInputs.empty();

        LocalDateTime since = now.minusMonths(WINDOW_MONTHS);
        long monthlyAvgSpend = monthlyAvgSpend(payments, since);

        // L1 소득 — 급여 요약만 읽는다(계좌 원본 아님). 더미는 급여가 고정·정기라 salary>0이면 규칙적.
        FundFlowInputs.IncomeProfile income =
                new FundFlowInputs.IncomeProfile(account.salary() > 0, account.payday(), account.salary());
        // L3 안정성 — 기간 가중 평균잔고 ÷ 카드 기반 월평균지출.
        FundFlowInputs.CashBuffer cashBuffer =
                new FundFlowInputs.CashBuffer(averageBalance(account, now), monthlyAvgSpend);

        return new FundFlowInputs(
                income,
                fixedExpense(recurring),
                cashBuffer,
                largeExpense(payments, recurring, monthlyAvgSpend, since, th),
                preferential(account, cards, th.cardPerformanceThreshold()));
    }

    /**
     * 창 안(since 이후) 카드 결제 합 ÷ 실제 지출이 있던 개월 수. 소비는 <b>카드 내역만</b> 센다(이중 계산 방지).
     * 지출이 없으면 0. 데이터가 2개월치뿐인 사용자를 6으로 나눠 과소평가하지 않도록 <b>실제 개월 수</b>로 나눈다.
     */
    static long monthlyAvgSpend(List<UserPayment> payments, LocalDateTime since) {
        long total = 0;
        Set<YearMonth> months = new HashSet<>();
        for (UserPayment p : payments) {
            if (p.getPaymentDate() == null || p.getPaymentDate().isBefore(since)) continue;
            total += Math.max(p.getAmount(), 0);
            months.add(YearMonth.from(p.getPaymentDate()));
        }
        return total / Math.max(1, months.size());
    }

    /**
     * L3의 평균잔고 — 잔고가 각 값으로 <b>머문 기간</b>으로 가중해 낸다.
     *
     * <p><b>왜 현재 잔액 하나로는 안 되나.</b> 통장 잔고는 월급날 솟았다가 한 달에 걸쳐 깎이는 톱니다. 점 하나를
     * 찍으면 월급 직후엔 버퍼가 두꺼운 사람, 직전엔 얇은 사람이 된다 — 같은 사람인데 <b>조회 시각으로 판정이
     * 뒤집힌다.</b> 게다가 THIN은 FP-01의 M2를 켜서 추천 순서를 통째로 바꾸므로 흔들림이 그대로 화면에 나온다.
     *
     * <p><b>왜 단순 평균이 아닌가.</b> 거래 건수로 평균 내면 결제가 몰린 며칠에 가중치가 쏠린다.
     * 350만으로 3일·90만으로 27일 있었으면 답은 116만이지 220만이 아니다.
     *
     * <p>거래내역이 없으면(신규 계좌·미조회) 현재 잔액으로 물러난다 — 없는 시계열을 지어내지 않는다.
     */
    static long averageBalance(MyDataResponses.AccountView account, LocalDateTime now) {
        List<MyDataResponses.AccountTxnView> txns = account.transactions() == null ? List.of()
                : account.transactions().stream()
                        .filter(t -> t.date() != null)
                        .sorted(Comparator.comparing(MyDataResponses.AccountTxnView::date))
                        .toList();   // 제공자는 최신순으로 주므로 오름차순으로 되돌린다
        if (txns.isEmpty()) return account.balance();

        // 본체 Clock과 제공자 커트오프가 어긋나도(데모 고정일 등) 마지막 구간이 음수가 되지 않게 끝을 맞춘다.
        LocalDateTime lastAt = txns.get(txns.size() - 1).date();
        LocalDateTime end = lastAt.isAfter(now) ? lastAt : now;

        double weighted = 0;
        long totalMinutes = 0;
        for (int i = 0; i < txns.size(); i++) {
            boolean last = i + 1 == txns.size();
            long minutes = Math.max(0, ChronoUnit.MINUTES.between(
                    txns.get(i).date(), last ? end : txns.get(i + 1).date()));
            // 마지막 구간의 잔고는 현재 잔액이 정본이다(그 뒤로 거래가 없으니 balanceAfter와 같아야 한다).
            long balance = last ? account.balance() : txns.get(i).balanceAfter();
            weighted += (double) balance * minutes;
            totalMinutes += minutes;
        }
        // 거래가 전부 같은 시각이면 기간 가중을 낼 수 없다 → 스냅샷.
        return totalMinutes <= 0 ? account.balance() : Math.round(weighted / totalMinutes);
    }

    // ── L2 고정비 ─────────────────────────────────────────────────────────────

    /**
     * L2 — <b>고정형</b> 반복 결제만 고정비로 센다. 루틴형(아침 커피 같은 습관)은 제외한다: 끊으려면
     * 끊을 수 있는 소비지 매달 빠져나가는 고정비가 아니다.
     *
     * <p>주기가 제각각(주간·월간)이라 <b>월 환산</b>해 더한다 — 주간 1만원은 월 4.3만원이다.
     * 고정형을 하나도 못 찾으면 {@code null}(=UNKNOWN)이다. "고정비 0원"이라고 말하지 않는다.
     */
    static FundFlowInputs.FixedExpenseSummary fixedExpense(List<RecurringPayment> recurring) {
        List<RecurringPayment> fixed = fixedOnly(recurring);
        if (fixed.isEmpty()) return null;

        long monthlyTotal = 0;
        for (RecurringPayment r : fixed) {
            monthlyTotal += Math.round(r.representativeAmount() * (DAYS_PER_MONTH / r.periodDays()));
        }
        return new FundFlowInputs.FixedExpenseSummary(monthlyTotal, withdrawalDaySpread(fixed));
    }

    private static List<RecurringPayment> fixedOnly(List<RecurringPayment> recurring) {
        if (recurring == null) return List.of();
        return recurring.stream()
                .filter(r -> r.type() == RecurringPayment.Type.FIXED)
                .filter(r -> r.periodDays() != null && r.periodDays() > 0)
                .toList();
    }

    /**
     * 인출일이 며칠에 걸쳐 흩어져 있나(일). 좁으면 특정 며칠에 몰려 그 시기 잔고가 얇아진다.
     * 고정비가 1건뿐이면 흩어짐을 말할 수 없어 {@code null}.
     */
    static Integer withdrawalDaySpread(List<RecurringPayment> fixed) {
        List<Integer> days = fixed.stream()
                .map(RecurringPayment::nextExpected)
                .filter(Objects::nonNull)
                .map(LocalDate::getDayOfMonth)
                .sorted()
                .toList();
        if (days.size() < 2) return null;
        return days.get(days.size() - 1) - days.get(0);
    }

    // ── L4 큰 1회성 지출 ──────────────────────────────────────────────────────

    /**
     * L4 — <b>반복이 아닌 큰 단건</b> 결제의 주기와 규칙성.
     *
     * <p><b>`큰`의 기준은 그 사람의 월평균지출 대비 비율</b>이다(사용자 확정 2026-08-04). 고정 금액으로 자르면
     * 씀씀이 큰 사람은 매달 여러 건이 걸려 늘 "목돈이 자주 나간다"가 되고, 작은 사람은 아무것도 안 걸린다.
     * 월평균지출을 모르면(카드 이력 없음) 기준 자체를 못 세우므로 {@code null}(=UNKNOWN)을 낸다.
     *
     * <p><b>반복 결제는 뺀다.</b> 월세·통신비처럼 매달 나가는 큰 돈은 이미 L2 고정비로 세었고, 여기서 또 세면
     * 같은 지출이 두 축에 잡혀 "목돈이 매달 나간다"는 잘못된 신호가 된다. §8.1이 경고한 자리이기도 하다 —
     * 1회성이 금액의 44%를 차지하므로 <b>반복만 보면 절반을 놓치고, 반복을 안 빼면 두 번 센다.</b>
     *
     * <p>큰 지출이 2건 미만이면 간격을 낼 수 없어 주기 {@code null}(=SMOOTH, 큰 지출 없음)로 둔다.
     * 6개월 창에 단 1건은 되풀이되는 유동성 수요라고 보기 어렵다.
     */
    static FundFlowInputs.LargeExpense largeExpense(List<UserPayment> payments, List<RecurringPayment> recurring,
                                                    long monthlyAvgSpend, LocalDateTime since, Thresholds th) {
        if (monthlyAvgSpend <= 0) return null;              // 기준을 세울 수 없다 → UNKNOWN
        long threshold = Math.round(monthlyAvgSpend * th.largeExpenseRatio());
        Set<String> recurringMerchants = fixedMerchantIdentities(recurring);

        List<LocalDate> days = payments.stream()
                .filter(p -> p.getPaymentDate() != null && !p.getPaymentDate().isBefore(since))
                .filter(p -> p.getAmount() >= threshold)
                .filter(p -> !recurringMerchants.contains(merchantIdentity(p)))
                .map(p -> p.getPaymentDate().toLocalDate())
                .distinct()
                .sorted()
                .toList();
        if (days.size() < 2) return new FundFlowInputs.LargeExpense(null, false);

        double[] gaps = new double[days.size() - 1];
        for (int i = 1; i < days.size(); i++) {
            gaps[i - 1] = ChronoUnit.DAYS.between(days.get(i - 1), days.get(i));
        }
        double meanGap = Stats.mean(gaps);
        if (meanGap <= 0) return new FundFlowInputs.LargeExpense(null, false);

        // 간격이 고르면 예측 가능. 들쭉날쭉하면(변동계수 큼) 언제 나갈지 모른다 → 파킹 쪽(M2).
        boolean predictable = Stats.coefficientOfVariation(gaps) <= th.largeExpensePredictableCvMax();
        int cycleMonths = Math.max(1, (int) Math.round(meanGap / DAYS_PER_MONTH));
        return new FundFlowInputs.LargeExpense(cycleMonths, predictable);
    }

    /** 고정형 반복으로 이미 잡힌 가맹점들 — 큰 지출에서 빼기 위한 집합. */
    private static Set<String> fixedMerchantIdentities(List<RecurringPayment> recurring) {
        Set<String> out = new HashSet<>();
        for (RecurringPayment r : fixedOnly(recurring)) {
            String identity = r.businessNumber() != null ? r.businessNumber() : r.merchantName();
            if (identity != null) out.add(identity);
        }
        return out;
    }

    /**
     * 안정 식별자(사업자번호) 우선, 없으면 표시명. {@code RecurringPaymentDetector}의 가맹점 식별과
     * <b>같은 규칙이어야</b> 반복으로 잡힌 가맹점을 여기서 정확히 빼낼 수 있다.
     */
    private static String merchantIdentity(UserPayment p) {
        return p.getBusinessNumber() != null ? p.getBusinessNumber() : p.getMerchantName();
    }

    // ── L5 우대조건 ───────────────────────────────────────────────────────────

    /**
     * L5 — 적금 우대조건에 흔한 <b>카드 실적</b>과 <b>급여이체</b> 충족 여부. 자동이체는 더미에 해당 거래가
     * 없어 뺐다(§4.1 · R7).
     *
     * <p><b>카드 실적</b>은 보유 카드의 <b>전월 실적 중 가장 큰 값</b>이 임계 이상인지로 본다. 상품이 요구하는
     * 실적액은 자연어 {@code spclCnd}에만 있어 상품별로 알 수 없으므로(D2), 흔한 기준선을 설정값으로 둔다.
     * 카드사별로 실적이 나뉘어 합산하지 않는 것은 실제 우대조건이 보통 <b>한 카드사 기준</b>이기 때문이다.
     *
     * <p><b>급여이체</b>는 계좌에 급여가 잡히는지로 본다. 카드가 한 장도 없으면 실적은 미충족으로 나가는데,
     * 카드가 없으면 실제로도 카드 실적을 채울 수 없으므로 사실과 어긋나지 않는다.
     */
    static FundFlowInputs.Preferential preferential(MyDataResponses.AccountView account, List<UserCard> cards,
                                                    int cardPerformanceThreshold) {
        if (account == null) return null;
        int bestPerformance = cards == null ? 0
                : cards.stream().mapToInt(UserCard::getPrevPerformance).max().orElse(0);
        return new FundFlowInputs.Preferential(
                bestPerformance >= cardPerformanceThreshold,
                account.salary() > 0);
    }

    /**
     * 재료 조립에 쓰는 임계값 묶음. 인자 수를 줄이려 한데 모은다 — 값은 전부 설정에서 온다(설계원칙 4).
     */
    record Thresholds(double largeExpenseRatio, double largeExpensePredictableCvMax,
                      int cardPerformanceThreshold) {}
}
