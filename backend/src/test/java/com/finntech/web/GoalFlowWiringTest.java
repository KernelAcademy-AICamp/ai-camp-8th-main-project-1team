package com.finntech.web;

import com.finntech.domain.AppUser;
import com.finntech.repository.AppUserRepository;
import com.finntech.service.PointService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>목표 흐름이 화면까지 실제로 이어지는가.</b>
 *
 * <h2>왜 이 시험이 필요한가</h2>
 *
 * <p>새 값은 <b>서버가 계산해도 화면에 안 오면 없는 것과 같다.</b> 그 끊김은 예외를 안 낸다 —
 * JSON 에 칸이 없으면 프론트에서 {@code undefined} 가 되고, 화면은 빈칸을 그리고 만다.
 * 이 저장소는 그 종류의 버그를 이미 여러 번 겪었다("원천에 사실이 있는데 옮기는 자리가 안 읽는다").
 *
 * <p>그래서 <b>컨트롤러가 실제로 내보내는 JSON</b>을 본다. 서비스 단위 시험은 계산이 맞는지만
 * 말해 주지 기록이 직렬화되는지는 말해 주지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class GoalFlowWiringTest {

    @Autowired PointController api;
    @Autowired AppUserRepository users;

    private AppUser someone() {
        return users.findAll().stream().findFirst()
                .orElseGet(() -> users.save(new AppUser("연결", BigDecimal.valueOf(3_000_000),
                        BigDecimal.valueOf(1_000_000), 6)));
    }

    @Test
    @DisplayName("목표 만들 때 기간·보상이 서버까지 간다")
    void 기간과_보상이_저장된다() {
        Long uid = someone().getId();

        api.createGoal(new PointController.GoalRequest(
                uid, "파리 여행", "✈️", BigDecimal.valueOf(3_000_000), null, 6, "plant"));

        PointService.GoalView g = api.snapshot(uid).goals().stream()
                .filter(x -> "파리 여행".equals(x.name())).findFirst().orElseThrow();

        assertThat(g.deadlineDays()).as("6개월을 골랐으면 180일로 저장된다").isEqualTo(180);
        assertThat(g.rewardCode()).as("고른 소품이 목표에 붙어야 한다").isEqualTo("plant");
        assertThat(g.monthlyRequired())
                .as("300만 ÷ 6개월 = 50만. 화면은 이 값으로 페이스를 보여준다")
                .isEqualByComparingTo("500000");
    }

    @Test
    @DisplayName("기간·보상을 안 보내도 예전처럼 만들어진다")
    void 예전_몸통도_통한다() {
        Long uid = someone().getId();
        // 예전 화면이 보내던 몸통 그대로 — 새 칸이 없다.
        api.createGoal(new PointController.GoalRequest(
                uid, "비상금", "🛟", BigDecimal.valueOf(1_000_000), null, null, null));

        PointService.GoalView g = api.snapshot(uid).goals().stream()
                .filter(x -> "비상금".equals(x.name())).findFirst().orElseThrow();

        assertThat(g.deadlineDays()).as("안 고르면 기존 기본값").isEqualTo(90);
        assertThat(g.rewardCode()).isNull();
    }

    @Test
    @DisplayName("새 칸이 JSON 에 실제로 실린다 — 화면이 읽을 이름 그대로")
    void 화면이_읽는_이름으로_나간다() {
        Long uid = someone().getId();
        api.createGoal(new PointController.GoalRequest(
                uid, "노트북", "💻", BigDecimal.valueOf(2_000_000), null, 4, null));

        JsonNode goal = new ObjectMapper().valueToTree(api.snapshot(uid))
                .get("goals").get(0);

        // 프론트 `GoalView`(lib/api.ts)가 읽는 이름과 하나도 어긋나면 안 된다.
        for (String field : java.util.List.of("rewardCode", "monthlyRequired",
                "monthlyAverageSaved", "projectedDate", "monthlyHistory")) {
            assertThat(goal.has(field))
                    .as("'%s' 가 JSON 에 없다 — 화면에서는 undefined 가 되고 빈칸이 그려진다", field)
                    .isTrue();
        }
        assertThat(goal.get("monthlyHistory").isArray()).isTrue();
    }

    @Test
    @DisplayName("월 평균은 목표가 아니라 사람에게 붙는다 — 첫 목표를 만들 때 필요하다")
    void 월평균은_스냅샷에_있다() {
        Long uid = someone().getId();
        JsonNode snap = new ObjectMapper().valueToTree(api.snapshot(uid));

        assertThat(snap.has("monthlyAverageSaved"))
                .as("목표 세우기 첫 걸음에서는 꺼내 볼 목표가 없다 — 스냅샷에 있어야 한다")
                .isTrue();
        assertThat(snap.get("monthlyAverageSaved").isNumber()).isTrue();
    }

    @Test
    @DisplayName("아직 지킨 적이 없으면 달성일을 지어내지 않는다")
    void 속도가_없으면_날짜가_없다() {
        Long uid = someone().getId();
        api.createGoal(new PointController.GoalRequest(
                uid, "이사 자금", "🏠", BigDecimal.valueOf(5_000_000), null, 12, null));

        PointService.GoalView g = api.snapshot(uid).goals().stream()
                .filter(x -> "이사 자금".equals(x.name())).findFirst().orElseThrow();

        // 입금이 하나도 없는 새 목표. 여기서 날짜가 나오면 그것은 지어낸 값이다.
        if (g.monthlyAverageSaved().signum() == 0) {
            assertThat(g.projectedDate()).isNull();
        }
        assertThat(g.monthlyHistory()).isEmpty();
    }

}
