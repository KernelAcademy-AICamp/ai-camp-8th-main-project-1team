package com.finntech.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 마이데이터 서버(backend-mydata) 응답을 역직렬화하는 클라이언트 측 DTO.
 * backend-mydata 의 {@code ApiResponse}/{@code MyDataDtos} 스키마와 필드명이 일치해야 한다.
 */
public final class MyDataResponses {
    private MyDataResponses() {}

    public record Envelope<T>(int statusCode, String message, T data) {}

    public record CompanyView(Long id, String name, String imgUrl) {}

    /** 연동 가능 은행. id는 제공자가 이름순으로 매긴 순번(결정론). */
    public record BankView(Long id, String name) {}

    /** @param midCategory 혜택 대상 소비 중분류(식비·카페/간식 등). 예전에는 7대분류였다. */
    public record BenefitView(String midCategory, int discountPercent,
                              int performanceStart, int performanceEnd, int monthlyLimit) {}

    public record CardProductView(Long code, String name, String imgUrl, String color,
                                  CompanyView company, List<BenefitView> benefits) {}

    /**
     * 결제 1건 — 제공자는 <b>업종코드까지만</b> 준다.
     *
     * <p>"이 소비가 사용자에게 무엇인가"는 우리가 판단할 몫이다
     * ({@link com.finntech.engine.IndustryCategoryMapper}). 제공자가 소비 카테고리를 정해 주면
     * 앱의 분류 품질을 검증할 방법이 없고, 실제 마이데이터도 업종까지만 준다.
     */
    public record PaymentView(String id, LocalDateTime date, String industryCode,
                              int amount, String merchantName, Long cardCode,
                              String businessNumber) {}

    public record CardView(String cardId, LocalDate expirationDate,
                           CardProductView cardProduct, UserView user, List<PaymentView> payments) {}

    // 데이터 최소화(W7-2): 제공자가 주민번호·전화번호를 응답에 싣지 않는다(본체 미사용). 격리가 뚫려도 PII 미유출.
    public record UserView(String id, String name) {}

    /** 입출금 통장(§13-11) — 은행·계좌·월급·잔액 + 최근 입출금 내역. */
    public record AccountView(String accountNumber, String bank, String product, String salaryPayer,
                              int salary, int payday, long balance, List<AccountTxnView> transactions) {}

    /** {@code balanceAfter} = 이 거래 직후의 잔액. +/−만 있으면 "그래서 지금 얼마인가"를 사용자가 암산해야 한다. */
    /** description=적요(상대·성격), note=비고(취급점·채널), balanceAfter=거래 직후 잔액. */
    public record AccountTxnView(LocalDateTime date, String type, long amount, String description,
                                 String note, long balanceAfter) {}

    /**
     * 가맹점 조회(번호→주소) 응답 — mydata의 MerchantView와 필드명 일치.
     *
     * <p>{@code industryCode}는 제공자가 준 업종이고 {@code category}는 <b>우리가 붙인</b> 소비 중분류다.
     * 제공자 응답에는 category가 없어 역직렬화 직후엔 null이며, 프록시가 매핑해 채운다
     * (결제와 같은 경계 — 제공자는 업종까지, 소비 분류는 앱이 한다).
     */
    public record MerchantView(String industryCode, String category, String businessNumber, String merchantName,
                               String address, Double lat, Double lng, boolean online) {}
}
