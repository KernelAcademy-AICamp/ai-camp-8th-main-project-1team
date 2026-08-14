package com.finntech.ledger;

import com.finntech.domain.SpendingLedger;
import com.finntech.domain.SpendingLedgerDirty;
import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V34 의 칸 이름과 엔티티의 칸 이름이 <b>정확히 같은가</b>.
 *
 * <h2>왜 이런 시험이 필요한가 — 나머지 검사가 전부 통과하기 때문이다</h2>
 *
 * <p>시험 프로파일은 <b>H2 인메모리에 {@code ddl-auto: create-drop} 이고 Flyway 가 꺼져
 * 있다.</b> 즉 시험은 <b>엔티티에서 만든 스키마</b>를 쓰고 마이그레이션 파일을 읽지도 않는다.
 * 운영은 반대로 <b>마이그레이션이 만든 스키마</b>에 {@code ddl-auto: validate} 를 건다.
 * 그래서 둘이 어긋나면 초록불을 보고 배포한 뒤 <b>기동에서 처음 안다</b> — 그때는 이미 적용된
 * 파일이라 고칠 수도 없다(CLAUDE.md 규칙 3).
 *
 * <p>이 시험은 그 구멍의 <b>이름 층</b>만 막는다. DB 없이 파일을 글자로 읽으므로 어떤
 * 프로파일에서도 돌고, 칸을 하나 빠뜨리거나 이름을 다르게 적는 순간 걸린다.
 *
 * <p><b>타입까지는 못 본다.</b> {@code BIT(1)} 대신 {@code BOOLEAN} 을 적는 종류의 사고는
 * 로컬 MySQL 예행({@code docker-compose.prod.local-db.yml} + {@code mysql} 프로파일)이
 * 잡는다. 그 절차를 대신하지 않는다.
 */
class SpendingLedgerSchemaTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V34__spending_ledger.sql");

    /** {@code    칸이름   TYPE ...} — 주석과 제약(PRIMARY KEY·KEY)을 걸러낸 뒤 이름만 집는다. */
    private static final Pattern COLUMN = Pattern.compile(
            "^\\s+([a-z][a-z0-9_]*)\\s+(VARCHAR|BIGINT|INT|DATE|DATETIME|DOUBLE|BIT)\\b");

    @Test
    @DisplayName("spending_ledger — 마이그레이션과 엔티티의 칸이 같다")
    void ledgerColumnsMatch() throws IOException {
        assertThat(columnsOf("spending_ledger"))
                .as("V34 의 spending_ledger 칸과 SpendingLedger 엔티티의 @Column 이 어긋난다")
                .isEqualTo(entityColumnsOf(SpendingLedger.class));
    }

    @Test
    @DisplayName("spending_ledger_dirty — 마이그레이션과 엔티티의 칸이 같다")
    void dirtyColumnsMatch() throws IOException {
        // 대기열의 id 는 @Column 이 없어 Hibernate 기본 이름('id')이 붙는다 — 그것까지 넣고 견준다.
        Set<String> expected = entityColumnsOf(SpendingLedgerDirty.class);
        expected.add("id");
        assertThat(columnsOf("spending_ledger_dirty")).isEqualTo(expected);
    }

    @Test
    @DisplayName("되돌릴 수 없는 문장을 쓰지 않는다 — 배포 실패 시 롤백은 코드만 되돌린다")
    void migrationIsReversible() throws IOException {
        // guard-main 워크플로가 보는 것과 같은 낱말이다. CI 가 막기 전에 여기서 걸린다.
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8).toLowerCase();
        assertThat(sql).doesNotContain("drop table", "drop column", "drop index",
                "rename table", "rename column", "truncate");
    }

    /** 마이그레이션 파일에서 그 표의 칸 이름을 순서대로 집는다. */
    private static Set<String> columnsOf(String table) throws IOException {
        Set<String> columns = new LinkedHashSet<>();
        boolean inside = false;
        for (String raw : Files.readAllLines(MIGRATION, StandardCharsets.UTF_8)) {
            String line = raw.replaceAll("--.*$", "");
            if (line.contains("CREATE TABLE " + table + " (")) {
                inside = true;
                continue;
            }
            if (!inside) continue;
            if (line.startsWith(")")) break;                      // 표 정의 끝
            Matcher matcher = COLUMN.matcher(line);
            if (matcher.find()) columns.add(matcher.group(1));
        }
        assertThat(columns).as("%s 의 칸을 하나도 못 읽었다 — 파일 형식이 바뀌었나", table).isNotEmpty();
        return columns;
    }

    /** 엔티티의 {@code @Column(name=...)} 집합. 이름이 없으면 필드 이름이 곧 칸 이름이다. */
    private static Set<String> entityColumnsOf(Class<?> entity) {
        Set<String> columns = new LinkedHashSet<>();
        for (Field field : entity.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            Column column = field.getAnnotation(Column.class);
            if (column == null) continue;
            columns.add(column.name().isEmpty() ? field.getName() : column.name());
        }
        return columns;
    }
}
