package com.finntech.mydata.generation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 통장 거래(이체·월급·이자·세금) 생성 — <b>순수 함수</b>. 생성 시점에 만들어 DB에 적재한다.
 *
 * <p><b>왜 조회에서 생성으로 옮겼는가.</b> 예전에는 통장을 열 때마다 개설일부터 지금까지의 이체를
 * 다시 계산했다. 그 방식에는 두 가지가 걸린다.
 *
 * <ol>
 *   <li><b>조회 시점이 데이터를 바꾼다.</b> 이체 생성은 "지금 이후는 건너뛴다"로 잘리는데, 건너뛴
 *       거래가 난수 열을 소비하지 않아 <b>그 달의 입금 총액이 커트오프에 따라 달라졌다.</b>
 *       어제 본 통장과 오늘 본 통장의 지난주 입금액이 다르면 통장이 아니다.</li>
 *   <li>사용자마다 매 조회에 9개월치 월 루프가 돈다 — 목록 화면 하나에 사람 수만큼 반복된다.</li>
 * </ol>
 *
 * <p>생성 시점에는 커트오프가 없다. <b>미래분까지 전부</b> 만들어 두고 조회에서 {@code date <= now}로
 * 거른다 — 결제내역이 이미 그렇게 동작한다(§13-11 실시간성).
 *
 * <p><b>순서가 강제된다.</b> 이자는 그 시점 실잔액에 붙고 실잔액은 이체에 좌우되므로
 * {@code 이체 → 이자·세금} 순으로만 계산할 수 있다. {@link #generate}가 그 순서를 안에서 지킨다.
 */
public final class AccountTxnGenerator {

    private AccountTxnGenerator() {}

    /**
     * 통장 거래 한 줄(잔액 없음 — 잔액은 조회에서 굴린다. 결제가 늘면 저장된 잔액은 즉시 낡는다).
     *
     * @param source 무엇이 만든 거래인가 — {@code TRANSFER}(사람 간 이체) · {@code SALARY} ·
     *               {@code INTEREST} · {@code TAX} · {@code CARD}(결제 복제).
     *               만든 쪽이 붙인다. 적요·비고에서 되짚으면 "이자입금"이라 적힌 이체와
     *               구분이 안 되고, 정기 고액입금이 급여와 같은 펌뱅킹 채널이라 더욱 그렇다.
     * @param paymentId 복제한 결제의 ID({@code source=CARD}일 때만, 그 외 null).
     *                  사본에는 원본을 가리키는 것이 있어야 정리 단계가 둘을 함께 지울 수 있다.
     */
    public record Row(LocalDateTime date, String type, long amount, String description,
                      String note, String source, String paymentId) {

        /** 통장 자체 거래(이체·월급·이자·세금) — 복제한 결제가 없다. */
        Row(LocalDateTime date, String type, long amount, String description, String note, String source) {
            this(date, type, amount, description, note, source, null);
        }
    }

    // ── 사람 이름(이체 상대) ──────────────────────────────────────────────────
    private static final String[] SURNAMES = {
        "김","김","김","이","이","박","최","정","강","조","윤","장","임","한","오","서","신","권",
        "황","안","송","전","홍","유","고","문","양","손","배","백","허","남","심","노","하","곽"
    };
    private static final String[] GIVEN1 = {
        "민","서","지","예","하","도","시","주","유","준","현","승","은","다","소","태","재","성",
        "진","채","수","우","규","연","가","나","선","형","정","윤"
    };
    private static final String[] GIVEN2 = {
        "준","우","현","진","호","원","빈","석","훈","찬","영","복","희","린","아","은","연","율",
        "경","미","지","수","혁","민","환","태","솔","겸","하","서"
    };

    /** 정기 이체(급여·매달 고액입금)의 채널. 일회성 송금과 구분된다. */
    public static final String PAYROLL_CHANNEL = "펌뱅킹";

    /**
     * 일회성 이체의 비고 채널. 개인이 앱·ATM으로 보내는 경로들이다.
     *
     * <p>펌뱅킹은 여기 넣지 않는다 — 기업이 정기 이체에 쓰는 채널이라, 일반 이체에 섞이면
     * 급여·정기 송금과 구분이 사라진다. 정기 이체는 {@link #PAYROLL_CHANNEL}을 쓴다.
     */
    private static final String[] CHANNELS = {
        "당행CD","타행CD","전자금융이체","계좌대체","타행MB","타행IB","제휴CD","FB이체","FBS"
    };

    /** 이자 원천징수 — 통장에는 소득세(14%)와 지방소득세(1.4%)가 따로 찍힌다. */
    private static final double INCOME_TAX_RATE = 0.14, LOCAL_TAX_RATE = 0.014;
    /** 연이율 범위. 입출금 통장이라 낮다(정기예금이 아니다). */
    private static final double RATE_MIN = 0.001, RATE_MAX = 0.020;

    /**
     * 통장의 자체 거래(이체·월급·이자·세금)를 개설일부터 {@code end}까지 전부 만든다. 날짜순 정렬.
     *
     * <p>카드 결제는 여기서 만들지 않는다 — 이미 생성된 결제를 그대로 통장 출금으로 옮기면 되므로
     * 호출부가 담당한다({@code GenerationRunner}).
     *
     * @param cardOutByMonth 월별 카드 지출 합계(스케일 적용 후). 이자가 붙을 실잔액을 구하는 데 쓴다 —
     *                       많이 쓴 달은 잔액이 낮아 이자도 적다.
     */
    public static List<Row> generate(String accountNumber, String bank, String salaryPayer,
                                     LocalDate openedDate, int salary, int payday, long initialBalance,
                                     LocalDateTime end, Map<YearMonth, Long> cardOutByMonth) {
        List<Row> moves = transfers(accountNumber, openedDate, end);
        List<Row> out = new ArrayList<>(moves);
        out.addAll(salaryDeposits(salaryPayer, openedDate, salary, payday, end));
        out.addAll(interestAndTax(accountNumber, bank, openedDate, salary, payday,
                initialBalance, end, cardOutByMonth, moves));
        out.sort(java.util.Comparator.comparing(Row::date).thenComparing(Row::description));
        return out;
    }

    private static String randomName(Random rng) {
        return SURNAMES[rng.nextInt(SURNAMES.length)]
                + GIVEN1[rng.nextInt(GIVEN1.length)] + GIVEN2[rng.nextInt(GIVEN2.length)];
    }

    /**
     * 개설일부터 end까지의 사람 간 계좌이체.
     *
     * <p><b>결정론이어야 한다</b>(마스터 §4 원칙 3). 계좌번호와 연·월을 시드로 삼아 같은 달은
     * 몇 번을 돌려도 같은 이체가 나온다.
     */
    static List<Row> transfers(String accountNumber, LocalDate openedDate, LocalDateTime end) {
        List<Row> out = new ArrayList<>();
        // 고액 입금을 보내는 사람은 계좌마다 하나로 고정한다(계좌번호가 시드라 늘 같은 이름).
        Random who = new Random(accountNumber.hashCode());
        String payer = SURNAMES[who.nextInt(SURNAMES.length)]
                + GIVEN1[who.nextInt(GIVEN1.length)] + GIVEN2[who.nextInt(GIVEN2.length)];
        YearMonth last = YearMonth.from(end);
        for (YearMonth ym = YearMonth.from(openedDate); !ym.isAfter(last); ym = ym.plusMonths(1)) {
            Random rng = new Random((accountNumber + "/" + ym).hashCode() & 0xffffffffL);
            // ── 출금 먼저 만든다. 월 30건 안팎을 날짜별 확률로 흩는다(없는 날도, 겹치는 날도 있게).
            int days = ym.lengthOfMonth();
            List<Row> outs = new ArrayList<>();
            long spent = 0;
            for (int day = 1; day <= days; day++) {
                LocalDate d = ym.atDay(day);
                if (d.isBefore(openedDate)) continue;
                double u = rng.nextDouble();
                int n = u < 0.24 ? 0 : u < 0.76 ? 1 : 2;      // 평균 약 1.0건/일 → 월 30건 안팎
                for (int i = 0; i < n; i++) {
                    LocalDateTime when = d.atTime(8 + rng.nextInt(14), rng.nextInt(60));
                    // 3,000~30,000원 · 1,000원 단위. 개인이 앱으로 보내는 송금의 실제 크기다.
                    // (단위를 10,000원으로 잘못 두면 한 건이 3~15만원이 되고, 입금은 이 합계의
                    //  88~96%로 역산되므로 입금까지 수십만원으로 함께 부풀어 오른다.)
                    long amount = (3 + rng.nextInt(28)) * 1_000L;
                    if (when.isAfter(end)) continue;
                    spent += amount;
                    outs.add(new Row(when, "WITHDRAWAL", amount,
                            randomName(rng), CHANNELS[rng.nextInt(CHANNELS.length)], "TRANSFER"));
                }
            }
            out.addAll(outs);
            if (outs.isEmpty()) continue;

            // ── 입금은 출금의 1/5 건수. 금액은 **출금 합계보다 살짝 적게** 맞춘다 —
            // 통장이 계속 불거나 계속 마르지 않으면서, 카드 지출만큼은 실제로 줄어들게 하려는 것이다.
            int inCount = Math.max(2, Math.round(outs.size() / 5f));
            long target = (long) (spent * (0.88 + rng.nextDouble() * 0.08));   // 88~96%

            // 그중 한 건은 매달 **같은 사람**이 보내는 고액 입금. 다달이 이름이 바뀌면
            // 정기적인 관계(가족 송금·고정 정산)로 읽히지 않는다.
            // 비고는 급여와 같은 펌뱅킹 — 매달 같은 날 같은 사람이 보내는 정기 이체는
            // 실제로도 펌뱅킹으로 처리된다. 채널이 달마다 바뀌면 일회성 송금처럼 보인다.
            // 30,000~300,000원 · 1만원 단위. 상한(target/2)에 걸리면 끝수가 남으므로 1만원 아래로 버린다
            // — 버리지 않으면 `204,997원` 같은 값이 나와 사람이 보낸 송금으로 읽히지 않는다.
            long big = Math.min(target / 2, (3 + rng.nextInt(28)) * 10_000L) / 10_000L * 10_000L;
            LocalDate bigDay = ym.atDay(1 + rng.nextInt(days));
            LocalDateTime bigAt = bigDay.atTime(10 + rng.nextInt(9), rng.nextInt(60));
            if (!bigDay.isBefore(openedDate) && !bigAt.isAfter(end) && big > 0) {
                out.add(new Row(bigAt, "DEPOSIT", big, payer, PAYROLL_CHANNEL, "TRANSFER"));
            } else {
                big = 0;
            }

            // 남은 금액을 나머지 입금 건수로 쪼갠다. 균등분할이면 같은 금액이 반복돼 티가 나므로
            // 가중치를 랜덤으로 주고, 마지막 건이 반올림 오차를 흡수한다. 전부 1,000원 단위.
            int rest = Math.max(1, inCount - (big > 0 ? 1 : 0));
            long remain = Math.max(0, target - big);
            if (remain > 0) {
                double[] w = new double[rest];
                double sum = 0;
                for (int i = 0; i < rest; i++) { w[i] = 0.5 + rng.nextDouble(); sum += w[i]; }
                long allocated = 0;
                for (int i = 0; i < rest; i++) {
                    long amount = (i == rest - 1)
                            ? remain - allocated
                            : Math.round(remain * w[i] / sum / 1000d) * 1000L;
                    amount = Math.max(1000, amount / 1000 * 1000);
                    allocated += amount;
                    LocalDate d = ym.atDay(1 + rng.nextInt(days));
                    LocalDateTime when = d.atTime(9 + rng.nextInt(11), rng.nextInt(60));
                    String name = randomName(rng);
                    String channel = CHANNELS[rng.nextInt(CHANNELS.length)];
                    if (d.isBefore(openedDate) || when.isAfter(end)) continue;
                    out.add(new Row(when, "DEPOSIT", amount, name, channel, "TRANSFER"));
                }
            }
        }
        return out;
    }

    /**
     * 계좌별 연이율 — 계좌번호 해시에서 0.1~2.0% 사이로 뽑는다.
     *
     * <p>난수를 그때그때 뽑지 않는 이유는 재현성이다(마스터 §4 원칙 3). 같은 계좌는 몇 번을 돌려도
     * 같은 이율이어야 잔액이 흔들리지 않는다. 계좌번호는 불변이므로 시드로 적당하다.
     */
    static double annualRate(String accountNumber) {
        int h = accountNumber.hashCode();
        double unit = (h & 0x7fffffff) / (double) Integer.MAX_VALUE;   // [0,1)
        return RATE_MIN + unit * (RATE_MAX - RATE_MIN);
    }

    /**
     * 매달 이자 입금과 그 직후의 이자소득세 출금(≤end).
     *
     * <p>이자는 <b>그 시점 실잔액</b>에 붙는다. 많이 쓴 달은 잔액이 낮아 이자도 적다 — "안 쓰면
     * 더 붙는다"가 숫자로 드러나야 소비 조언 앱의 서사가 산다.
     */
    static List<Row> interestAndTax(String accountNumber, String bank, LocalDate openedDate,
                                    int salary, int payday, long initialBalance, LocalDateTime end,
                                    Map<YearMonth, Long> cardOutByMonth, List<Row> moves) {
        // 그 달의 '나간 돈' = 카드결제 + 계좌이체 순유출. 이체를 빼먹으면 이자가 실제 잔액과 어긋난다.
        Map<YearMonth, Long> outByMonth = new java.util.HashMap<>(cardOutByMonth);
        for (Row t : moves) {
            outByMonth.merge(YearMonth.from(t.date()),
                    "DEPOSIT".equals(t.type()) ? -t.amount() : t.amount(), Long::sum);
        }

        double rate = annualRate(accountNumber);
        // 이자일은 개설일의 '일'을 따른다(말일 보정). 월급날과 겹치지 않게 시각만 다르게 둔다.
        int day = Math.min(openedDate.getDayOfMonth(), 28);
        LocalDate d = openedDate.withDayOfMonth(day);
        if (!d.isAfter(openedDate)) d = d.plusMonths(1);   // 개설 당월은 이자 없음

        long balance = initialBalance;
        LocalDate salaryDay = openedDate.withDayOfMonth(Math.min(payday, 28));
        if (salaryDay.isBefore(openedDate)) salaryDay = salaryDay.plusMonths(1);

        List<Row> out = new ArrayList<>();
        for (; !d.atTime(0, 5).isAfter(end); d = d.plusMonths(1)) {
            YearMonth ym = YearMonth.from(d);
            for (; !salaryDay.isAfter(d); salaryDay = salaryDay.plusMonths(1)) balance += salary;
            balance -= outByMonth.getOrDefault(ym.minusMonths(1), 0L);

            long interest = Math.max(0, (long) (Math.max(0, balance) * rate / 12.0));
            if (interest <= 0) continue;
            // 통장에는 소득세와 지방소득세가 **따로** 찍힌다. 합쳐 쓰면 실제 통장과 다르다.
            long incomeTax = (long) (interest * INCOME_TAX_RATE);
            long localTax = (long) (interest * LOCAL_TAX_RATE);
            String office = bank + "본부";
            out.add(new Row(d.atTime(0, 5), "DEPOSIT", interest, "이자입금", office, "INTEREST"));
            if (incomeTax > 0) out.add(new Row(d.atTime(0, 6), "WITHDRAWAL", incomeTax, "결산소득세", office, "TAX"));
            if (localTax > 0) out.add(new Row(d.atTime(0, 7), "WITHDRAWAL", localTax, "결산지방세", office, "TAX"));
            balance += interest - incomeTax - localTax;
        }
        return out;
    }

    /** 개설일 이후 매달 월급날(payday≤28)에 입금된 월급(≤end). 적요는 상대(회사), 비고는 채널. */
    static List<Row> salaryDeposits(String salaryPayer, LocalDate openedDate,
                                    int salary, int payday, LocalDateTime end) {
        List<Row> out = new ArrayList<>();
        LocalDate d = openedDate.withDayOfMonth(payday);
        if (d.isBefore(openedDate)) d = d.plusMonths(1);
        // 기업 급여이체는 펌뱅킹으로 나간다.
        for (; !d.atTime(9, 0).isAfter(end); d = d.plusMonths(1)) {
            out.add(new Row(d.atTime(9, 0), "DEPOSIT", salary, salaryPayer, PAYROLL_CHANNEL, "SALARY"));
        }
        return out;
    }
}
