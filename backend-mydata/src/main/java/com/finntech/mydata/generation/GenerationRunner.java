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
import java.util.Collections;
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
            // 실카드명 카탈로그(§13-11) — 나무위키 '카드 상품' 페이지에서 카드사별 실제 상품명 추출(총 115종).
            // card_code=카드사×카드명 1:1. 한 사람은 서로 다른 card_code만 배정받아(insertCards) 같은 카드명 중복 없음.
            Object[][] cards = { // {code, name, color, companyId}
                // 삼성카드(9001)
                {9101L, "삼성 taptap O", "#1428A0", 9001L}, {9102L, "삼성 taptap S", "#1428A0", 9001L}, {9103L, "삼성 taptap I", "#1428A0", 9001L},
                {9104L, "삼성 iD ON", "#1428A0", 9001L}, {9105L, "삼성 iD ALL", "#1428A0", 9001L}, {9106L, "삼성 iD POCKET", "#1428A0", 9001L},
                {9107L, "삼성 iD SIMPLE", "#1428A0", 9001L}, {9108L, "삼성 iD GLOBAL", "#1428A0", 9001L}, {9109L, "삼성 iD ENERGY", "#1428A0", 9001L},
                {9110L, "삼성 THE iD. TITANIUM", "#1428A0", 9001L}, {9111L, "삼성 THE iD. PLATINUM", "#1428A0", 9001L}, {9112L, "삼성 THE iD. 1st", "#1428A0", 9001L},
                {9113L, "삼성 THE 1", "#1428A0", 9001L}, {9114L, "삼성 RAUME O", "#1428A0", 9001L}, {9115L, "삼성 American Express Reserve", "#1428A0", 9001L},
                {9116L, "삼성 American Express Blue", "#1428A0", 9001L}, {9117L, "삼성 네이버페이 taptap", "#1428A0", 9001L}, {9118L, "삼성 삼성페이 taptap", "#1428A0", 9001L},
                // 신한카드(9002)
                {9201L, "신한 Deep Dream", "#0046FF", 9002L}, {9202L, "신한 Mr.Life", "#0046FF", 9002L}, {9203L, "신한 처음", "#0046FF", 9002L},
                {9204L, "신한 Air One", "#0046FF", 9002L}, {9205L, "신한 Air 1.5", "#0046FF", 9002L}, {9206L, "신한 The BEST", "#0046FF", 9002L},
                {9207L, "신한 The CLASSIC+", "#0046FF", 9002L}, {9208L, "신한 B.Big", "#0046FF", 9002L}, {9209L, "신한 YaY", "#0046FF", 9002L},
                {9210L, "신한 Point Plan", "#0046FF", 9002L}, {9211L, "신한 더모아", "#0046FF", 9002L}, {9212L, "신한 Hey Young", "#0046FF", 9002L},
                {9213L, "신한 플리", "#0046FF", 9002L}, {9214L, "신한 Simple Platinum", "#0046FF", 9002L}, {9215L, "신한 Shopping", "#0046FF", 9002L},
                {9216L, "신한 GLAM", "#0046FF", 9002L},
                // 현대카드(9003)
                {9301L, "현대 the Black", "#111111", 9003L}, {9302L, "현대 the Purple", "#111111", 9003L}, {9303L, "현대 the Red", "#111111", 9003L},
                {9304L, "현대 the Green", "#111111", 9003L}, {9305L, "현대 the Pink", "#111111", 9003L}, {9306L, "현대 the Orange", "#111111", 9003L},
                {9307L, "현대 Summit", "#111111", 9003L}, {9308L, "현대 Copper", "#111111", 9003L}, {9309L, "현대 Velvet", "#111111", 9003L},
                {9310L, "현대 M", "#111111", 9003L}, {9311L, "현대 MM", "#111111", 9003L}, {9312L, "현대 X", "#111111", 9003L},
                {9313L, "현대 Z everyday", "#111111", 9003L}, {9314L, "현대 Z play", "#111111", 9003L}, {9315L, "현대 ZERO Edition3", "#111111", 9003L},
                {9316L, "현대 ZERO Up", "#111111", 9003L},
                // KB국민카드(9004)
                {9401L, "KB국민 WE:SH", "#FFBC00", 9004L}, {9402L, "KB국민 톡톡", "#FFBC00", 9004L}, {9403L, "KB국민 노리", "#FFBC00", 9004L},
                {9404L, "KB국민 청춘대로", "#FFBC00", 9004L}, {9405L, "KB국민 마이핏", "#FFBC00", 9004L}, {9406L, "KB국민 Easy Pick", "#FFBC00", 9004L},
                {9407L, "KB국민 Easy all", "#FFBC00", 9004L}, {9408L, "KB국민 Easy On", "#FFBC00", 9004L}, {9409L, "KB국민 다담", "#FFBC00", 9004L},
                {9410L, "KB국민 펫코노미", "#FFBC00", 9004L}, {9411L, "KB국민 탄탄대로", "#FFBC00", 9004L}, {9412L, "KB국민 굿데이", "#FFBC00", 9004L},
                {9413L, "KB국민 The Easy", "#FFBC00", 9004L}, {9414L, "KB국민 Liiv M", "#FFBC00", 9004L}, {9415L, "KB국민 Get100", "#FFBC00", 9004L},
                {9416L, "KB국민 트래블러스", "#FFBC00", 9004L},
                // 롯데카드(9005)
                {9501L, "롯데 LOCA", "#DA291C", 9005L}, {9502L, "롯데 LOCA Classic", "#DA291C", 9005L}, {9503L, "롯데 LOCA Platinum", "#DA291C", 9005L},
                {9504L, "롯데 LOCA 365", "#DA291C", 9005L}, {9505L, "롯데 LOCA For", "#DA291C", 9005L}, {9506L, "롯데 LOCA In", "#DA291C", 9005L},
                {9507L, "롯데 LOCA 나누기", "#DA291C", 9005L}, {9508L, "롯데 LOCA LIKIT", "#DA291C", 9005L}, {9509L, "롯데 디지로카 City", "#DA291C", 9005L},
                {9510L, "롯데 디지로카 발견", "#DA291C", 9005L}, {9511L, "롯데 LIKIT ALL", "#DA291C", 9005L}, {9512L, "롯데 LIKIT FUN", "#DA291C", 9005L},
                {9513L, "롯데 LIKIT ON", "#DA291C", 9005L}, {9514L, "롯데 포인트플러스", "#DA291C", 9005L}, {9515L, "롯데 AVENUEL", "#DA291C", 9005L},
                {9516L, "롯데 Hilton Honors", "#DA291C", 9005L},
                // 우리카드(9006)
                {9601L, "우리 카드의정석", "#0067AC", 9006L}, {9602L, "우리 카드의정석2 DAILY", "#0067AC", 9006L}, {9603L, "우리 카드의정석2 SUPER", "#0067AC", 9006L},
                {9604L, "우리 카드의정석2 SHOPPER", "#0067AC", 9006L}, {9605L, "우리 카드의정석2 AUTO", "#0067AC", 9006L}, {9606L, "우리 카드의정석2 SIMPLE", "#0067AC", 9006L},
                {9607L, "우리 카드의정석2 ROUTINE", "#0067AC", 9006L}, {9608L, "우리 EVERY WON POINT", "#0067AC", 9006L}, {9609L, "우리 UniMile", "#0067AC", 9006L},
                {9610L, "우리 TWO CHAIRS W", "#0067AC", 9006L}, {9611L, "우리 the OPUS", "#0067AC", 9006L}, {9612L, "우리 DA@카드의정석", "#0067AC", 9006L},
                {9613L, "우리 우리WON모바일", "#0067AC", 9006L}, {9614L, "우리 위비트래블", "#0067AC", 9006L}, {9615L, "우리 ROYAL BLUE", "#0067AC", 9006L},
                {9616L, "우리 K-패스", "#0067AC", 9006L},
                // 하나카드(9007)
                {9701L, "하나 트래블로그", "#008485", 9007L}, {9702L, "하나 트래블로그 PRESTIGE", "#008485", 9007L}, {9703L, "하나 원더", "#008485", 9007L},
                {9704L, "하나 1Q Daily", "#008485", 9007L}, {9705L, "하나 1Q Living", "#008485", 9007L}, {9706L, "하나 1Q Shopping", "#008485", 9007L},
                {9707L, "하나 1Q Special", "#008485", 9007L}, {9708L, "하나 MULTI", "#008485", 9007L}, {9709L, "하나 MULTI Any", "#008485", 9007L},
                {9710L, "하나 MULTI On", "#008485", 9007L}, {9711L, "하나 MULTI Oil", "#008485", 9007L}, {9712L, "하나 MULTI Global", "#008485", 9007L},
                {9713L, "하나 #tag1 Orange", "#008485", 9007L}, {9714L, "하나 #tag1 Navy", "#008485", 9007L}, {9715L, "하나 CLUB SK", "#008485", 9007L},
                {9716L, "하나 VIVA G", "#008485", 9007L}, {9717L, "하나 my pass", "#008485", 9007L},
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
        // 중복 카드명 방지(§13-11): 한 사람이 같은 카드사의 같은 카드를 카드번호만 바꿔 여러 장 갖는 건 비현실적.
        // 카탈로그(card_code=카드사×카드명 1:1)를 셔플해 서로 다른 card_code를 cardCount개 뽑는다(복원추출 금지).
        List<Long> pool = new ArrayList<>(cardCodes);
        Collections.shuffle(pool, r);
        int n = Math.min(u.cardCount(), pool.size());
        List<String> ids = new ArrayList<>(n);
        for (int c = 0; c < n; c++) {
            String cardId = String.format("%04d-%04d-%04d-%04d",
                    r.nextInt(10000), r.nextInt(10000), r.nextInt(10000), r.nextInt(10000));
            long code = pool.get(c);
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
