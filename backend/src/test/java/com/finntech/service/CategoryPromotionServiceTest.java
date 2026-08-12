package com.finntech.service;

import com.finntech.domain.Category;
import com.finntech.domain.UserPayment;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.CategoryRepository;
import com.finntech.repository.ConsumptionRepository;
import com.finntech.repository.ReportRepository;
import com.finntech.repository.UserPaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>온보딩이 1,200원에서 막히던 자리.</b>
 *
 * <p>실 명세서의 PG·상품권 결제는 사업자번호로 등록 업종을 물어도 답이 안 나온다 — 한 번호에
 * 토스페이·카카오페이·기프티스타가 함께 붙기 때문이다. 그래서 답이 <b>있는데도</b> 추정층에
 * 머물고, 계산이 읽는 {@code Consumption} 에는 확정만 들어가 화면의 110,680원이 서버에서는
 * 1,200원이 됐다. 강도를 최소로 내려도 그보다 작아질 수 없어 <b>챌린지를 만드는 것이
 * 불가능</b>했다(2026-08-12 운영 userId=30).
 */
class CategoryPromotionServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 12, 12, 0);

    private UserPaymentRepository payments;
    private ConsumptionRepository consumptions;
    private CategoryRepository categories;
    private ReportRepository reports;
    private CategoryPromotionService service;

    @BeforeEach
    void setUp() {
        payments = mock(UserPaymentRepository.class);
        consumptions = mock(ConsumptionRepository.class);
        categories = mock(CategoryRepository.class);
        reports = mock(ReportRepository.class);
        when(categories.findByCode(anyString()))
                .thenAnswer(call -> Optional.of(new Category(call.getArgument(0), call.getArgument(0))));
        when(consumptions.findBySourcePaymentId(anyString())).thenReturn(List.of());
        service = new CategoryPromotionService(payments, consumptions, categories,
                new IndustryCategoryMapper(new tools.jackson.databind.ObjectMapper()), reports);
    }

    /** 미분류인데 모델이 답을 준 결제 — PG·상품권이 정확히 이 모양이다. */
    private static UserPayment estimated(String id, String guess, LocalDateTime at) {
        UserPayment payment = new UserPayment(id, 1L, "card", 1L, at, "5814",
                IndustryCategoryMapper.UNCLASSIFIED, 10_000, "쿠팡-쿠팡", "1168119948");
        payment.suggestCategory2(guess, "LLM");
        return payment;
    }

    private void given(UserPayment... rows) {
        when(payments.findByUserIdOrderByPaymentDateDesc(anyLong())).thenReturn(List.of(rows));
    }

    @Test
    @DisplayName("추정이 원장에 반영된다 — 여기가 온보딩을 뚫는다")
    void appliesTheEstimate() {
        UserPayment row = estimated("1:a", "쇼핑", NOW.minusDays(3));
        given(row);

        assertThat(service.applyEstimates(1L)).isEqualTo(1);
        assertThat(row.getCategory2()).as("계산이 세는 값이 된다").isEqualTo("쇼핑");
    }

    @Test
    @DisplayName("출처는 LLM_LOCAL — 사람·사전·등록업종과 구별된다")
    void marksWhereItCameFrom() {
        UserPayment row = estimated("1:a", "쇼핑", NOW.minusDays(3));
        given(row);

        service.applyEstimates(1L);

        // 같은 이름으로 적으면 나중에 어느 값이 모델에서 왔는지 가려낼 방법이 없고,
        // "사실이 추정을 이긴다"를 코드로 지킬 수도 없다.
        assertThat(row.getCategory2Source()).isEqualTo("LLM_LOCAL");
    }

    @Test
    @DisplayName("카테고리를 가리지 않는다 — 가리면 화면마다 다른 숫자가 나온다")
    void appliesEveryCategory() {
        UserPayment shopping = estimated("1:a", "쇼핑", NOW.minusDays(3));
        UserPayment food = estimated("1:b", "식비", NOW.minusDays(3));
        given(shopping, food);

        // 챌린지는 시작되는데 리포트·점수는 옛 숫자인 상태를 만들지 않는다.
        assertThat(service.applyEstimates(1L)).isEqualTo(2);
        assertThat(food.getCategory2()).isEqualTo("식비");
    }

    @Test
    @DisplayName("기간도 가리지 않는다 — 리포트는 창 밖도 읽는다")
    void appliesOutsideAnyWindow() {
        UserPayment old = estimated("1:a", "쇼핑", NOW.minusDays(200));
        given(old);

        assertThat(service.applyEstimates(1L)).isEqualTo(1);
        assertThat(old.getCategory2()).isEqualTo("쇼핑");
    }

    @Test
    @DisplayName("이미 답이 있는 결제는 덮지 않는다 — 사실이 추정을 이긴다")
    void neverOverwritesAnExistingAnswer() {
        UserPayment byUser = estimated("1:a", "쇼핑", NOW.minusDays(3));
        byUser.confirmCategory2("식비", "USER");
        UserPayment byRegistry = estimated("1:b", "쇼핑", NOW.minusDays(3));
        byRegistry.confirmCategory2("교통/자동차", "DICT");
        given(byUser, byRegistry);

        assertThat(service.applyEstimates(1L)).isZero();
        assertThat(byUser.getCategory2()).isEqualTo("식비");
        assertThat(byRegistry.getCategory2()).isEqualTo("교통/자동차");
    }

    @Test
    @DisplayName("추정이 없는 결제는 그대로 둔다")
    void leavesPaymentsWithoutAnEstimate() {
        UserPayment none = new UserPayment("1:a", 1L, "card", 1L, NOW.minusDays(3), "5814",
                IndustryCategoryMapper.UNCLASSIFIED, 10_000, "어떤가게", "1234567890");
        given(none);

        assertThat(service.applyEstimates(1L)).isZero();
        assertThat(none.getCategory2()).isEqualTo(IndustryCategoryMapper.UNCLASSIFIED);
    }

    @Test
    @DisplayName("모르는 중분류는 거절한다 — 어느 화면에도 안 잡히는 카테고리를 원장에 만들지 않는다")
    void rejectsCategoriesOutsideTheAxis() {
        UserPayment row = estimated("1:a", "그런거없음", NOW.minusDays(3));
        given(row);

        assertThat(service.applyEstimates(1L)).isZero();
        assertThat(row.getCategory2()).isEqualTo(IndustryCategoryMapper.UNCLASSIFIED);
    }

    @Test
    @DisplayName("반영한 것이 없으면 리포트 캐시를 건드리지 않는다")
    void doesNothingWhenThereIsNothingToApply() {
        given();

        assertThat(service.applyEstimates(1L)).isZero();
        verify(reports, never()).deleteByUserId(anyLong());
    }

    @Test
    @DisplayName("사용자가 없으면 아무것도 하지 않는다")
    void nullUserIsANoop() {
        assertThat(service.applyEstimates(null)).isZero();
    }

    /**
     * <b>전역 사전은 이 통로로 바뀌지 않는다 — 이것이 설계의 선이다.</b>
     *
     * <p>사전은 전역 자산이라 한 사람의 오분류가 모두에게 간다. 한 사람의 원장 안에서는 그
     * 위험이 없다 — 틀리면 그 사람만 틀리고, 그 사람이 화면에서 고치면 끝난다.
     *
     * <p>협력자 목록에 사전이 <b>아예 없다</b>는 것이 그 보장이다. 편의로 주입하는 순간
     * 이 시험이 막는다 — 들고 있으면 언젠가 부르게 된다.
     */
    @Test
    @DisplayName("사전을 만질 방법이 없다 — 협력자에 사전이 없다")
    void cannotReachTheDictionary() {
        assertThat(java.util.Arrays.stream(CategoryPromotionService.class.getDeclaredFields())
                .map(field -> field.getType().getSimpleName()))
                .as("사전 서비스를 들고 있으면 언젠가 부르게 된다")
                .doesNotContain("MerchantCategoryService", "MerchantCategoryRepository");
    }
}
