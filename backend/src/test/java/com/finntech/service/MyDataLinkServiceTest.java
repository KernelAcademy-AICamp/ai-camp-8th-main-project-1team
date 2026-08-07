package com.finntech.service;

import com.finntech.domain.AppUser;
import com.finntech.domain.Category;
import com.finntech.domain.UserCardCompany;
import com.finntech.repository.*;
import com.finntech.service.MyDataResponses.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 연동 직후의 <b>증분 기준선</b>이 옳은지 본다.
 *
 * <p>이 값이 실제로 받아온 마지막 결제보다 앞서 찍히면, 그 사이의 결제는 다음 증분 조회에서
 * 조건({@code paymentDate > lastRenewalTime})에 걸러져 <b>영영 들어오지 않는다.</b>
 * 자동 동기화를 붙여도 마찬가지라, 배치가 아무리 돌아도 그날 낮 소비가 통째로 비어 보인다.
 */
class MyDataLinkServiceTest {

    private static final LocalDateTime 마지막_결제 = LocalDateTime.of(2026, 7, 28, 11, 40);

    private static PaymentView 결제(String id, LocalDateTime at, int amount) {
        // 5914 = 영화 및 비디오물 상영업 → 취미/여가. 제공자는 업종코드까지만 준다.
        return new PaymentView(id, at, "5914", amount, "씨네Q", 0, 9713L, "1234567890");
    }

    private static CardView 카드(List<PaymentView> payments) {
        CompanyView company = new CompanyView(9007L, "하나카드", null);
        CardProductView product = new CardProductView(9713L, "하나 원큐", null, "#111", company, List.of());
        return new CardView("0107-0319-8232-0101", LocalDate.of(2030, 1, 31), 300_000,
                product, new UserView("ci", "홍길동"), payments);
    }

    /** 연동 당일 오전에 연결하고, 그날 남은 시간의 결제가 아직 안 왔을 때의 기준선. */
    @Test
    void 증분_기준선은_실제로_받아온_마지막_결제_시각이다() {
        MyDataClient client = mock(MyDataClient.class);
        AppUserRepository users = mock(AppUserRepository.class);
        UserCardRepository cards = mock(UserCardRepository.class);
        UserPaymentRepository payments = mock(UserPaymentRepository.class);
        ConsumptionRepository consumptions = mock(ConsumptionRepository.class);
        CategoryRepository categories = mock(CategoryRepository.class);
        UserCardCompanyRepository links = mock(UserCardCompanyRepository.class);
        UserBankRepository bankLinks = mock(UserBankRepository.class);
        ReportRepository reports = mock(ReportRepository.class);

        AppUser user = mock(AppUser.class);
        when(user.getCi()).thenReturn("ci");
        when(user.isConsentGiven()).thenReturn(true);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(categories.findByCode(anyString())).thenReturn(Optional.of(new Category("여가", "여가")));
        when(client.findCards(9007L, "ci")).thenReturn(List.of(카드(List.of(
                결제("p1", LocalDateTime.of(2026, 7, 24, 18, 9), 12_000),
                결제("p2", 마지막_결제, 6_400)))));
        when(client.findAccount("ci")).thenReturn(null);

        // 연동 시각은 마지막 결제보다 한참 뒤 — 예전 코드는 여기서 '오늘 23:59:59'를 찍었다.
        Clock clock = Clock.fixed(
                LocalDateTime.of(2026, 7, 28, 14, 0).atZone(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault());

        new MyDataLinkService(client, users, cards, payments, consumptions, categories,
                new com.finntech.engine.IndustryCategoryMapper(new tools.jackson.databind.ObjectMapper()),
                emptyDictionary(), emptyKinds(), offLookup(), noAsk(), links,
                mock(UserBankRepository.class), reports, clock, "")
                .linkCardCompanies(1L, List.of(9007L));

        ArgumentCaptor<UserCardCompany> saved = ArgumentCaptor.forClass(UserCardCompany.class);
        verify(links).save(saved.capture());
        assertEquals(마지막_결제, saved.getValue().getLastRenewalTime(),
                "기준선이 앞서 찍히면 그 사이 결제가 증분에서 영영 빠진다");
        assertTrue(saved.getValue().getLinkedAt().isAfter(마지막_결제), "연동 시각은 별개로 남는다");
    }

    /** 결제가 하나도 없는 카드사 — 기준선을 '지금'으로 잡으면 이후 과거분 보정이 막힌다. */
    @Test
    void 결제가_없으면_기준선을_넉넉히_과거로_잡는다() {
        MyDataClient client = mock(MyDataClient.class);
        AppUserRepository users = mock(AppUserRepository.class);
        UserCardCompanyRepository links = mock(UserCardCompanyRepository.class);

        AppUser user = mock(AppUser.class);
        when(user.getCi()).thenReturn("ci");
        when(user.isConsentGiven()).thenReturn(true);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(client.findCards(9007L, "ci")).thenReturn(List.of(카드(List.of())));
        when(client.findAccount("ci")).thenReturn(null);

        LocalDateTime 연동시각 = LocalDateTime.of(2026, 7, 28, 14, 0);
        Clock clock = Clock.fixed(연동시각.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

        new MyDataLinkService(client, users, mock(UserCardRepository.class), mock(UserPaymentRepository.class),
                mock(ConsumptionRepository.class), mock(CategoryRepository.class),
                new com.finntech.engine.IndustryCategoryMapper(new tools.jackson.databind.ObjectMapper()),
                emptyDictionary(), emptyKinds(), offLookup(), noAsk(), links,
                mock(UserBankRepository.class), mock(ReportRepository.class), clock, "")
                .linkCardCompanies(1L, List.of(9007L));

        ArgumentCaptor<UserCardCompany> saved = ArgumentCaptor.forClass(UserCardCompany.class);
        verify(links).save(saved.capture());
        assertTrue(saved.getValue().getLastRenewalTime().isBefore(연동시각.minusMonths(6)),
                "받아온 결제가 없으면 다음 증분이 무엇이든 집어올 수 있어야 한다");
    }

    /**
     * 빈 확정 분류 사전. 이 테스트가 보는 것은 <b>증분 기준선</b>이라 사전은 관여하지 않는다 —
     * 다만 적재 경로가 사전을 한 번 읽으므로 널이 아닌 것을 준다.
     */
    /** 관측 판정 — 시험에서는 표가 비어 있다(상호가 하나뿐인 상태와 같아 완화가 통과한다). */
    /**
     * LLM 질의를 <b>안 하는</b> 것 — 분류기가 꺼져 있어({@code aiEnabled()} false) 아무것도 묻지 않는다.
     *
     * <p>이 시험이 보는 것은 증분 기준선이라 분류는 상관이 없다. 그래도 대역을 넣는 이유는
     * 단위 시험이 바깥 서버를 부르지 않는 것이 <b>설정이 아니라 기본값</b>이라야 하기 때문이다.
     */
    /** 임시 분류를 <b>끈</b> 것 — 기본 상태 그대로다(주소·키·모델이 비면 안 부른다). */
    private static TempClassifierService offTempClassifier() {
        return new TempClassifierService(new com.finntech.config.TempClassifierProperties(),
                new com.finntech.engine.IndustryCategoryMapper(new tools.jackson.databind.ObjectMapper()),
                mock(MerchantClassifierService.class), new tools.jackson.databind.ObjectMapper());
    }

    private static MerchantAskService noAsk() {
        return new MerchantAskService(mock(com.finntech.repository.UserPaymentRepository.class),
                mock(com.finntech.repository.ConsumptionRepository.class),
                mock(com.finntech.repository.CategoryRepository.class),
                emptyDictionary(), mock(MerchantClassifierService.class),
                offTempClassifier(), java.time.Clock.systemDefaultZone());
    }

    /**
     * 등록 업종 조회를 <b>끈</b> 것 — 기본 상태 그대로다.
     *
     * <p>이 통로는 주소·추출식이 환경변수로 들어와야 켜지고, 안 주면 조용히 꺼진 채 있다.
     * 단위 테스트에서 바깥 서버를 부르지 않는 것이 그래서 설정이 아니라 <b>기본값</b>이다.
     */
    private static IndustryLookupService offLookup() {
        return new IndustryLookupService(new com.finntech.config.IndustryLookupProperties(),
                new com.finntech.engine.IndustryCategoryMapper(new tools.jackson.databind.ObjectMapper()),
                emptyKinds());
    }

    private static BusinessNumberKindService emptyKinds() {
        var repo = mock(com.finntech.repository.BusinessNumberKindRepository.class);
        when(repo.findById(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());
        return new BusinessNumberKindService(repo, 5, 2, 0.10);
    }

    private static MerchantCategoryService emptyDictionary() {
        var repo = mock(com.finntech.repository.MerchantCategoryRepository.class);
        when(repo.findAll()).thenReturn(java.util.List.of());
        var kindRepo = mock(com.finntech.repository.BusinessNumberKindRepository.class);
        when(kindRepo.findById(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());
        return new MerchantCategoryService(repo,
                new com.finntech.engine.IndustryCategoryMapper(new tools.jackson.databind.ObjectMapper()),
                new BusinessNumberKindService(kindRepo, 5, 2, 0.10));
    }
}
