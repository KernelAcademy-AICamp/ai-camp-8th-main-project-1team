package com.finntech.freechannel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>바깥으로 나가는 문이 하나인지</b>를 지킨다.
 *
 * <p><b>왜 이런 시험이 필요한가.</b> 2026-08-07 재감사에서 배운 것이 정확히 이것이다. 저장소에는
 * {@code util/HttpClients} 라는 규약이 있었고 클래스 주석까지 "이걸 거쳐라"라고 적어 뒀는데,
 * <b>네 곳이 그냥 {@code RestClient.builder()} 를 썼다.</b> 규약을 만든 사람은 규약을 봤지만,
 * 규약을 <i>안 쓰는 코드</i>는 아무도 안 봤다.
 *
 * <p>필수 인자로 만드는 것만으로는 안 막힌다. 새 코드가 <b>큐를 안 거치고</b> 통로를 직접 부르면
 * 그 인자를 볼 일이 없기 때문이다. 그래서 <b>누가 문을 열 수 있는지</b>를 목록으로 못박는다.
 *
 * <p><b>목록에 없는 클래스가 걸리면 빌드가 깨진다.</b> 그때 할 일은 목록에 이름을 더하는 것이
 * 아니라 — 그건 규약을 지우는 것이다 — {@link FreeChannelQueue#submit} 을 거치는 것이다.
 * 차선을 고르는 물음은 하나뿐이다: <i>"이 일을 안 하면 사용자가 지금 나쁜 것을 보는가."</i>
 */
class OneDoorTest {

    /**
     * 무료 통로를 직접 부를 수 있는 자리.
     *
     * <ul>
     *   <li>{@code TempClassifierService} — 통로 그 자체(HTTP 를 실제로 내는 곳)</li>
     *   <li>{@code MerchantBrandService} — 큐가 부르는 콜백 안에서만 쓴다</li>
     *   <li>{@code NarrativeCacheService} — 같음</li>
     *   <li>{@code UsageGlossaryService} — 같음. 통계 용어의 말투 다듬기를 {@link Lane#ADMIN} 으로
     *       올린다(맨 뒤 차선 — 늦어도 사용자는 아무것도 안 나빠지고 원문이 그대로 뜬다)</li>
     *   <li>{@code MerchantAskService} — ②-c 임시 분류. 결제 적재 흐름에 붙어 있어 아직 큐 밖이다
     *       (다음 차례다 — 그때 이 줄이 지워진다)</li>
     * </ul>
     */
    private static final Set<String> ALLOWED = Set.of(
            "TempClassifierService",
            "MerchantBrandService",
            "NarrativeCacheService",
            "UsageGlossaryService",
            "MerchantAskService");

    /** 통로를 쓴다는 표시 — 이 이름이 소스에 보이면 바깥으로 나갈 수 있다는 뜻이다. */
    private static final List<String> MARKERS =
            List.of("TempClassifierService", "temporary.", "free.sentence(");

    @Test
    @DisplayName("무료 통로는 큐를 거치지 않고는 못 부른다")
    void nobodyBypassesTheQueue() throws IOException {
        Path root = Path.of("src/main/java/com/finntech");
        try (Stream<Path> files = Files.walk(root)) {
            List<String> offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(OneDoorTest::touchesFreeChannel)
                    .map(p -> p.getFileName().toString().replace(".java", ""))
                    .filter(name -> !ALLOWED.contains(name))
                    .sorted().toList();

            assertThat(offenders).as("""
                    무료 통로를 직접 부르는 새 자리가 생겼다.

                    목록에 이름을 더하지 말고 FreeChannelQueue.submit(차선, 키, 할 일) 을 거쳐라.
                    고르는 물음은 하나다 — "이 일을 안 하면 사용자가 지금 나쁜 것을 보는가."
                      본다   → Lane.USER_NOW
                      아니다 → Lane.USER_REFRESH · USER_BACKGROUND · DUMMY
                      운영자만 본다 → Lane.ADMIN

                    통로를 직접 부르면 예산도 순서도 없다. 브랜드 273곳이 실사용자의 문장을 굶기고,
                    그 사실이 아무 데도 안 남는다.""")
                    .isEmpty();
        }
    }

    private static boolean touchesFreeChannel(Path file) {
        try {
            String src = Files.readString(file);
            // 자기 자신(통로 정의)과 큐 자체는 이 검사의 대상이 아니다.
            if (file.toString().contains("/freechannel/")) return false;
            return MARKERS.stream().anyMatch(src::contains);
        } catch (IOException e) {
            return false;
        }
    }
}
