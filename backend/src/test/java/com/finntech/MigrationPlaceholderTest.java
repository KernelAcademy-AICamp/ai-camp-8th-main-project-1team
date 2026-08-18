package com.finntech;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마이그레이션에 <b>Flyway 플레이스홀더로 읽히는 꼴</b>이 없는지 본다.
 *
 * <h2>왜 이 시험이 있나 — 실제로 한 번 막혔다</h2>
 *
 * <p>Flyway 는 SQL 파일에 플레이스홀더 치환을 돌린다. 달러표 뒤 중괄호로 감싼 이름을 찾아
 * 값으로 바꾸는데, <b>주석 안에 있어도 똑같이 찾는다.</b> 값을 못 찾으면 그 파일을 파싱하다
 * 통째로 실패한다:
 *
 * <pre>
 * FlywayException: Unable to parse statement in db/migration/V35__usage_event.sql
 *                  at line 13 col 1. No value provided for placeholder: …
 * </pre>
 *
 * <p>V35 의 머리말이 프론트 코드 예시를 그대로 옮겨 적었다가 이것에 걸렸다. 이 저장소의
 * 마이그레이션은 <b>근거를 길게 적는 관례</b>라 코드 조각을 인용할 일이 잦고, 그러다 보면
 * 또 밟는다.
 *
 * <h2>CI 가 못 잡는다 — 그래서 이 층에서 잡는다</h2>
 *
 * <p>시험은 H2 + {@code ddl-auto: create-drop} + <b>Flyway 꺼짐</b>이다. 마이그레이션 파일을
 * 아예 읽지 않으므로 700개가 다 초록불이어도 <b>운영 기동에서 처음</b> 안다. 그때는 규칙 3
 * 때문에 파일을 못 고치고 새 마이그레이션을 얹어야 한다.
 *
 * <p>파일을 텍스트로 훑는 값싼 시험 하나가 그 구멍을 막는다 —
 * {@link MigrationVersionTest} 가 번호 충돌을 같은 방식으로 막는 것과 같다.
 */
class MigrationPlaceholderTest {

    /** Flyway 의 기본 접두·접미. 이름은 영숫자·점·밑줄·하이픈으로 본다. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^}]*}");

    private static final Path DIR = Path.of("src/main/resources/db/migration");

    @Test
    @DisplayName("마이그레이션 어디에도 플레이스홀더 꼴이 없다 — 주석 안이라도 안 된다")
    void 플레이스홀더_꼴이_없다() throws IOException {
        try (Stream<Path> files = Files.list(DIR)) {
            List<String> offenders = files
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .flatMap(MigrationPlaceholderTest::hits)
                    .toList();

            assertThat(offenders).as("""
                    Flyway 가 이것을 플레이스홀더로 읽어 마이그레이션이 실패한다 — 주석 안이라도.

                    코드 예시를 인용할 때는 그 꼴을 그대로 옮기지 말고 말로 풀어 적는다.
                    (또는 spring.flyway.placeholder-replacement=false 로 끌 수 있지만, 그러면
                     쓰고 있는 다른 곳까지 함께 꺼진다.)

                    시험은 H2 + Flyway 꺼짐이라 이 오류를 운영 기동에서 처음 만난다.
                    그때는 규칙 3 때문에 파일을 못 고치고 새 마이그레이션을 얹어야 한다.""")
                    .isEmpty();
        }
    }

    private static Stream<String> hits(Path file) {
        try {
            String src = Files.readString(file);
            return PLACEHOLDER.matcher(src).results()
                    .map(m -> file.getFileName() + " → " + m.group());
        } catch (IOException e) {
            return Stream.empty();
        }
    }
}
