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
import com.finntech.mydata.util.Msisdn;
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
     * <p>{@code 642004}(포털 및 기타 인터넷 정보 매개 서비스업)는 이 저장소가 이미
     * "알 수 없는 결제"에 쓰는 값이고({@code UnknownPgPaymentRunner}), {@code nts-mid.tsv} 에
     * 없으므로 본체의 {@code midOf()} 가 <b>카테고리없음</b>을 준다 → ML 판정 대상에서 빠진다.
     * <b>모르는 것을 아는 척 분류하지 않는다.</b> 스키마가 {@code NOT NULL} 이라 비워 둘 수는 없다.
     */
    public static final String UNKNOWN_INDUSTRY = "642004";
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

    /**
     * @param backfilled          이미 있던 행에 사업자번호를 <b>채워 넣은</b> 수
     * @param withBusinessNumber  사업자번호가 실린 결제 수 — <b>사전이 붙을 수 있는 건수</b>다
     */
    public record ImportResult(String ci, String cardId, int accepted, int rejected,
                               int backfilled, int withBusinessNumber,
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
        // 원장 표기(010-1234-5678)로 저장한다. **본인인증이 전화번호로 명의자를 찾는데 그 조회가
        // 정확일치**라, 숫자만으로 넣으면 있는 사람을 못 찾아 `PHONE_MISMATCH` 가 뜬다 —
        // 실제 사람이 자기 번호를 정확히 넣고 "전화번호가 다릅니다"를 듣게 된다(2026-08-05 실측).
        // CI 는 숫자만 남겨 만드므로(Ci.of) 표기를 바꿔도 신원은 그대로다.
        String stored = Msisdn.format(phone);
        MyDataUser user = userRepository.findById(ci).orElseGet(() -> {
            MyDataUser u = new MyDataUser(ci, name, social7, stored);
            // 페르소나는 비운다 — 실제 사람에게 생성용 꼬리표를 붙이지 않는다.
            u.setDataSplit(SPLIT);
            return userRepository.save(u);
        });
        // 이미 있던 사람이 학습 셋에 있었다면 여기서 빼 준다(재실행 안전).
        if (!SPLIT.equals(user.getDataSplit())) {
            user.setDataSplit(SPLIT);
            userRepository.save(user);
        }
        // 숫자만으로 저장돼 있던 사람도 여기서 표기를 맞춘다 — 위와 같은 태도의 재실행 안전이다.
        if (!stored.equals(user.getPhoneNumber())) {
            user.setPhoneNumber(stored);
            userRepository.save(user);
        }
        ensureCard(user, cardCode);
        return user;
    }

    /**
     * 명세서 CSV를 적재한다. 형식: {@code 날짜,가맹점,금액[,업종코드][,사업자번호]}.
     *
     * <p>업종코드는 선택이다 — 명세서에 없는 것이 보통이고, 없으면 비워 둔다.
     * <b>모르는 것을 아는 척 분류하지 않는다.</b>
     *
     * <p><b>사업자번호는 확정 분류 사전의 키다.</b> 명세서에 있으면 반드시 실어야 한다 —
     * 없으면 사전 조회가 '번호 없음' 갈래로 빠져, 사전이 아무리 차 있어도 안 붙는다.
     * 뒤에 붙인 칸이라 <b>기존 4칸 파일은 그대로 읽힌다.</b>
     */
    @Transactional
    public ImportResult importCsv(String name, String social7, String phone, Long cardCode, String csv) {
        MyDataUser user = ensurePerson(name, social7, phone, cardCode);
        MyDataCard card = cardRepository.findByUser(user.getId()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("카드가 없다 — ensurePerson 이 실패했다"));
        return importCsvInto(user, card, csv);
    }

    /**
     * <b>지정한 카드에</b> 명세서를 넣는다.
     *
     * <p>{@link #importCsv} 에서 갈라냈다 — 예전에는 "그 사람의 첫 카드"에만 넣을 수 있어
     * 카드가 여러 장이면 두 번째부터 갈 곳이 없었다. 넣을 카드를 밖에서 정하게 하면
     * 한 사람이 카드사 여럿을 신청해도 각각 제자리에 들어간다.
     */
    private ImportResult importCsvInto(MyDataUser user, MyDataCard card, String csv) {
        List<RowResult> problems = new ArrayList<>();
        int accepted = 0, rejected = 0, backfilled = 0, withBusinessNumber = 0;
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
            // 취소·환불(음수)도 <b>그대로 받는다.</b> 버리면 원결제만 남아 <b>안 쓴 돈이 소비로
            // 잡힌다</b> — 이 명세서 하나에서만 59건 246만원이 그렇게 부풀려져 있었다
            // (2026-08-05 실측). 짝을 찾아 원결제를 지우는 방식은 쓰지 않는다: 부분취소가 있고
            // 원결제가 명세서 기간 밖일 수도 있어, 짝짓기는 틀릴 때 조용히 틀린다.
            // 음수 한 줄로 넣어 두면 합계가 알아서 상쇄되고, 부분취소도 그만큼만 빠진다.
            long amount = parseAmount(c[2]);
            if (amount == 0) {
                rejected++;
                problems.add(new RowResult(i + 1, "금액을 못 읽음: " + c[2], line));
                continue;
            }
            String merchant = c[1].trim();
            String industryCode = c.length >= 4 && !c[3].isBlank() ? c[3].trim() : UNKNOWN_INDUSTRY;
            String businessNumber = c.length >= 5 ? normalizeBusinessNumber(c[4]) : null;

            // 결제 id는 (CI, **카드**, 날짜, 줄)로 결정론이다 — 같은 파일을 두 번 올려도
            // 행이 두 배가 되지 않는다.
            //
            // **카드를 넣지 않으면 카드가 여러 장일 때 조용히 깨진다**(2026-08-12). 카드사별로
            // 명세서를 따로 받으면 서로 다른 카드의 같은 날짜·같은 줄 번호가 **같은 id**가 되어,
            // 두 번째 카드의 결제가 "이미 있다"로 건너뛰어진다. 화면에는 `accepted`만 줄어들 뿐
            // 아무 오류도 안 난다 — §8-U 가 말하는 침묵이다.
            String cardKey = card.getId().replace("-", "").substring(0, 4);
            String id = "real-" + user.getId().substring(0, 8) + "-" + cardKey + "-"
                    + d.get().toString().replace("-", "") + "-" + String.format("%04d", i);

            // 같은 파일을 두 번 올려도 행이 두 배가 되지 않는다. 다만 **그냥 건너뛰면 안 된다** —
            // 결제 id 에 칸 수가 안 들어가므로, 사업자번호를 붙여 다시 올려도 전건이 skip 되어
            // `accepted=0` 만 뜨고 번호는 영영 null 로 남는다. "다 들어갔다"와 "아무 일도 안
            // 일어났다"가 똑같아 보이는 침묵이다(§8-U). 비어 있으면 채운다.
            Optional<MyDataPayment> existing = paymentRepository.findById(id);
            if (existing.isPresent()) {
                MyDataPayment old = existing.get();
                if (businessNumber != null && old.getBusinessNumber() == null) {
                    old.setBusinessNumber(businessNumber);
                    paymentRepository.save(old);
                    backfilled++;
                }
                if (old.getBusinessNumber() != null) withBusinessNumber++;
                continue;
            }

            // 명세서는 시각을 안 준다. 정오로 둔다 — 0시로 채우면 night 축이 전부 켜져
            // **모든 결제가 심야 결제로** 판정된다. 모르는 값을 0으로 두는 건 중립이 아니다.
            MyDataPayment row = new MyDataPayment(id, card, d.get().atTime(12, 0),
                    industryCode, UNKNOWN_INDUSTRY.equals(industryCode) ? UNKNOWN_CATEGORY2 : null,
                    (int) amount, merchant);
            // 사업자번호가 **확정 분류 사전의 키**다. 이걸 안 실으면 사전이 아무리 차 있어도
            // 실데이터에는 영영 안 붙는다 — 조회가 '번호 없음' 갈래로 빠지기 때문이다
            // (`MerchantCategoryService.lookup` ②). 2026-08-05 에 그 상태를 실측으로 확인했다.
            row.setBusinessNumber(businessNumber);
            paymentRepository.save(row);
            accepted++;
            if (businessNumber != null) withBusinessNumber++;
        }
        return new ImportResult(user.getId(), card.getId(), accepted, rejected,
                backfilled, withBusinessNumber, List.copyOf(problems));
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
        cardRepository.save(newCard(user, product, 0, null));
    }

    /**
     * 카드 한 장을 만든다. <b>카드 번호는 (CI, 순번)에서 파생해 결정론</b>이다 —
     * 같은 신청을 다시 넣어도 같은 카드에 붙어 행이 두 배가 되지 않는다.
     *
     * <p>순번을 섞지 않으면 여러 장이 <b>전부 같은 번호</b>가 되어 두 번째 카드부터
     * 저장이 깨진다(PK 충돌). 예전에는 카드가 한 장뿐이라 드러나지 않던 구멍이다.
     */
    private static MyDataCard newCard(MyDataUser user, CardProduct product, int index, String displayName) {
        String hash = sha256Hex(user.getId() + ":card:" + index);
        String serial = hash.substring(0, 4) + "-" + hash.substring(4, 8) + "-"
                + hash.substring(8, 12) + "-" + hash.substring(12, 16);
        MyDataCard card = new MyDataCard(serial, user, product, LocalDate.now().plusYears(4));
        card.setDisplayName(displayName == null || displayName.isBlank() ? null : displayName.trim());
        return card;
    }

    private static String sha256Hex(String value) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte octet : out) {
                hex.append(Character.forDigit((octet >> 4) & 0xF, 16));
                hex.append(Character.forDigit(octet & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** 신청 화면이 고를 카드 상품 한 줄. */
    public record CatalogRow(Long cardCode, String cardName, Long companyId, String companyName) {}

    /**
     * 카드 상품 카탈로그 — 카드사 7곳 · 상품 115종. 기준 데이터라 개인정보가 없다.
     *
     * <p><b>여기서 트랜잭션 안에 담아 돌려준다.</b> 엔티티를 그대로 컨트롤러로 올리면
     * {@code open-in-view: false} 라 {@code getCardCompany()} 에서 세션이 이미 닫혀 있어
     * {@code LazyInitializationException} 이 난다 — 화면에는 500 만 뜨고 이유는 안 보인다.
     */
    @Transactional(readOnly = true)
    public List<CatalogRow> cardCatalog() {
        return cardProductRepository.findAll().stream()
                .map(product -> new CatalogRow(product.getCode(), product.getName(),
                        product.getCardCompany().getId(), product.getCardCompany().getName()))
                .sorted(java.util.Comparator.comparing(CatalogRow::companyName)
                        .thenComparing(CatalogRow::cardName))
                .toList();
    }

    /** 카드 한 장에 대한 적재 요청 — 카드사·상품·표시명과 그 카드의 명세서. */
    public record CardImport(Long cardCode, String displayName, String csv) {}

    /** 카드별 결과 — 화면이 "어느 카드가 몇 건"을 그대로 보여줄 수 있게 나눠 준다. */
    public record CardImportResult(String cardId, String cardName, String displayName,
                                   String companyName, int accepted, int rejected,
                                   int backfilled, int withBusinessNumber,
                                   List<RowResult> problems) {}

    public record BatchImportResult(String ci, List<CardImportResult> cards,
                                    int accepted, int rejected, int withBusinessNumber) {}

    /**
     * <b>카드 여러 장을 한 번에 적재한다</b> (설계서 F3).
     *
     * <p>실사용자는 카드사별로 명세서를 따로 받으므로 신청 한 건에 파일이 여럿이다.
     * 카드를 하나로 합치면 "어느 카드에서 쓴 돈인가"가 사라지고, 카드별 실적·혜택 계산이
     * 성립하지 않는다. 그래서 <b>파일 하나가 카드 한 장</b>이다.
     *
     * <p>카드는 <b>목록 순서대로</b> 만든다 — 순서가 곧 결정론 카드 번호의 재료라,
     * 같은 신청을 다시 넣으면 같은 카드에 붙는다.
     */
    @Transactional
    public BatchImportResult importBatch(String name, String social7, String phone,
                                         List<CardImport> imports) {
        MyDataUser user = ensurePerson(name, social7, phone, firstCardCode(imports));
        List<MyDataCard> existing = cardRepository.findByUser(user.getId());

        List<CardImportResult> results = new ArrayList<>(imports.size());
        int accepted = 0, rejected = 0, withBusinessNumber = 0;

        for (int index = 0; index < imports.size(); index++) {
            CardImport request = imports.get(index);
            CardProduct product = (request.cardCode() == null
                    ? cardProductRepository.findAll().stream().findFirst()
                    : cardProductRepository.findById(request.cardCode()))
                    .orElseThrow(() -> new IllegalStateException("카드 상품을 찾을 수 없다: " + request.cardCode()));

            MyDataCard card = newCard(user, product, index, request.displayName());
            // 재신청이면 같은 번호의 카드가 이미 있다 — 그것을 그대로 쓴다.
            MyDataCard target = existing.stream()
                    .filter(c -> c.getId().equals(card.getId()))
                    .findFirst()
                    .orElseGet(() -> cardRepository.save(card));
            if (request.displayName() != null && !request.displayName().isBlank()) {
                target.setDisplayName(request.displayName().trim());
                cardRepository.save(target);
            }

            ImportResult one = importCsvInto(user, target, request.csv());
            accepted += one.accepted();
            rejected += one.rejected();
            withBusinessNumber += one.withBusinessNumber();
            results.add(new CardImportResult(target.getId(), product.getName(),
                    target.getDisplayName(), product.getCardCompany().getName(),
                    one.accepted(), one.rejected(), one.backfilled(),
                    one.withBusinessNumber(), one.problems()));
        }
        return new BatchImportResult(user.getId(), List.copyOf(results),
                accepted, rejected, withBusinessNumber);
    }

    private static Long firstCardCode(List<CardImport> imports) {
        return imports.isEmpty() ? null : imports.get(0).cardCode();
    }

    /**
     * 사업자등록번호를 숫자 10자리로 정규화한다. 아니면 {@code null}.
     *
     * <p>명세서마다 표기가 다르다 — {@code 012-34-56789}, {@code 0123456789}, 공백 섞인 것.
     * (예시 번호는 <b>0 으로 시작</b>한다 — 국세청이 발급하지 않는 대역이라 실물과 겹치지 않는다.)
     * 사전의 키가 이 번호라, 표기가 갈리면 <b>같은 사업자가 다른 사업자가 된다</b>.
     * 앱 쪽 {@code MerchantCategory.normalize} 와 같은 규칙을 쓴다.
     *
     * <p><b>10자리가 아니면 비운다.</b> 잘린 번호나 오타를 그대로 넣으면 사전이 엉뚱한
     * 사업자에 붙는다 — 업종코드를 모를 때 넘겨짚지 않는 것과 같은 이유다.
     */
    static String normalizeBusinessNumber(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("\\D", "");
        return digits.length() == 10 ? digits : null;
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
}
