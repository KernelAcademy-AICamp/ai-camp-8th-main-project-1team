package com.finntech.mydata.service;

import com.finntech.mydata.domain.CardProduct;
import com.finntech.mydata.domain.MyDataCard;
import com.finntech.mydata.domain.MyDataPayment;
import com.finntech.mydata.domain.MyDataUser;
import com.finntech.mydata.repository.CardProductRepository;
import com.finntech.mydata.repository.MyDataCardRepository;
import com.finntech.mydata.repository.MyDataPaymentRepository;
import com.finntech.mydata.repository.MyDataUserRepository;
import com.finntech.mydata.util.Ci;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * <b>실제 사람 한 명의 카드 사용내역을 제공자 DB에 넣는다</b> (2026-08-02).
 *
 * <p><b>왜 제공자인가.</b> 이 앱이 지금까지 판정한 것은 전부 생성기가 만든 소비이고,
 * "시간이 지날수록 낭비가 줄어든다"는 서비스 효과도 <b>생성기가 심어 놓은 가정</b>이다.
 * 실제 사람의 소비를 한 번 통과시켜 봐야 그 구분을 말할 수 있다.
 *
 * <p>그리고 여기 넣어야 <b>정상 경로로 흐른다</b> — 본인인증(CI) → 카드사 연결 → 본체가 조회.
 * 본체 DB에 직접 넣으면 그 경로를 건너뛰고, 덤프({@code finntech_mydata})에도 안 실려
 * AWS·운영 MySQL로 가지 않는다.
 *
 * <h2>세 가지를 못박는다</h2>
 *
 * <p><b>① 학습에서 자동으로 빠진다.</b> {@code data_split = SERVICE} 로 넣는다.
 * {@code ml/train.py} 가 {@code split != "SERVICE"} 로 거르므로, 나중에 재학습을 하더라도
 * 이 사람의 결제는 학습에 안 들어간다 — <b>시험 문제를 교재에 넣지 않는다.</b>
 * 새 규칙을 만든 게 아니라 원래 "실서비스 사용자" 몫으로 있던 칸을 쓰는 것이다.
 *
 * <p><b>② 페르소나를 붙이지 않는다.</b> 생성된 사람은 과소비형·절약형 같은 꼬리표를 갖는데,
 * 실제 사람에게 그걸 붙이면 <b>우리가 모르는 것을 아는 척</b>하는 것이다. {@code persona = null} 로 둔다.
 *
 * <p><b>③ 업종을 모르면 분류하지 않는다.</b> 명세서에는 업종코드가 없는 것이 보통이다.
 * 없으면 비워 두고, 본체의 업종→중분류 매핑이 "카테고리없음"으로 받아 <b>판정 대상에서 제외</b>한다.
 * 가맹점명으로 업종을 넘겨짚지 않는다 — 그건 §8-S에서 이미 기각한 종류의 추론이다.
 *
 * <p><b>신원은 호출자가 준다.</b> 이름·주민앞7·전화번호로 CI를 만든다(생성된 사람들과 같은 산식).
 * 그래야 본인인증이 이 사람을 찾아낸다. 주민번호 뒷자리는 받지 않는다 — 쓸 데가 없다.
 */
@Service
public class RealPersonImportService {

    /** 학습에서 빼는 칸. {@code ml/train.py} 가 이 값을 걸러낸다. */
    public static final String SPLIT = "SERVICE";

    /**
     * 업종을 모를 때 쓰는 코드. 카드 명세서에는 업종코드가 없는 것이 보통이다.
     *
     * <p>{@code 6312} 는 이 저장소가 이미 "알 수 없는 결제"에 쓰는 값이고
     * ({@code UnknownPgPaymentRunner}), {@code ksic-mapping.tsv} 에 없으므로 본체의
     * {@code midOf()} 가 <b>카테고리없음</b>을 준다 → ML 판정 대상에서 빠진다.
     * <b>모르는 것을 아는 척 분류하지 않는다.</b> 스키마가 {@code NOT NULL} 이라 비워 둘 수는 없다.
     */
    public static final String UNKNOWN_KSIC = "6312";
    /** 업종을 모를 때의 소비맥락. 제공자 DB에만 남고 본체로는 안 나간다. */
    private static final String UNKNOWN_CATEGORY2 = "미분류";

    /** 명세서가 쓰는 날짜 표기들 — 카드사마다 다르고 한 파일 안에서도 섞여 나온다. */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("yy-MM-dd"),
            DateTimeFormatter.ofPattern("yy.MM.dd"));

    private final MyDataUserRepository userRepository;
    private final MyDataCardRepository cardRepository;
    private final MyDataPaymentRepository paymentRepository;
    private final CardProductRepository cardProductRepository;

    public RealPersonImportService(MyDataUserRepository userRepository,
                                   MyDataCardRepository cardRepository,
                                   MyDataPaymentRepository paymentRepository,
                                   CardProductRepository cardProductRepository) {
        this.userRepository = userRepository;
        this.cardRepository = cardRepository;
        this.paymentRepository = paymentRepository;
        this.cardProductRepository = cardProductRepository;
    }

    /** 안 들어간 줄은 <b>줄 번호와 사유를 달고</b> 돌아온다 — 조용히 버리면 몇 건이 왜 빠졌는지 모른다. */
    public record RowResult(int line, String reason, String raw) {}

    public record ImportResult(String ci, String cardId, int accepted, int rejected,
                               List<RowResult> problems) {}

    /**
     * 신원을 정하고 카드 한 장을 붙인다. 같은 신원을 다시 부르면 <b>그 사람을 그대로 쓴다</b> —
     * 부를 때마다 새로 만들면 실데이터가 여러 사람으로 흩어진다.
     *
     * @param cardCode 붙일 카드 상품 코드. 없으면 카탈로그의 첫 상품을 쓴다(어느 카드사든 조회는 같다).
     */
    @Transactional
    public MyDataUser ensurePerson(String name, String social7, String phone, Long cardCode) {
        String ci = Ci.of(name, social7, phone);
        MyDataUser user = userRepository.findById(ci).orElseGet(() -> {
            MyDataUser u = new MyDataUser(ci, name, social7, digits(phone));
            // 페르소나는 비운다 — 실제 사람에게 생성용 꼬리표를 붙이지 않는다.
            u.setDataSplit(SPLIT);
            return userRepository.save(u);
        });
        // 이미 있던 사람이 학습 셋에 있었다면 여기서 빼 준다(재실행 안전).
        if (!SPLIT.equals(user.getDataSplit())) {
            user.setDataSplit(SPLIT);
            userRepository.save(user);
        }
        ensureCard(user, cardCode);
        return user;
    }

    /**
     * 명세서 CSV를 적재한다. 형식: {@code 날짜,가맹점,금액[,업종코드]}.
     *
     * <p>업종코드는 선택이다 — 명세서에 없는 것이 보통이고, 없으면 비워 둔다.
     * <b>모르는 것을 아는 척 분류하지 않는다.</b>
     */
    @Transactional
    public ImportResult importCsv(String name, String social7, String phone, Long cardCode, String csv) {
        MyDataUser user = ensurePerson(name, social7, phone, cardCode);
        MyDataCard card = cardRepository.findByUser(user.getId()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("카드가 없다 — ensurePerson 이 실패했다"));

        List<RowResult> problems = new ArrayList<>();
        int accepted = 0, rejected = 0;
        String[] lines = csv == null ? new String[0] : csv.split("\\r?\\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] c = splitCsv(line);
            if (c.length < 3) {
                if (i == 0) continue;                       // 머리글
                rejected++; problems.add(new RowResult(i + 1, "칸이 3개 미만", line));
                continue;
            }
            Optional<LocalDate> d = parseDate(c[0]);
            if (d.isEmpty()) {
                if (i == 0) continue;                       // 머리글
                rejected++; problems.add(new RowResult(i + 1, "날짜를 못 읽음: " + c[0], line));
                continue;
            }
            long amount = parseAmount(c[2]);
            if (amount <= 0) {
                rejected++;
                problems.add(new RowResult(i + 1,
                        amount < 0 ? "취소·환불(음수)은 아직 안 받아요" : "금액을 못 읽음: " + c[2], line));
                continue;
            }
            String merchant = c[1].trim();
            String ksic = c.length >= 4 && !c[3].isBlank() ? c[3].trim() : UNKNOWN_KSIC;

            // 결제 id는 (CI, 줄, 날짜)로 결정론이다 — 같은 파일을 두 번 올려도 행이 두 배가 되지 않는다.
            String id = "real-" + user.getId().substring(0, 8) + "-" + d.get().toString().replace("-", "")
                    + "-" + String.format("%04d", i);
            if (paymentRepository.existsById(id)) continue;

            // 명세서는 시각을 안 준다. 정오로 둔다 — 0시로 채우면 night 축이 전부 켜져
            // **모든 결제가 심야 결제로** 판정된다. 모르는 값을 0으로 두는 건 중립이 아니다.
            paymentRepository.save(new MyDataPayment(id, card, d.get().atTime(12, 0),
                    ksic, UNKNOWN_KSIC.equals(ksic) ? UNKNOWN_CATEGORY2 : null,
                    (int) amount, merchant, 0));
            accepted++;
        }
        return new ImportResult(user.getId(), card.getId(), accepted, rejected, List.copyOf(problems));
    }

    /** 이 사람의 결제를 전부 지운다 — 실 개인정보라 넣는 길과 같은 무게로 둔다. */
    @Transactional
    public long purge(String name, String social7, String phone) {
        String ci = Ci.of(name, social7, phone);
        long n = 0;
        for (MyDataCard card : cardRepository.findByUser(ci)) {
            List<MyDataPayment> ps = paymentRepository.findByCardUpTo(
                    card.getId(), LocalDateTime.of(2999, 12, 31, 23, 59));
            n += ps.size();
            paymentRepository.deleteAll(ps);
        }
        return n;
    }

    private void ensureCard(MyDataUser user, Long cardCode) {
        if (!cardRepository.findByUser(user.getId()).isEmpty()) return;
        CardProduct product = (cardCode == null
                ? cardProductRepository.findAll().stream().findFirst()
                : cardProductRepository.findById(cardCode))
                .orElseThrow(() -> new IllegalStateException("카드 상품 카탈로그가 비어 있다"));
        // 카드 번호는 CI에서 파생한다 — 결정론이라 다시 넣어도 같은 카드가 된다.
        String h = user.getId();
        String serial = h.substring(0, 4) + "-" + h.substring(4, 8) + "-"
                + h.substring(8, 12) + "-" + h.substring(12, 16);
        MyDataCard card = new MyDataCard(serial, user, product, LocalDate.now().plusYears(4), 0);
        cardRepository.save(card);
    }

    /** 따옴표 안의 쉼표를 살린다 — 가맹점명에 흔하다("스타벅스 강남R점, 1층"). */
    private static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (char ch : line.toCharArray()) {
            if (ch == '"') { quoted = !quoted; continue; }
            if (ch == ',' && !quoted) { out.add(cur.toString()); cur.setLength(0); continue; }
            cur.append(ch);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private static Optional<LocalDate> parseDate(String raw) {
        String s = raw == null ? "" : raw.trim().replaceAll("[()]", "");
        if (s.isEmpty()) return Optional.empty();
        for (DateTimeFormatter f : DATE_FORMATS) {
            try { return Optional.of(LocalDate.parse(s, f)); } catch (RuntimeException ignored) { /* 다음 형식 */ }
        }
        return Optional.empty();
    }

    /** "12,000원"·"₩12,000"·"-3,000" 을 읽는다. 음수는 부호를 살려 돌려준다(호출부가 가른다). */
    private static long parseAmount(String raw) {
        if (raw == null) return 0;
        String s = raw.trim();
        boolean negative = s.startsWith("-") || s.startsWith("−");
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try {
            long v = Long.parseLong(digits);
            return negative ? -v : v;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String digits(String v) {
        return v == null ? "" : v.replaceAll("[^0-9]", "");
    }
}
