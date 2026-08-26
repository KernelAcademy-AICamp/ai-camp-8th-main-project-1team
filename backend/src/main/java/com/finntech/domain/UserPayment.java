package com.finntech.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 마이데이터에서 불러온 카드 결제내역 (§13). 마이데이터 표준의 결제내역(user_payment) 구조.
 * '내 카드'·'내 소비' 화면의 원천이며, 동시에 {@code Consumption(source=MYDATA)}로도 투영돼 기존 엔진에 재사용된다.
 */
@Entity
@Table(name = "user_payment", indexes = {
        @Index(name = "idx_user_payment_user_date", columnList = "user_id, payment_date")
})
// 결제가 생기거나·분류가 바뀌거나·지워지면 정리된 소비 원장(V34)이 따라와야 한다. 호출부에
// 심지 않는 이유는 이 클래스가 이미 아는 것이다 — 확정을 적는 자리가 여섯 곳이라 흩어 놓으면
// 한 곳이 빠진다(아래 confirmCategory2 머리말). 콜백은 누가 바꿨든 걸린다.
@jakarta.persistence.EntityListeners(com.finntech.ledger.LedgerDirtyListener.class)
public class UserPayment {

    /**
     * 적재 키 = {@code 앱사용자id + ":" + 제공자 결제id}. {@link #rowId} 로만 만든다.
     *
     * <p><b>왜 제공자 id를 그대로 쓰지 않는가.</b> 예전에는 제공자가 준 결제 id가 그대로 PK였다.
     * 그러면 <b>앱 사용자가 달라도 같은 신원(CI)이면 행이 하나뿐이라</b>, 두 번째 계정이 연동하는
     * 순간 {@code save()}가 기존 행의 {@code user_id}를 덮어써 먼저 연동한 사람의 화면이 통째로 빈다.
     * 실제로 운영에서 재현했다 — 한 계정의 결제 2,404건이 다른 계정으로 옮겨갔다.
     *
     * <p>데모 신원은 5개뿐이고 보는 사람마다 브라우저가 달라 앱 계정이 따로 생긴다. 두 사람이 같은
     * 페르소나를 고르는 일은 예외가 아니라 <b>기본값</b>이므로, 키를 계정별로 분리한다.
     */
    @Id
    @Column(name = "payment_id", length = 40)
    private String paymentId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "card_serial", nullable = false, length = 24)
    private String cardSerial;

    @Column(name = "card_code", nullable = false)
    private Long cardCode;

    @Column(name = "payment_date", nullable = false)
    private LocalDateTime paymentDate;

/**
     * 제공자가 준 업종코드(KSIC 세분류 4자리). 분류의 원본 근거라 그대로 보관한다.
     *
     * <p><b>실 명세서에는 이 값이 없다.</b> 그래서 적재기가 자리채움값
     * {@link #PLACEHOLDER_INDUSTRY} 를 넣는데, 그 코드는 대조표에 아예 없어 카드 혜택 축도
     * 중분류도 안 나온다. 실측(2026-08-21) 결과 <b>실사용자 결제 1,579건 전부</b>가 그 값을
     * 들고 있었고, 그래서 카드추천이 실사용자에게는 축 없음으로만 답하고 있었다.
     * {@link #learnIndustryCode} 가 그 자리를 메운다.
     */
    @Column(name = "ksic_code", nullable = false, length = 8)
    private String industryCode;

    /**
     * <b>추정 업종코드</b> — 모델이 답한 업종 이름을 표로 옮긴 값.
     *
     * <p>{@link #industryCode}(확정)와 갈라 둔다. 한 칸에 섞으면 읽는 쪽이 추정을 사실로 쓴다 —
     * {@code category2} 와 {@code category2Llm} 을 가른 것과 같은 이치다(마스터 §4 원칙 1).
     *
     * <p><b>판정에 참여하지 않는다.</b> 카드 혜택축도 확정 칸만 읽는다.
     */
    /**
     * <b>소비내역에 적을 이름</b>(V44) — 원문의 <b>부분집합</b>이다.
     *
     * <p>지어내지 않는다. 하는 일은 실제 결제처를 알아내는 것이 아니라 <b>확실히 버려도 되는
     * 것만 버리는 것</b>이라, 새 사실을 만들지 않으므로 틀릴 수가 없다. 규칙은
     * {@code MerchantDisplayName} 한 곳에 있다.
     *
     * <p><b>계산이 아니라 기록이다.</b> 화면을 열 때마다 표기 1,200여 개를 다시 훑지 않는다 —
     * {@code category2}·{@code ksic_code} 를 결제 행에 적어 둔 것과 같은 이유다.
     *
     * <p>{@link #merchantName} 은 그대로 둔다. 어느 지점인지가 사라지면 안 된다.
     */
    @Column(name = "display_name", length = 60)
    private String displayName;

    /** 표시명을 무엇으로 정했나 — {@code BRAND}·{@code RESIDUE}·{@code AGENCY_ONLY}·{@code RAW}. */
    @Column(name = "display_name_source", length = 16)
    private String displayNameSource;

    /** 거쳐 간 결제대행사. <b>사업자번호가 알려 준 사실</b>이라 상호에서 짐작한 것이 아니다. */
    @Column(name = "via_agency", length = 40)
    private String viaAgency;

    @Column(name = "ksic_code_llm", length = 8)
    private String industryCodeGuess;

    /**
     * 실 명세서 적재기가 넣는 <b>자리채움 업종코드</b>({@code RealPersonImportService.UNKNOWN_INDUSTRY}).
     * 두 곳이 같은 값을 알아야 해서 상수로 둔다 — 한쪽만 고치면 조용히 어긋난다.
     *
     * <p><b>{@code 0} 으로 시작한다.</b> 국세청은 그런 번호를 발급하지 않아 진짜 코드와 겹치지
     * 않는다 — 이 저장소가 사업자번호에 대해 이미 세워 둔 규칙과 같다
     * ({@code scripts/industry/check_no_real_numbers.py}).
     */
    public static final String PLACEHOLDER_INDUSTRY = "000000";

    /**
     * <b>옛 자리표</b> — {@code 642004}(포털 및 기타 인터넷 정보 매개 서비스업).
     *
     * <p>진짜 코드를 자리표로 쓰던 시절의 값이다(V41 이 옮겼다). 마이그레이션이 닿지 않는
     * 곳에서 온 행이 있을 수 있으므로 <b>읽을 때는 여전히 자리표로 본다</b> — 안 그러면
     * 그 행은 "이미 확정이 있다"로 보여 영영 안 채워진다.
     */
    public static final String LEGACY_PLACEHOLDER_INDUSTRY = "642004";

    /** 그 코드가 <b>"모른다"</b>를 뜻하는가 — 새 자리표와 옛 자리표 둘 다. */
    public static boolean isPlaceholderIndustry(String code) {
        return PLACEHOLDER_INDUSTRY.equals(code) || LEGACY_PLACEHOLDER_INDUSTRY.equals(code);
    }

/**
     * 우리 소비 중분류 — 업종코드를 대조표로 옮긴 결과.
     *
     * <p>ML의 {@code cat2} 특징이 이 값을 쓴다. 예전에는 제공자의 소비맥락 52종이었는데,
     * 제공자가 더는 넘기지 않으므로(업종까지만 준다) 우리가 정한 축으로 바꿨다.
     */
    @Column(name = "category2", length = 30)
    private String category2;

    @Column(nullable = false)
    private int amount;

    @Column(name = "merchant_name", length = 60)
    private String merchantName;

    // 받은 혜택 금액은 두지 않는다 — 마이데이터가 할인·적립액을 주지 않는다(카드-005·카드-008
    // 어디에도 없다). 이 카드로 얼마를 아꼈는지는 카드 혜택 룰을 아는 쪽이 승인내역에서
    // 계산할 몫이고, 계산 없이 표시할 수 있는 값이 아니다.

    /** 가맹점 사업자등록번호 10자리(마이데이터에서 전달). 사용자는 이 번호로 가맹점 주소를 조회한다(§13). */
    @Column(name = "business_number", length = 10)
    private String businessNumber;

    /**
     * LLM 이 가맹점명만 보고 추정한 중분류 — <b>표시 전용이다.</b>
     *
     * <p>{@code category2} 를 덮지 않는 것이 요점이다. {@code WasteScoringService} 가 그 필드를
     * 직접 읽어 낭비를 판정하므로, 덮는 순간 <i>"판단은 설명가능한 모델이"</i>(마스터 §4-1)가
     * 깨진다. 화면에는 "AI 추정" 배지로 보이고, 사람이 "맞아요"를 눌러야 확정 분류가 된다.
     */
    @Column(name = "category2_llm", length = 30)
    private String category2Llm;

    /**
     * {@code category2} 가 어디서 왔나 — {@code NONE}·{@code LLM}·{@code USER}·{@code DICT}.
     *
     * <p>{@code DICT}(확정 분류 사전)와 {@code USER}(사람이 확인)는 <b>처음부터 확정</b>이라
     * 판정에 그대로 참여한다. {@code LLM} 만 격리 대상이다.
     */
    @Column(name = "category2_source", nullable = false, length = 10)
    private String category2Source = "NONE";

    /**
     * 적재 키를 만든다. 적재하는 쪽과 중복을 확인하는 쪽이 <b>같은 함수</b>를 써야 한다 —
     * 한쪽만 규칙이 다르면 이미 있는 행을 못 찾아 같은 결제가 두 번 쌓인다.
     */
    public static String rowId(Long userId, String providerPaymentId) {
        return userId + ":" + providerPaymentId;
    }

    /** 실제 사람이 넣은 결제에만 붙는 제공자 키 접두사({@code RealPersonImportService}). */
    private static final String REAL_PREFIX = "real-";

    /**
     * 실제 사람의 명세서에서 온 결제인가 — <b>확정 분류 사전에 들어갈 자격</b>이다.
     *
     * <p>더미 사용자의 사업자번호는 생성기가 만들어 낸 것이라 <b>실재하지 않는다.</b> 데모로
     * 앱을 둘러보다 "맞아요"를 누르면 그 가짜 번호가 사전에 쌓이고, 사전은 그 순간
     * <i>"실제 사업자번호와 중분류"</i> 라는 약속을 어긴다. 그래서 쓰기 앞에 이 관문을 둔다.
     *
     * <p>읽기는 막지 않는다 — 사전에 실물만 있으면 더미가 그것을 읽어도 오염되지 않는다.
     */
    public boolean isFromRealPerson() {
        if (paymentId == null) return false;
        int colon = paymentId.indexOf(':');
        return colon >= 0 && paymentId.startsWith(REAL_PREFIX, colon + 1);
    }

    protected UserPayment() {}

    public UserPayment(String paymentId, Long userId, String cardSerial, Long cardCode,
                       LocalDateTime paymentDate, String industryCode, String category2,
                       int amount, String merchantName, String businessNumber) {
        this.paymentId = paymentId;
        this.userId = userId;
        this.cardSerial = cardSerial;
        this.cardCode = cardCode;
        this.paymentDate = paymentDate;
        this.industryCode = industryCode;
        this.category2 = category2;
        this.amount = amount;
        this.merchantName = merchantName;
        this.businessNumber = businessNumber;
    }

    public String getPaymentId() { return paymentId; }
    public Long getUserId() { return userId; }
    public String getCardSerial() { return cardSerial; }
    public Long getCardCode() { return cardCode; }
    public LocalDateTime getPaymentDate() { return paymentDate; }
    public String getKsicCode() { return industryCode; }
    public String getCategory2() { return category2; }
    public int getAmount() { return amount; }
    public String getMerchantName() { return merchantName; }
    public String getBusinessNumber() { return businessNumber; }
    public String getIndustryCodeGuess() { return industryCodeGuess; }

    /**
     * <b>추정 업종코드를 적는다</b> — 확정 칸은 건드리지 않는다.
     *
     * <p>이미 확정이 들어와 있으면 추정을 적지 않는다. 사실이 있는데 추측을 나란히 두면
     * 읽는 쪽이 무엇을 믿을지 고민하게 된다.
     */
    public boolean guessIndustryCode(String code) {
        if (code == null || code.isBlank()) return false;
        if (!isPlaceholderIndustry(this.industryCode)) return false;          // 확정이 있다
        if (code.equals(this.industryCodeGuess)) return false;
        this.industryCodeGuess = code;
        return true;
    }

    public String getCategory2Llm() { return category2Llm; }
    public String getCategory2Source() { return category2Source; }

    /** AI 추정을 담는다 — {@code category2} 는 건드리지 않는다. 출처는 유료 통로. */
    public void suggestCategory2(String llmCategory2) {
        suggestCategory2(llmCategory2, "LLM");
    }

    /**
     * AI 추정을 <b>출처와 함께</b> 담는다.
     *
     * <p>추정을 내는 통로가 둘이 됐다 — 유료(사전에 남는다)와 무료(사전에 안 남는다).
     * 화면에는 둘 다 "AI 추정"으로 똑같이 보이지만 <b>성질이 다르다</b>:
     * 유료 답은 사전에 쌓여 다음에도 같은 값이 나오고, 무료 답은 임시라 유료 답이 오면 덮인다.
     *
     * <p>구분해 두지 않으면 나중에 <i>"무료 통로가 이상하다"</i> 싶을 때 어느 값이 그쪽 것인지
     * 가려낼 방법이 없다. 화면 표시는 같아도 기록은 갈라 둔다.
     *
     * <p><b>"모름"은 추정이 아니다.</b> 모델이 답을 못 준 것을 {@code 카테고리없음} 이라는
     * 값으로 적으면 두 가지가 망가진다(2026-08-21 실측).
     *
     * <ul>
     *   <li><b>집계가 부풀려진다</b> — 미분류를 {@code category2_source='NONE'} 으로 세면
     *       이것이 <i>분류된 것</i>으로 잡힌다. 라진우가 그 기준으로 2건이었는데 실제 미분류는
     *       19건(372,961원)이었고, 분류율도 92.4%가 아니라 84.3%였다.</li>
     *   <li><b>화면이 거짓 배지를 단다</b> — {@code OnboardingController} 는
     *       "확정이 비었는데 추정이 있다"를 <i>AI 추정</i>으로 보여준다. 값이 {@code 카테고리없음}
     *       인데 "AI가 추정했다"고 표시된다.</li>
     * </ul>
     *
     * <p>그래서 모르는 값은 <b>안 적는다.</b> 못 맞혔다는 사실은 사전의 시도 기록
     * ({@code llm_attempts}·{@code last_attempt_at})과 무료 통로의 {@code misses} 가 들고 있다.
     *
     * @param source {@code LLM}(유료) 또는 {@code TEMP}(무료 임시)
     */
    public void suggestCategory2(String llmCategory2, String source) {
        if (llmCategory2 == null || llmCategory2.isBlank()
                || com.finntech.engine.IndustryCategoryMapper.isUnknown(llmCategory2)) {
            return;                       // 모름은 추정이 아니다 — 아무것도 안 적는다
        }
        this.category2Llm = llmCategory2;
        this.category2Source = source;
    }


    /**
     * 확정 분류를 적용한다 — 사전에서 왔거나({@code DICT}) 사람이 확인한 것({@code USER})이다.
     * 이때는 {@code category2} 를 바꾼다. 근거가 사람이라 판정에 참여해도 원칙이 깨지지 않는다.
     */
    /**
     * <b>알아낸 업종코드를 자리채움 위에 적는다.</b>
     *
     * <p>카드 혜택 축은 중분류가 아니라 <b>업종코드</b>로 정해진다({@code cardAxisOf}).
     * 실 명세서에는 코드가 없어 그 축이 통째로 죽어 있었다. 무료·유료 통로가 업종을
     * 알아내면 그 이름에서 나온 코드를 여기 적어 축이 살아나게 한다.
     *
     * <p><b>사실을 덮지 않는다.</b> 제공자가 준 진짜 코드가 있으면 그대로 둔다 — 자리채움일
     * 때만 갈아 끼운다. 그 값이 추정에서 왔다는 사실은 {@code category2Source} 가 이미
     * 들고 있다(사전에는 안 적는다 — 전역 자산에 추정을 번지게 하지 않는다, V29).
     *
     * @return 실제로 바뀌었으면 {@code true}
     */
    public boolean learnIndustryCode(String code) {
        if (code == null || code.isBlank()) return false;
        if (!isPlaceholderIndustry(this.industryCode)) return false;
        if (code.equals(this.industryCode)) return false;
        this.industryCode = code;
        return true;
    }

    /**
     * 표시명을 적는다 — <b>바뀔 때만</b>.
     *
     * <p>{@code true} 를 돌려주는 것이 요점이다. 부르는 쪽이 <i>"이번에 실제로 고쳤나"</i>를
     * 알아야 리포트 캐시를 깰지 말지 정한다 — {@link #learnIndustryCode} 와 같은 계약이다.
     *
     * @return 실제로 바뀌었으면 {@code true}
     */
    public boolean learnDisplayName(String display, String source, String agency) {
        if (display == null || display.isBlank() || source == null) return false;
        String cut = display.length() > 60 ? display.substring(0, 60) : display;
        String via = agency == null || agency.isBlank() ? null
                : (agency.length() > 40 ? agency.substring(0, 40) : agency);
        if (cut.equals(this.displayName) && source.equals(this.displayNameSource)
                && java.util.Objects.equals(via, this.viaAgency)) {
            return false;
        }
        this.displayName = cut;
        this.displayNameSource = source;
        this.viaAgency = via;
        return true;
    }

    public String getDisplayName() { return displayName; }

    public String getDisplayNameSource() { return displayNameSource; }

    public String getViaAgency() { return viaAgency; }

    /**
     * <b>결제대행사 자신이라 무엇을 샀는지 알 수 없다고 적는다</b> — `간편결제`.
     *
     * <p><b>사람이 정한 것은 안 덮는다.</b> 우리 PG 목록이 틀려 진짜 가맹점이 잘못 걸릴 수
     * 있고, 그때 사용자가 고쳐 둔 답을 배치가 매일 밤 되돌리면 고칠 방법이 없어진다.
     *
     * @return 실제로 바뀌었으면 {@code true}
     */
    public boolean markSimplePay() {
        if ("USER".equals(this.category2Source)) return false;
        String simplePay = com.finntech.engine.IndustryCategoryMapper.SIMPLE_PAY;
        if (simplePay.equals(this.category2)) return false;
        // 추정은 함께 치운다 — 무엇을 샀는지 모른다고 적으면서 추정을 남기면 화면이 갈린다.
        this.category2Llm = null;
        this.category2 = simplePay;
        this.category2Source = "AGENCY";
        return true;
    }

    public void confirmCategory2(String category2, String source) {
        // **임시 추정을 함께 치운다.** 무료 통로의 답은 확정이 오기 전까지만 사는 값인데,
        // 남겨 두면 확정이 붙은 뒤에도 옛 추정이 화면에 따라다닌다. 규칙을 여기 한 곳에 두는
        // 이유는 확정을 적는 자리가 여섯 곳이라 흩어 놓으면 한 곳이 빠지기 때문이다.
        // `TEMP` 는 2026-08-21 이후로 안 쓴다(무료·유료를 가르던 두 층을 없앴다). 그 전에
        // 쓰인 줄이 DB 에 남아 있어 청소 분기는 그대로 둔다.
        if ("TEMP".equals(this.category2Source)) {
            this.category2Llm = null;
        }
        this.category2 = category2;
        this.category2Source = source;
    }
}
