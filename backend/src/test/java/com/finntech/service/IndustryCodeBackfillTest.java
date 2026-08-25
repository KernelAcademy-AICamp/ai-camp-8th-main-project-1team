package com.finntech.service;

import com.finntech.domain.AppUser;
import com.finntech.domain.MerchantCategory;
import com.finntech.domain.UserPayment;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.freechannel.FreeChannelQueue;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.MerchantBrandRepository;
import com.finntech.repository.MerchantCategoryRepository;
import com.finntech.repository.ReportRepository;
import com.finntech.repository.UserPaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>업종코드를 소분류에서 되찾되, 확실한 것과 짐작한 것을 갈라 적는가.</b>
 *
 * <p>카드 혜택축은 중분류가 아니라 <b>업종코드</b>로 정해지는데, 실 명세서에는 그 코드가 없어
 * 자리채움값이 들어간다 — 실사용자 결제 2,135건 중 <b>1,653건(77%)</b>이 그랬다(2026-08-25).
 *
 * <p>그런데 <b>확정은 40%가 한계</b>다. 소분류 170개 중 업종 하나를 가리키는 것은 62개뿐이라
 * ({@code 한식} 은 업종이 넷) 나머지는 짐작할 수밖에 없다. 한 칸에 섞으면 읽는 쪽이 짐작을
 * 사실로 쓴다 — 그래서 칸을 가르고, 여기서 그 경계를 잠근다.
 */
class IndustryCodeBackfillTest {

    private final IndustryCategoryMapper industries = new IndustryCategoryMapper(new ObjectMapper());
    private final List<UserPayment> rows = new ArrayList<>();
    private final List<MerchantCategory> dict = new ArrayList<>();

    /** 자리채움 업종코드를 든 실사용자 결제 하나. */
    private UserPayment payment(String merchant, String category2) {
        UserPayment p = new UserPayment("p-" + rows.size(), 7L, "card", 1L,
                LocalDateTime.of(2026, 8, 1, 12, 0), UserPayment.PLACEHOLDER_INDUSTRY,
                category2, 10_000, merchant, "0000000001");
        rows.add(p);
        return p;
    }

    private void known(String merchant, String sub) {
        MerchantCategory row = new MerchantCategory("0000000001", merchant, "식비",
                MerchantCategory.Source.USER_CSV, null, null);
        row.applySub(sub);
        dict.add(row);
    }

    private IndustryCodeBackfill backfill() {
        AppUserRepository users = mock(AppUserRepository.class);
        AppUser real = mock(AppUser.class);
        when(real.isRealPerson()).thenReturn(true);
        when(real.getId()).thenReturn(7L);
        when(users.findAll()).thenReturn(List.of(real));
        UserPaymentRepository payments = mock(UserPaymentRepository.class);
        when(payments.findByUserIdOrderByPaymentDateDesc(anyLong())).thenReturn(rows);
        MerchantCategoryRepository repository = mock(MerchantCategoryRepository.class);
        when(repository.findAll()).thenReturn(dict);
        MerchantBrandService brands = new MerchantBrandService(
                mock(MerchantBrandRepository.class), mock(MerchantCategoryRepository.class),
                mock(TempClassifierService.class), mock(UserPaymentRepository.class),
                new ObjectMapper(), null, mock(FreeChannelQueue.class));
        return new IndustryCodeBackfill(users, payments, repository, industries, brands,
                mock(ReportRepository.class));
    }

    /** {@code 치킨} 은 업종이 {@code 치킨 전문점} 하나뿐이고 그 코드도 하나다 — 확정이다. */
    @Test
    @DisplayName("소분류가 업종 하나를 가리키면 확정 칸에 적는다")
    void 확정이면_확정_칸에() {
        UserPayment p = payment("깐부치킨 양재역점", "식비");
        known("깐부치킨 양재역점", "치킨");

        var result = backfill().run(false);

        assertThat(result.confirmed()).isEqualTo(1);
        assertThat(result.guessed()).isZero();
        assertThat(p.getKsicCode()).isEqualTo("552107");
        assertThat(p.getIndustryCodeGuess()).as("확정을 얻었으면 추정은 안 적는다").isNull();
        assertThat(result.axes()).containsKey("외식");
    }

    /** {@code 한식} 은 업종이 넷이라 어느 코드인지 모른다 — 짐작이므로 추정 칸이다. */
    @Test
    @DisplayName("소분류가 코드를 여럿 가리키면 추정 칸에 적는다")
    void 갈리면_추정_칸에() {
        UserPayment p = payment("어느 국밥집", "식비");
        known("어느 국밥집", "한식");

        var result = backfill().run(false);

        assertThat(result.guessed()).isEqualTo(1);
        assertThat(result.confirmed()).isZero();
        assertThat(p.getKsicCode()).as("확정 칸은 그대로 자리채움값이어야 한다")
                .isEqualTo(UserPayment.PLACEHOLDER_INDUSTRY);
        assertThat(p.getIndustryCodeGuess()).isNotBlank();
        assertThat(industries.midOf(p.getIndustryCodeGuess()))
                .as("중분류로 좁혔으므로 그 코드는 식비여야 한다").isEqualTo("식비");
    }

    @Test
    @DisplayName("맛보기는 세기만 하고 아무것도 안 고친다")
    void 맛보기는_안_고친다() {
        UserPayment p = payment("깐부치킨 양재역점", "식비");
        known("깐부치킨 양재역점", "치킨");

        var result = backfill().run(true);

        assertThat(result.dryRun()).isTrue();
        assertThat(result.confirmed()).isEqualTo(1);
        assertThat(p.getKsicCode()).isEqualTo(UserPayment.PLACEHOLDER_INDUSTRY);
        assertThat(p.getIndustryCodeGuess()).isNull();
    }

    /** 소분류를 못 얻으면 <b>아무것도 적지 않는다.</b> 모르는 것을 지어내지 않는다. */
    @Test
    @DisplayName("소분류가 없으면 비워 둔다")
    void 모르면_비운다() {
        UserPayment p = payment("어느 동네 가게", "카테고리없음");

        var result = backfill().run(false);

        assertThat(result.unknown()).isEqualTo(1);
        assertThat(p.getKsicCode()).isEqualTo(UserPayment.PLACEHOLDER_INDUSTRY);
        assertThat(p.getIndustryCodeGuess()).isNull();
    }

    /** 사전이 모르는 상호는 <b>표기표가 받는다</b> — 브랜드가 붙으면 소분류가 따라온다. */
    @Test
    @DisplayName("사전에 없어도 표기표가 아는 브랜드면 채운다")
    void 표기표가_받는다() {
        UserPayment p = payment("스타벅스 강남점", "카페/간식");

        var result = backfill().run(false);

        assertThat(result.confirmed()).isEqualTo(1);
        assertThat(p.getKsicCode()).as("커피 전문점").isEqualTo("552303");
        assertThat(result.axes()).containsKey("카페/디저트");
    }

    /**
     * <b>한 사람만 훑는 문이 있어야 한다.</b> 없으면 추정 칸은 관리자가 손으로 누를 때만
     * 채워지고, 새 사용자는 명세서를 넣어도 빈 칸을 본다 — 연동 끝과 야간 배치가 이것을 부른다.
     */
    @Test
    @DisplayName("한 사람만 훑을 수 있다")
    void 한_사람만_훑는다() {
        payment("깐부치킨 양재역점", "식비");
        known("깐부치킨 양재역점", "치킨");

        var result = backfill().runFor(7L, false);

        assertThat(result.confirmed()).isEqualTo(1);
        assertThat(rows.get(0).getKsicCode()).isEqualTo("552107");
    }

    /** 확정이 이미 들어와 있는 결제는 건드리지 않는다. */
    @Test
    @DisplayName("진짜 코드가 있는 결제는 안 본다")
    void 진짜_코드는_안_건드린다() {
        UserPayment p = new UserPayment("p-real", 7L, "card", 1L,
                LocalDateTime.of(2026, 8, 1, 12, 0), "552303", "카페/간식",
                5_000, "스타벅스 강남점", "0000000001");
        rows.add(p);

        var result = backfill().run(false);

        assertThat(result.scanned()).isZero();
        assertThat(p.getKsicCode()).isEqualTo("552303");
    }
}
