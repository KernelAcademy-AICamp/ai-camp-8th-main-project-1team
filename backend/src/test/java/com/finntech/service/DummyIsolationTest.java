package com.finntech.service;

import com.finntech.domain.AppUser;
import com.finntech.domain.UserPayment;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * <b>더미는 어느 바깥 호출에도, 어느 전역 표에도 들어가지 않는다.</b>
 *
 * <p>여기 있는 것은 2026-08-07 재감사에서 나온 <b>샌 자리</b>들이다. 게이트를 결제 단위로
 * 여덟 곳에 흩어 두었더니 두 곳이 빠져 있었고, 빠진 곳이 하필 <b>전역 판정 표</b>였다 —
 * {@code business_number_kind} 22행 중 20행이 더미 결제만으로 만들어져 있었다.
 *
 * <p>그래서 게이트를 사용자 단위로 하나 더, <b>가장 바깥에</b> 뒀다. 뒤에 단계가 하나 늘어도
 * 자동으로 막히는 자리다. 안쪽 게이트는 심층 방어로 남긴다.
 */
class DummyIsolationTest {

    private static UserPayment payment(String providerId, String merchantName, String biz) {
        return new UserPayment(UserPayment.rowId(24L, providerId), 24L, "card", 1L,
                LocalDateTime.of(2026, 8, 1, 12, 0), "5814",
                IndustryCategoryMapper.UNCLASSIFIED, 9_000, merchantName, 0, biz);
    }

    /** 협력자를 한 번에 세운다 — 무엇을 검사하는지가 시험마다 다르므로 대역만 돌려준다. */
    private record Rig(MyDataLinkService service, MerchantAskService ask,
                       MerchantBrandService brands, IndustryLookupService lookup,
                       BusinessNumberKindService kinds, UserPaymentRepository payments,
                       AppUserRepository users) {}

    private static Rig rig(boolean realPerson, List<UserPayment> rows) {
        MyDataClient client = mock(MyDataClient.class);
        AppUserRepository users = mock(AppUserRepository.class);
        AppUser user = mock(AppUser.class);
        when(user.getCi()).thenReturn("ci");
        when(user.isConsentGiven()).thenReturn(true);
        when(user.isRealPerson()).thenReturn(realPerson);
        when(users.findById(24L)).thenReturn(Optional.of(user));

        var payments = mock(UserPaymentRepository.class);
        when(payments.findByUserIdOrderByPaymentDateDesc(24L)).thenReturn(rows);
        // 미분류가 있다고 해 둔다 — 게이트가 없으면 조회·질의가 반드시 돌아야 하는 상태다.
        when(payments.countByUserIdAndCategory2(anyLong(), anyString())).thenReturn(3L);
        when(payments.findByUserIdAndCategory2OrderByPaymentDateDesc(anyLong(), anyString()))
                .thenReturn(rows);

        var links = mock(UserCardCompanyRepository.class);
        when(links.findByUserIdOrderByCompanyIdAsc(24L)).thenReturn(List.of());

        var ask = mock(MerchantAskService.class);
        var brands = mock(MerchantBrandService.class);
        var lookup = mock(IndustryLookupService.class);
        when(lookup.usable()).thenReturn(true);
        var kinds = mock(BusinessNumberKindService.class);

        MyDataLinkService service = TestServices.linkService(client, users,
                mock(UserCardRepository.class), payments, mock(ConsumptionRepository.class),
                mock(CategoryRepository.class),
                new IndustryCategoryMapper(new tools.jackson.databind.ObjectMapper()),
                mock(MerchantCategoryService.class), kinds, lookup, ask, brands, links,
                mock(UserBankRepository.class), mock(ReportRepository.class),
                Clock.systemDefaultZone(), "");
        return new Rig(service, ask, brands, lookup, kinds, payments, users);
    }

    /**
     * 안쪽 게이트만 있으면 <b>안 쓸 결제를 전부 읽고 나서 버린다.</b>
     *
     * <p>연동된 13명 중 12명이 더미이고 전원 미분류 결제가 있어 관문을 통과했다. 회차마다
     * `lookupUnknownIndustries` 와 `labelBrands` 가 각각 결제를 통째로 읽어 <b>3만 8천 행을
     * 읽고 439건을 처리</b>하고 있었다(2026-08-07 실측).
     */
    @Test
    @DisplayName("더미 사용자는 조회·질의·브랜드에 아예 들어가지 않는다")
    void dummyUsersSkipEveryFollowUp() {
        Rig r = rig(false, List.of(payment("p1", "어떤가게", "1234567890")));

        r.service().renew(24L);

        verify(r.ask(), never()).ask(anyLong(), anyInt());
        verify(r.brands(), never()).enqueuePending(any(), any());
        verify(r.lookup(), never()).industryOfMerchant(anyString());
        // 결제를 읽지도 않는다 — 값싼 조기 종료가 요점이다.
        verify(r.payments(), never()).findByUserIdOrderByPaymentDateDesc(24L);
    }

    /** 게이트가 실사용자까지 막으면 그것이 더 나쁜 결함이다 — 조용히 아무 일도 안 하게 된다. */
    @Test
    @DisplayName("실사용자는 그대로 후속 단계를 탄다")
    void realUsersStillRunFollowUps() {
        Rig r = rig(true, List.of(payment("real-p1", "넷플릭스", "1234567890")));

        r.service().renew(24L);

        verify(r.ask(), times(1)).ask(eq(24L), anyInt());
        verify(r.brands(), times(1)).enqueuePending(any(), any());
    }

    /**
     * <b>전역 판정 표에 더미가 들어가면 생성기가 실사용자의 분류를 정한다.</b>
     *
     * <p>티머니({@code 1048183559})가 그랬다 — 생성기와 실제 사람이 같이 쓰는 실재 번호다.
     * 판정에 쓰인 상호는 더미의 것이었고, `SINGLE` 로 굳으면 완화가 열려 그 번호의 실사용자
     * 결제 전부가 형제 행의 분류를 물려받는다.
     */
    @Test
    @DisplayName("관측은 실제 사람의 결제만 센다 — 더미 상호는 전역 판정에 안 들어간다")
    void observationCountsRealPaymentsOnly() {
        Rig dummy = rig(true, List.of(
                payment("d1", "티머니", "1048183559"),
                payment("d2", "(주)티머니", "1048183559")));
        dummy.service().linkCardCompanies(24L, List.of(), List.of());
        verify(dummy.kinds(), never()).observe(anyString(), any(), any(), any());

        Rig real = rig(true, List.of(
                payment("real-t1", "카카오택시-서울33바2592", "1048183559"),
                payment("real-t2", "티머니 택시-서울32자4102", "1048183559")));
        real.service().linkCardCompanies(24L, List.of(), List.of());
        // 적재가 한 번, 후속 단계가 회차마다 한 번 더 관측한다 — 판정 표를 비운 뒤 재구축이
        // 새 결제를 기다리지 않게 하려는 것이다(V24). 몇 번인지가 아니라 **돌긴 하는가**가 계약이다.
        verify(real.kinds(), atLeastOnce()).observe(eq("1048183559"), any(), any(), any());
    }

    /**
     * <b>표시가 거짓으로 굳으면 실사용자가 조용히 전부 멈춘다.</b>
     *
     * <p>표시는 적재가 정하는데, 적재를 다시 안 돌리면 갱신될 일이 없다 — 결제는 이미 있는데
     * 칸만 나중에 생긴 DB(백필이 없는 개발 H2), 증분만 돌던 사용자. 증분은 이미 있는 결제를
     * 건너뛰므로 그 결제로는 켜 주지도 못한다. 그러면 조회·질의·브랜드가 <b>아무 오류 없이</b>
     * 안 돈다. 표시가 틀리는 두 방향 중 이쪽이 훨씬 나쁘다 — 그래서 관문이 "아니다"라고 할 때만
     * 원장으로 되짚어 스스로 고친다(2026-08-07 재감사).
     */
    @Test
    @DisplayName("표시가 원장과 어긋나면 관문이 스스로 바로잡는다")
    void theGateHealsAStaleFlag() {
        Rig r = rig(false, List.of(payment("real-p1", "넷플릭스", "1234567890")));
        // 칸은 false 인데 원장에는 실물 결제가 있다 — 어긋난 상태다.
        when(r.payments().existsRealPersonPaymentByUserId(24L)).thenReturn(true);

        r.service().renew(24L);

        verify(r.brands(), times(1)).enqueuePending(any(), any());
        verify(r.users(), atLeastOnce()).save(any(AppUser.class));   // 고쳐서 적어 둔다
    }

    /** 되짚기가 늘 도는 것은 아니다 — 정말 더미면 한 번 묻고 끝난다(값싼 조기 종료가 목적이다). */
    @Test
    @DisplayName("정말 더미면 되짚어도 여전히 더미다 — 결제는 읽지 않는다")
    void healingDoesNotOpenTheGateForRealDummies() {
        Rig r = rig(false, List.of(payment("p1", "어떤가게", "1234567890")));
        when(r.payments().existsRealPersonPaymentByUserId(24L)).thenReturn(false);

        r.service().renew(24L);

        verify(r.brands(), never()).enqueuePending(any(), any());
        verify(r.payments(), never()).findByUserIdOrderByPaymentDateDesc(24L);
    }
}
