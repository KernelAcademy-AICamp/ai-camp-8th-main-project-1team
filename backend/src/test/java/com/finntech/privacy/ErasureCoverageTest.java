package com.finntech.privacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>사람에게 매달린 표는 하나도 빠짐없이 파기된다.</b>
 *
 * <p><b>왜 이런 시험이 필요한가.</b> {@code PrivacyService.eraseUserData} 는 표 이름을 <b>손으로
 * 나열</b>한다. 손으로 관리하는 목록은 새 표가 생길 때 조용히 뒤처지고, <b>빠뜨린 것은 영원히
 * "파기 완료"로 보인다</b> — 아무도 없는 것을 못 보기 때문이다.
 *
 * <p>실제로 그렇게 됐다. 지킴이 표 열 개가 <b>통째로</b> 빠져 있었고(2026-08-20 발견),
 * 그중 {@code guardian_transaction} 은 가맹점명과 금액을, {@code guardian_notification} 은
 * 그 소비를 두고 한 말을 그대로 들고 있었다. 소비내역을 지워도 그것들이 남았으니
 * "삭제했다"가 사실이 아니었다 — {@code PrivacyService} 가 {@code Alert}·{@code Report} 를
 * 함께 지우며 처음부터 경계하던 바로 그 실패 모양이다.
 *
 * <p><b>그래서 목록을 코드에서 읽는다.</b> {@code userId} 를 든 엔티티를 소스에서 찾아,
 * 각각에 대해 파기가 <b>무엇을 하는지</b>를 이 시험이 요구한다. 새 엔티티를 만들면 여기서
 * 빨간불이 나고, 그때 할 일은 목록에 이름을 더하는 것이 아니라 <b>파기에 한 줄을 더하는
 * 것</b>이다 — 정말 남겨야 하면 {@link #KEPT} 에 <b>사유와 함께</b> 적는다.
 */
class ErasureCoverageTest {

    private static final Path PRIVACY_SERVICE =
            Path.of("src/main/java/com/finntech/service/PrivacyService.java");
    private static final List<Path> ENTITY_ROOTS = List.of(
            Path.of("src/main/java/com/finntech/domain"),
            Path.of("src/main/java/com/finntech/guardian/domain"));

    /**
     * <b>파기하지 않는 것과 그 사유.</b> 비워 두는 것이 기본이고, 여기 적는 것은 예외다.
     *
     * <p>사유를 함께 적게 한 이유: 이름만 적을 수 있으면 빨간불을 끄는 가장 쉬운 방법이
     * "여기 한 줄 추가"가 되고, 그러면 이 시험은 아무것도 안 지킨다.
     */
    private static final Map<String, String> KEPT = Map.of();

    @Test
    @DisplayName("userId 를 든 엔티티는 모두 파기되거나, 사유와 함께 남긴다")
    void everyUserScopedEntityIsErased() throws IOException {
        String source = Files.readString(PRIVACY_SERVICE);
        String erasure = erasureBody(source);
        List<String> uncovered = userScopedEntities().stream()
                .filter(entity -> !KEPT.containsKey(entity))
                .filter(entity -> !erases(source, erasure, entity))
                .sorted().toList();

        assertThat(uncovered).as("""
                사람에게 매달린 표가 파기에서 빠졌다.

                PrivacyService.eraseUserData 에 그 표를 지우는 한 줄을 더해라.
                정말 남겨야 하는 것이면 ErasureCoverageTest.KEPT 에 **사유와 함께** 적어라 —
                사유를 못 쓰겠으면 그건 남길 이유가 없다는 뜻이다.

                지킴이 표 열 개가 이렇게 빠져 있었고, guardian_transaction 에는 가맹점명과
                금액이 그대로 남아 있었다(2026-08-20).""")
                .isEmpty();
    }

    /** KEPT 에 죽은 이름이 쌓이면 목록이 알리바이가 된다 — 실재하는 엔티티만 남긴다. */
    @Test
    @DisplayName("남기기로 한 이름은 실재하는 엔티티다")
    void keptNamesAreReal() throws IOException {
        Set<String> entities = Set.copyOf(userScopedEntities());
        assertThat(entities).as("KEPT 에 적힌 이름이 더 이상 없다 — 지워라").containsAll(KEPT.keySet());
    }

    /**
     * <b>이 시험이 정말 뭔가를 세고 있는가.</b> 목록이 비면 위 검사는 언제나 초록불이고,
     * 아무도 그 사실을 모른다 — 검사기가 검사 대상을 잃는 것이 가장 조용한 실패다.
     */
    @Test
    @DisplayName("검사 대상이 비어 있지 않다")
    void thereIsSomethingToCheck() throws IOException {
        assertThat(userScopedEntities())
                .as("userId 를 든 엔티티를 하나도 못 찾았다 — 탐지 규칙이 깨졌다")
                .hasSizeGreaterThan(20);
    }

    /** {@code userId} 를 든 엔티티 — 파기가 책임져야 할 목록이다. */
    private static List<String> userScopedEntities() throws IOException {
        List<String> found = new java.util.ArrayList<>();
        for (Path root : ENTITY_ROOTS) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String src = Files.readString(file);
                    if (!src.contains("@Entity")) continue;
                    // 사람에게 매달렸다는 표시 — 필드든 칼럼 이름이든 하나면 된다.
                    if (src.contains("private Long userId") || src.contains("name = \"user_id\"")) {
                        found.add(file.getFileName().toString().replace(".java", ""));
                    }
                }
            }
        }
        return found;
    }

    /**
     * 그 엔티티를 파기가 실제로 <b>지우는가</b>.
     *
     * <p>주입만 확인하면 모자란다 — 읽으려고 주입한 리포지토리도 통과해 버린다. 그래서
     * 두 걸음이다: 타입({@code <엔티티>Repository}, 규약이라 예외가 없다)으로 <b>필드 이름을
     * 찾고</b>, 파기 본문이 그 필드에 지우는 호출을 하는지 본다. 필드 이름은 규약을 안 따르므로
     * ({@code SavingsGoalRepository goalRepository}) 이름으로 맞히려 하면 안 된다.
     */
    private static boolean erases(String source, String erasure, String entity) {
        java.util.regex.Matcher declaration = java.util.regex.Pattern
                .compile(entity + "Repository\\s+(\\w+)\\s*;")
                .matcher(source);
        if (!declaration.find()) return false;      // 주입조차 안 돼 있다
        String field = declaration.group(1);
        return java.util.regex.Pattern
                .compile(java.util.regex.Pattern.quote(field) + "\\s*\\.\\s*(delete|detach)")
                .matcher(erasure).find();
    }

    /**
     * 파기가 하는 일 전부 — {@code eraseUserData} 부터 클래스 끝까지.
     *
     * <p>메서드 하나만 잘라내지 않는 이유: 파기는 {@code eraseGuardian} 같은 도우미로 갈라져
     * 있고, 앞으로 더 갈라질 것이다. 잘라내는 규칙이 정교할수록 <b>갈라진 조각을 놓친다</b>.
     */
    private static String erasureBody(String source) {
        int start = source.indexOf("public int eraseUserData");
        if (start < 0) throw new IllegalStateException("eraseUserData 를 못 찾았다 — 이 시험이 무엇을 보는지 다시 정해야 한다");
        return source.substring(start);
    }
}
