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

/** 제공자가 준 업종코드(KSIC 세분류 4자리). 분류의 원본 근거라 그대로 보관한다. */
    @Column(name = "ksic_code", nullable = false, length = 8)
    private String industryCode;

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
     * @param source {@code LLM}(유료) 또는 {@code TEMP}(무료 임시)
     */
    public void suggestCategory2(String llmCategory2, String source) {
        this.category2Llm = llmCategory2;
        this.category2Source = source;
    }


    /**
     * 확정 분류를 적용한다 — 사전에서 왔거나({@code DICT}) 사람이 확인한 것({@code USER})이다.
     * 이때는 {@code category2} 를 바꾼다. 근거가 사람이라 판정에 참여해도 원칙이 깨지지 않는다.
     */
    public void confirmCategory2(String category2, String source) {
        // **임시 추정을 함께 치운다.** 무료 통로의 답은 확정이 오기 전까지만 사는 값인데,
        // 남겨 두면 확정이 붙은 뒤에도 옛 추정이 화면에 따라다닌다. 규칙을 여기 한 곳에 두는
        // 이유는 확정을 적는 자리가 여섯 곳이라 흩어 놓으면 한 곳이 빠지기 때문이다.
        if ("TEMP".equals(this.category2Source)) {
            this.category2Llm = null;
        }
        this.category2 = category2;
        this.category2Source = source;
    }
}
