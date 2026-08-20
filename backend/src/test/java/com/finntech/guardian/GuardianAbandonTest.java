package com.finntech.guardian;

import com.finntech.domain.AppUser;
import com.finntech.domain.Category;
import com.finntech.domain.Consumption;
import com.finntech.domain.Enums;
import com.finntech.guardian.domain.GuardianChallenge;
import com.finntech.guardian.domain.GuardianEnums.ChallengeState;
import com.finntech.guardian.repository.GuardianChallengeRepository;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>온보딩을 다시 열 수 있어야 한다.</b>
 *
 * <p>온보딩은 진행 중인 챌린지가 있으면 서버가 409 로 막는다. 그래서 한 번 목표를 세운
 * 사람은 그것을 <b>통째로 다시 세울 길이 없었다</b> — 로그아웃해도 브라우저만 비울 뿐
 * 서버의 챌린지는 그대로라, 다시 인증하면 곧장 홈이다.
 *
 * <p>여기서 잠그는 것은 셋이다 — ① 닫히는가 ② <b>지워지지는 않는가</b>(중단은 '없던 일'이
 * 아니라 지난 기록이다) ③ 닫은 뒤 새 챌린지를 만들 수 있는가.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GuardianAbandonTest {

    @Autowired GuardianService guardianService;
    @Autowired GuardianChallengeRepository challenges;
    @Autowired AppUserRepository users;
    @Autowired CategoryRepository categories;
    @Autowired ConsumptionRepository consumptions;

    /** 기준 지출이 나오려면 이력이 있어야 한다 — 없으면 챌린지 생성 자체가 안 된다. */
    private Long personWithHistory() {
        AppUser user = users.save(new AppUser("중단-" + UUID.randomUUID().toString().substring(0, 12),
                new BigDecimal("3000000"), new BigDecimal("1000000"), 12));
        // **코드가 챌린지의 카테고리 이름과 같아야 한다** — 기준 지출을 그 코드로 찾는다.
        Category cafe = categories.findByCode("카페")
                .orElseGet(() -> categories.save(new Category("카페", "카페")));
        for (int i = 1; i <= 10; i++) {
            consumptions.save(new Consumption(user.getId(), cafe, new BigDecimal("20000"),
                    LocalDateTime.now().minusDays(i), false, Enums.DataSource.DUMMY_SEED));
        }
        return user.getId();
    }

    private GuardianChallenge start(Long userId) {
        return guardianService.createChallenge(userId, List.of("카페"), List.of(),
                50_000L, null, null, 30, List.of(), Map.of("카페", 50_000L));
    }

    @Test
    @DisplayName("진행 중인 챌린지를 닫는다")
    void 진행_중인_것을_닫는다() {
        Long userId = personWithHistory();
        GuardianChallenge ch = start(userId);
        assertThat(challenges.findRunning(userId)).isPresent();

        var ended = guardianService.abandonRunning(userId);

        assertThat(ended).isPresent();
        assertThat(ended.get().getId()).isEqualTo(ch.getId());
        assertThat(challenges.findRunning(userId)).as("이제 진행 중인 것이 없다").isEmpty();
    }

    /**
     * <b>중단은 '없던 일'이 아니다.</b> 행을 지우면 그 달에 지킨 기록도 함께 사라져,
     * 목표를 고쳐 잡으려던 사람이 지나온 시간을 잃는다.
     */
    @Test
    @DisplayName("지우지 않고 상태만 바꾼다 — 지난 기록으로 남는다")
    void 지우지_않는다() {
        Long userId = personWithHistory();
        GuardianChallenge ch = start(userId);

        guardianService.abandonRunning(userId);

        assertThat(challenges.findById(ch.getId()))
                .as("행이 남아 있어야 지난 챌린지 목록에 보인다").isPresent()
                .get().extracting(GuardianChallenge::getState).isEqualTo(ChallengeState.ABANDONED);
    }

    @Test
    @DisplayName("닫은 뒤에는 새 챌린지를 만들 수 있다 — 이것이 이 기능의 목적이다")
    void 닫으면_다시_만들_수_있다() {
        Long userId = personWithHistory();
        start(userId);
        guardianService.abandonRunning(userId);

        GuardianChallenge again = start(userId);

        assertThat(again.getId()).isNotNull();
        assertThat(challenges.findRunning(userId)).isPresent();
    }

    @Test
    @DisplayName("진행 중인 것이 없어도 오류가 아니다 — 이미 원하는 상태다")
    void 없어도_오류가_아니다() {
        Long userId = personWithHistory();

        assertThat(guardianService.abandonRunning(userId)).isEmpty();
    }
}
