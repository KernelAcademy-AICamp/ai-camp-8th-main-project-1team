package com.finntech.mydata.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 대량 마이데이터 생성 오케스트레이터 — {@code mydata.generation.enabled=true}일 때만 실행.
 * 인구 생성 → 사용자·카드·결제(하루활동 시뮬레이터)를 JDBC 배치로 적재. 결정론(마스터 시드).
 * 실제 11M 생성은 이 컴포넌트를 켜고 애플리케이션을 기동하면 컴퓨터가 수행(Claude 사용량과 분리).
 */
@Component
@Order(100) // 시드 카탈로그(MydataSeedGenerator) 이후
public class GenerationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GenerationRunner.class);

    private static final String USER_SQL = "INSERT INTO mydata_user " +
            "(mydata_user_id, mydata_user_name, mydata_user_social_number, mydata_user_phone_number, " +
            "mydata_user_persona, mydata_user_data_split) VALUES (?,?,?,?,?,?)";
    private static final String CARD_SQL = "INSERT INTO mydata_card " +
            "(mydata_card_id, mydata_user_id, card_code, mydata_card_expiration_date, mydata_card_prev_month_amount) " +
            "VALUES (?,?,?,?,?)";
    private static final String PAY_SQL = "INSERT INTO mydata_payment " +
            "(mydata_payment_id, mydata_card_id, mydata_payment_date, mydata_payment_category1, " +
            "mydata_payment_category2, mydata_payment_amount, mydata_payment_merchant_name, " +
            "mydata_payment_received_benefit_amount, mydata_payment_channel, mydata_payment_product_name, " +
            "mydata_payment_product_price, mydata_payment_quantity, mydata_payment_waste_label, " +
            "mydata_payment_discretionary_score, mydata_payment_location_address, " +
            "mydata_payment_location_lat, mydata_payment_location_lng) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private final JdbcTemplate jdbc;
    private final PopulationBuilder population;
    private final DailyActivitySimulator simulator;
    private final CatalogLoader catalog;
    private final GenerationProperties props;

    public GenerationRunner(JdbcTemplate jdbc, PopulationBuilder population,
                            DailyActivitySimulator simulator, CatalogLoader catalog,
                            GenerationProperties props) {
        this.jdbc = jdbc;
        this.population = population;
        this.simulator = simulator;
        this.catalog = catalog;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isEnabled()) return;
        Integer already = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mydata_user WHERE mydata_user_data_split IS NOT NULL", Integer.class);
        if (already != null && already > 0) {
            log.info("[generation] 이미 생성된 데이터({}명) 존재 → 건너뜀(재생성하려면 DB 정리 후)", already);
            return;
        }
        long t0 = System.currentTimeMillis();
        int userCount = estimateUserCount();
        List<Long> cardCodes = ensureCardCatalog();
        log.info("[generation] 시작 — 목표 {}건, 추정 사용자 {}명, 시드 {}",
                props.getTargetCount(), userCount, props.getSeed());

        List<GeneratedUser> users = population.build(props.getSeed(), userCount);
        long payTotal = 0;
        int done = 0;
        for (GeneratedUser u : users) {
            insertUser(u);
            List<String> cardIds = insertCards(u, cardCodes);
            payTotal += insertPayments(u, cardIds);
            if (++done % 2000 == 0) {
                log.info("[generation] {}/{}명, 결제 {}건 ({}s)",
                        done, users.size(), payTotal, (System.currentTimeMillis() - t0) / 1000);
            }
        }
        log.info("[generation] 완료 — 사용자 {}명 · 결제 {}건 · {}s",
                users.size(), payTotal, (System.currentTimeMillis() - t0) / 1000);
        logSummary();
    }

    /** 생성 후 분포 리포트(검증용) — 로그로 페르소나·낭비율·채널·표본을 남긴다. */
    private void logSummary() {
        try {
            log.info("[generation] === 요약 리포트 ===");
            jdbc.query("SELECT mydata_user_persona, COUNT(*) c FROM mydata_user " +
                    "WHERE mydata_user_data_split IS NOT NULL GROUP BY mydata_user_persona",
                    (RowCallbackHandler) rs ->
                    log.info("[generation]  페르소나 {} : {}명", rs.getString(1), rs.getInt(2)));
            Double waste = jdbc.queryForObject("SELECT 100.0*SUM(CASE WHEN mydata_payment_waste_label='WASTE' " +
                    "THEN 1 ELSE 0 END)/COUNT(*) FROM mydata_payment", Double.class);
            Double online = jdbc.queryForObject("SELECT 100.0*SUM(CASE WHEN mydata_payment_channel='ONLINE' " +
                    "THEN 1 ELSE 0 END)/COUNT(*) FROM mydata_payment", Double.class);
            log.info("[generation]  낭비율 {}% · 온라인 {}%", fmt(waste), fmt(online));
            log.info("[generation]  샘플 가맹점:");
            jdbc.query("SELECT DISTINCT mydata_payment_merchant_name FROM mydata_payment " +
                    "WHERE mydata_payment_channel='OFFLINE' LIMIT 8",
                    (RowCallbackHandler) rs -> log.info("[generation]    {}", rs.getString(1)));
        } catch (RuntimeException e) {
            log.warn("[generation] 요약 리포트 생략: {}", e.getMessage());
        }
    }

    private static String fmt(Double d) { return d == null ? "-" : String.format("%.1f", d); }

    /** 목표 건수 / 사용자당 평균 건수(가중 txPerMonth × 지평/30). */
    private int estimateUserCount() {
        double avgPerMonth = 0;
        for (var p : catalog.personas()) {
            avgPerMonth += p.populationShare() * p.txPerMonthMean();
        }
        double perUser = Math.max(1, avgPerMonth * (props.getHistoryDays() / 30.0));
        return (int) Math.max(1, Math.round(props.getTargetCount() / perUser));
    }

    /**
     * 카드 카탈로그 확보 — 없으면 생성(§13-11 개선: 실제 카드사·대표 카드 상품명·브랜드색).
     * 마이데이터에 "삼성 taptap O", "신한 Deep Dream" 같은 실카드명이 뜨도록 한다. 사용자는 insertCards에서
     * 이 카탈로그의 카드를 무작위 배정받아 여러 카드사 카드를 보유한다(더미 학습용 — 실사·제휴 없음).
     */
    private List<Long> ensureCardCatalog() {
        List<Long> codes = jdbc.queryForList("SELECT card_code FROM card", Long.class);
        if (codes.isEmpty()) {
            Object[][] companies = {
                {9001L, "삼성카드"}, {9002L, "신한카드"}, {9003L, "현대카드"}, {9004L, "KB국민카드"},
                {9005L, "롯데카드"}, {9006L, "우리카드"}, {9007L, "하나카드"},
            };
            for (Object[] co : companies) {
                jdbc.update("INSERT INTO card_company (card_company_id, card_company_name) VALUES (?,?)", co[0], co[1]);
            }
            Object[][] cards = { // {code, name, color, companyId}
                {9101L, "삼성 taptap O", "#1428A0", 9001L}, {9102L, "삼성 iD ON", "#0033A0", 9001L}, {9103L, "삼성 taptap S", "#00A9E0", 9001L},
                {9111L, "신한 Deep Dream", "#0046FF", 9002L}, {9112L, "신한 Mr.Life", "#1A1A1A", 9002L}, {9113L, "신한 Deep On", "#5B2E8C", 9002L},
                {9121L, "현대카드 M", "#111111", 9003L}, {9122L, "현대카드 ZERO Edition2", "#E60012", 9003L}, {9123L, "현대카드 X", "#4A4A4A", 9003L},
                {9131L, "KB 톡톡Pay", "#FFBC00", 9004L}, {9132L, "KB 나라사랑", "#00857D", 9004L}, {9133L, "KB My WE:SH", "#7A5FFF", 9004L},
                {9141L, "롯데 LOCA 365", "#DA291C", 9005L}, {9142L, "롯데 아임원", "#ED1C24", 9005L},
                {9151L, "우리 카드의정석", "#0067AC", 9006L}, {9152L, "우리 DA@카드", "#00A9CE", 9006L},
                {9161L, "하나 원큐데일리", "#008485", 9007L}, {9162L, "하나 트래블로그", "#00C0B5", 9007L},
            };
            for (Object[] c : cards) {
                jdbc.update("INSERT INTO card (card_code, card_name, card_color, card_company_id) VALUES (?,?,?,?)",
                        c[0], c[1], c[2], c[3]);
            }
            codes = jdbc.queryForList("SELECT card_code FROM card", Long.class);
        }
        return codes;
    }

    private void insertUser(GeneratedUser u) {
        var v = u.variant();
        jdbc.update(USER_SQL, u.id(), v.baseName() + "_" + Long.toHexString(u.userSeed()).substring(0, 4),
                "900101-1000000", "010-0000-0000", v.baseName(), u.dataSplit());
    }

    private List<String> insertCards(GeneratedUser u, List<Long> cardCodes) {
        Random r = GenSeed.rng(u.userSeed(), 7);
        List<String> ids = new ArrayList<>(u.cardCount());
        for (int c = 0; c < u.cardCount(); c++) {
            String cardId = String.format("%04d-%04d-%04d-%04d",
                    r.nextInt(10000), r.nextInt(10000), r.nextInt(10000), r.nextInt(10000));
            long code = cardCodes.get(r.nextInt(cardCodes.size()));
            jdbc.update(CARD_SQL, cardId, u.id(), code, Date.valueOf(LocalDate.of(2030, 12, 31)),
                    (int) Math.min(Integer.MAX_VALUE, u.variant().monthlyTotalMean()));
            ids.add(cardId);
        }
        return ids;
    }

    private long insertPayments(GeneratedUser u, List<String> cardIds) {
        LocalDate end = u.startDate().plusDays(props.getHistoryDays());
        List<GenTxn> txns = simulator.simulate(u, end);
        List<Object[]> batch = new ArrayList<>(txns.size());
        int seq = 0;
        for (GenTxn t : txns) {
            String payId = "g" + u.id().substring(0, 16) + "-" + (seq++);
            String cardId = cardIds.get(Math.min(t.cardSlot(), cardIds.size() - 1));
            batch.add(new Object[]{
                    payId, cardId, Timestamp.valueOf(t.date()), t.category1(), t.category2(),
                    t.amount(), t.merchant(), 0, t.channel(), t.productName(), t.productPrice(),
                    t.quantity(), t.wasteLabel(), t.discretionaryScore(), t.address(), t.lat(), t.lon()
            });
        }
        jdbc.batchUpdate(PAY_SQL, batch);
        return batch.size();
    }
}
