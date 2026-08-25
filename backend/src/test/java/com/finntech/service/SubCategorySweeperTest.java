package com.finntech.service;

import com.finntech.domain.MerchantCategory;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.MerchantCategoryRepository;
import com.finntech.repository.ReportRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>표를 고쳐도 이미 적힌 사전은 스스로 안 고쳐진다.</b>
 *
 * <p>사전에 확정이 적히면 그 가맹점은 다시 묻지 않는다. 그래서 소분류 표를 넣고 모호한 업종을
 * 뺐어도, 그 전에 쇼핑으로 굳은 행은 그대로 남는다 — 훑는 문이 없으면 바뀐 것이 아무것도 없다.
 *
 * <p>여기서 잠그는 것은 넷이다: ① 맛보기는 정말 아무것도 안 고치는가 ② 어긋남을 소분류
 * 기준으로 되돌리는가 ③ 근거를 잃은 행을 되돌리는가 ④ <b>사람의 손을 안 덮는가</b>.
 */
class SubCategorySweeperTest {

    private final IndustryCategoryMapper industries = new IndustryCategoryMapper(new ObjectMapper());
    private final List<MerchantCategory> table = new ArrayList<>();

    private final MyDataLinkService link = mock(MyDataLinkService.class);

    private SubCategorySweeper sweeper() {
        MerchantCategoryRepository repository = mock(MerchantCategoryRepository.class);
        when(repository.findAll()).thenReturn(table);
        AppUserRepository users = mock(AppUserRepository.class);
        when(users.findAll()).thenReturn(java.util.List.of());
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<MyDataLinkService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(link);
        return new SubCategorySweeper(repository, industries, users,
                mock(ReportRepository.class), provider);
    }

    /** 브랜드가 붙은 행 하나. 브랜드는 사전에 적혀 있는 값이라 여기서 직접 심는다. */
    private MerchantCategory row(String name, String brand, String category2,
                                 MerchantCategory.Source source) {
        MerchantCategory r = new MerchantCategory("1234567890", name, category2, source, null, null);
        r.adoptBrand(brand);
        table.add(r);
        return r;
    }

    @Test
    @DisplayName("맛보기는 세기만 하고 아무것도 안 고친다")
    void 맛보기는_안_고친다() {
        MerchantCategory 배민 = row("우아한형제들", "배달의민족", "쇼핑", MerchantCategory.Source.LLM_GUESS);

        var result = sweeper().sweep(true);

        assertThat(result.dryRun()).isTrue();
        assertThat(result.disagreed()).as("어긋남은 세어야 한다").isEqualTo(1);
        assertThat(배민.getCategory2()).as("맛보기가 값을 고쳤다").isEqualTo("쇼핑");
        assertThat(배민.getCategory3()).as("맛보기가 소분류를 찍었다").isNull();
    }

    /**
     * 배달의민족은 업종을 전자상거래로 등록해 쇼핑으로 굳어 있었다. 브랜드가 소분류
     * {@code 배달} 을 주고 그 소분류는 식비에만 속하므로, <b>적힌 쇼핑이 틀린 것</b>이다.
     */
    @Test
    @DisplayName("중분류가 소분류와 어긋나면 되돌린다")
    void 어긋나면_되돌린다() {
        MerchantCategory 배민 = row("우아한형제들", "배달의민족", "쇼핑", MerchantCategory.Source.LLM_GUESS);

        var result = sweeper().sweep(false);

        assertThat(result.disagreed()).isEqualTo(1);
        assertThat(배민.getCategory3()).as("소분류를 찍어야 한다").isEqualTo("배달");
        // **답을 아는 행은 되돌리지 않고 바로 고친다** — 소분류가 중분류를 결정하므로
        // 다시 물을 이유가 없다.
        assertThat(배민.getCategory2()).isEqualTo("식비");
        assertThat(배민.getSource()).isEqualTo(MerchantCategory.Source.USER_CSV.name());
        assertThat(result.samples()).anyMatch(s -> s.contains("배달"));
    }

    @Test
    @DisplayName("소분류와 맞는 행은 그대로 둔다")
    void 맞는_행은_안_건드린다() {
        MerchantCategory 카카오티 = row("카카오모빌리티", "카카오T", "교통/자동차", MerchantCategory.Source.LLM_GUESS);

        var result = sweeper().sweep(false);

        assertThat(result.disagreed()).isZero();
        assertThat(카카오티.getSource()).isEqualTo(MerchantCategory.Source.LLM_GUESS.name());
        assertThat(카카오티.getCategory3()).as("맞는 행에도 소분류는 찍는다").isEqualTo("택시");
    }

    /**
     * <b>사람이 손으로 확인한 것은 표보다 위다.</b> 훑기가 사람의 판단을 덮으면, 고쳐 놓은
     * 분류가 배치 한 번에 사라지는데 아무 예외도 안 난다 — 조용히 틀리는 종류다.
     */
    @Test
    @DisplayName("사람이 확인한 행은 되돌리지 않는다")
    void 사람의_손은_안_덮는다() {
        MerchantCategory 사람이_고침 = row("우아한형제들", "배달의민족", "쇼핑",
                MerchantCategory.Source.USER_CONFIRMED);
        MerchantCategory 씨앗 = row("쿠팡(주)", "쿠팡", "생활", MerchantCategory.Source.USER_CSV);

        sweeper().sweep(false);

        assertThat(사람이_고침.getCategory2()).as("사람이 고친 것을 표가 덮었다").isEqualTo("쇼핑");
        assertThat(사람이_고침.getSource()).isEqualTo(MerchantCategory.Source.USER_CONFIRMED.name());
        assertThat(씨앗.getCategory2()).isEqualTo("생활");
    }

    /**
     * <b>사전만 고치면 화면은 안 바뀐다.</b> 원장의 분류는 사전이 아니라 {@code UserPayment}
     * 에서 오고, {@code LedgerDirtyListener} 는 {@code MerchantCategory} 에 안 달려 있다.
     * 그 사이를 잇는 것이 {@code applyResolved} 뿐이라 훑기가 그것을 부른다.
     */
    @Test
    @DisplayName("고친 분류를 결제까지 밀어 넣는다")
    void 결제까지_민다() {
        row("우아한형제들", "배달의민족", "쇼핑", MerchantCategory.Source.LLM_GUESS);
        com.finntech.domain.AppUser real = mock(com.finntech.domain.AppUser.class);
        when(real.isRealPerson()).thenReturn(true);
        when(real.getId()).thenReturn(7L);

        MerchantCategoryRepository repository = mock(MerchantCategoryRepository.class);
        when(repository.findAll()).thenReturn(table);
        AppUserRepository users = mock(AppUserRepository.class);
        when(users.findAll()).thenReturn(java.util.List.of(real));
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<MyDataLinkService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(link);
        when(link.applyResolved(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.eq("DICT"))).thenReturn(3);

        var result = new SubCategorySweeper(repository, industries, users,
                mock(ReportRepository.class), provider).sweep(false);

        assertThat(result.payments()).as("결제를 안 고치면 화면은 그대로다").isEqualTo(3);
    }

    @Test
    @DisplayName("맛보기는 결제를 안 건드린다")
    void 맛보기는_결제도_안_건드린다() {
        row("우아한형제들", "배달의민족", "쇼핑", MerchantCategory.Source.LLM_GUESS);
        assertThat(sweeper().sweep(true).payments()).isZero();
        org.mockito.Mockito.verifyNoInteractions(link);
    }

    @Test
    @DisplayName("어긋남을 중분류 쌍으로 세어 규모를 보여 준다")
    void 규모를_보여_준다() {
        row("우아한형제들", "배달의민족", "쇼핑", MerchantCategory.Source.LLM_GUESS);
        row("쿠팡이츠서비스", "쿠팡이츠", "쇼핑", MerchantCategory.Source.LLM_GUESS);
        row("카카오모빌리티", "카카오T", "교통/자동차", MerchantCategory.Source.LLM_GUESS);

        assertThat(sweeper().byMid()).containsEntry("쇼핑 → 식비", 2).hasSize(1);
    }

    /** 회사명에는 소분류가 안 붙으므로 훑기가 아무 말도 하지 않는다 — 찍지 않는 것이 답이다. */
    @Test
    @DisplayName("회사명 브랜드는 어긋남으로 세지 않는다")
    void 회사명은_판정하지_않는다() {
        MerchantCategory 카카오 = row("카카오", "카카오", "취미/여가", MerchantCategory.Source.LLM_GUESS);

        var result = sweeper().sweep(false);

        assertThat(result.disagreed()).isZero();
        assertThat(카카오.getCategory3()).isNull();
        assertThat(카카오.getSource()).isEqualTo(MerchantCategory.Source.LLM_GUESS.name());
    }
}
