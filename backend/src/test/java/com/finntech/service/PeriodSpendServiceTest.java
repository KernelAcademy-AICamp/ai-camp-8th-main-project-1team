package com.finntech.service;

import com.finntech.domain.AppUser;
import com.finntech.domain.Category;
import com.finntech.domain.Consumption;
import com.finntech.domain.Enums;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.CategoryRepository;
import com.finntech.repository.ConsumptionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>리포트는 챌린지 없이도 답해야 한다.</b>
 *
 * <p>일별 계열이 지킴이 주간 리포트에서만 나오던 탓에, 소비 내역에는 결제가 쌓여 있는데
 * 리포트만 비는 일이 있었다(사용자 보고 2026-08-20). 여기서 잠그는 것은 <b>챌린지를 한 번도
 * 만들지 않은 사람에게도 그 주·그 달의 숫자가 나온다</b>는 것이다.
 *
 * <p>더불어 재현성도 잠근다 — 빈 날을 0으로 채우는가(안 채우면 막대가 밀린다), 동점인
 * 카테고리의 순서가 정해져 있는가(원칙 3).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PeriodSpendServiceTest {

    @Autowired PeriodSpendService service;
    @Autowired AppUserRepository users;
    @Autowired CategoryRepository categories;
    @Autowired ConsumptionRepository consumptions;
    @Autowired Clock clock;

    private AppUser person() {
        return users.save(new AppUser("기간-" + UUID.randomUUID().toString().substring(0, 12),
                new BigDecimal("3000000"), new BigDecimal("1000000"), 12));
    }

    private Category cat(String code) {
        return categories.findByCode(code).orElseGet(() -> categories.save(new Category(code, code)));
    }

    private void spend(Long userId, String code, long amount, LocalDate on) {
        consumptions.save(new Consumption(userId, cat(code), BigDecimal.valueOf(amount),
                on.atTime(12, 0), false, Enums.DataSource.USER_INPUT));
    }

    private LocalDate monday() {
        return LocalDate.now(clock).with(DayOfWeek.MONDAY);
    }

    @Test
    @DisplayName("챌린지가 없어도 이번 주 소비가 나온다 — 이것이 이 서비스의 목적이다")
    void 챌린지_없이도_나온다() {
        AppUser me = person();
        spend(me.getId(), "식비", 12_000, monday());
        spend(me.getId(), "카페", 5_000, monday().plusDays(2));

        var got = service.of(me.getId(), "week", 0);

        assertThat(got.total()).isEqualTo(17_000);
        assertThat(got.count()).isEqualTo(2);
        assertThat(got.start()).isEqualTo(monday());
        assertThat(got.end()).isEqualTo(monday().plusDays(6));
    }

    /** 빠진 날이 있으면 막대 그래프가 하루씩 밀려 그려진다 — 0으로 채워야 자리가 맞는다. */
    @Test
    @DisplayName("결제가 없는 날도 0으로 채운다 — 일곱 칸이 항상 일곱 칸이다")
    void 빈_날도_채운다() {
        AppUser me = person();
        spend(me.getId(), "식비", 9_000, monday().plusDays(3));

        var got = service.of(me.getId(), "week", 0);

        assertThat(got.days()).hasSize(7);
        assertThat(got.days().get(0).amount()).isZero();
        assertThat(got.days().get(3).amount()).isEqualTo(9_000);
        // 날짜가 오름차순으로 고정돼 있다(원칙 3).
        assertThat(got.days()).isSortedAccordingTo((a, b) -> a.date().compareTo(b.date()));
    }

    @Test
    @DisplayName("지난 주를 물으면 지난 주만 센다")
    void 지난_주는_지난_주만() {
        AppUser me = person();
        spend(me.getId(), "식비", 30_000, monday().minusWeeks(1).plusDays(1));
        spend(me.getId(), "식비", 70_000, monday().plusDays(1));

        assertThat(service.of(me.getId(), "week", 1).total()).isEqualTo(30_000);
        assertThat(service.of(me.getId(), "week", 0).total()).isEqualTo(70_000);
    }

    @Test
    @DisplayName("달은 1일부터 말일까지다")
    void 달은_달_경계로() {
        AppUser me = person();
        LocalDate first = LocalDate.now(clock).withDayOfMonth(1);
        spend(me.getId(), "식비", 11_000, first);
        spend(me.getId(), "식비", 22_000, first.minusDays(1));   // 지난 달 말일

        var got = service.of(me.getId(), "month", 0);

        assertThat(got.start()).isEqualTo(first);
        assertThat(got.end()).isEqualTo(first.plusMonths(1).minusDays(1));
        assertThat(got.total()).isEqualTo(11_000);
    }

    /** 동점이 나올 때 순서가 흔들리면 화면이 새로고침마다 달라진다(원칙 3). */
    @Test
    @DisplayName("카테고리는 금액 내림차순, 같으면 코드순 — 동점에서도 순서가 정해진다")
    void 카테고리_순서가_고정이다() {
        AppUser me = person();
        spend(me.getId(), "나중코드", 5_000, monday());
        spend(me.getId(), "가장코드", 5_000, monday());
        spend(me.getId(), "큰금액코드", 9_000, monday());

        var got = service.of(me.getId(), "week", 0);

        assertThat(got.byCategory()).extracting(PeriodSpendService.CatSpend::code)
                .containsExactly("큰금액코드", "가장코드", "나중코드");
    }

    @Test
    @DisplayName("미래 구간은 묻지 않은 것으로 본다 — 음수 offset 은 이번 기간이다")
    void 음수는_이번_기간() {
        AppUser me = person();
        spend(me.getId(), "식비", 8_000, monday());

        assertThat(service.of(me.getId(), "week", -5).total()).isEqualTo(8_000);
    }

    @Test
    @DisplayName("소비가 하나도 없으면 0이고 칸은 그대로 있다 — 화면이 고장난 것이 아니다")
    void 없으면_영이다() {
        AppUser me = person();

        var got = service.of(me.getId(), "week", 0);

        assertThat(got.total()).isZero();
        assertThat(got.count()).isZero();
        assertThat(got.days()).hasSize(7);
        assertThat(got.byCategory()).isEmpty();
    }
}
