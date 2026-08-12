package com.finntech.guardian;

import com.finntech.domain.Category;
import com.finntech.domain.Consumption;
import com.finntech.domain.Enums;
import com.finntech.guardian.repository.GuardianChallengeCategoryRepository;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>카테고리별 예산</b> — 사용자가 정한 강도가 그대로 예산이 되어야 한다.
 *
 * <p>예전에는 예산이 챌린지에 숫자 하나뿐이라, 화면이 카테고리별로 보여줄 때 전체 캡을
 * <b>균등분할</b>했다. 온보딩에서 배달 50%·카페 20%로 다르게 정해도 화면은 같은 값을 보여줬다
 * (정산 코드에 그 사실이 주석으로 적혀 있었다).
 *
 * <p><b>판정은 바뀌지 않는다.</b> 챌린지의 성공/실패와 잔디는 여전히 합계 기준이다
 * (사용자 결정 2026-07-31) — 카테고리로 실패까지 가르면 카테고리 수만큼 실패 확률이 오른다.
 */
/*
 * 시각 고정(finntech.demo.today)은 여기 걸지 않는다 — seed() 가 이미 오늘 기준 상대 날짜를
 * 쓰기 때문이다. 둘을 같이 쓰면 **시드는 실시간으로 흐르는데 창만 과거에 멈춰** 한 달쯤 뒤에
 * 결제가 전부 창 밖으로 나간다. 시드와 창은 같은 시간을 봐야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")   // 인메모리 H2 — 파일 DB 를 쓰면 낡은 스키마가 남는다
@Transactional
class ChallengeCategoryCapTest {

    private static final Long USER = 555_111L;

    @Autowired GuardianService guardianService;
    @Autowired GuardianChallengeCategoryRepository categoryRepository;
    @Autowired ConsumptionRepository consumptionRepository;
    @Autowired CategoryRepository categories;

    @BeforeEach
    void seed() {
        Category delivery = categories.findByCode("CCAP_DELIVERY")
                .orElseGet(() -> categories.save(new Category("CCAP_DELIVERY", "배달")));
        Category cafe = categories.findByCode("CCAP_CAFE")
                .orElseGet(() -> categories.save(new Category("CCAP_CAFE", "카페")));
        consumptionRepository.deleteByUserIdAndSource(USER, Enums.DataSource.DUMMY_SEED);
        // **날짜를 오늘 기준으로 잡는다.** 예전에는 `2026-07-10` 처럼 박아 두고 주석에
        // "창(7/4~8/3)" 이라고 적었는데, 기준선 창은 `now.minusDays(30)` 으로 **매일 밀린다.**
        // 그래서 2026-08-10 이 되자 7/10 하나가 창 밖으로 떨어져 배달 기준선이 20만 → 15만이
        // 됐고 시험 셋이 한꺼번에 깨졌다. 코드는 멀쩡한데 달력이 바뀌어서 깨지는 시험이다.
        //
        // 창 한복판(20일 전~14일 전)에 두면 달력이 어디에 있든 안 걸린다.
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 4; i++) {                       // 배달 20만
            consumptionRepository.save(new Consumption(USER, delivery, new BigDecimal("50000"),
                    today.minusDays(20 - i).atTime(19, 0), false, Enums.DataSource.DUMMY_SEED));
        }
        for (int i = 0; i < 2; i++) {                       // 카페 10만
            consumptionRepository.save(new Consumption(USER, cafe, new BigDecimal("50000"),
                    today.minusDays(15 - i).atTime(15, 0), false, Enums.DataSource.DUMMY_SEED));
        }
    }

    private Map<String, Long> capsOf(Long challengeId) {
        return categoryRepository.findByChallenge(challengeId).stream()
                .collect(Collectors.toMap(c -> c.getCategory(), c -> c.getCap()));
    }

    @Test
    @DisplayName("카테고리마다 정한 강도가 그대로 예산이 된다 — 균등분할하지 않는다")
    void 강도가_예산이_된다() {
        // 배달은 절반(10만), 카페는 20%(2만)를 지키기로 한다.
        var ch = guardianService.createChallenge(USER,
                List.of("CCAP_DELIVERY", "CCAP_CAFE"), List.of(),
                120_000L, null, null, 30, List.of(),
                Map.of("CCAP_DELIVERY", 100_000L, "CCAP_CAFE", 20_000L));

        var caps = capsOf(ch.getId());
        assertThat(caps.get("CCAP_DELIVERY")).as("배달 20만 − 10만").isEqualTo(100_000L);
        assertThat(caps.get("CCAP_CAFE")).as("카페 10만 − 2만").isEqualTo(80_000L);
        // 균등분할이었다면 둘 다 (30만−12만)/2 = 9만이었을 것이다.
        assertThat(caps.get("CCAP_DELIVERY")).isNotEqualTo(caps.get("CCAP_CAFE"));
    }

    @Test
    @DisplayName("카테고리별 목표를 안 주면 예전처럼 균등분할한다 — 옛 클라이언트가 깨지지 않는다")
    void 목표가_없으면_균등분할() {
        var ch = guardianService.createChallenge(USER,
                List.of("CCAP_DELIVERY", "CCAP_CAFE"), List.of(),
                120_000L, null, null, 30);

        var caps = capsOf(ch.getId());
        assertThat(caps).hasSize(2);
        // 각 카테고리 기준에서 총목표의 절반(6만)씩 뺀다.
        assertThat(caps.get("CCAP_DELIVERY")).isEqualTo(200_000L - 60_000L);
        assertThat(caps.get("CCAP_CAFE")).isEqualTo(100_000L - 60_000L);
    }

    @Test
    @DisplayName("합계 예산은 그대로다 — 판정은 여전히 합계로 한다")
    void 합계는_그대로다() {
        var ch = guardianService.createChallenge(USER,
                List.of("CCAP_DELIVERY", "CCAP_CAFE"), List.of(),
                120_000L, null, null, 30, List.of(),
                Map.of("CCAP_DELIVERY", 100_000L, "CCAP_CAFE", 20_000L));

        assertThat(ch.getBaselineAmount()).as("배달 20만 + 카페 10만").isEqualTo(300_000L);
        assertThat(ch.getChallengeCap()).as("30만 − 12만").isEqualTo(180_000L);
    }
}
