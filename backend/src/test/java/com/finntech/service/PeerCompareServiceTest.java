package com.finntech.service;

import com.finntech.domain.AppUser;
import com.finntech.domain.Category;
import com.finntech.domain.Consumption;
import com.finntech.domain.Enums;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.CategoryRepository;
import com.finntech.repository.ConsumptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>또래 비교는 견줄 수 있을 때만 말한다.</b>
 *
 * <p>이 서비스가 지키는 것은 숫자의 정확도가 아니라 <b>말하지 않을 때를 아는 것</b>이다 —
 * 출생연도를 모르거나 표본이 얇으면 아무 말도 안 한다. 표본 서넛으로 만든 '또래'는
 * 그중 한 명의 이사·수술 한 번이고, 그것을 사실처럼 보여주면 비교가 아니라 착시다.
 *
 * <p>평균이 아니라 <b>중앙값</b>인 것도 같은 이유다. 소비는 오른쪽으로 긴 꼬리라 평균은
 * 몇 명에게 끌려가고, 그러면 대부분이 "나는 또래보다 적게 쓴다"를 듣는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PeerCompareServiceTest {

    private static final int YEAR = 1996;

    @Autowired PeerCompareService service;
    @Autowired AppUserRepository users;
    @Autowired ConsumptionRepository consumptions;
    @Autowired CategoryRepository categories;
    @Autowired Clock clock;

    private Category category;

    @BeforeEach
    void setUp() {
        // 시험 프로파일은 카테고리 표가 비어 있을 수 있다 — 없으면 하나 만들어 쓴다.
        category = categories.findAll().stream().findFirst()
                .orElseGet(() -> categories.save(new Category("식비", "식비")));
    }

    /** 닉네임은 유일 제약이 있다 — 시험끼리 부딪히지 않게 매번 다르게 짓는다. */
    private AppUser person(Integer birthYear) {
        // 닉네임 칸이 40자라 UUID 를 통째로 붙이면 넘친다(41자).
        AppUser u = new AppUser("peer-" + UUID.randomUUID().toString().substring(0, 18),
                BigDecimal.valueOf(3_000_000),
                BigDecimal.valueOf(10_000_000), 12);
        u.setBirthYear(birthYear);
        return users.save(u);
    }

    private void spend(AppUser who, long amount) {
        consumptions.save(new Consumption(who.getId(), category, BigDecimal.valueOf(amount),
                LocalDateTime.now(clock).minusDays(3), false, Enums.DataSource.USER_INPUT));
    }

    @Test
    @DisplayName("출생연도를 모르면 비교하지 않는다")
    void 출생연도가_없으면_안_한다() {
        AppUser me = person(null);
        spend(me, 100_000);
        for (int i = 0; i < 8; i++) spend(person(YEAR), 200_000);

        assertThat(service.compare(me.getId(), 30)).isNull();
    }

    @Test
    @DisplayName("또래가 적으면 비교하지 않는다 — 서넛으로는 '또래'가 안 된다")
    void 표본이_얇으면_안_한다() {
        AppUser me = person(YEAR);
        spend(me, 100_000);
        for (int i = 0; i < 3; i++) spend(person(YEAR), 200_000);

        assertThat(service.compare(me.getId(), 30)).isNull();
    }

    @Test
    @DisplayName("표본이 차면 중앙값으로 견준다")
    void 중앙값으로_견준다() {
        AppUser me = person(YEAR);
        spend(me, 100_000);
        // 10·20·30·40·50만 → 중앙값 30만. 평균은 30만으로 같으니 다음 시험이 둘을 가른다.
        long[] amounts = { 100_000, 200_000, 300_000, 400_000, 500_000 };
        for (long a : amounts) spend(person(YEAR), a);

        PeerCompareService.PeerCompare got = service.compare(me.getId(), 30);

        assertThat(got).isNotNull();
        assertThat(got.mine()).isEqualTo(100_000);
        assertThat(got.peer()).isEqualTo(300_000);
        assertThat(got.sampleSize()).isEqualTo(5);
        assertThat(got.days()).isEqualTo(30);
    }

    /**
     * <b>이 시험이 평균과 중앙값을 가른다.</b> 한 명이 아주 많이 쓰면 평균은 끌려가지만
     * 중앙값은 안 움직인다 — 그래야 대부분의 사용자가 자기 자리를 안다.
     */
    @Test
    @DisplayName("한 명이 아주 많이 써도 또래 값이 끌려가지 않는다")
    void 큰_값_하나에_안_끌린다() {
        AppUser me = person(YEAR);
        spend(me, 100_000);
        for (long a : new long[] { 100_000, 100_000, 100_000, 100_000 }) spend(person(YEAR), a);
        spend(person(YEAR), 100_000_000);          // 이사·수술 같은 한 번

        PeerCompareService.PeerCompare got = service.compare(me.getId(), 30);

        assertThat(got).isNotNull();
        // 평균이면 2천만원대가 된다. 중앙값이라 10만원이다.
        assertThat(got.peer()).isEqualTo(100_000);
    }

    @Test
    @DisplayName("나이대 밖은 또래가 아니다")
    void 나이대_밖은_안_센다() {
        AppUser me = person(YEAR);
        spend(me, 100_000);
        for (int i = 0; i < 8; i++) spend(person(YEAR - 20), 200_000);   // 스무 살 위

        assertThat(service.compare(me.getId(), 30)).isNull();
    }

    @Test
    @DisplayName("내 소비는 또래 값에 안 섞인다")
    void 나는_또래에서_빠진다() {
        AppUser me = person(YEAR);
        spend(me, 900_000);
        for (int i = 0; i < 5; i++) spend(person(YEAR), 100_000);

        PeerCompareService.PeerCompare got = service.compare(me.getId(), 30);

        assertThat(got).isNotNull();
        assertThat(got.mine()).isEqualTo(900_000);
        // 내 90만원이 섞였다면 중앙값이 10만원보다 컸을 것이다.
        assertThat(got.peer()).isEqualTo(100_000);
    }
}
