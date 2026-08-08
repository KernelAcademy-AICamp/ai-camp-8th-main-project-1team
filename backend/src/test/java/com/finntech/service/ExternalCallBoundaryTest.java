package com.finntech.service;

import com.finntech.domain.AppUser;
import com.finntech.repository.*;
import com.finntech.service.MyDataResponses.CardView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * <b>바깥 서버를 부르는 동안 트랜잭션을 붙잡지 않는다</b> — 그리고 같은 사용자를 두 번 돌리지 않는다.
 *
 * <p>여기 있는 것은 2026-08-07 감사에서 나온 결함들이다. 셋 다 <b>증상이 안 보인다</b>는 공통점이
 * 있다 — 기능은 그대로 동작하고, DB 커넥션이 몇 분씩 묶이거나 유료 호출이 두 배로 나갈 뿐이다.
 * 그래서 계약을 시험으로 못박아 둔다.
 */
class ExternalCallBoundaryTest {

    private static Transactional tx(Class<?> type, String name, Class<?>... args) throws Exception {
        Method m = type.getMethod(name, args);
        return m.getAnnotation(Transactional.class);
    }

    @Test
    @DisplayName("모델·조회를 부르는 진입점에는 트랜잭션이 없고, 읽기·쓰기 단계에만 있다")
    void externalCallsRunOutsideTransactions() throws Exception {
        // ── 분류 질의: ask 는 맨몸, plan 은 읽기 전용, applyGuesses 만 쓰기
        assertThat(tx(MerchantAskService.class, "ask", Long.class, int.class))
                .as("ask 에 트랜잭션이 붙으면 모델 호출이 그 안에서 돈다 — 미분류 50곳이면 8분").isNull();
        assertThat(tx(MerchantAskService.class, "plan", Long.class).readOnly()).isTrue();
        assertThat(tx(MerchantAskService.class, "applyGuesses", Long.class, java.util.Map.class,
                java.util.Map.class, java.util.Map.class, java.util.Set.class)).isNotNull();

        // ── 브랜드: label 은 맨몸, findPending 은 읽기 전용, persist 만 쓰기
        assertThat(tx(MerchantBrandService.class, "label", List.class, java.util.Set.class, int.class))
                .as("브랜드 질의는 가맹점당 6~10초다 — 스무 곳이면 3분").isNull();
        assertThat(tx(MerchantBrandService.class, "findPending", List.class, java.util.Set.class)
                .readOnly()).isTrue();
        assertThat(tx(MerchantBrandService.class, "persist", java.util.Map.class, java.util.Map.class))
                .isNotNull();

        // ── 마이데이터: 연동·동기화는 맨몸, 적재만 트랜잭션
        assertThat(tx(MyDataLinkService.class, "renew", Long.class))
                .as("renew 가 트랜잭션이면 위 분리가 통째로 무의미해진다").isNull();
        assertThat(tx(MyDataLinkService.class, "linkCardCompanies", Long.class, List.class, List.class))
                .isNull();
        assertThat(tx(MyDataLinkService.class, "pullNewPayments", Long.class)).isNotNull();
        assertThat(tx(MyDataLinkService.class, "loadCardCompanies", Long.class, List.class, List.class))
                .isNotNull();
        // 조회가 알아낸 것을 결제에 입히는 자리 — 여기서 다시 읽어야 영속 상태다.
        assertThat(tx(MyDataLinkService.class, "applyResolved", Long.class, java.util.Map.class))
                .as("트랜잭션 없이 고치면 아무 데도 안 써진다").isNotNull();
    }

    /**
     * <b>부르는 쪽이 다시 감싸면 위의 분리가 통째로 무의미해진다.</b>
     *
     * <p>서비스에서 트랜잭션을 걷어내도 진입점이 {@code @Transactional} 이면 안에서 연 세 단계가
     * 전부 그 하나에 합류한다. 2026-08-07 감사에서 실제로 두 자리가 그렇게 남아 있었다 —
     * 미분류 화면(임계값 1 이라 남은 것을 전부 몰아 묻는다)과 연동의 2-인자 형태(같은 객체
     * 안에서 3-인자를 부르므로 프록시를 안 거친다).
     */
    @Test
    @DisplayName("진입점이 트랜잭션으로 다시 감싸지 않는다")
    void entryPointsDoNotRewrapTheBoundary() throws Exception {
        assertThat(tx(com.finntech.web.MerchantCategoryController.class, "unclassified", Long.class))
                .as("미분류 화면이 트랜잭션이면 무료·유료 모델 호출이 그 안에서 돈다").isNull();
        assertThat(tx(MyDataLinkService.class, "linkCardCompanies", Long.class, List.class))
                .as("2-인자 형태는 3-인자를 같은 객체 안에서 부른다 — 여기 트랜잭션이 붙으면 전부 합류한다")
                .isNull();
        // 기관 찾기는 카드사마다 왕복한다 — 자기 Javadoc 이 "8곳이면 왕복 8번 + 계좌 1번"이라고 적어 뒀다.
        assertThat(tx(MyDataLinkService.class, "discover", Long.class))
                .as("왕복 아홉 번 내내 커넥션을 붙잡을 이유가 없다 — DB 는 맨 앞 사용자 조회 한 번뿐이다")
                .isNull();
        assertThat(tx(MyDataLinkService.class, "merchant", String.class))
                .as("DB 를 하나도 안 만지는데 트랜잭션을 열면 커넥션만 잡는다").isNull();
    }

    /**
     * <b>'헛물'을 셀 자격은 {@code answered} 하나가 정한다.</b>
     *
     * <p>세 번이면 그 가맹점이 '기타'로 종결되고, 종결은 결제와 소비 원장까지 고쳐 리포트에서
     * 그 지출을 지운다. 그러니 "물어보고 답을 받았는데 그 가맹점이 답에 없었다" 만 세어야 한다.
     * 아래 네 경우는 전부 <b>우리가 못 물은 것</b>이라 세면 안 되는데, 예전에는 첫 줄만 막혀
     * 있었다(2026-08-07 감사·재감사).
     *
     * <p>이 시험이 이전 판에서 놓쳤던 것도 같이 못박는다 — 그때는 결제 목록을 비워 두어
     * 헛물 세는 루프에 <b>한 번도 들어가지 않은 채</b> 통과했다. 그래서 대조군을 둔다.
     */
    @Test
    @DisplayName("헛물은 '물어보고 답을 받은' 가맹점에서만 센다")
    void missesAreCountedOnlyForMerchantsTheModelAnsweredFor() {
        java.util.Map<String, String> none = java.util.Map.of();
        java.util.Map<String, TempClassifierService.Guess> noTemp = java.util.Map.of();

        // ── 대조군: 물어봤고 답을 받았는데 넷플릭스가 답에 없었다 → 이것만 헛물이다
        var asked = mock(MerchantCategoryService.class);
        askServiceOverNetflix(asked).applyGuesses(24L, none, none, noTemp, Set.of("넷플릭스"));
        verify(asked, times(1)).noteLlmMiss(any(), any());

        // ① 통로가 안 돌았다 — 키가 없거나 임계값 미달. 답이 없는 게 아니라 묻지를 않았다.
        var offline = mock(MerchantCategoryService.class);
        askServiceOverNetflix(offline).applyGuesses(24L, none, none, noTemp, Set.of());
        verify(offline, never()).noteLlmMiss(any(), any());

        // ② 사전이 이미 추정을 안다 — ①에서 질문 목록에서 빠지므로 answered 에 없다.
        var remembered = mock(MerchantCategoryService.class);
        askServiceOverNetflix(remembered).applyGuesses(
                24L, java.util.Map.of("넷플릭스", "구독"), none, noTemp, Set.of());
        verify(remembered, never()).noteLlmMiss(any(), any());

        // ③ 상한을 넘겼거나 ①·③ 사이에 새로 들어왔다 — 다른 곳은 물었지만 이 곳은 안 물었다.
        var beyondCap = mock(MerchantCategoryService.class);
        askServiceOverNetflix(beyondCap).applyGuesses(24L, none, none, noTemp, Set.of("다른가게"));
        verify(beyondCap, never()).noteLlmMiss(any(), any());

        // ④ 물어봤고 답을 얻었다 — 헛물이 아니다.
        var answered = mock(MerchantCategoryService.class);
        askServiceOverNetflix(answered).applyGuesses(
                24L, none, java.util.Map.of("넷플릭스", "구독"), noTemp, Set.of("넷플릭스"));
        verify(answered, never()).noteLlmMiss(any(), any());
    }

    /**
     * 통로가 죽었는데 "물어봤다"로 세면 <b>키를 안 넣은 환경에서 화면 세 번</b>에 미분류 전부가
     * '기타'로 종결된다. 그래서 분류기가 '답을 받은 곳'을 스스로 보고해야 한다.
     */
    @Test
    @DisplayName("분류기는 답을 받은 가맹점만 보고한다 — 통로가 꺼져 있으면 아무것도 안 담는다")
    void classifierReportsOnlyWhatItActuallyAnsweredFor() {
        // 키가 없으면 HTTP 를 한 번도 안 낸다 — 그때 answered 가 비어 있어야 한다.
        var off = new MerchantClassifierService(
                new com.finntech.engine.IndustryCategoryMapper(new tools.jackson.databind.ObjectMapper()),
                "", "", "https://example.invalid");
        assertThat(off.aiEnabled()).isFalse();

        Set<String> answered = new java.util.HashSet<>();
        var got = off.classify(List.of("넷플릭스", "어떤가게"), Set.of(),
                new java.util.TreeMap<>(), answered);

        assertThat(got).isEmpty();
        assertThat(answered).as("묻지도 않았는데 '물어봤다'가 담기면 그 가맹점이 종결된다").isEmpty();
    }

    /** 미분류 결제 한 건('넷플릭스')만 보이는 질의 서비스. */
    private static MerchantAskService askServiceOverNetflix(MerchantCategoryService dictionary) {
        var row = new com.finntech.domain.UserPayment("24:real-1", 24L, "card", 1L,
                java.time.LocalDateTime.of(2026, 8, 1, 12, 0), "5814",
                com.finntech.engine.IndustryCategoryMapper.UNCLASSIFIED, 13_500,
                "넷플릭스", 0, "1234567890");
        var payments = mock(UserPaymentRepository.class);
        when(payments.findByUserIdAndCategory2OrderByPaymentDateDesc(anyLong(), anyString()))
                .thenReturn(List.of(row));
        var classifier = mock(MerchantClassifierService.class);
        when(classifier.worthAsking(anyString(), any())).thenReturn(true);

        var self = new java.util.concurrent.atomic.AtomicReference<MerchantAskService>();
        var service = new MerchantAskService(payments, mock(ConsumptionRepository.class),
                mock(CategoryRepository.class), dictionary, classifier,
                mock(TempClassifierService.class), java.time.Clock.systemDefaultZone(),
                TestServices.selfOf(self));
        self.set(service);
        return service;
    }

    /**
     * 분류 질의의 진입로는 <b>셋</b>이다 — 5분 배치, 화면의 {@code /sync}, 그리고 미분류 화면을
     * 여는 순간({@code ON_DEMAND_MIN}). 자물쇠를 동기화에만 두면 세 번째가 그대로 샌다.
     */
    @Test
    @DisplayName("이미 묻고 있으면 모델을 다시 부르지 않되, 아는 것은 돌려준다")
    void overlappingAsksSkipTheModelButStillAnswer() throws Exception {
        var row = new com.finntech.domain.UserPayment("24:real-1", 24L, "card", 1L,
                java.time.LocalDateTime.of(2026, 8, 1, 12, 0), "5814",
                com.finntech.engine.IndustryCategoryMapper.UNCLASSIFIED, 5_000,
                "어떤가게", 0, "1234567890");
        var payments = mock(UserPaymentRepository.class);
        when(payments.findByUserIdAndCategory2OrderByPaymentDateDesc(anyLong(), anyString()))
                .thenReturn(List.of(row));

        var classifier = mock(MerchantClassifierService.class);
        when(classifier.worthAsking(anyString(), any())).thenReturn(true);
        var dictionary = mock(MerchantCategoryService.class);
        when(dictionary.guess(any(), anyString())).thenReturn(Optional.empty());

        var temporary = mock(TempClassifierService.class);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        when(temporary.classify(anyList())).thenAnswer(inv -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return java.util.Map.<String, TempClassifierService.Guess>of();
        });

        var self = new java.util.concurrent.atomic.AtomicReference<MerchantAskService>();
        var service = new MerchantAskService(payments, mock(ConsumptionRepository.class),
                mock(CategoryRepository.class), dictionary, classifier, temporary,
                java.time.Clock.systemDefaultZone(), TestServices.selfOf(self));
        self.set(service);

        Thread first = new Thread(() -> service.ask(24L, 1));
        first.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        MerchantAskService.Asked second = service.ask(24L, 1);
        assertThat(second).as("빈손으로 돌아가지 않는다 — 화면이 그릴 것은 준다").isNotNull();
        assertThat(second.rows()).hasSize(1);
        verify(temporary, times(1)).classify(anyList());
        verify(classifier, never()).classify(anyList(), any(), any());

        release.countDown();
        first.join(5_000);
    }

    /**
     * 진입로가 둘이다 — 5분 배치와 화면의 {@code POST /api/mydata/sync}. 겹치면 같은 가맹점을
     * 두 모델에 두 번 묻는다. 유료 통로에서는 그게 곧 돈이다.
     */
    @Test
    @DisplayName("같은 사용자를 두 번 동시에 동기화하지 않는다")
    void concurrentSyncForSameUserIsSkipped() throws Exception {
        MyDataClient client = mock(MyDataClient.class);
        AppUserRepository users = mock(AppUserRepository.class);
        AppUser user = mock(AppUser.class);
        when(user.getCi()).thenReturn("ci");
        when(user.isConsentGiven()).thenReturn(true);
        when(users.findById(1L)).thenReturn(Optional.of(user));

        when(user.isRealPerson()).thenReturn(true);          // 후속 단계까지 실제로 가야 한다

        var links = mock(UserCardCompanyRepository.class);
        var pulls = new AtomicInteger();
        when(links.findByUserIdOrderByCompanyIdAsc(1L)).thenAnswer(inv -> {
            pulls.incrementAndGet();                          // 결제 당기기 — 자물쇠 밖이다
            return List.<com.finntech.domain.UserCardCompany>of();
        });

        var payments = mock(UserPaymentRepository.class);
        var followUps = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        when(payments.countByUserIdAndCategory2(anyLong(), anyString())).thenAnswer(inv -> {
            followUps.incrementAndGet();                      // 후속 단계 — 자물쇠 안이다
            started.countDown();
            release.await(5, TimeUnit.SECONDS);               // 첫 회차를 붙잡아 둔다
            return 0L;
        });
        when(payments.findByUserIdOrderByPaymentDateDesc(anyLong())).thenReturn(List.of());

        MyDataLinkService service = TestServices.linkService(client, users,
                mock(UserCardRepository.class), payments, mock(ConsumptionRepository.class),
                mock(CategoryRepository.class),
                new com.finntech.engine.IndustryCategoryMapper(new tools.jackson.databind.ObjectMapper()),
                mock(MerchantCategoryService.class), mock(BusinessNumberKindService.class),
                mock(IndustryLookupService.class), mock(MerchantAskService.class),
                mock(MerchantBrandService.class), links, mock(UserBankRepository.class),
                mock(ReportRepository.class), java.time.Clock.systemDefaultZone(), "");

        Thread first = new Thread(() -> service.renew(1L));
        first.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).as("첫 회차가 시작돼야 한다").isTrue();

        service.renew(1L);

        // **자물쇠가 막는 것은 모델이지 결제 당기기가 아니다.** 예전에는 자물쇠를 못 잡으면
        // 제공자를 부르지도 않고 0 을 돌려줬고, 화면은 그 0 을 "이미 최신 상태예요"로 읽었다 —
        // 방금 한 결제를 안 보여 주면서. 당기는 것은 멱등이고 값이 안 든다(2026-08-07 재감사).
        assertThat(pulls.get()).as("겹쳐도 결제는 당긴다 — 빈손으로 돌려보내지 않는다").isEqualTo(2);
        assertThat(followUps.get()).as("모델·조회·브랜드는 한 번만 — 두 번 물으면 그게 곧 돈이다")
                .isEqualTo(1);

        release.countDown();
        first.join(5_000);

        // **끝나면 자물쇠를 놓는다** — 안 놓으면 그 사용자는 후속 단계가 영영 안 돈다.
        service.renew(1L);
        assertThat(followUps.get()).as("끝난 뒤에는 다시 돌 수 있어야 한다").isEqualTo(2);
    }

    /** 연동에도 같은 계약이 걸린다 — 카드사 목록을 받아오는 것은 트랜잭션 안이라도 짧다. */
    @Test
    @DisplayName("연동은 적재가 끝난 뒤에 조회·질의·브랜드를 돌린다")
    void followUpsRunAfterTheLoadingTransaction() {
        MyDataClient client = mock(MyDataClient.class);
        AppUserRepository users = mock(AppUserRepository.class);
        AppUser user = mock(AppUser.class);
        when(user.getCi()).thenReturn("ci");
        when(user.isConsentGiven()).thenReturn(true);
        // 실사용자라야 후속 단계까지 실제로 간다 — 더미면 `runFollowUps` 가 첫 줄에서 물러나
        // 이 시험이 "브랜드를 안 불렀다"를 엉뚱한 이유로 통과한다(더미 격리는 별도 시험 소관).
        when(user.isRealPerson()).thenReturn(true);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(client.findCards(anyLong(), anyString())).thenReturn(List.<CardView>of());
        when(client.findAccount(anyString())).thenReturn(null);

        var payments = mock(UserPaymentRepository.class);
        when(payments.countByUserIdAndCategory2(anyLong(), anyString())).thenReturn(0L);
        // **결제가 있어야 시험이 무엇인가를 검사한다.** 빈 목록으로 두면 `labelBrands` 가
        // `rows.isEmpty()` 에서 끝나 `label` 에 닿을 수가 없고, 그러면 아래 단정은 코드가
        // 무엇을 하든 참이다 — 순서도 트랜잭션 경계도 안 본다(2026-08-07 재감사에서 드러났다).
        when(payments.findByUserIdOrderByPaymentDateDesc(anyLong())).thenReturn(List.of(
                new com.finntech.domain.UserPayment("1:real-1", 1L, "card", 1L,
                        java.time.LocalDateTime.of(2026, 8, 1, 12, 0), "5814",
                        com.finntech.engine.IndustryCategoryMapper.UNCLASSIFIED, 5_000,
                        "어떤가게", 0, "1234567890")));
        var brands = mock(MerchantBrandService.class);

        MyDataLinkService service = TestServices.linkService(client, users,
                mock(UserCardRepository.class), payments, mock(ConsumptionRepository.class),
                mock(CategoryRepository.class),
                new com.finntech.engine.IndustryCategoryMapper(new tools.jackson.databind.ObjectMapper()),
                mock(MerchantCategoryService.class), mock(BusinessNumberKindService.class),
                mock(IndustryLookupService.class), mock(MerchantAskService.class), brands,
                mock(UserCardCompanyRepository.class), mock(UserBankRepository.class),
                mock(ReportRepository.class), java.time.Clock.systemDefaultZone(), "");

        service.linkCardCompanies(1L, List.of(9007L), List.of());

        // **적재가 끝난 뒤에 후속 단계가 실제로 돈다.** 예전에는 이 호출이 적재 트랜잭션
        // 안에 있었다. 여기서 label 이 불렸다는 것은 `runFollowUps` 까지 갔다는 뜻이고,
        // 그 자리는 `loadCardCompanies` 가 커밋한 다음이다.
        verify(brands, times(1)).enqueuePending(any(), any());
    }
}
