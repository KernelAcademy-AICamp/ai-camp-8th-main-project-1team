package com.finntech.mydata.generation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 생성기 엔드투엔드 — Spring 기동 시 소규모 생성이 실제 사용자·카드·결제(라벨·채널·위치 포함)를 적재하는지.
 * 시드 비활성 → 카탈로그 자동 생성, 전량 생성 결제. 격리 인메모리 H2.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:gen_e2e;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "mydata.seed.enabled=false",
        "mydata.generation.enabled=true",
        // history-days=280로 사용자당 결제가 많아 target/perUser가 사용자 수를 정한다(estimateUserCount).
        // data_split 4종(train/val/test/service)이 사용자별 확률 배정이므로, 충분한 사용자 수를 확보하려면
        // target을 넉넉히(≈12명↑) 준다 — 소규모면 split 종류가 2종으로 줄어 검증이 흔들린다(§13-11).
        "mydata.generation.target-count=45000",   // 빈도 상향(예산비례)으로 사용자당 결제가 많아져 target을 올려 사용자 수(≈20명↑) 확보
        "mydata.generation.merchant-csv-path=target/merchants-e2e.csv"   // 정리 CSV 작성 검증
})
class GenerationEndToEndTest {

    @Autowired JdbcTemplate jdbc;

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void generatorPersistsPaymentsWithLabelChannelLocation() throws Exception {
        int users = count("SELECT COUNT(*) FROM mydata_user WHERE mydata_user_data_split IS NOT NULL");
        int pays = count("SELECT COUNT(*) FROM mydata_payment");
        assertThat(users).isGreaterThan(0);
        assertThat(pays).isGreaterThan(500);

        // 전량 낭비/필수 라벨 + 값 유효
        assertThat(count("SELECT COUNT(*) FROM mydata_payment WHERE mydata_payment_waste_label IS NOT NULL"))
                .isEqualTo(pays);
        assertThat(count("SELECT COUNT(*) FROM mydata_payment " +
                "WHERE mydata_payment_waste_label NOT IN ('WASTE','ESSENTIAL')")).isZero();

        // 채널·상품 채워짐
        assertThat(count("SELECT COUNT(*) FROM mydata_payment WHERE mydata_payment_product_name IS NOT NULL"))
                .isEqualTo(pays);

        // 온라인(전국 본사 위치) + 오프라인(앵커 동 위치) 공존 — 이제 둘 다 위치가 채워진다
        assertThat(count("SELECT COUNT(*) FROM mydata_payment " +
                "WHERE mydata_payment_channel='ONLINE' AND mydata_payment_location_lat IS NOT NULL")).isGreaterThan(0);
        assertThat(count("SELECT COUNT(*) FROM mydata_payment " +
                "WHERE mydata_payment_channel='OFFLINE' AND mydata_payment_location_lat IS NOT NULL")).isGreaterThan(0);

        // 전 결제에 유효 형식 사업자번호(10자리) + 고유 가맹점 집계(번호→주소 조회 소스)
        assertThat(count("SELECT COUNT(*) FROM mydata_payment " +
                "WHERE mydata_payment_business_number IS NOT NULL " +
                "AND LENGTH(mydata_payment_business_number)=10")).isEqualTo(pays);
        int merchants = count("SELECT COUNT(*) FROM mydata_merchant");
        assertThat(merchants).isGreaterThan(0);
        assertThat(count("SELECT COUNT(DISTINCT mydata_payment_business_number) FROM mydata_payment"))
                .isEqualTo(merchants);                                    // 가맹점 = 사업자번호 DISTINCT
        assertThat(count("SELECT COUNT(*) FROM mydata_merchant WHERE address LIKE '%번지'"))
                .isEqualTo(merchants);                                    // 온라인 포함 전부 지번주소 보유

        // 정리 CSV(가맹점명·사업자번호·주소·온라인)가 헤더 + 가맹점 수만큼 기록되고 형식이 유효하다
        var csv = java.nio.file.Path.of("target/merchants-e2e.csv");
        assertThat(java.nio.file.Files.exists(csv)).isTrue();
        var lines = java.nio.file.Files.readAllLines(csv);
        assertThat(lines.get(0)).isEqualTo("가맹점명,사업자등록번호,주소,온라인");
        assertThat(lines).hasSize(merchants + 1);                         // 헤더 + 고유 가맹점
        String sample = lines.get(1);
        assertThat(sample).containsPattern("\\d{3}-\\d{2}-\\d{5}");       // 사업자번호 XXX-YY-ZZZZA
        assertThat(sample).contains("번지");                              // 지번주소
        assertThat(sample).matches(".*,(Y|N)$");                          // 온라인 플래그

        // 데이터 분리 4종
        assertThat(count("SELECT COUNT(DISTINCT mydata_user_data_split) FROM mydata_user " +
                "WHERE mydata_user_data_split IS NOT NULL")).isGreaterThanOrEqualTo(3);

        // 낭비·필수 둘 다 존재(서비스 효과·충동 반영)
        assertThat(count("SELECT COUNT(*) FROM mydata_payment WHERE mydata_payment_waste_label='WASTE'"))
                .isGreaterThan(0);
        assertThat(count("SELECT COUNT(*) FROM mydata_payment WHERE mydata_payment_waste_label='ESSENTIAL'"))
                .isGreaterThan(0);
    }

    @Test
    void 통장거래가_생성시점에_적재된다() {
        int accounts = count("SELECT COUNT(*) FROM mydata_account");
        assertThat(accounts).isGreaterThan(0);

        // 통장이 있는 사람에겐 거래도 있다 — 거래 없는 통장은 잔액을 굴릴 수 없다.
        assertThat(count("SELECT COUNT(DISTINCT mydata_account_id) FROM mydata_account_txn"))
                .isEqualTo(accounts);

        // 출처 5종이 다 나온다. 하나라도 비면 그 계열 생성이 죽은 것이다
        // (예전에 조회 시 계산하던 이체·이자·세금이 여기로 옮겨 왔다).
        for (String src : new String[]{"TRANSFER", "SALARY", "INTEREST", "TAX", "CARD"}) {
            assertThat(count("SELECT COUNT(*) FROM mydata_account_txn "
                    + "WHERE mydata_account_txn_source='" + src + "'"))
                    .as("출처 %s", src).isGreaterThan(0);
        }

        // 카드 출금은 결제의 사본이다 — 건수가 어긋나면 둘이 갈라진 것이다.
        assertThat(count("SELECT COUNT(*) FROM mydata_account_txn WHERE mydata_account_txn_source='CARD'"))
                .isEqualTo(count("SELECT COUNT(*) FROM mydata_payment"));

        // 사본은 원본을 가리킨다. 생성 후 정리 단계가 충돌 사업자번호의 결제를 지울 때
        // 이 열로 통장 사본도 함께 지운다 — 없으면 결제 없는 출금만 통장에 남는다.
        assertThat(count("SELECT COUNT(*) FROM mydata_account_txn "
                + "WHERE mydata_account_txn_source='CARD' AND mydata_account_txn_payment_id IS NULL")).isZero();
        assertThat(count("SELECT COUNT(*) FROM mydata_account_txn t "
                + "LEFT JOIN mydata_payment p ON p.mydata_payment_id = t.mydata_account_txn_payment_id "
                + "WHERE t.mydata_account_txn_source='CARD' AND p.mydata_payment_id IS NULL")).isZero();
        // 나머지 출처는 복제한 결제가 없다.
        assertThat(count("SELECT COUNT(*) FROM mydata_account_txn "
                + "WHERE mydata_account_txn_source<>'CARD' AND mydata_account_txn_payment_id IS NOT NULL")).isZero();

        // 사본은 원본과 **같은 거래**여야 한다. 건수만 맞고 값이 어긋나면 통장과 카드내역이
        // 서로 다른 말을 한다. 특히 금액은 월급 기반 스케일·스냅을 거친 뒤의 값을 복사해야 한다 —
        // 원본 t.amount()를 실으면 배율이 빠져 두 값이 갈라진다.
        String joined = "FROM mydata_account_txn t JOIN mydata_payment p "
                + "ON p.mydata_payment_id = t.mydata_account_txn_payment_id "
                + "WHERE t.mydata_account_txn_source='CARD' AND ";
        assertThat(count("SELECT COUNT(*) " + joined
                + "t.mydata_account_txn_amount <> p.mydata_payment_amount")).as("금액").isZero();
        assertThat(count("SELECT COUNT(*) " + joined
                + "t.mydata_account_txn_date <> p.mydata_payment_date")).as("일시").isZero();
        assertThat(count("SELECT COUNT(*) " + joined
                + "t.mydata_account_txn_description <> p.mydata_payment_merchant_name")).as("적요=가맹점").isZero();
        // 카드 결제는 통장에서 언제나 나가는 돈이다.
        assertThat(count("SELECT COUNT(*) FROM mydata_account_txn "
                + "WHERE mydata_account_txn_source='CARD' AND mydata_account_txn_type<>'WITHDRAWAL'")).isZero();

        // 금액은 부호 없는 절대액이고 종류는 두 가지뿐이다.
        assertThat(count("SELECT COUNT(*) FROM mydata_account_txn WHERE mydata_account_txn_amount <= 0")).isZero();
        assertThat(count("SELECT COUNT(*) FROM mydata_account_txn "
                + "WHERE mydata_account_txn_type NOT IN ('DEPOSIT','WITHDRAWAL')")).isZero();

        // 적요·비고가 비면 통장 화면에 빈 줄이 생긴다.
        assertThat(count("SELECT COUNT(*) FROM mydata_account_txn "
                + "WHERE mydata_account_txn_description IS NULL OR mydata_account_txn_description=''")).isZero();
        assertThat(count("SELECT COUNT(*) FROM mydata_account_txn "
                + "WHERE mydata_account_txn_note IS NULL OR mydata_account_txn_note=''")).isZero();

        // 개설일 이전 거래는 없다. 커트오프 이후(미래) 거래는 있어야 한다 —
        // 결제내역과 같이 미래분까지 적재해 두고 조회가 date<=now로 거른다(§13-11 실시간성).
        assertThat(count("SELECT COUNT(*) FROM mydata_account_txn t JOIN mydata_account a "
                + "ON a.mydata_account_id = t.mydata_account_id "
                + "WHERE t.mydata_account_txn_date < a.mydata_account_opened_date")).isZero();
        assertThat(count("SELECT COUNT(*) FROM mydata_account_txn "
                + "WHERE mydata_account_txn_date > TIMESTAMP '2026-07-21 23:59:59'")).isGreaterThan(0);
    }

    @Test
    void 통장_잔액이_한_번도_마이너스로_내려가지_않는다() {
        // 과소비형·외식형은 카드 지출이 급여를 넘는다(페르소나의 사실). 초기잔액이 그 적자를
        // 감당하지 못하면 통장이 음수로 가는데, 시연에서 마이너스 통장은 의도가 아니다.
        // 예전에는 생성 후 스크립트로 고쳤지만 통장 거래를 적재하는 지금은 그럴 수 없다 —
        // 초기잔액을 바꾸면 잔액에 비례하는 이자가 통째로 낡는다. 그래서 생성이 굴려 보고 정한다.
        record Low(String account, long lowest) {}
        var lows = jdbc.query(
                "SELECT a.mydata_account_id, a.mydata_account_initial_balance, "
                + "  t.mydata_account_txn_date, t.mydata_account_txn_type, t.mydata_account_txn_amount "
                + "FROM mydata_account a JOIN mydata_account_txn t "
                + "  ON t.mydata_account_id = a.mydata_account_id "
                + "ORDER BY a.mydata_account_id, t.mydata_account_txn_date, "
                + "  t.mydata_account_txn_description",
                (rs, i) -> new Object[]{rs.getString(1), rs.getLong(2), rs.getString(4), rs.getLong(5)});

        java.util.Map<String, Long> running = new java.util.LinkedHashMap<>();
        java.util.Map<String, Long> lowest = new java.util.LinkedHashMap<>();
        for (Object[] r : lows) {
            String acc = (String) r[0];
            long bal = running.computeIfAbsent(acc, k -> (Long) r[1]);
            bal += "DEPOSIT".equals(r[2]) ? (Long) r[3] : -(Long) r[3];
            running.put(acc, bal);
            lowest.merge(acc, bal, Math::min);
        }
        assertThat(lowest).isNotEmpty();
        var negative = lowest.entrySet().stream().filter(e -> e.getValue() < 0)
                .map(e -> new Low(e.getKey(), e.getValue())).toList();
        assertThat(negative).as("마이너스로 내려간 통장").isEmpty();
    }
}
