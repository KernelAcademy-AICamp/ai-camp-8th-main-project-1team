package com.finntech.guardian;

import com.finntech.guardian.domain.GuardianChallenge;
import com.finntech.guardian.domain.GuardianEnums.ChallengeState;
import com.finntech.guardian.repository.GuardianChallengeRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ②가 ③에게 넘기는 확정 지킨 돈 이력({@code history})만 검증한다.
 * 정본은 `07_취향분석및추천_Agent_설계.md` §11 · `06_지킴이_Agent_설계.md` §1.
 */
class GuardianSettlementHistoryTest {

    private static final LocalDate START = LocalDate.of(2026, 6, 1);

    /**
     * 챌린지 하나. 지출은 setter가 없어 정산 경로로만 쌓이므로 리플렉션으로 넣는다 —
     * 이 테스트가 보는 것은 <b>확보 절약액 식과 상태 필터</b>뿐이다.
     */
    private static GuardianChallenge challenge(long baseline, long target, long spent, ChallengeState state) {
        GuardianChallenge ch = new GuardianChallenge(1L, List.of("배달"), List.of(),
                baseline, target, 0.1, START, START.plusDays(29), null, null,
                LocalDateTime.of(2026, 6, 1, 0, 0));
        ch.setState(state);
        set(ch, "spentAmount", spent);
        return ch;
    }

    private static void set(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 저장소만 흉내 낸다 — history는 조회 하나와 순수 계산으로 끝난다. */
    @SuppressWarnings("unchecked")
    private static List<GuardianSettlementService.SettledChallenge> historyOf(List<GuardianChallenge> stored) {
        GuardianChallengeRepository repo = (GuardianChallengeRepository) java.lang.reflect.Proxy.newProxyInstance(
                GuardianChallengeRepository.class.getClassLoader(),
                new Class<?>[]{GuardianChallengeRepository.class},
                (proxy, method, args) -> "findByUserIdOrderByIdDesc".equals(method.getName())
                        ? stored : defaultFor(method));
        GuardianSettlementService service = new GuardianSettlementService(
                repo, null, null, null, null, null);
        return service.history(1L);
    }

    private static Object defaultFor(Method method) {
        Class<?> t = method.getReturnType();
        if (t == boolean.class) return false;
        if (t.isPrimitive()) return 0;
        return List.class.isAssignableFrom(t) ? List.of() : null;
    }

    @Test
    void 확보절약액은_min_목표_기준지출빼기지출_이다() {
        // 기준 100만 · 목표 30만 · 지출 80만 → 100−80=20만, 목표(30만)보다 작으니 20만
        var out = historyOf(List.of(challenge(1_000_000, 300_000, 800_000, ChallengeState.PARTIAL)));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).securedSaving()).isEqualTo(200_000);
        assertThat(out.get(0).defenseRate()).isEqualTo(200_000 / 300_000.0);
    }

    /** 아껴도 목표 이상은 세지 않는다 — 방어율이 100%를 넘으면 안 된다. */
    @Test
    void 목표를_넘겨_아껴도_목표까지만_센다() {
        // 100−10=90만이지만 목표는 30만 → 30만
        var out = historyOf(List.of(challenge(1_000_000, 300_000, 100_000, ChallengeState.SUCCESS)));

        assertThat(out.get(0).securedSaving()).isEqualTo(300_000);
        assertThat(out.get(0).defenseRate()).isEqualTo(1.0);
    }

    /** 초과 지출로 음수가 나와도 0에서 멈춘다 — §8.1의 `환불이 kept_mean을 흔든다`를 막는 하한. */
    @Test
    void 기준을_넘겨_써도_0_아래로_안_간다() {
        var out = historyOf(List.of(challenge(1_000_000, 300_000, 1_500_000, ChallengeState.FAILED)));

        assertThat(out.get(0).securedSaving()).isZero();
        assertThat(out.get(0).defenseRate()).isZero();
    }

    /** 환불로 지출이 음수가 되어도 목표를 넘지 않는다 — 상한이 막는다. */
    @Test
    void 환불로_지출이_음수여도_목표를_넘지_않는다() {
        var out = historyOf(List.of(challenge(1_000_000, 300_000, -50_000, ChallengeState.SUCCESS)));

        assertThat(out.get(0).securedSaving()).isEqualTo(300_000);
    }

    /** 확정되지 않은 금액은 이력이 아니다. */
    @Test
    void 진행중_정산중_중도포기는_제외한다() {
        var out = historyOf(List.of(
                challenge(1_000_000, 300_000, 500_000, ChallengeState.ACTIVE),
                challenge(1_000_000, 300_000, 500_000, ChallengeState.AT_RISK),
                challenge(1_000_000, 300_000, 500_000, ChallengeState.EXCEEDED),
                challenge(1_000_000, 300_000, 500_000, ChallengeState.SETTLING),
                challenge(1_000_000, 300_000, 500_000, ChallengeState.SETUP),
                challenge(1_000_000, 300_000, 500_000, ChallengeState.ABANDONED)));

        assertThat(out).isEmpty();
    }

    @Test
    void 결과가_확정된_상태만_담는다() {
        var out = historyOf(List.of(
                challenge(1_000_000, 300_000, 500_000, ChallengeState.SUCCESS),
                challenge(1_000_000, 300_000, 500_000, ChallengeState.PARTIAL),
                challenge(1_000_000, 300_000, 500_000, ChallengeState.SHORTFALL),
                challenge(1_000_000, 300_000, 500_000, ChallengeState.FAILED),
                challenge(1_000_000, 300_000, 500_000, ChallengeState.REWARD_PENDING),
                challenge(1_000_000, 300_000, 500_000, ChallengeState.RESTART_OFFER),
                challenge(1_000_000, 300_000, 500_000, ChallengeState.CLOSED),
                challenge(1_000_000, 300_000, 500_000, ChallengeState.ACTIVE)));

        assertThat(out).hasSize(7);
    }

    @Test
    void 챌린지가_없으면_빈_이력이다() {
        assertThat(historyOf(List.of())).isEmpty();
    }

    /** 목표가 0이면 나눗셈을 하지 않는다. */
    @Test
    void 목표가_0이면_방어율은_0이다() {
        var out = historyOf(List.of(challenge(1_000_000, 0, 500_000, ChallengeState.CLOSED)));

        assertThat(out.get(0).defenseRate()).isZero();
        assertThat(out.get(0).securedSaving()).isZero();
    }
}
