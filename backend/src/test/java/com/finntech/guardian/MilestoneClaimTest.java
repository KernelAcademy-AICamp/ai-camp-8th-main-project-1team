package com.finntech.guardian;

import com.finntech.guardian.domain.GuardianItems;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마일스톤 지급 이력 — <b>'열렸는가'와 '받았는가'를 가른다.</b>
 *
 * <p>실제로 밟은 버그: 지급 여부를 소유 종수로만 판정했더니 21종을 모은 사용자가 세 보상 전부
 * '받음'으로 표시된 채 <b>한 장도 못 받았다</b>(청구 버튼이 뜨지 않았다). 동시에 청구 API는
 * 종수만 보니 부를수록 계속 지급됐다.
 */
class MilestoneClaimTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 30, 9, 0);

    @Test
    @DisplayName("받기 전에는 claimed가 아니다 — 그래야 청구 버튼이 뜬다")
    void notClaimedBeforeClaiming() {
        GuardianItems it = new GuardianItems(1L, NOW);
        assertThat(it.hasClaimed(10)).isFalse();
        assertThat(it.hasClaimed(15)).isFalse();
    }

    @Test
    @DisplayName("받은 것만 claimed가 된다 — 다른 마일스톤은 그대로 열려 있다")
    void marksOnlyTheClaimedOne() {
        GuardianItems it = new GuardianItems(1L, NOW);
        it.markClaimed(10, NOW);
        assertThat(it.hasClaimed(10)).isTrue();
        assertThat(it.hasClaimed(15)).isFalse();
        assertThat(it.hasClaimed(20)).isFalse();
    }

    @Test
    @DisplayName("두 번 표시해도 한 번만 남는다(멱등) — 목록이 부풀지 않는다")
    void markingTwiceIsIdempotent() {
        GuardianItems it = new GuardianItems(1L, NOW);
        it.markClaimed(10, NOW);
        it.markClaimed(10, NOW);
        it.markClaimed(15, NOW);
        assertThat(it.hasClaimed(10)).isTrue();
        assertThat(it.hasClaimed(15)).isTrue();
        // 1이 10에 걸리거나 5가 15에 걸리는 부분일치가 없어야 한다
        assertThat(it.hasClaimed(1)).isFalse();
        assertThat(it.hasClaimed(5)).isFalse();
        assertThat(it.hasClaimed(0)).isFalse();
    }

    @Test
    @DisplayName("여러 개를 받아도 각각 정확히 기억한다")
    void remembersAll() {
        GuardianItems it = new GuardianItems(1L, NOW);
        for (int c : new int[]{10, 15, 20}) it.markClaimed(c, NOW);
        for (int c : new int[]{10, 15, 20}) assertThat(it.hasClaimed(c)).as("%d종", c).isTrue();
        assertThat(it.hasClaimed(25)).isFalse();
    }
}
