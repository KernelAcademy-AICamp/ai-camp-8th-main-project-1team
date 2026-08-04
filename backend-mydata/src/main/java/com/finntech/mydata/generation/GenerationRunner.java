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
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            "(mydata_payment_id, mydata_card_id, mydata_payment_date, mydata_payment_ksic_code, " +
            "mydata_payment_category2, mydata_payment_amount, mydata_payment_merchant_name, " +
            "mydata_payment_received_benefit_amount, mydata_payment_channel, mydata_payment_product_name, " +
            "mydata_payment_product_price, mydata_payment_quantity, mydata_payment_waste_label, " +
            "mydata_payment_discretionary_score, mydata_payment_location_address, " +
            "mydata_payment_location_lat, mydata_payment_location_lng, mydata_payment_business_number) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String ACCOUNT_TXN_SQL = "INSERT INTO mydata_account_txn " +
            "(mydata_account_id, mydata_account_txn_date, mydata_account_txn_type, " +
            "mydata_account_txn_amount, mydata_account_txn_description, mydata_account_txn_note, " +
            "mydata_account_txn_source, mydata_account_txn_payment_id) VALUES (?,?,?,?,?,?,?,?)";
    private static final String ACCOUNT_SQL = "INSERT INTO mydata_account " +
            "(mydata_account_id, mydata_user_id, mydata_account_bank, mydata_account_product, " +
            "mydata_account_salary_payer, mydata_account_opened_date, mydata_account_salary, " +
            "mydata_account_payday, mydata_account_initial_balance) VALUES (?,?,?,?,?,?,?,?,?)";

    /**
     * 금액 배율의 하한·상한.
     *
     * <p>상한을 두는 이유는 카탈로그의 가격대를 지키는 것이다. 품목마다 {@code priceLow~priceHigh}가
     * 이미 있는데 그 위에 월급 기반 배율을 무제한으로 곱하면 "1일권 18만원"이 나온다.
     * 1.6배까지는 지역·업체 차이로 읽히지만 그 위는 존재하지 않는 가격이다.
     *
     * <p>배율이 상한에 걸리면 그 사용자의 월지출은 목표에 못 미친다. 그건 받아들인다 —
     * 목표 총액을 맞추려고 없는 가격을 만들어 내는 것보다, 총액이 조금 덜 맞는 편이 낫다.
     */
    private static final double FLEX_SCALE_MIN = 0.1, FLEX_SCALE_MAX = 1.6;

    /**
     * 월급·월 카드지출의 현실 범위 (사용자 결정 2026-07-31).
     *
     * <p>재조정 전에는 월급 평균 449만·월지출 평균 583만이었고, 지출이 100~350만 안에 드는 사용자가
     * 4,511명 중 548명(12%)뿐이었다. 시연에서 '통상 직장인의 소비'로 읽히지 않는다.
     *
     * <p><b>두 상한이 함께 걸린다.</b> 월급만 낮추면 지출률 1.45가 그대로 곱해져 상한을 넘고,
     * 지출만 자르면 월급 높은 사람이 전부 흑자가 된다. 그래서 목표 월지출도 여기서 묶는다.
     */
    private static final long SALARY_MIN = 2_000_000L, SALARY_MAX = 4_000_000L;
    private static final long SPEND_MIN = 1_000_000L, SPEND_MAX = 3_500_000L;

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
    /** 고시요금 여부를 카탈로그에 물어보기 위해 필요하다(contexts.json의 fixedTariff). */
    private final CatalogSampler sampler;

    public GenerationRunner(JdbcTemplate jdbc, PopulationBuilder population,
                            DailyActivitySimulator simulator, CatalogLoader catalog,
                            CatalogSampler sampler, GenerationProperties props) {
        this.jdbc = jdbc;
        this.population = population;
        this.simulator = simulator;
        this.catalog = catalog;
        this.sampler = sampler;
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
        Map<Long, String> cardNameById = cardCompanyNames();
        long payTotal = 0, txnTotal = 0;
        int done = 0;
        for (GeneratedUser u : users) {
            insertUser(u);
            List<IssuedCard> cards = insertCards(u, cardCodes, cardNameById);
            LocalDate genEnd = u.startDate().plusDays(props.getHistoryDays());
            List<GenTxn> txns = simulator.simulate(u, genEnd);
            EconomyPlan econ = planEconomy(u, txns);          // 월급·지출률·금액 스케일·통장 산출
            CardOutflow flow = insertPayments(u, cards, txns, econ.scale());
            payTotal += flow.count();
            // 통장을 먼저 굴려 보고 초기잔액을 확정한다 — 굴려 보기 전에는 마이너스가 되는지 알 수 없다.
            Ledger ledger = buildLedger(econ, u.startDate(), genEnd, flow);
            insertAccount(u, econ, ledger.initialBalance());   // 통장 거래의 FK 대상 — 먼저 넣는다
            txnTotal += insertAccountTxns(econ, ledger, flow);
            if (++done % 2000 == 0) {
                log.info("[generation] {}/{}명, 결제 {}건, 통장거래 {}건 ({}s)",
                        done, users.size(), payTotal, txnTotal, (System.currentTimeMillis() - t0) / 1000);
            }
        }
        log.info("[generation] 완료 — 사용자 {}명 · 결제 {}건 · 통장거래 {}건 · {}s",
                users.size(), payTotal, txnTotal, (System.currentTimeMillis() - t0) / 1000);
        populateMerchants();
        logSummary();
    }

    /**
     * 고유 가맹점 집계 — 결제에서 사업자번호 DISTINCT로 {가맹점명·지번주소·좌표}를 뽑아 mydata_merchant에 채운다.
     * 번호·주소·좌표는 신원에서 결정론 파생돼 사업자번호당 상수라, 대표 표시명(MIN)만 골라도 일관된다.
     * 사용자의 '번호→주소' 조회와 정리 CSV의 소스.
     *
     * <p><b>택시 번호판은 떼고 담는다.</b> 택시 결제의 표시명에는 차량번호가 붙지만
     * ({@code 티머니택시경기31아2122}) 결제하는 가맹점은 브랜드 하나다. 떼지 않으면 가맹점
     * 대표명이 아무 차량의 번호판이 되어, 사업자번호로 조회했을 때 엉뚱한 이름이 나온다.
     */
    private void populateMerchants() {
        jdbc.update("DELETE FROM mydata_merchant");
        int n = jdbc.update(
                "INSERT INTO mydata_merchant (business_number, merchant_name, address, lat, lng, online, ksic_code) " +
                "SELECT mydata_payment_business_number, " +
                "MIN(REGEXP_REPLACE(mydata_payment_merchant_name, " +
                "                   '[가-힣]{2}3[1-6][아바사자][0-9]{4}$', '')), " +
                "MIN(mydata_payment_location_address), MIN(mydata_payment_location_lat), " +
                "MIN(mydata_payment_location_lng), " +
                "MAX(CASE WHEN mydata_payment_channel = 'ONLINE' THEN 1 ELSE 0 END), " +
                // 가맹점의 업종. 앱이 사업자번호로 조회할 때 결제 없이도 분류할 수 있어야 한다.
                // 한 가맹점의 결제는 전부 같은 업종이므로 MIN으로 대표를 골라도 값이 같다.
                "MIN(mydata_payment_ksic_code) " +
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

    /** 발급된 카드 1장 — 통장 출금의 비고에 카드사명이 필요해 함께 들고 다닌다. */
    private record IssuedCard(String id, String company) {}

    private List<IssuedCard> insertCards(GeneratedUser u, List<Long> cardCodes,
                                         Map<Long, String> companyByCode) {
        Random r = GenSeed.rng(u.userSeed(), 7);
        // 중복 카드명 방지(§13-11): 한 사람이 같은 카드사의 같은 카드를 카드번호만 바꿔 여러 장 갖는 건 비현실적.
        // 카탈로그(card_code=카드사×카드명 1:1)를 셔플해 서로 다른 card_code를 cardCount개 뽑는다(복원추출 금지).
        List<Long> pool = new ArrayList<>(cardCodes);
        Collections.shuffle(pool, r);
        int n = Math.min(u.cardCount(), pool.size());
        List<IssuedCard> ids = new ArrayList<>(n);
        for (int c = 0; c < n; c++) {
            String cardId = String.format("%04d-%04d-%04d-%04d",
                    r.nextInt(10000), r.nextInt(10000), r.nextInt(10000), r.nextInt(10000));
            long code = pool.get(c);
            jdbc.update(CARD_SQL, cardId, u.id(), code, Date.valueOf(LocalDate.of(2030, 12, 31)),
                    (int) Math.min(Integer.MAX_VALUE, u.variant().monthlyTotalMean()));
            ids.add(new IssuedCard(cardId, companyByCode.getOrDefault(code, "카드")));
        }
        return ids;
    }

    /** 카드코드 → 카드사명. 사용자마다 다시 묻지 않도록 생성 시작에 한 번만 읽는다. */
    private Map<Long, String> cardCompanyNames() {
        Map<Long, String> out = new HashMap<>();
        jdbc.query("SELECT c.card_code, co.card_company_name FROM card c "
                        + "JOIN card_company co ON co.card_company_id = c.card_company_id",
                (RowCallbackHandler) rs -> out.put(rs.getLong(1), rs.getString(2)));
        return out;
    }

    /**
     * 결제 적재 — 금액에 통장 보정 스케일을 곱해(월지출≈월급×지출률) 다시 스냅한 뒤 배치 삽입.
     *
     * <p><b>고시요금은 스케일에서 뺀다.</b> 지하철·KTX·통신비는 사람이 부자든 아니든 같은 값이다.
     * 여기에 월급 기반 배율을 곱하면 실존하지 않는 요금이 명세서에 찍힌다.
     * 대신 그만큼을 나머지 결제가 흡수해야 월지출 총합이 목표에 맞는다 — 아래에서 스케일을
     * 다시 계산한다.
     *
     * <p><b>단가도 함께 배율한다.</b> 예전에는 {@code amount}에만 스케일을 곱하고
     * {@code productPrice}는 원본을 넣어, 스키마 주석이 약속한 {@code amount ≈ 단가 × 수량}이
     * 깨져 있었다.
     */
    /**
     * 결제 적재의 부산물 — 통장에 옮길 재료. 결제를 다시 읽지 않기 위해 여기서 함께 만든다.
     *
     * @param count      적재한 결제 수
     * @param rows       통장에 복제할 카드 출금(스케일 적용 후 금액)
     * @param outByMonth 월별 카드 지출 합계 — 이자가 붙을 실잔액을 구하는 데 쓴다
     */
    /**
     * @param transitByMonth 월별 <b>대중교통</b> 결제 합계. K-패스 환급액을 구하는 데 쓴다.
     *                       택시·주유·통행료는 K-패스 대상이 아니라 빠진다(사용자 결정 2026-07-31).
     */
    private record CardOutflow(long count, List<AccountTxnGenerator.Row> rows,
                               Map<YearMonth, Long> outByMonth,
                               Map<YearMonth, Long> transitByMonth) {}

    /** K-패스 환급 대상 카테고리. 택시는 제외한다. */
    private static final String TRANSIT_CATEGORY = "대중교통";

    private CardOutflow insertPayments(GeneratedUser u, List<IssuedCard> cards,
                                       List<GenTxn> txns, double scale) {
        Random ar = GenSeed.rng(u.userSeed(), 91);   // 금액 스냅용(결정론)

        // 고시요금분은 그대로 두고, 나머지가 목표 총액을 맞추도록 스케일을 재계산한다.
        long fixedTotal = 0, flexTotal = 0;
        for (GenTxn t : txns) {
            if (isFixedTariff(t)) fixedTotal += t.amount();
            else flexTotal += t.amount();
        }
        long target = Math.round((fixedTotal + flexTotal) * scale);
        // **배율에 상한이 있어야 한다.** 예전에는 하한(0.1)만 있어서, 월급이 큰 사용자는 모든 금액이
        // 몇 배로 부풀었다 — 실측에서 '클라이밍 1일권 181,000원'(카탈로그 20,000~30,000)이 나왔고
        // 수량 1건 결제의 27.7%가 카탈로그 상한을 넘었으며 최대 14.2배까지 벌어졌다.
        //
        // 부유함은 같은 물건을 몇 배 주고 사는 것이 아니다 — **더 비싼 품목**을 고르거나
        // **더 자주** 쓰는 것으로 나타나야 하고, 그 둘은 이미 페르소나(categoryMix·txPerMonth)가 한다.
        // 그래서 금액 배율은 카탈로그 가격대를 크게 벗어나지 않는 범위로 묶는다.
        double flexScale = flexTotal > 0
                ? Math.max(FLEX_SCALE_MIN, Math.min(FLEX_SCALE_MAX, (target - fixedTotal) / (double) flexTotal))
                : 1.0;

        List<Object[]> batch = new ArrayList<>(txns.size());
        List<AccountTxnGenerator.Row> outflow = new ArrayList<>(txns.size());
        Map<YearMonth, Long> outByMonth = new HashMap<>();
        Map<YearMonth, Long> transitByMonth = new HashMap<>();
        int seq = 0;
        for (GenTxn t : txns) {
            String payId = "g" + u.id().substring(0, 16) + "-" + (seq++);
            IssuedCard card = cards.get(Math.min(t.cardSlot(), cards.size() - 1));
            String cardId = card.id();
            int amount, unitPrice;
            if (isFixedTariff(t)) {
                amount = t.amount();
                unitPrice = t.productPrice();
            } else {
                amount = DailyActivitySimulator.snapAmount(
                        Math.max(100, (int) Math.round(t.amount() * flexScale)), ar);
                int qty = Math.max(1, t.quantity());
                unitPrice = Math.max(100, amount / qty);
            }
            batch.add(new Object[]{
                    payId, cardId, Timestamp.valueOf(t.date()), t.industryCode(), t.category2(),
                    amount, t.merchant(), 0, t.channel(), t.productName(), unitPrice,
                    t.quantity(), t.wasteLabel(), t.discretionaryScore(), t.address(), t.lat(), t.lon(),
                    t.businessNumber()
            });
            // 통장 쪽 사본. 적요는 가맹점, 비고는 카드사 — 실제 통장의 카드 출금이 그렇게 찍힌다.
            outflow.add(new AccountTxnGenerator.Row(t.date(), "WITHDRAWAL", amount,
                    t.merchant(), card.company(), "CARD", payId));
            outByMonth.merge(YearMonth.from(t.date()), (long) amount, Long::sum);
            if (TRANSIT_CATEGORY.equals(t.category2())) {
                transitByMonth.merge(YearMonth.from(t.date()), (long) amount, Long::sum);
            }
        }
        jdbc.batchUpdate(PAY_SQL, batch);
        return new CardOutflow(batch.size(), outflow, outByMonth, transitByMonth);
    }

    /** 고시요금 맥락인가 — 카탈로그가 정한다(contexts.json의 fixedTariff). */
    private boolean isFixedTariff(GenTxn t) {
        var ctx = sampler.context(t.category2());
        return ctx != null && ctx.fixedTariff();
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

        // ── 월급 ── 페르소나 기준액 × 개인편차, 10만원 단위, [200만, 400만] 클램프.
        //
        // 예전에는 상한이 1200만이고 8%가 고소득 분기(×1.6~2.8)를 탔다. 그러면 월 카드지출이
        // 평균 583만까지 올라가 "통상 직장인의 소비"로 읽히지 않았다(사용자 결정 2026-07-31).
        // 고소득 분기는 400만 상한과 모순이므로 뺀다.
        int base = baseSalary(u.variant().baseName());
        int salary = (int) Math.min(SALARY_MAX, Math.max(SALARY_MIN,
                Math.round(base * GenSeed.uniform(r, 0.82, 1.22) / 100_000.0) * 100_000L));

        // ── 지출률 ── **페르소나는 월급이 아니라 지출률로 드러낸다.**
        //
        // 예전에는 과소비형이 월급도 제일 높아서, 적자를 만들려면 지출률을 1.5까지 올려야 했다.
        // 월급 상한(400만)과 지출 상한(350만)이 함께 걸리면 그 구조에서는 고소득 과소비형이 전부
        // 흑자가 된다. 월급과 지출률을 떼면 '저소득 과소비형은 적자, 고소득 절약형은 흑자'가
        // 자연히 갈린다. 낭비율이 높을수록 더 쓴다는 관계는 그대로 둔다.
        double spendRatio = Math.max(0.5, Math.min(1.45, 0.5 + wasteRatio * 1.35));
        double months = Math.max(1.0, props.getHistoryDays() / 30.0);
        // 목표 월지출도 [100만, 350만]으로 묶는다 — 두 상한이 함께 걸려야 요구가 지켜진다.
        double monthlyTarget = Math.max(SPEND_MIN, Math.min(SPEND_MAX, salary * spendRatio));
        double targetTotal = monthlyTarget * months;
        double scale = raw > 0 ? targetTotal / raw : 1.0;

        int payday = 1 + r.nextInt(28);
        // ── 초기 잔액 ── 적자면 버틸 만큼 넣어 주고, 흑자면 적게 시작한다.
        //
        // 적자 사용자는 잔액이 계속 줄어드는 것이 정상이다(사용자 결정 2026-07-31). 그런데 시작
        // 잔액이 적으면 관측 기간 중간에 0을 뚫고, 잔액 보정 라운드가 개입해 소비를 깎아 버린다.
        // 그래서 **필요한 만큼 먼저 넣는다** — 총 적자에 여유 1.3~2.2배.
        long monthlyDeficit = Math.max(0L, Math.round(monthlyTarget) - salary);
        long need = Math.round(monthlyDeficit * months * GenSeed.uniform(r, 1.3, 2.2));
        long initialBalance = Math.min(BALANCE_CAP, Math.max(0L,
                Math.round((need + salary * GenSeed.uniform(r, 0.3, 3.0)) / 100_000.0) * 100_000L));
        String[] a = ACCOUNTS[r.nextInt(ACCOUNTS.length)];
        String accountNumber = fillAccountNumber(a[2], r);
        List<String> cos = companies();
        String payer = cos.get(r.nextInt(cos.size()));   // 월급 입금처(회사명) 랜덤
        return new EconomyPlan(accountNumber, a[0], a[1], payer, salary, payday, initialBalance, scale);
    }

    /**
     * 페르소나별 월급 기준액. 2025 중위 ~282만·평균 ~350만 근처에 모으고 차등은 <b>작게</b> 둔다.
     *
     * <p>페르소나의 성격은 {@code spendRatio}(지출률)가 드러낸다. 여기서 크게 벌리면 '과소비형은
     * 원래 많이 번다'가 되어, 적자를 만들려고 지출률을 억지로 올려야 했던 옛 구조로 되돌아간다.
     */
    private static int baseSalary(String persona) {
        return switch (persona) {
            case "절약형" -> 2_900_000;
            case "균형형" -> 3_000_000;
            case "과소비형" -> 2_800_000;
            case "구독과다형" -> 3_000_000;
            case "외식형" -> 3_000_000;
            default -> 3_000_000;
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

    private void insertAccount(GeneratedUser u, EconomyPlan e, long initialBalance) {
        jdbc.update(ACCOUNT_SQL, e.accountNumber(), u.id(), e.bank(), e.product(), e.salaryPayer(),
                Date.valueOf(u.startDate()), e.salary(), e.payday(), initialBalance);
    }

    /** 확정된 통장 — 보정된 초기잔액과 그 잔액으로 만든 거래 전부(날짜순). */
    private record Ledger(long initialBalance, List<AccountTxnGenerator.Row> rows) {}

    /** 초기잔액은 10만원 단위로 올린다 — 어중간한 값은 생성 데이터답지 않다. */
    private static final long BALANCE_UNIT = 100_000L;
    /** 바닥을 친 뒤에도 남겨 둘 여유(월급 배수). 딱 0원에 맞추면 통장이 늘 아슬아슬해 보인다. */
    private static final double MARGIN_MONTHS_MIN = 2.0, MARGIN_MONTHS_MAX = 6.0;
    /** 초기잔액 보정 재시도 상한. 이자가 잔액에 비례해 조금씩 늘어 보통 1회로 수렴한다. */
    private static final int BALANCE_FIX_ROUNDS = 6;   // 위·아래를 번갈아 맞추므로 여유를 둔다
    /**
     * 통장 잔액 상한. 더미 데이터에 수억 원짜리 통장이 보이면 시연이 어색하다.
     * 아래(0)와 위(이 값) 사이에 들어가도록 초기잔액을 조정한다 — 스윙 자체가 이보다 크면
     * <b>0을 지키는 쪽을 택한다</b>. 마이너스 통장이 상한 초과보다 더 어색하기 때문이다.
     */
    private static final long BALANCE_CAP = 20_000_000L;

    /**
     * 통장 거래를 만들고, <b>잔액이 마이너스로 내려가지 않도록 초기잔액을 보정</b>한다.
     *
     * <p><b>왜 여기인가.</b> 과소비형·외식형은 카드 지출이 급여를 크게 넘는다(페르소나의 사실이다).
     * 초기잔액이 그 적자를 감당하지 못하면 통장이 음수로 가는데, 시연에서 마이너스 통장이 보이는 것은
     * 의도가 아니다. 예전에는 생성이 끝난 뒤 {@code scripts/fix-account-balance.py}가 제공자 API로
     * 실제 잔액을 물어 초기잔액을 올렸다. 통장 거래를 <b>적재</b>하는 지금은 그 방식이 성립하지 않는다 —
     * 초기잔액을 올리면 잔액에 비례하는 <b>이자가 통째로 낡기</b> 때문이다. 그래서 굴려 보고 고치는 일을
     * 생성 안으로 들여온다. 여유는 본인 월급의 배수로 줘 결과가 한 구간에 뭉치지 않게 한다.
     *
     * <p>보정하면 이자가 늘어 잔액이 다시 바뀌므로 몇 번 되돌린다. 이자는 월 0.1~2.0%/12라
     * 증가분이 작아 보통 한 번에 수렴한다.
     */
    private Ledger buildLedger(EconomyPlan e, LocalDate opened, LocalDate genEnd, CardOutflow flow) {
        LocalDateTime end = genEnd.atTime(23, 59, 59);
        Random r = new Random(e.accountNumber().hashCode() ^ 0x5eedL);
        double marginMonths = GenSeed.uniform(r, MARGIN_MONTHS_MIN, MARGIN_MONTHS_MAX);

        long initial = e.initialBalance();
        List<AccountTxnGenerator.Row> rows = List.of();
        long goodInitial = -1;                     // 마지막으로 '0 이상'이 확인된 상태
        List<AccountTxnGenerator.Row> goodRows = List.of();

        for (int round = 0; round <= BALANCE_FIX_ROUNDS; round++) {
            rows = AccountTxnGenerator.generate(e.accountNumber(), e.bank(), e.salaryPayer(),
                    opened, e.salary(), e.payday(), initial, end,
                    flow.outByMonth(), flow.transitByMonth());
            long lowest = lowestBalance(initial, rows, flow);
            if (lowest < 0) {                      // 아래가 뚫렸다 — 올린다
                if (round == BALANCE_FIX_ROUNDS) break;
                long need = -lowest + Math.round(e.salary() * marginMonths);
                initial += (need + BALANCE_UNIT - 1) / BALANCE_UNIT * BALANCE_UNIT;
                continue;
            }
            goodInitial = initial; goodRows = rows;   // 여기까지는 안전하다
            long highest = highestBalance(initial, rows, flow);
            if (highest <= BALANCE_CAP || round == BALANCE_FIX_ROUNDS) break;
            // 위가 뚫렸다 — 내린다. 내릴 수 있는 여유가 곧 lowest다(그만큼 내리면 최저가 0).
            long next = (Math.max(initial - lowest, initial - (highest - BALANCE_CAP)))
                    / BALANCE_UNIT * BALANCE_UNIT;
            if (next >= initial) break;            // 더 못 내린다 — 스윙이 상한보다 크다
            initial = next;
        }
        // 내리다가 아래가 뚫렸으면(이자가 줄어 잔액 궤적이 달라진다) 마지막 안전 상태로 되돌린다.
        // **마이너스 통장이 상한 초과보다 나쁘다** — 화면에 음수 잔액이 보이면 안 된다.
        if (goodInitial >= 0 && lowestBalance(initial, rows, flow) < 0) {
            initial = goodInitial;
            rows = goodRows;
        }
        return new Ledger(initial, rows);
    }

    /** 통장·카드 거래를 굴렸을 때의 최고 잔액. 상한(2,000만원)을 지키는지 보는 데 쓴다. */
    private long highestBalance(long initial, List<AccountTxnGenerator.Row> rows, CardOutflow flow) {
        List<AccountTxnGenerator.Row> all = new ArrayList<>(rows);
        all.addAll(flow.rows());
        all.sort(java.util.Comparator.comparing(AccountTxnGenerator.Row::date)
                .thenComparing(AccountTxnGenerator.Row::description));
        long running = initial, highest = initial;
        for (AccountTxnGenerator.Row t : all) {
            running += "DEPOSIT".equals(t.type()) ? t.amount() : -t.amount();
            if (running > highest) highest = running;
        }
        return highest;
    }

    /** 통장·카드 거래를 시간순으로 굴렸을 때의 최저 잔액. 조회가 굴리는 것과 같은 순서여야 한다. */
    private long lowestBalance(long initial, List<AccountTxnGenerator.Row> rows, CardOutflow flow) {
        List<AccountTxnGenerator.Row> all = new ArrayList<>(rows);
        all.addAll(flow.rows());
        all.sort(java.util.Comparator.comparing(AccountTxnGenerator.Row::date)
                .thenComparing(AccountTxnGenerator.Row::description));
        long running = initial, lowest = initial;
        for (AccountTxnGenerator.Row t : all) {
            running += "DEPOSIT".equals(t.type()) ? t.amount() : -t.amount();
            if (running < lowest) lowest = running;
        }
        return lowest;
    }

    /**
     * 통장 거래를 적재한다 — 생성 시점에 만들어 두고 조회는 읽기만 한다(§13-11).
     *
     * <p><b>왜 조회가 아니라 여기인가.</b> 예전에는 통장을 열 때마다 이체를 다시 계산했는데,
     * 그 계산이 "지금 이후는 건너뛴다"로 잘리면서 <b>조회 시점이 지난달 입금 총액을 바꿨다.</b>
     * 여기서는 커트오프가 없으므로 생성 종료일까지 전부 만든다 — 결제내역과 같은 방식으로,
     * 조회가 {@code date <= now}로 거른다.
     *
     * <p><b>순서가 강제된다.</b> 이자는 그 시점 실잔액에 붙고 실잔액은 이체와 카드 지출에
     * 좌우되므로 {@code 이체 → 이자·세금} 순으로만 계산할 수 있다
     * ({@link AccountTxnGenerator#generate}가 안에서 지킨다).
     */
    private long insertAccountTxns(EconomyPlan e, Ledger ledger, CardOutflow flow) {
        List<Object[]> batch = new ArrayList<>(ledger.rows().size() + flow.rows().size());
        for (AccountTxnGenerator.Row t : ledger.rows()) batch.add(txnRow(e, t));
        // 카드 출금은 결제의 사본이다. 통장 한 장으로 잔액이 굴러가려면 같은 표에 있어야 한다.
        if (props.isCopyCardPaymentsToAccount()) {
            for (AccountTxnGenerator.Row t : flow.rows()) batch.add(txnRow(e, t));
        }
        jdbc.batchUpdate(ACCOUNT_TXN_SQL, batch);
        return batch.size();
    }

    private static Object[] txnRow(EconomyPlan e, AccountTxnGenerator.Row t) {
        return new Object[]{e.accountNumber(), Timestamp.valueOf(t.date()), t.type(),
                t.amount(), t.description(), t.note(), t.source(), t.paymentId()};
    }
}
