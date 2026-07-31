package com.finntech.domain;

import com.finntech.domain.UserMerchantStance.Stance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>가맹점 판정 성향의 전이</b> — 한 번에 제외하지 않고, 되돌릴 수 있어야 한다.
 *
 * <p>사용자가 "이건 낭비가 아니다"를 누르면 그 가게를 통째로 빼 버리고 싶어진다. 그런데
 * <b>같은 가게에서 낭비 목적으로 살 수도 있다</b> — 자격증 책을 사던 서점에서 만화책을 몰아
 * 살 수 있다(사용자 지적 2026-07-31). 그래서 임계를 올리는 단계를 사이에 둔다.
 *
 * <p>되돌릴 길도 반드시 있어야 한다. 한 번 새어나간 지출이 영영 안 잡히는 것이 더 나쁘다.
 */
class UserMerchantStanceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 12, 0);
    private static final int TO_LENIENT = 1;
    private static final int TO_EXCLUDED = 3;

    private UserMerchantStance fresh() {
        return new UserMerchantStance(1L, "1234567890", "교보문고", NOW);
    }

    @Test
    @DisplayName("처음에는 전역 임계 그대로다")
    void 처음은_보통이다() {
        assertThat(fresh().getStance()).isEqualTo(Stance.NORMAL);
    }

    @Test
    @DisplayName("한 번 빼면 관대로 — 제외가 아니다. 같은 가게에서 낭비를 살 수도 있다")
    void 한번이면_관대까지만() {
        UserMerchantStance s = fresh();
        s.kept(TO_LENIENT, TO_EXCLUDED, NOW);
        assertThat(s.getStance()).as("한 번에 제외로 가면 안 된다").isEqualTo(Stance.LENIENT);
    }

    @Test
    @DisplayName("반복해서 빼면 그때 제외로 간다 — 누적된 사용자 판단이다")
    void 반복하면_제외() {
        UserMerchantStance s = fresh();
        for (int i = 0; i < TO_EXCLUDED; i++) s.kept(TO_LENIENT, TO_EXCLUDED, NOW);
        assertThat(s.getStance()).isEqualTo(Stance.EXCLUDED);
        assertThat(s.getKeptCount()).isEqualTo(TO_EXCLUDED);
    }

    @Test
    @DisplayName("'역시 낭비였다'면 한 칸 내려온다 — 되돌릴 길이 없으면 영영 안 잡힌다")
    void 되돌릴_수_있다() {
        UserMerchantStance s = fresh();
        for (int i = 0; i < TO_EXCLUDED; i++) s.kept(TO_LENIENT, TO_EXCLUDED, NOW);
        assertThat(s.getStance()).isEqualTo(Stance.EXCLUDED);

        s.notKept(TO_LENIENT, TO_EXCLUDED);
        assertThat(s.getStance()).as("제외 → 관대").isEqualTo(Stance.LENIENT);

        s.notKept(TO_LENIENT, TO_EXCLUDED);
        assertThat(s.getStance()).as("관대 → 보통").isEqualTo(Stance.NORMAL);

        s.notKept(TO_LENIENT, TO_EXCLUDED);
        assertThat(s.getStance()).as("보통 아래로는 안 내려간다").isEqualTo(Stance.NORMAL);
    }

    @Test
    @DisplayName("되돌린 뒤 한 번만 더 빼면 원래 단계로 돌아온다 — 쌓은 판단을 0으로 지우지 않는다")
    void 되돌려도_판단은_남는다() {
        UserMerchantStance s = fresh();
        for (int i = 0; i < TO_EXCLUDED; i++) s.kept(TO_LENIENT, TO_EXCLUDED, NOW);
        s.notKept(TO_LENIENT, TO_EXCLUDED);          // EXCLUDED → LENIENT
        s.kept(TO_LENIENT, TO_EXCLUDED, NOW);        // 한 번만 더
        assertThat(s.getStance()).isEqualTo(Stance.EXCLUDED);
    }
}
