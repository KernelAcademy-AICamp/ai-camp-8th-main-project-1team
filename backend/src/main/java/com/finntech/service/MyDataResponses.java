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

    public record BenefitView(String category1Name, int discountPercent,
                              int performanceStart, int performanceEnd, int monthlyLimit) {}

    public record CardProductView(Long code, String name, String imgUrl, String color,
                                  CompanyView company, List<BenefitView> benefits) {}

    public record PaymentView(String id, LocalDateTime date, String category1, String category2,
                              int amount, String merchantName, int receivedBenefitAmount, Long cardCode) {}

    public record CardView(String cardId, LocalDate expirationDate, int prevMonthAmount,
                           CardProductView cardProduct, UserView user, List<PaymentView> payments) {}

    // 데이터 최소화(W7-2): 제공자가 주민번호·전화번호를 응답에 싣지 않는다(본체 미사용). 격리가 뚫려도 PII 미유출.
    public record UserView(String id, String name) {}
}
