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
            "mydata_payment_location_lat, mydata_payment_location_lng, mydata_payment_business_number) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String ACCOUNT_SQL = "INSERT INTO mydata_account " +
            "(mydata_account_id, mydata_user_id, mydata_account_bank, mydata_account_product, " +
            "mydata_account_salary_payer, mydata_account_opened_date, mydata_account_salary, " +
            "mydata_account_payday, mydata_account_initial_balance) VALUES (?,?,?,?,?,?,?,?,?)";

    /** 입출금 통장 카탈로그(§13-11) — {은행, 상품명, 계좌번호형식('#'=랜덤숫자)}. 금융결제원 CMS 자리수 참조. */
    // 계좌번호 형식 — 금융결제원 CMS 계좌번호체계(2026.05.08)의 은행별 '보통예금' 행에 맞춘다.
    // 리터럴 숫자 = 과목코드(보통)·단축코드(PDF 지정), '#' = 랜덤숫자(점번호·일련번호·검증번호).
    // 예) 우리 SYYY-…: 단축S=1·과목YYY=006 → 1006 / 신한 YYY-…: 과목 100 / 농협 YYY-…: 과목 301.
    // (수협·케이뱅크는 PDF가 보통 과목코드를 지정·검증하지 않음 → 과목 자리도 랜덤.)
    private static final String[][] ACCOUNTS = {
        {"한국산업은행", "KDB Hi 입출금통장", "013-####-####-###"},
        {"NH농협은행", "NH주거래우대통장", "301-####-####-##"},
        {"NH농협은행", "NH1934우대통장", "351-####-####-##"},
        {"신한은행", "신한 주거래 미래설계통장", "100-###-######"},
        {"우리은행", "우월한 월급 통장", "1006-###-######"},
        {"우리은행", "WON통장", "1006-###-######"},
        {"우리은행", "우리 SUPER주거래 통장", "1006-###-######"},
        {"SC제일은행", "내월급통장", "###-10-######"},
        {"SC제일은행", "제일EZ통장", "###-10-######"},
        {"SC제일은행", "SC제일Hi통장", "###-10-######"},
        {"하나은행", "달달 하나 통장", "105-######-###05"},
        {"하나은행", "원픽 통장", "110-######-###05"},
        {"IBK기업은행", "IBK중기근로자급여파킹통장", "001-01-#######"},
        {"IBK기업은행", "IBK주거래생활금융통장", "001-01-#######"},
        {"IBK기업은행", "IBK간편한통장", "001-01-#######"},
        {"KB국민은행", "KB스타통장", "400401-##-######"},
        {"KB국민은행", "KB모임금고", "272701-##-######"},
        {"Sh수협은행", "Sh평생주거래우대통장", "101#-####-####"},
        {"Sh수협은행", "Sh내가만든통장", "201#-####-####"},
        {"Sh수협은행", "잇딴주머니통장", "101#-####-####"},
        {"iM뱅크", "iM스마트통장", "505-##-######-#"},
        {"BNK부산은행", "마!이통장", "101-####-####-##"},
        {"광주은행", "매일이자Wa파킹통장", "112-10##-######"},
        {"광주은행", "365파킹통장", "112-10##-######"},
        {"제주은행", "J간편한통장", "700-###-######"},
        {"전북은행", "JB 언택트 통장", "###-02-######-#"},
        {"전북은행", "씨드모아 통장", "###-13-######-#"},
        {"BNK경남은행", "BNK파킹통장", "###-07-######-#"},
        {"케이뱅크", "생활통장", "100-1##-######"},
        {"케이뱅크", "사장님통장", "100-2##-######"},
        {"카카오뱅크", "카카오뱅크 통장", "3333-##-#######"},
        {"토스뱅크", "토스뱅크 통장", "100#-####-####"},
    };

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
            List<GenTxn> txns = simulator.simulate(u, u.startDate().plusDays(props.getHistoryDays()));
            EconomyPlan econ = planEconomy(u, txns);          // 월급·지출률·금액 스케일·통장 산출
            payTotal += insertPayments(u, cardIds, txns, econ.scale());
            insertAccount(u, econ);
            if (++done % 2000 == 0) {
                log.info("[generation] {}/{}명, 결제 {}건 ({}s)",
                        done, users.size(), payTotal, (System.currentTimeMillis() - t0) / 1000);
            }
        }
        log.info("[generation] 완료 — 사용자 {}명 · 결제 {}건 · {}s",
                users.size(), payTotal, (System.currentTimeMillis() - t0) / 1000);
        populateMerchants();
        logSummary();
    }

    /**
     * 고유 가맹점 집계 — 결제에서 사업자번호 DISTINCT로 {가맹점명·지번주소·좌표}를 뽑아 mydata_merchant에 채운다.
     * 번호·주소·좌표는 신원에서 결정론 파생돼 사업자번호당 상수라, 대표 표시명(MIN)만 골라도 일관된다.
     * 사용자의 '번호→주소' 조회와 정리 CSV의 소스.
     */
    private void populateMerchants() {
        jdbc.update("DELETE FROM mydata_merchant");
        int n = jdbc.update(
                "INSERT INTO mydata_merchant (business_number, merchant_name, address, lat, lng, online) " +
                "SELECT mydata_payment_business_number, MIN(mydata_payment_merchant_name), " +
                "MIN(mydata_payment_location_address), MIN(mydata_payment_location_lat), " +
                "MIN(mydata_payment_location_lng), " +
                "MAX(CASE WHEN mydata_payment_channel = 'ONLINE' THEN 1 ELSE 0 END) " +
                "FROM mydata_payment WHERE mydata_payment_business_number IS NOT NULL " +
                "GROUP BY mydata_payment_business_number");
        log.info("[generation] 고유 가맹점 {}건 집계 → mydata_merchant", n);
        if (!props.getMerchantCsvPath().isBlank()) writeMerchantCsv(props.getMerchantCsvPath());
    }

    /** 정리 CSV(가맹점명·사업자등록번호·주소·온라인) 작성 — mydata_merchant를 스트리밍해 쓴다. */
    private void writeMerchantCsv(String path) {
        try {
            java.nio.file.Path out = java.nio.file.Path.of(path);
            if (out.getParent() != null) java.nio.file.Files.createDirectories(out.getParent());
            java.io.BufferedWriter w = java.nio.file.Files.newBufferedWriter(
                    out, java.nio.charset.StandardCharsets.UTF_8);
            w.write("가맹점명,사업자등록번호,주소,온라인\n");
            int[] cnt = {0};
            jdbc.query("SELECT merchant_name, business_number, address, online FROM mydata_merchant " +
                    "ORDER BY business_number", (RowCallbackHandler) rs -> {
                try {
                    String biz = rs.getString("business_number");
                    String bizFmt = (biz != null && biz.length() == 10)
                            ? biz.substring(0, 3) + "-" + biz.substring(3, 5) + "-" + biz.substring(5) : biz;
                    w.write(csvField(rs.getString("merchant_name")) + "," + bizFmt + ","
                            + csvField(rs.getString("address")) + "," + (rs.getBoolean("online") ? "Y" : "N") + "\n");
                    cnt[0]++;
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            });
            w.close();
            log.info("[generation] 정리 CSV {}건 → {}", cnt[0], path);
        } catch (Exception e) {
            log.warn("[generation] 정리 CSV 작성 실패: {}", e.getMessage());
        }
    }

    /** CSV 필드 이스케이프(콤마·따옴표 포함 시 큰따옴표로 감싸고 내부 따옴표는 중복). */
    private static String csvField(String s) {
        if (s == null) return "";
        return (s.contains(",") || s.contains("\"") || s.contains("\n"))
                ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
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

    /** 결제 적재 — 금액에 통장 보정 스케일을 곱해(월지출≈월급×지출률) 다시 스냅한 뒤 배치 삽입. */
    private long insertPayments(GeneratedUser u, List<String> cardIds, List<GenTxn> txns, double scale) {
        Random ar = GenSeed.rng(u.userSeed(), 91);   // 금액 스냅용(결정론)
        List<Object[]> batch = new ArrayList<>(txns.size());
        int seq = 0;
        for (GenTxn t : txns) {
            String payId = "g" + u.id().substring(0, 16) + "-" + (seq++);
            String cardId = cardIds.get(Math.min(t.cardSlot(), cardIds.size() - 1));
            int amount = DailyActivitySimulator.snapAmount(
                    Math.max(100, (int) Math.round(t.amount() * scale)), ar);
            batch.add(new Object[]{
                    payId, cardId, Timestamp.valueOf(t.date()), t.category1(), t.category2(),
                    amount, t.merchant(), 0, t.channel(), t.productName(), t.productPrice(),
                    t.quantity(), t.wasteLabel(), t.discretionaryScore(), t.address(), t.lat(), t.lon(),
                    t.businessNumber()
            });
        }
        jdbc.batchUpdate(PAY_SQL, batch);
        return batch.size();
    }

    // ── 통장·월급·지출 보정(§13-11 경제 모델) ──────────────────────────────
    /** 월급 입금처 회사 목록(부가통신사업자, generation/companies.txt) — 최초 1회 로드. */
    private List<String> companies;

    private List<String> companies() {
        if (companies == null) {
            try (var in = getClass().getResourceAsStream("/generation/companies.txt")) {
                companies = new java.io.BufferedReader(new java.io.InputStreamReader(
                        java.util.Objects.requireNonNull(in), java.nio.charset.StandardCharsets.UTF_8))
                        .lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
            } catch (Exception e) {
                log.warn("[generation] companies.txt 로드 실패 → 기본값 사용: {}", e.getMessage());
                companies = List.of("(주)핀테크", "주식회사 데모컴퍼니");
            }
        }
        return companies;
    }

    /** {계좌번호, 은행, 상품, 월급입금처, 월급, 월급날, 초기잔액, 금액스케일}. */
    private record EconomyPlan(String accountNumber, String bank, String product, String salaryPayer,
                               int salary, int payday, long initialBalance, double scale) {}

    /**
     * 월급·지출률로 카드 지출을 현실화한다.
     *  - 월급 = 페르소나 기준액 × 개인편차, 10만원 단위, [210만(최저임금), 1200만] 클램프.
     *  - 지출률 = 0.55 + 낭비율×1.5 (낭비 적으면 <1: 흑자, 많으면 >1: 적자).
     *  - 목표 총지출 = 월급 × 지출률 × 개월수 → 원지출 대비 스케일. → 필수지출<월급, 낭비가 지출을 월급 위로.
     */
    private EconomyPlan planEconomy(GeneratedUser u, List<GenTxn> txns) {
        Random r = GenSeed.rng(u.userSeed(), 90);
        long raw = 0, waste = 0;
        for (GenTxn t : txns) { raw += t.amount(); if ("WASTE".equals(t.wasteLabel())) waste += t.amount(); }
        double wasteRatio = raw > 0 ? (double) waste / raw : 0.0;

        // 월급 = 페르소나 기준액 × 개인편차. 대부분 통상 수준(0.7~1.6배)이되, 8%는 고소득(추가 1.6~2.8배).
        // 10만원 단위, [210만(최저임금)~1200만] 클램프 → 최저임금~고소득까지 넓은 현실 분포.
        int base = baseSalary(u.variant().baseName());
        double f = GenSeed.uniform(r, 0.7, 1.6);
        if (r.nextDouble() < 0.08) f *= GenSeed.uniform(r, 1.6, 2.8);   // 소수의 고소득자
        int salary = (int) Math.min(12_000_000L, Math.max(2_100_000L,
                Math.round(base * f / 100_000.0) * 100_000L));
        double spendRatio = Math.max(0.5, Math.min(1.5, 0.55 + wasteRatio * 1.5));
        double months = Math.max(1.0, props.getHistoryDays() / 30.0);
        double targetTotal = (double) salary * spendRatio * months;
        double scale = raw > 0 ? targetTotal / raw : 1.0;

        int payday = 1 + r.nextInt(28);
        long initialBalance = Math.round(salary * GenSeed.uniform(r, 0.3, 12.0) / 100_000.0) * 100_000L;
        String[] a = ACCOUNTS[r.nextInt(ACCOUNTS.length)];
        String accountNumber = fillAccountNumber(a[2], r);
        List<String> cos = companies();
        String payer = cos.get(r.nextInt(cos.size()));   // 월급 입금처(회사명) 랜덤
        return new EconomyPlan(accountNumber, a[0], a[1], payer, salary, payday, initialBalance, scale);
    }

    private static int baseSalary(String persona) {
        return switch (persona) {   // 통상 노동자 수준(2025 중위 ~282만·평균 ~350만)에 페르소나별 소폭 차등
            case "절약형" -> 2_600_000;
            case "균형형" -> 3_200_000;
            case "과소비형" -> 3_900_000;
            case "구독과다형" -> 3_400_000;
            case "외식형" -> 3_600_000;
            default -> 3_200_000;
        };
    }

    /** 계좌번호 형식('#'=랜덤숫자)을 채운다. */
    private static String fillAccountNumber(String format, Random r) {
        StringBuilder sb = new StringBuilder(format.length());
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            sb.append(c == '#' ? (char) ('0' + r.nextInt(10)) : c);
        }
        return sb.toString();
    }

    private void insertAccount(GeneratedUser u, EconomyPlan e) {
        jdbc.update(ACCOUNT_SQL, e.accountNumber(), u.id(), e.bank(), e.product(), e.salaryPayer(),
                Date.valueOf(u.startDate()), e.salary(), e.payday(), e.initialBalance());
    }
}
