package com.finntech.mydata.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 마이데이터 응답 DTO 모음 (nested records). 본체가 이 스키마를 그대로 소비한다.
 * 카드/결제/사용자 응답 뷰 계열(마이데이터 카드 1장 + 상품·소유자·결제내역).
 */
public final class MyDataDtos {
    private MyDataDtos() {}

    // 데이터 최소화(W7-2): 주민번호·전화번호는 응답에 싣지 않는다(본체 미소비). 격리가 뚫려도 PII 미유출.
    public record UserView(String id, String name) {}

    /**
     * 신원 대조 결과 — 본인인증이 <b>어느 항목이 틀렸는지</b> 가려내는 재료다.
     *
     * <p>제공자는 <b>사실만</b> 답한다. "이름이 틀렸다"는 판정은 본체가 한다(마스터 §4 원칙 1).
     * 그래서 여기서 돌려주는 것은 조회 결과일 뿐이고, 이름·주민번호는 <b>일치 여부(불리언)</b>로만
     * 나간다 — 남의 실명이 그대로 넘어가지 않게 한다.
     *
     * @param exists        셋(이름·주민7·전화)이 모두 맞는 사람이 있는가
     * @param phoneTaken    그 전화번호로 등록된 사람이 있는가
     * @param phoneNameOk   그 번호 명의자의 이름이 입력한 이름과 같은가 (번호 미등록이면 false)
     * @param phoneSocialOk 그 번호 명의자의 주민번호 앞 7자리가 같은가 (번호 미등록이면 false)
     * @param personFound   이름+주민번호로 찾은 사람이 있는가 (번호는 다를 수 있다)
     */
    public record IdentityMatchView(boolean exists, boolean phoneTaken,
                                    boolean phoneNameOk, boolean phoneSocialOk,
                                    boolean personFound) {}

    public record CompanyView(Long id, String name, String imgUrl) {}

    /**
     * 연동 가능 은행. 은행에는 카드사 같은 카탈로그 테이블이 없어 계좌에 있는 은행명을
     * 이름순으로 세운 <b>순번</b>을 id로 준다 — 데이터가 결정론이라 id도 결정론이다.
     */
    public record BankView(Long id, String name) {}

    /** @param midCategory 혜택 대상 소비 중분류(식비·카페/간식 등). 예전에는 7대분류였다. */
    public record BenefitView(String midCategory, int discountPercent,
                              int performanceStart, int performanceEnd, int monthlyLimit) {}

    public record CardProductView(Long code, String name, String imgUrl, String color,
                                  CompanyView company, List<BenefitView> benefits) {}

    /**
     * 결제 1건.
     *
     * <p><b>소비 카테고리를 넘기지 않는다.</b> 마이데이터 제공자가 아는 것은 "이 가맹점이 무슨
     * 업종인가"({@code ksicCode})까지고, "사용자에게 이 소비가 무엇인가"는 앱이 판단할 몫이다.
     * 실제 마이데이터도 그렇게 동작하며, 이 경계를 지켜야 앱의 분류 품질을 검증할 수 있다.
     * 생성 시점의 소비맥락(category2)은 제공자 DB에만 남아 학습에 쓰인다 — 낭비 라벨과 같다.
     */
    public record PaymentView(String id, LocalDateTime date, String ksicCode,
                              int amount, String merchantName, int receivedBenefitAmount, Long cardCode,
                              String businessNumber) {}

    /** 카드 1장 + 그 카드의 상품정보·소유자·결제내역 전체 — 본체가 UserCard/UserPayment로 영속화. */
    public record CardView(String cardId, LocalDate expirationDate, int prevMonthAmount,
                           CardProductView cardProduct, UserView user, List<PaymentView> payments) {}

    /** 입출금 통장 1건(§13-11 경제 모델) — 은행·상품·계좌·월급·잔액 + 최근 입출금 내역(월급 입금 + 카드 출금). */
    public record AccountView(String accountNumber, String bank, String product, String salaryPayer,
                              int salary, int payday, long balance, List<AccountTxnView> transactions) {}

    /** 통장 입출금 1건. type = DEPOSIT(월급 입금) | WITHDRAWAL(카드 출금). amount는 부호 없는 절대액. */
    /**
     * 통장 거래 한 줄. {@code balanceAfter}는 <b>이 거래 직후의 잔액</b>이다.
     * +/− 금액만 있으면 "그래서 지금 얼마인가"를 사용자가 암산해야 한다 — 통장을 보는 이유가 그건데.
     */
    /**
     * 통장 거래 한 줄 — 실제 통장의 두 칸 구조를 따른다.
     *
     * <ul>
     *   <li>{@code description}(적요) — 거래 상대나 성격. 예: {@code 뚜레쥬르 병영1동점}, {@code 이자입금}, {@code 김민준}
     *   <li>{@code note}(비고) — 취급점이나 채널. 예: {@code KB체크}, {@code BNK경남은행본부}, {@code 전자금융이체}
     *   <li>{@code balanceAfter} — 이 거래 직후의 잔액
     * </ul>
     */
    public record AccountTxnView(LocalDateTime date, String type, long amount, String description,
                                 String note, long balanceAfter) {}

    /** 가맹점 조회(번호→주소) — 사용자가 결제에 실린 사업자번호로 가맹점명·지번주소를 조회한다. */
    public record MerchantView(String businessNumber, String merchantName, String address,
                               Double lat, Double lng, boolean online) {}
}
