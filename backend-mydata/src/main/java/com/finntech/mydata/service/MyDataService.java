package com.finntech.mydata.service;

import com.finntech.mydata.domain.*;
import com.finntech.mydata.dto.MyDataDtos.*;
import com.finntech.mydata.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 마이데이터 조회 서비스 — 본체가 요청한 사용자(CI)+카드사의 카드·결제내역을 DTO로 조립한다.
 * 인증은 없다(내부 서버-투-서버 신뢰).
 *
 * <p><b>현재시각 커트오프(§13-11)</b>: 조회는 {@code 결제일 ≤ now}만 반환한다. 미래 날짜로 미리 생성해둔 결제는
 * now가 그 시점을 지나면 자동으로 등장해 '실시간 연동'처럼 보인다. now는 {@code mydata.now}로 정한다
 * (기본 {@code reference}=시드 기준일 끝 → 현재 데이터 전부 노출·결정론적, {@code system}=실시간, 또는 ISO datetime).
 */
@Service
public class MyDataService {

    private final MyDataUserRepository userRepository;
    private final MyDataCardRepository cardRepository;
    private final MyDataPaymentRepository paymentRepository;
    private final CardCompanyRepository companyRepository;
    private final MyDataAccountRepository accountRepository;
    private final MyDataMerchantRepository merchantRepository;
    private final String nowSetting;
    private final LocalDate referenceDate;
    /** 전체 조회 하한(W4-3): 0=무제한(현행), N>0이면 최근 N개월만 반환해 대량 사용자 응답 폭주를 막는다. */
    private final int monthsFloor;

    public MyDataService(MyDataUserRepository userRepository, MyDataCardRepository cardRepository,
                         MyDataPaymentRepository paymentRepository, CardCompanyRepository companyRepository,
                         MyDataAccountRepository accountRepository, MyDataMerchantRepository merchantRepository,
                         @Value("${mydata.now:reference}") String nowSetting,
                         @Value("${mydata.seed.reference-date:2026-07-21}") String referenceDate,
                         @Value("${mydata.query.months-floor:0}") int monthsFloor) {
        this.userRepository = userRepository;
        this.cardRepository = cardRepository;
        this.paymentRepository = paymentRepository;
        this.companyRepository = companyRepository;
        this.accountRepository = accountRepository;
        this.merchantRepository = merchantRepository;
        // 빈 문자열은 '미설정'으로 본다. env(MYDATA_NOW=)가 비어 있으면 Spring은 yml의 기본값이 아니라
        // 빈 문자열을 넘긴다 — 이걸 그대로 parse하면 기동 후 조회에서 DateTimeParseException으로 터진다.
        this.nowSetting = (nowSetting == null || nowSetting.isBlank()) ? "system" : nowSetting.trim();
        this.referenceDate = LocalDate.parse(
                (referenceDate == null || referenceDate.isBlank()) ? "2026-07-21" : referenceDate.trim());
        this.monthsFloor = monthsFloor;
    }

    /**
     * 입출금 통장 조회(§13-11 경제 모델).
     *
     * <p><b>잔액은 저장하지 않고 계산한다.</b> 통장 거래는 행으로 쌓아둔 것이 아니라
     * 개설일·월급·이자·카드결제에서 유도된다. 그래서 결제가 하나 들어오면 즉시 반영된다.
     *
     * <p><b>기간을 맞추는 것이 중요하다.</b> 예전에는 카드 출금만 "최근 40건"으로 자르고 월급·이자는
     * 개설일부터 전부 만들었다. 그 결과 최근 며칠 치 출금 옆에 몇 달 전 급여·이자만 덩그러니 놓여
     * <b>한 푼도 안 쓰고 이자만 받은 통장</b>처럼 보였다. 이제 셋 다 같은 구간으로 자른다.
     *
     * @param months 최근 N개월(당월 포함). 1이면 이번 달, 7이면 이번 달 + 이전 6개월.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<AccountView> findAccount(String userId, int months) {
        return accountRepository.findByUser_Id(userId).map(a -> {
            LocalDateTime now = cutoff();
            int m = Math.max(1, months);
            LocalDateTime from = java.time.YearMonth.from(now).minusMonths(m - 1L).atDay(1).atStartOfDay();
            if (from.isBefore(a.getOpenedDate().atStartOfDay())) from = a.getOpenedDate().atStartOfDay();

            // 전 기간의 입금(월급·이자)을 만든다 — 구간 밖의 것은 '구간 시작 잔액'을 구하는 데 쓰인다.
            List<AccountTxnView> salary = salaryDeposits(a, now);
            List<AccountTxnView> moves = transfers(a, now);
            List<AccountTxnView> interest = interestAndTax(a, userId, now, moves);

            // 구간 시작 시점의 잔액. 그 이전 출금 합계는 한 번의 질의로 받는다.
            long opening = a.getInitialBalance() - paymentRepository.sumByUserUpTo(userId, from.minusNanos(1));
            for (AccountTxnView t : salary) if (t.date().isBefore(from)) opening += t.amount();
            for (AccountTxnView t : concat(interest, moves)) {
                if (!t.date().isBefore(from)) continue;
                opening += "DEPOSIT".equals(t.type()) ? t.amount() : -t.amount();
            }

            // 구간 안의 거래를 모아 시간순으로 잔액을 굴린다.
            List<AccountTxnView> rows = new java.util.ArrayList<>();
            for (AccountTxnView t : concat(salary, interest, moves)) {
                if (!t.date().isBefore(from)) rows.add(t);
            }
            for (MyDataPayment p : paymentRepository.findByUserBetween(userId, from, now)) {
                rows.add(new AccountTxnView(p.getPaymentDate(), "WITHDRAWAL", p.getAmount(),
                        p.getMerchantName(),
                        p.getCard().getCardProduct().getCardCompany().getName(), 0));
            }
            rows.sort(java.util.Comparator.comparing(AccountTxnView::date));

            long running = opening;
            List<AccountTxnView> txns = new java.util.ArrayList<>(rows.size());
            for (AccountTxnView t : rows) {
                running += "DEPOSIT".equals(t.type()) ? t.amount() : -t.amount();
                txns.add(new AccountTxnView(t.date(), t.type(), t.amount(), t.description(), t.note(), running));
            }
            long balance = running;   // 구간 끝 = 지금 잔액(구간은 항상 now까지다)
            java.util.Collections.reverse(txns);   // 화면은 최신순

            return new AccountView(a.getAccountNumber(), a.getBank(), a.getProduct(), a.getSalaryPayer(),
                    a.getSalary(), a.getPayday(), balance, txns);
        });
    }

    /** 기본 조회 — 이번 달. */
    @Transactional(readOnly = true)
    public java.util.Optional<AccountView> findAccount(String userId) {
        return findAccount(userId, 1);
    }

    @SafeVarargs
    private static List<AccountTxnView> concat(List<AccountTxnView>... lists) {
        List<AccountTxnView> out = new java.util.ArrayList<>();
        for (List<AccountTxnView> l : lists) out.addAll(l);
        return out;
    }

    // ── 계좌이체(사람 간) ────────────────────────────────────────────────────
    // 통장에 카드값·급여·이자만 있으면 실제 통장처럼 보이지 않는다. 사람에게 보내고 받는 이체가
    // 통장의 대부분을 차지하기 때문이다. 여기서 만든다 — 이것도 저장하지 않고 계산한다.

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
    private static final String PAYROLL_CHANNEL = "펌뱅킹";
    /**
     * 일회성 이체의 비고 채널. 개인이 앱·ATM으로 보내는 경로들이다.
     *
     * <p>펌뱅킹은 여기 넣지 않는다 — 기업이 정기 이체에 쓰는 채널이라, 일반 이체에 섞이면
     * 급여·정기 송금과 구분이 사라진다. 정기 이체는 {@link #PAYROLL_CHANNEL}을 쓴다.
     */
    private static final String[] CHANNELS = {
        "당행CD","타행CD","전자금융이체","계좌대체","타행MB","타행IB","제휴CD","FB이체","FBS"
    };

    private static String randomName(java.util.Random rng) {
        return SURNAMES[rng.nextInt(SURNAMES.length)]
                + GIVEN1[rng.nextInt(GIVEN1.length)] + GIVEN2[rng.nextInt(GIVEN2.length)];
    }

    /**
     * 개설일부터 now까지의 사람 간 계좌이체를 만든다.
     *
     * <p><b>결정론이어야 한다</b>(마스터 §4 원칙 3). 조회할 때마다 이체가 달라지면 잔액이 흔들리고
     * 이자 계산까지 어긋난다. 계좌번호와 연·월을 시드로 삼아 같은 달은 언제 조회해도 같은 이체가 나온다.
     */
    private List<AccountTxnView> transfers(MyDataAccount a, LocalDateTime now) {
        List<AccountTxnView> out = new java.util.ArrayList<>();
        // 고액 입금을 보내는 사람은 계좌마다 하나로 고정한다(계좌번호가 시드라 늘 같은 이름).
        java.util.Random who = new java.util.Random(a.getAccountNumber().hashCode());
        String payer = SURNAMES[who.nextInt(SURNAMES.length)]
                + GIVEN1[who.nextInt(GIVEN1.length)] + GIVEN2[who.nextInt(GIVEN2.length)];
        java.time.YearMonth ym = java.time.YearMonth.from(a.getOpenedDate());
        java.time.YearMonth last = java.time.YearMonth.from(now);
        for (; !ym.isAfter(last); ym = ym.plusMonths(1)) {
            java.util.Random rng = new java.util.Random(
                    (a.getAccountNumber() + "/" + ym).hashCode() & 0xffffffffL);
            // ── 출금 먼저 만든다. 월 30건 안팎을 날짜별 확률로 흩는다(없는 날도, 겹치는 날도 있게).
            int days = ym.lengthOfMonth();
            List<AccountTxnView> outs = new java.util.ArrayList<>();
            long spent = 0;
            for (int day = 1; day <= days; day++) {
                LocalDate d = ym.atDay(day);
                if (d.isBefore(a.getOpenedDate())) continue;
                double u = rng.nextDouble();
                int n = u < 0.24 ? 0 : u < 0.76 ? 1 : 2;      // 평균 약 1.0건/일 → 월 30건 안팎
                for (int i = 0; i < n; i++) {
                    LocalDateTime when = d.atTime(8 + rng.nextInt(14), rng.nextInt(60));
                    if (when.isAfter(now)) continue;
                    // 3,000~30,000원 · 1,000원 단위. 개인이 앱으로 보내는 송금의 실제 크기다.
                    // (단위를 10,000원으로 잘못 두면 한 건이 3~15만원이 되고, 입금은 이 합계의
                    //  88~96%로 역산되므로 입금까지 수십만원으로 함께 부풀어 오른다.)
                    long amount = (3 + rng.nextInt(28)) * 1_000L;
                    spent += amount;
                    outs.add(new AccountTxnView(when, "WITHDRAWAL", amount,
                            randomName(rng), CHANNELS[rng.nextInt(CHANNELS.length)], 0));
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
            if (!bigDay.isBefore(a.getOpenedDate()) && !bigAt.isAfter(now) && big > 0) {
                out.add(new AccountTxnView(bigAt, "DEPOSIT", big, payer, PAYROLL_CHANNEL, 0));
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
                    if (d.isBefore(a.getOpenedDate())) continue;
                    LocalDateTime when = d.atTime(9 + rng.nextInt(11), rng.nextInt(60));
                    if (when.isAfter(now)) continue;
                    out.add(new AccountTxnView(when, "DEPOSIT", amount,
                            randomName(rng), CHANNELS[rng.nextInt(CHANNELS.length)], 0));
                }
            }
        }
        return out;
    }

    /** 이자 원천징수 — 통장에는 소득세(14%)와 지방소득세(1.4%)가 따로 찍힌다. */
    private static final double INCOME_TAX_RATE = 0.14, LOCAL_TAX_RATE = 0.014;
    /** 연이율 범위. 입출금 통장이라 낮다(정기예금이 아니다). */
    private static final double RATE_MIN = 0.001, RATE_MAX = 0.020;

    /**
     * 계좌별 연이율 — 계좌번호 해시에서 0.1~2.0% 사이로 뽑는다.
     *
     * <p>난수를 그때그때 뽑지 않는 이유는 재현성이다(마스터 §4 원칙 3). 같은 계좌는 언제 조회해도
     * 같은 이율이어야 잔액이 흔들리지 않는다. 계좌번호는 불변이므로 시드로 적당하다.
     */
    private static double annualRate(MyDataAccount a) {
        int h = a.getAccountNumber().hashCode();
        double unit = (h & 0x7fffffff) / (double) Integer.MAX_VALUE;   // [0,1)
        return RATE_MIN + unit * (RATE_MAX - RATE_MIN);
    }

    /**
     * 매달 이자 입금과 그 직후의 이자소득세 출금을 만든다(≤now).
     *
     * <p><b>저장하지 않고 계산한다.</b> 월급 입금과 같은 방식이다 — 통장 거래는 행으로 쌓아둔 것이
     * 아니라 개설일·월급·결제내역에서 유도된다. 그래서 이자를 넣는 데 마이데이터 재생성이 필요 없다.
     *
     * <p>이자는 <b>그 시점 실잔액</b>에 붙는다. 많이 쓴 달은 잔액이 낮아 이자도 적다 — "안 쓰면
     * 더 붙는다"가 숫자로 드러나야 소비 조언 앱의 서사가 산다.
     */
    private List<AccountTxnView> interestAndTax(MyDataAccount a, String userId, LocalDateTime now,
                                                List<AccountTxnView> moves) {
        // 그 달의 '나간 돈' = 카드결제 + 계좌이체 순유출. 이체를 빼먹으면 이자가 실제 잔액과 어긋난다.
        java.util.Map<java.time.YearMonth, Long> outByMonth = new java.util.HashMap<>();
        for (Object[] row : paymentRepository.sumByUserPerMonth(userId, now)) {
            outByMonth.merge(java.time.YearMonth.of(((Number) row[0]).intValue(), ((Number) row[1]).intValue()),
                    ((Number) row[2]).longValue(), Long::sum);
        }
        for (AccountTxnView t : moves) {
            outByMonth.merge(java.time.YearMonth.from(t.date()),
                    "DEPOSIT".equals(t.type()) ? -t.amount() : t.amount(), Long::sum);
        }

        double rate = annualRate(a);
        // 이자일은 개설일의 '일'을 따른다(말일 보정). 월급날과 겹치지 않게 시각만 다르게 둔다.
        int day = Math.min(a.getOpenedDate().getDayOfMonth(), 28);
        LocalDate d = a.getOpenedDate().withDayOfMonth(day);
        if (!d.isAfter(a.getOpenedDate())) d = d.plusMonths(1);   // 개설 당월은 이자 없음

        long balance = a.getInitialBalance();
        LocalDate salaryDay = a.getOpenedDate().withDayOfMonth(Math.min(a.getPayday(), 28));
        if (salaryDay.isBefore(a.getOpenedDate())) salaryDay = salaryDay.plusMonths(1);

        List<AccountTxnView> out = new java.util.ArrayList<>();
        for (; !d.atTime(0, 5).isAfter(now); d = d.plusMonths(1)) {
            java.time.YearMonth ym = java.time.YearMonth.from(d);
            for (; !salaryDay.isAfter(d); salaryDay = salaryDay.plusMonths(1)) balance += a.getSalary();
            balance -= outByMonth.getOrDefault(ym.minusMonths(1), 0L);

            long interest = Math.max(0, (long) (Math.max(0, balance) * rate / 12.0));
            if (interest <= 0) continue;
            // 통장에는 소득세와 지방소득세가 **따로** 찍힌다. 합쳐 쓰면 실제 통장과 다르다.
            long incomeTax = (long) (interest * INCOME_TAX_RATE);
            long localTax = (long) (interest * LOCAL_TAX_RATE);
            String office = a.getBank() + "본부";
            out.add(new AccountTxnView(d.atTime(0, 5), "DEPOSIT", interest, "이자입금", office, 0));
            if (incomeTax > 0) out.add(new AccountTxnView(d.atTime(0, 6), "WITHDRAWAL", incomeTax, "결산소득세", office, 0));
            if (localTax > 0) out.add(new AccountTxnView(d.atTime(0, 7), "WITHDRAWAL", localTax, "결산지방세", office, 0));
            balance += interest - incomeTax - localTax;
        }
        return out;
    }

    /** 개설일 이후 매달 월급날(payday≤28)에 입금된 월급 내역(≤now). 잔액 계산과 내역 표시에 공용. */
    private List<AccountTxnView> salaryDeposits(MyDataAccount a, LocalDateTime now) {
        List<AccountTxnView> out = new java.util.ArrayList<>();
        LocalDate d = a.getOpenedDate().withDayOfMonth(a.getPayday());
        if (d.isBefore(a.getOpenedDate())) d = d.plusMonths(1);
        // 적요는 상대(회사), 비고는 채널. 기업 급여이체는 펌뱅킹으로 나간다.
        for (; !d.atTime(9, 0).isAfter(now); d = d.plusMonths(1)) {
            out.add(new AccountTxnView(d.atTime(9, 0), "DEPOSIT", a.getSalary(),
                    a.getSalaryPayer(), PAYROLL_CHANNEL, 0));
        }
        return out;
    }

    /**
     * 조회 커트오프 시각. {@code reference}=시드 기준일의 하루 끝(현재 데이터 전부 노출),
     * {@code system}=실시간, 그 외는 ISO datetime으로 파싱(데모 시간 고정).
     */
    private LocalDateTime cutoff() {
        if ("system".equalsIgnoreCase(nowSetting)) return LocalDateTime.now();
        if ("reference".equalsIgnoreCase(nowSetting)) return referenceDate.atTime(23, 59, 59);
        return LocalDateTime.parse(nowSetting);
    }

    /** 존재 확인 — 본인인증 후 본체가 "이 CI가 마이데이터에 있는 회원인가"를 묻는다. */
    @Transactional(readOnly = true)
    public boolean userExists(String ci) {
        return userRepository.existsById(ci);
    }

    /**
     * 신원 대조 — 본인인증이 <b>어느 항목이 틀렸는지</b> 가려내도록 조회 사실만 돌려준다.
     *
     * <p>CI 하나로는 "안 맞는다"까지만 알 수 있다(해시라 어느 항목이 틀렸는지 되짚을 수 없다).
     * 그래서 번호로 한 번, 이름+주민번호로 한 번 찾아 <b>무엇이 맞고 무엇이 틀렸는지</b>를 준다.
     * 판정과 문구 선택은 본체 몫이다.
     */
    @Transactional(readOnly = true)
    public IdentityMatchView matchIdentity(String name, String social7, String phone) {
        String normalized = normalizePhone(phone);
        var byPhone = userRepository.findByPhoneNumber(normalized);
        var byPerson = userRepository.findByNameAndSocial7(name, social7);
        boolean phoneNameOk = byPhone.map(u -> u.getName().equals(name)).orElse(false);
        boolean phoneSocialOk = byPhone
                .map(u -> u.getSocialNumber().length() >= 7
                        && u.getSocialNumber().substring(0, 7).equals(social7))
                .orElse(false);
        boolean exists = byPhone.isPresent() && phoneNameOk && phoneSocialOk;
        return new IdentityMatchView(exists, byPhone.isPresent(), phoneNameOk, phoneSocialOk,
                byPerson.isPresent());
    }

    /** 저장 형식은 `010-1234-5678`이다. 입력이 하이픈 없이 와도 같은 사람을 찾게 맞춘다. */
    private static String normalizePhone(String phone) {
        String d = phone == null ? "" : phone.replaceAll("\\D", "");
        return d.length() == 11 ? d.substring(0, 3) + "-" + d.substring(3, 7) + "-" + d.substring(7) : phone;
    }

    /** 가맹점 조회(번호→주소) — 사용자가 결제의 사업자번호로 가맹점명·지번주소를 조회한다. 없으면 empty. */
    @Transactional(readOnly = true)
    public java.util.Optional<MerchantView> findMerchant(String businessNumber) {
        return merchantRepository.findById(businessNumber).map(m ->
                new MerchantView(m.getBusinessNumber(), m.getMerchantName(), m.getAddress(),
                        m.getLat(), m.getLng(), m.isOnline()));
    }

    /** 연동 가능 은행 목록(자산연결 화면용). id는 이름순 순번이라 조회마다 같다. */
    @Transactional(readOnly = true)
    public List<BankView> findBanks() {
        List<String> names = accountRepository.findDistinctBanks();
        List<BankView> out = new java.util.ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) out.add(new BankView((long) (i + 1), names.get(i)));
        return out;
    }

    /**
     * 고른 은행들에 있는 사용자의 계좌. 실제 마이데이터처럼 <b>있는 것만</b> 내려준다 —
     * 계좌가 없는 은행을 골랐다면 빈 목록이다(그것이 정확한 답이다).
     */
    @Transactional(readOnly = true)
    public List<AccountView> findAccountsByBanks(String userId, List<Long> bankIds) {
        List<String> all = accountRepository.findDistinctBanks();
        List<String> picked = bankIds.stream()
                .filter(id -> id != null && id >= 1 && id <= all.size())
                .map(id -> all.get((int) (id - 1))).toList();
        if (picked.isEmpty()) return List.of();
        // 사용자당 계좌가 1개라 목록이라도 0~1건이다. 상세(잔액·내역)는 findAccount가 계산한다.
        return accountRepository.findByUserAndBanks(userId, picked).isEmpty()
                ? List.of()
                : findAccount(userId).map(List::of).orElse(List.of());
    }

    /** 카드사 목록(연동 기관 선택용). */
    @Transactional(readOnly = true)
    public List<CompanyView> findCompanies() {
        return companyRepository.findAllByOrderByIdAsc().stream().map(this::toCompanyView).toList();
    }

    /** 전체 조회 — 사용자의 특정 카드사 카드 + (현재시각까지의) 결제내역. */
    @Transactional(readOnly = true)
    public List<CardView> findCards(Long companyId, String userId) {
        LocalDateTime now = cutoff();
        return cardRepository.findByUserAndCompany(userId, companyId).stream()
                .map(card -> toCardView(card, paymentsUpTo(card.getId(), now)))
                .toList();
    }

    /** 전체 조회의 결제 fetch — 하한(months-floor) 설정 시 최근 N개월만, 아니면 전체(현행). */
    private List<MyDataPayment> paymentsUpTo(String cardId, LocalDateTime now) {
        return monthsFloor > 0
                ? paymentRepository.findByCardBetween(cardId, now.minusMonths(monthsFloor), now)
                : paymentRepository.findByCardUpTo(cardId, now);
    }

    /** 증분 조회 — 마지막 동기화 이후 ~ 현재시각까지의 결제만. */
    @Transactional(readOnly = true)
    public List<CardView> findCardsSince(Long companyId, String userId, LocalDateTime lastRenewalTime) {
        LocalDateTime now = cutoff();
        return cardRepository.findByUserAndCompany(userId, companyId).stream()
                .map(card -> toCardView(card, paymentRepository.findByCardBetween(card.getId(), lastRenewalTime, now)))
                .toList();
    }

    private CardView toCardView(MyDataCard card, List<MyDataPayment> payments) {
        CardProduct product = card.getCardProduct();
        List<PaymentView> paymentViews = payments.stream()
                .map(payment -> toPaymentView(payment, product.getCode())).toList();
        return new CardView(
                card.getId(), card.getExpirationDate(), card.getPrevMonthAmount(),
                toProductView(product), toUserView(card.getUser()), paymentViews);
    }

    private CardProductView toProductView(CardProduct product) {
        List<BenefitView> benefits = product.getBenefits().stream().map(this::toBenefitView).toList();
        return new CardProductView(product.getCode(), product.getName(), product.getImgUrl(),
                product.getColor(), toCompanyView(product.getCardCompany()), benefits);
    }

    private BenefitView toBenefitView(CardBenefit benefit) {
        return new BenefitView(benefit.getCategory1Name(), benefit.getDiscountPercent(),
                benefit.getPerformanceStart(), benefit.getPerformanceEnd(), benefit.getMonthlyLimit());
    }

    private CompanyView toCompanyView(CardCompany company) {
        return new CompanyView(company.getId(), company.getName(), company.getImgUrl());
    }

    private UserView toUserView(MyDataUser user) {
        // 주민번호·전화번호는 서빙 응답에 싣지 않는다(데이터 최소화, W7-2). 저장은 하되 노출만 차단.
        return new UserView(user.getId(), user.getName());
    }

    private PaymentView toPaymentView(MyDataPayment payment, Long cardCode) {
        return new PaymentView(payment.getId(), payment.getPaymentDate(), payment.getCategory1(),
                payment.getCategory2(), payment.getAmount(), payment.getMerchantName(),
                payment.getReceivedBenefitAmount(), cardCode, payment.getBusinessNumber());
    }
}
