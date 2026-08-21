package com.finntech.freechannel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>앞 차선이 붐비면 우선순위가 사라진다.</b>
 *
 * <p>{@link FreeChannelQueue#take} 는 {@link Lane#USER_REFRESH} 까지는 토큰이 있는 만큼 다
 * 꺼내고, 그 아래 차선은 <b>한 번에 두 건</b>만 꺼낸다({@code LOW_LANE_PER_TICK}). 즉 앞 두
 * 차선에는 사실상 상한이 없다 — 거기에 많이 넣으면 같은 차선의 다른 일이 굶는다.
 *
 * <p>그래서 <b>무엇을 앞에 넣는지</b>가 이 구조의 전부다. 판단 기준은 {@link Lane} 이 정한
 * 한 문장이다 — <i>"이 일을 안 하면 사용자가 지금 나쁜 것을 보는가."</i>
 *
 * <p>이 시험은 그 배분이 조용히 무너지는 것을 막는다. 새 코드가 {@code USER_NOW} 를 쓰면
 * 여기가 걸리고, 그때 할 일은 목록에 이름을 더하는 것이 아니라 <b>정말 급한지</b>를 다시
 * 묻는 것이다.
 */
class LaneBalanceTest {

    /**
     * 앞 차선({@code USER_NOW}·{@code USER_REFRESH})을 쓸 수 있는 자리.
     *
     * <ul>
     *   <li>{@code NarrativeCacheService} — 문장이 <b>아예 없으면</b> 기계 같은 템플릿이 뜬다.
     *       있으면 어제 문장이라 {@code USER_REFRESH} 로 내려간다.</li>
     *   <li>{@code TempClassifierService} — 부르는 쪽이 정한 차선을 그대로 큐에 넘긴다.
     *       무엇을 앞에 둘지는 {@code MerchantAskService} 가 정하고, 그 상한은 아래
     *       {@link #classificationCapsTheFrontLane} 가 잠근다.</li>
     * </ul>
     */
    private static final List<String> MAY_USE_FRONT_LANE =
            List.of("NarrativeCacheService", "TempClassifierService");

    @Test
    @DisplayName("앞 차선은 정해진 자리에서만 쓴다")
    void frontLaneIsRationed() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path root = Path.of("src/main/java/com/finntech");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = f.getFileName().toString().replace(".java", "");
                if (MAY_USE_FRONT_LANE.contains(name)) continue;
                // **올리는 자리만 본다.** javadoc 의 `{@link Lane#USER_NOW}` 나 큐 자신의
                // 판정 로직(`lane.ordinal() > Lane.USER_REFRESH.ordinal()`)은 배분이 아니다.
                String src = Files.readString(f);
                if (src.contains("submit(Lane.USER_NOW") || src.contains("submit(Lane.USER_REFRESH")) {
                    offenders.add(name);
                }
            }
        }
        assertThat(offenders)
                .as("앞 차선은 상한이 없다 — 여기 이름이 늘면 우선순위가 사라진다. "
                        + "정말 '지금 나쁜 것을 보는가'를 다시 물어라")
                .isEmpty();
    }

    /**
     * 화면을 연 사람의 것이라도 <b>전부</b> 앞 차선으로 보내면 안 된다. 한 곳이 최대 세 번을
     * 부르므로 여덟 곳이면 24회 — 분당 예산 40 의 절반이 넘는다.
     */
    @Test
    @DisplayName("업종 분류는 앞 차선으로 보낼 개수에 상한이 있다")
    void classificationCapsTheFrontLane() throws IOException {
        String src = Files.readString(
                Path.of("src/main/java/com/finntech/service/MerchantAskService.java"));

        assertThat(src).as("상한 상수가 있어야 한다").contains("URGENT_HEAD");
        assertThat(src).as("앞 차선에는 앞머리만 보낸다")
                .contains("subList(0, head), Lane.USER_NOW");
        assertThat(src).as("나머지는 뒤 차선이다")
                .contains("Lane.USER_BACKGROUND");
    }

    /** 배경 적재는 보는 사람이 없다 — 앞 차선을 쓸 이유가 없다. */
    @Test
    @DisplayName("브랜드 라벨링은 뒤 차선이다")
    void brandingStaysBehind() throws IOException {
        String src = Files.readString(
                Path.of("src/main/java/com/finntech/service/MerchantBrandService.java"));

        assertThat(src).contains("Lane.USER_BACKGROUND");
        assertThat(src).doesNotContain("Lane.USER_NOW");
    }
}
