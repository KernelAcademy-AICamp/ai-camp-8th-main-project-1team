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

    public record CompanyView(Long id, String name, String imgUrl) {}

    public record BenefitView(String category1Name, int discountPercent,
                              int performanceStart, int performanceEnd, int monthlyLimit) {}

    public record CardProductView(Long code, String name, String imgUrl, String color,
                                  CompanyView company, List<BenefitView> benefits) {}

    public record PaymentView(String id, LocalDateTime date, String category1, String category2,
                              int amount, String merchantName, int receivedBenefitAmount, Long cardCode,
                              String businessNumber) {}

    /** 카드 1장 + 그 카드의 상품정보·소유자·결제내역 전체 — 본체가 UserCard/UserPayment로 영속화. */
    public record CardView(String cardId, LocalDate expirationDate, int prevMonthAmount,
                           CardProductView cardProduct, UserView user, List<PaymentView> payments) {}

    /** 입출금 통장 1건(§13-11 경제 모델) — 은행·상품·계좌·월급·잔액 + 최근 입출금 내역(월급 입금 + 카드 출금). */
    public record AccountView(String accountNumber, String bank, String product, String salaryPayer,
                              int salary, int payday, long balance, List<AccountTxnView> transactions) {}

    /** 통장 입출금 1건. type = DEPOSIT(월급 입금) | WITHDRAWAL(카드 출금). amount는 부호 없는 절대액. */
    public record AccountTxnView(LocalDateTime date, String type, long amount, String description) {}

    /** 가맹점 조회(번호→주소) — 사용자가 결제에 실린 사업자번호로 가맹점명·지번주소를 조회한다. */
    public record MerchantView(String businessNumber, String merchantName, String address,
                               Double lat, Double lng, boolean online) {}
}
