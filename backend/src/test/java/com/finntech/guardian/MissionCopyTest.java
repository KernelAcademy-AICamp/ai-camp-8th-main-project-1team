package com.finntech.guardian;

import com.finntech.guardian.domain.GuardianEnums.MissionType;
import com.finntech.guardian.domain.WeeklyMission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 미션 문구 — <b>리포트와 마이룸이 같은 미션을 같은 말로 부르는지</b> 고정한다.
 *
 * <p>규칙이 두 곳에 있으면 조용히 갈린다. 고를 때는 "금 19~22시 배달 안 쓰기"였는데
 * 정산에서 "배달 시간대 피하기"로 나오면, 사용자는 자기가 고른 것이 정산됐는지 알 수 없다.
 * 크래시가 없으니 테스트로 잡아야 한다.
 */
class MissionCopyTest {

    private static final LocalDate MON = LocalDate.of(2026, 8, 10);

    @Test
    @DisplayName("네 유형이 각자의 문장을 만든다")
    void everyTypeHasCopy() {
        assertThat(GuardianCopy.missionText(MissionType.MAX_COUNT, "배달", 2, null, null, null))
                .isEqualTo("배달 주 2회 이하");
        assertThat(GuardianCopy.missionText(MissionType.AVOID_SLOT, "배달", 0,
                DayOfWeek.FRIDAY, 19, 22))
                .isEqualTo("금 19~22시 배달 안 쓰기");
        assertThat(GuardianCopy.missionText(MissionType.NO_SPEND_STREAK_MIN, null, 3, null, null, null))
                .isEqualTo("무지출 3일 연속");
        assertThat(GuardianCopy.missionText(MissionType.LABELING_COUNT_MIN, null, 5, null, null, null))
                .isEqualTo("소비 성격 5건 답하기");
    }

    @Test
    @DisplayName("엔티티에서 뽑은 문구가 값으로 만든 것과 같다")
    void entityAndValuesAgree() {
        // 여기가 어긋나면 고를 때와 정산할 때의 문구가 갈린다.
        WeeklyMission slot = WeeklyMission.avoidSlot(1L, 1L, "배달",
                DayOfWeek.FRIDAY, 19, 22, MON, MON.plusDays(6), LocalDateTime.now());
        assertThat(GuardianCopy.missionText(slot))
                .isEqualTo(GuardianCopy.missionText(MissionType.AVOID_SLOT, "배달", 0,
                        DayOfWeek.FRIDAY, 19, 22));

        WeeklyMission count = new WeeklyMission(1L, 1L, MissionType.MAX_COUNT, "카페", 3,
                MON, MON.plusDays(6), LocalDateTime.now());
        assertThat(GuardianCopy.missionText(count)).isEqualTo("카페 주 3회 이하");
    }

    @Test
    @DisplayName("요일을 모르는 슬롯 미션도 문장이 깨지지 않는다")
    void slotWithoutWeekday() {
        // 구버전에 요일 없이 저장된 행이 남아 있을 수 있다 — null 이 문장에 새면 "null 19~22시"가 된다.
        assertThat(GuardianCopy.missionText(MissionType.AVOID_SLOT, "배달", 0, null, null, null))
                .isEqualTo("배달 시간대 피하기");
    }

    @Test
    @DisplayName("사용자가 말한 제외는 사다리를 건너뛴다")
    void userExclusionSkipsTheLadder() {
        // 사다리는 "세 번 뺐으니 필수인 듯하다"는 추정이고, 여기서는 사용자가 말했다.
        var s = new com.finntech.domain.UserMerchantStance(1L, "1234567890", "KTX", LocalDateTime.now());
        s.excludedByUser(3, LocalDateTime.now());

        assertThat(s.getStance()).isEqualTo(com.finntech.domain.UserMerchantStance.Stance.EXCLUDED);
        // 되돌리는 길은 그대로 — 통근이 끝나면 한 칸 내려온다.
        s.notKept(1, 3);
        assertThat(s.getStance()).isEqualTo(com.finntech.domain.UserMerchantStance.Stance.LENIENT);
    }
}
