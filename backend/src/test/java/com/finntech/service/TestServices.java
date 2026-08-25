package com.finntech.service;

import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 시험용 서비스 <b>조립기</b> — 생성자가 바뀌어도 고칠 곳이 한 군데다.
 *
 * <p>여기 있는 이유가 하나다. 서비스가 협력자를 하나 더 받을 때마다 대역을 손으로 세운
 * 시험 파일이 전부 깨졌다(2026-08-07까지 네 번). 깨진 곳을 하나씩 고치는 것은 같은 일을
 * 파일 수만큼 하는 것이고, 그러다 한 곳을 빠뜨리면 <b>빌드가 아니라 실행에서</b> 터진다.
 *
 * <p>조립을 한 곳에 모으면 생성자가 바뀔 때 여기만 고치면 된다.
 */
final class TestServices {

    private TestServices() {}

    /**
     * 시험용 큐 — <b>넣는 즉시 그 자리에서 돌린다.</b>
     *
     * <p>운영에서는 2초 발사기가 예산을 보고 꺼내지만, 시험의 관심사는 "무엇이 나가는가"이지
     * "언제 나가는가"가 아니다. 즉시 실행이면 시험이 스레드를 기다리지 않아도 된다.
     * 예산·순서 자체를 보는 시험은 진짜 큐를 직접 세운다.
     */
    static com.finntech.freechannel.FreeChannelQueue directQueue() {
        return new com.finntech.freechannel.FreeChannelQueue(40, 6, 500) {
            @Override
            public boolean submit(com.finntech.freechannel.Lane lane, String key, Runnable work) {
                work.run();
                return true;
            }
        };
    }

    /**
     * <b>자기 프록시를 매어 준다.</b> 운영에서는 스프링이 트랜잭션을 걸어 주는 프록시를 넣지만,
     * 시험에서는 자기 자신이면 충분하다 — 트랜잭션 경계가 관심사인 시험은 애노테이션을 직접 읽는다.
     */
    static <T> ObjectProvider<T> selfOf(AtomicReference<T> holder) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenAnswer(inv -> holder.get());
        return provider;
    }

    /** 질의를 <b>안 하는</b> 분류 질의 서비스 — 두 통로가 다 꺼져 있어 바깥 서버를 안 부른다. */
    static MerchantAskService askService(MerchantCategoryService dictionary,
                                         MerchantClassifierService classifier,
                                         TempClassifierService temporary) {
        var self = new AtomicReference<MerchantAskService>();
        var service = new MerchantAskService(
                mock(com.finntech.repository.UserPaymentRepository.class),
                mock(com.finntech.repository.ConsumptionRepository.class),
                mock(com.finntech.repository.CategoryRepository.class),
                dictionary, classifier, temporary,
                java.time.Clock.systemDefaultZone(),
                new com.finntech.engine.IndustryCategoryMapper(new tools.jackson.databind.ObjectMapper()),
                selfOf(self),
                // 배치 상한은 여기서 재지 않는다 — 시험이 넣은 만큼 다 묻게 넉넉히 준다.
                1000);
        self.set(service);
        return service;
    }

    /**
     * 마이데이터 연동 서비스 — 협력자를 통째로 받는다.
     *
     * <p>연동은 협력자가 열여섯이라, 시험 파일마다 손으로 세우면 협력자가 하나 늘 때마다
     * 전부 깨진다. 조립을 여기 모아 둔다.
     */
    static MyDataLinkService linkService(
            MyDataClient client,
            com.finntech.repository.AppUserRepository users,
            com.finntech.repository.UserCardRepository cards,
            com.finntech.repository.UserPaymentRepository payments,
            com.finntech.repository.ConsumptionRepository consumptions,
            com.finntech.repository.CategoryRepository categories,
            com.finntech.engine.IndustryCategoryMapper mapper,
            MerchantCategoryService dictionary,
            BusinessNumberKindService kinds,
            IndustryLookupService lookup,
            MerchantAskService ask,
            MerchantBrandService brands,
            com.finntech.repository.UserCardCompanyRepository links,
            com.finntech.repository.UserBankRepository bankLinks,
            com.finntech.repository.ReportRepository reports,
            java.time.Clock clock,
            String referenceDate) {
        // 후속 단계를 **같은 스레드에서** 돌리는 것이 기본이다 — 시험이 그 결과를 바로 볼 수 있게.
        return linkService(client, users, cards, payments, consumptions, categories, mapper,
                dictionary, kinds, lookup, ask, brands, links, bankLinks, reports, clock,
                referenceDate, Runnable::run);
    }

    /**
     * 일꾼까지 지정하는 변형 — <b>비동기라는 사실 자체</b>를 검사할 때 쓴다.
     *
     * <p>운영은 후속 단계를 배경 일꾼에게 넘기고 요청은 기다리지 않는다. 실행을 미루는 일꾼을
     * 주면 "넘기기만 하고 돌아왔는가"를 볼 수 있다.
     */
    static MyDataLinkService linkService(
            MyDataClient client,
            com.finntech.repository.AppUserRepository users,
            com.finntech.repository.UserCardRepository cards,
            com.finntech.repository.UserPaymentRepository payments,
            com.finntech.repository.ConsumptionRepository consumptions,
            com.finntech.repository.CategoryRepository categories,
            com.finntech.engine.IndustryCategoryMapper mapper,
            MerchantCategoryService dictionary,
            BusinessNumberKindService kinds,
            IndustryLookupService lookup,
            MerchantAskService ask,
            MerchantBrandService brands,
            com.finntech.repository.UserCardCompanyRepository links,
            com.finntech.repository.UserBankRepository bankLinks,
            com.finntech.repository.ReportRepository reports,
            java.time.Clock clock,
            String referenceDate,
            java.util.concurrent.Executor followUps) {
        var self = new AtomicReference<MyDataLinkService>();
        // 사전 리포지토리는 여기서 세운다 — 협력자가 하나 늘 때마다 시험 파일이 전부 깨지는 것을
        // 막는 것이 이 조립기의 목적이다(클래스 주석). 주소를 보는 시험은 직접 대역을 준다.
        var service = new MyDataLinkService(client, users, cards, payments, consumptions,
                categories, mapper, dictionary,
                mock(com.finntech.repository.MerchantCategoryRepository.class),
                kinds, lookup, ask, brands, links, bankLinks,
                reports, clock, referenceDate,
                // 추정 반영은 이 조립기를 쓰는 시험의 관심사가 아니다 — 대역으로 둔다.
                // 규칙 자체는 `CategoryPromotionServiceTest` 가 따로 검사한다.
                mock(CategoryPromotionService.class), followUps,
                // 소비 원장 표시도 마찬가지 — 표가 원장을 따라오는지는
                // `SpendingLedgerFactsTest` 가 통합으로 본다.
                mock(com.finntech.ledger.SpendingLedgerDirtyMarker.class), mock(com.finntech.service.IndustryCodeBackfill.class),
                selfOf(self));
        self.set(service);
        return service;
    }

    /**
     * 아무것도 모르는 브랜드 서비스 — 표도 사전도 비어 있고 모델은 꺼져 있다.
     *
     * <p>브랜드가 관심사가 아닌 시험(연동 기준선·사전 규칙 등)이 널 대신 쓰는 것이다.
     */
    static MerchantBrandService brandService() {
        var brands = mock(com.finntech.repository.MerchantBrandRepository.class);
        when(brands.findByMerchantName(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());
        when(brands.findByMerchantNameIn(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());
        return brandService(brands,
                mock(com.finntech.repository.MerchantCategoryRepository.class),
                mock(TempClassifierService.class),
                mock(com.finntech.repository.UserPaymentRepository.class));
    }

    /**
     * 대역을 직접 주는 형태.
     *
     * <p><b>자기 프록시</b>는 여기서 매어 준다. 운영에서는 스프링이 트랜잭션을 걸어 주는
     * 프록시를 넣지만, 시험에서는 자기 자신이면 충분하다 — 트랜잭션 경계가 관심사인 시험은
     * 애노테이션을 직접 읽어 본다.
     */
    static MerchantBrandService brandService(
            com.finntech.repository.MerchantBrandRepository brands,
            com.finntech.repository.MerchantCategoryRepository dictionary,
            TempClassifierService temporary,
            com.finntech.repository.UserPaymentRepository payments) {
        var self = new AtomicReference<MerchantBrandService>();
        var service = new MerchantBrandService(brands, dictionary, temporary, payments,
                new tools.jackson.databind.ObjectMapper(), selfOf(self), directQueue());
        self.set(service);
        return service;
    }
}
