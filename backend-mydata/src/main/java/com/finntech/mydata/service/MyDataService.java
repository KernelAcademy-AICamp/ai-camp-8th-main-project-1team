package com.finntech.mydata.service;

import com.finntech.mydata.domain.*;
import com.finntech.mydata.dto.MyDataDtos.*;
import com.finntech.mydata.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 마이데이터 조회 서비스 — 본체가 요청한 사용자(CI)+카드사의 카드·결제내역을 DTO로 조립한다.
 * 인증은 없다(내부 서버-투-서버 신뢰).
 */
@Service
public class MyDataService {

    private final MyDataUserRepository userRepository;
    private final MyDataCardRepository cardRepository;
    private final MyDataPaymentRepository paymentRepository;
    private final CardCompanyRepository companyRepository;

    public MyDataService(MyDataUserRepository userRepository, MyDataCardRepository cardRepository,
                         MyDataPaymentRepository paymentRepository, CardCompanyRepository companyRepository) {
        this.userRepository = userRepository;
        this.cardRepository = cardRepository;
        this.paymentRepository = paymentRepository;
        this.companyRepository = companyRepository;
    }

    /** 존재 확인 — 본인인증 후 본체가 "이 CI가 마이데이터에 있는 회원인가"를 묻는다. */
    @Transactional(readOnly = true)
    public boolean userExists(String ci) {
        return userRepository.existsById(ci);
    }

    /** 카드사 목록(연동 기관 선택용). */
    @Transactional(readOnly = true)
    public List<CompanyView> findCompanies() {
        return companyRepository.findAllByOrderByIdAsc().stream().map(this::toCompanyView).toList();
    }

    /** 전체 조회 — 사용자의 특정 카드사 카드 + 결제내역 전부. */
    @Transactional(readOnly = true)
    public List<CardView> findCards(Long companyId, String userId) {
        return cardRepository.findByUserAndCompany(userId, companyId).stream()
                .map(card -> toCardView(card, paymentRepository.findByCard(card.getId())))
                .toList();
    }

    /** 증분 조회 — 마지막 동기화 이후의 결제만. */
    @Transactional(readOnly = true)
    public List<CardView> findCardsSince(Long companyId, String userId, LocalDateTime lastRenewalTime) {
        return cardRepository.findByUserAndCompany(userId, companyId).stream()
                .map(card -> toCardView(card, paymentRepository.findByCardAfter(card.getId(), lastRenewalTime)))
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
        return new UserView(user.getId(), user.getName(), user.getSocialNumber(), user.getPhoneNumber());
    }

    private PaymentView toPaymentView(MyDataPayment payment, Long cardCode) {
        return new PaymentView(payment.getId(), payment.getPaymentDate(), payment.getCategory1(),
                payment.getCategory2(), payment.getAmount(), payment.getMerchantName(),
                payment.getReceivedBenefitAmount(), cardCode);
    }
}
