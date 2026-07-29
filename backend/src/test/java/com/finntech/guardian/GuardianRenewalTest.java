package com.finntech.guardian;

import com.finntech.guardian.GuardianSettlementService.CategoryResult;
import com.finntech.guardian.GuardianSettlementService.RenewalLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 다음 달 조정안 — <b>목표를 낮추는 판단</b>이 어긋나지 않게 고정한다.
 *
 * <p>이 규칙은 사람의 지속 여부를 가른다. 못 지킬 목표를 그대로 두면 그만두고, 반대로 성공했다고
 * 더 조이면 성공이 벌이 된다. 그래서 <b>올리는 선택지를 두지 않는다</b>는 것까지 테스트로 박는다.
 */
class GuardianRenewalTest {

    private static CategoryResult result(long cap, long spent) {
        long kept = Math.max(0, cap - spent);
        return new CategoryResult("배달·외식", cap, spent, kept, cap == 0 ? 0.0 : (double) kept / cap);
    }

    @Test
    @DisplayName("잘 지켰으면 그대로 둔다 — 성공이 더 센 목표로 돌아오지 않게")
    void keepsCapWhenAchieved() {
        // 40,000 중 2,000만 써서 95% 지킴
        RenewalLine line = GuardianSettlementService.suggest(result(40_000, 2_000));
        assertThat(line.action()).isEqualTo("KEEP");
        assertThat(line.suggestedCap()).isEqualTo(40_000);
        assertThat(line.reason()).contains("페이스");
    }

    @Test
    @DisplayName("못 지켰으면 실제 지출에 여유를 얹어 내린다")
    void lowersTowardActualSpending() {
        // 125,000 목표인데 84,000을 써서 33% 지킴 → 84,000 × 1.1 = 92,400 → 만원 단위 90,000
        RenewalLine line = GuardianSettlementService.suggest(result(125_000, 84_000));
        assertThat(line.action()).isEqualTo("LOWER");
        assertThat(line.suggestedCap()).isEqualTo(90_000);
        assertThat(line.suggestedCap()).isLessThan(line.currentCap());
    }

    @Test
    @DisplayName("한 번에 반토막 내지 않는다 — 목표가 너무 작아지면 챌린지가 의미를 잃는다")
    void doesNotCutBelowFloor() {
        // 크게 무너져서 한 푼도 못 지킨 경우(지출 0이라 '실제 지출' 기준이 0)
        RenewalLine line = GuardianSettlementService.suggest(result(100_000, 0));
        assertThat(line.suggestedCap()).isGreaterThanOrEqualTo(60_000);   // 하한 60%
    }

    @Test
    @DisplayName("초과해서 썼어도 한도를 올리지 않는다")
    void neverRaisesCap() {
        // 한도의 세 배를 썼다 — 실제 지출 기준이면 올라가야 하지만 올리지 않는다
        RenewalLine line = GuardianSettlementService.suggest(result(50_000, 150_000));
        assertThat(line.suggestedCap()).isLessThanOrEqualTo(50_000);
    }

    @Test
    @DisplayName("제안 한도는 만원 단위 — 104,500원 같은 값은 사람이 정한 목표로 안 읽힌다")
    void roundsToTenThousand() {
        for (long spent : List.of(31_234L, 47_777L, 88_001L, 12_345L)) {
            RenewalLine line = GuardianSettlementService.suggest(result(200_000, spent));
            assertThat(line.suggestedCap() % 10_000)
                    .as("지출 %d에 대한 제안 %d", spent, line.suggestedCap()).isZero();
        }
    }

    @Test
    @DisplayName("행동은 KEEP 아니면 LOWER 뿐이다")
    void onlyTwoActions() {
        for (long spent = 0; spent <= 200_000; spent += 7_000) {
            RenewalLine line = GuardianSettlementService.suggest(result(100_000, spent));
            assertThat(line.action()).isIn("KEEP", "LOWER");
            if (line.action().equals("LOWER")) {
                assertThat(line.suggestedCap()).isLessThan(line.currentCap());
            }
        }
    }

    @Test
    @DisplayName("CSV 카테고리 파싱 — 빈 값·공백을 흘리지 않는다")
    void csvIgnoresBlanks() {
        assertThat(GuardianSettlementService.csv("배달, 카페 ,,")).containsExactly("배달", "카페");
        assertThat(GuardianSettlementService.csv("")).isEmpty();
        assertThat(GuardianSettlementService.csv(null)).isEmpty();
    }
}
