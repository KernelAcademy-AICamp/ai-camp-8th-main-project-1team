package com.finntech;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마이그레이션 <b>버전 번호가 겹치지 않는가</b> — 겹치면 운영 기동이 죽는다.
 *
 * <h2>왜 시험이 필요한가 — 나머지 검사가 전부 통과하기 때문이다</h2>
 *
 * 2026-08-11 에 실제로 그런 병합이 있었다. 두 브랜치가 각자 {@code V28} 을 만들었는데,
 * <b>파일 이름이 달라서 git 은 충돌로 보지 않았다.</b> 재 본 결과가 이랬다:
 *
 * <pre>
 *   git 병합   충돌 0건
 *   컴파일     통과
 *   시험       544개 전부 통과
 *   운영 기동  실패 — FlywayException: Found more than one migration with version 28
 * </pre>
 *
 * 시험이 못 잡은 이유가 구조적이다. 시험 프로파일은 <b>H2 인메모리에 {@code ddl-auto:
 * create-drop} 이고 Flyway 가 꺼져 있다</b>({@code application.yml} 기본 프로파일
 * {@code flyway.enabled: false}). Flyway 는 <b>운영 MySQL 프로파일에서만</b> 켜진다.
 * 그래서 초록불을 보고 배포하면 <b>그때 처음 안다.</b>
 *
 * <p>이 시험은 그 구멍만 막는다 — DB 없이 <b>파일 이름만</b> 본다. 그래서 어떤 프로파일에서도
 * 돌고, 브랜치를 합치는 순간 걸린다.
 *
 * <h2>여기서 실패했다면</h2>
 *
 * 겹친 번호 중 <b>아직 운영에 적용되지 않은 쪽</b>을 뒤 번호로 옮긴다(새 파일이므로
 * CLAUDE.md 규칙 3 에 걸리지 않는다 — 규칙 3 은 <b>이미 적용된</b> 파일을 보호한다).
 * 이미 적용된 쪽은 절대 건드리지 않는다.
 *
 * <p>옮긴 뒤에는 <b>develop 을 먼저 병합하고 배포한다.</b> 새 번호가 develop 의 번호들보다
 * 뒤에 놓여야 한다 — 먼저 배포해 버리면 나중에 오는 낮은 번호를 Flyway 가 거부한다
 * ({@code Detected resolved migration not applied to database}). {@code outOfOrder=true} 로
 * 우회하지 않는다. 그 설정은 순서가 정말 중요한 다음 마이그레이션까지 조용히 통과시킨다.
 */
class MigrationVersionTest {

    /** 이름 규약 — {@code V<번호>__<설명>.sql}. Flyway 가 번호를 여기서 읽는다. */
    private static final Pattern NAME = Pattern.compile("^V(\\d+)__[A-Za-z0-9_]+\\.sql$");

    /** 두 모듈을 다 본다. 한쪽만 보면 다른 쪽이 같은 사고를 그대로 낸다. */
    private static final List<String> DIRS = List.of(
            "src/main/resources/db/migration",                  // backend
            "../backend-mydata/src/main/resources/db/migration" // backend-mydata
    );

    @Test
    @DisplayName("같은 버전 번호를 쓰는 마이그레이션이 없다 — 있으면 운영 기동이 죽는다")
    void versionsAreUnique() throws IOException {
        for (String dir : DIRS) {
            Path root = Path.of(dir);
            if (!Files.isDirectory(root)) continue;   // 모듈이 없는 실행 위치에서는 건너뛴다

            Map<Integer, List<String>> byVersion = new LinkedHashMap<>();
            List<String> malformed = new ArrayList<>();
            try (Stream<Path> files = Files.list(root)) {
                files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .forEach(name -> {
                            Matcher m = NAME.matcher(name);
                            if (!m.matches()) {
                                malformed.add(name);
                                return;
                            }
                            byVersion.computeIfAbsent(Integer.parseInt(m.group(1)),
                                    k -> new ArrayList<>()).add(name);
                        });
            }

            assertThat(malformed)
                    .as("%s — Flyway 가 번호를 못 읽는 이름이다(V<번호>__<설명>.sql)", dir)
                    .isEmpty();

            List<String> clashes = byVersion.entrySet().stream()
                    .filter(e -> e.getValue().size() > 1)
                    .map(e -> "V" + e.getKey() + " ← " + String.join(" · ", e.getValue()))
                    .toList();

            assertThat(clashes)
                    .as("""
                        %s 에 같은 버전을 쓰는 파일이 있다.

                        운영 기동이 이렇게 죽는다:
                          FlywayException: Found more than one migration with version <번호>

                        git 은 파일 이름이 달라 충돌로 보지 않고, 시험도 Flyway 를 안 타므로
                        (H2 + ddl-auto: create-drop) 이 시험 말고는 아무도 못 잡는다.

                        고치는 법: 겹친 것 중 **아직 운영에 적용되지 않은** 파일을 뒤 번호로
                        옮긴다. 이미 적용된 파일은 규칙 3 때문에 못 건드린다. 옮긴 뒤에는
                        develop 을 먼저 병합하고 배포한다(낮은 번호가 나중에 오면 Flyway 가
                        또 막는다).
                        """, dir)
                    .isEmpty();

            assertThat(byVersion).as("%s 에 마이그레이션이 하나도 없다 — 경로가 바뀌었나", dir)
                    .isNotEmpty();
        }
    }
}
