package com.finntech.mydata.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * '알 수 없는 PG 결제' 추가 작업(§13-11 보강). 모든 사용자에게, PG사(전자지급결제대행)를 통한 결제라
 * <b>무엇을 샀는지 알 수 없는</b> 결제를 <b>가끔씩</b>(사용자별 1~8주 랜덤 간격, 랜덤일 1건씩) 주입한다.
 * 실 카드내역에는 이렇게 가맹점이 PG사로만 찍혀 품목을 알 수 없는 결제가 섞인다.
 *
 * <p>학습(ML)과 무관한 단순 데이터 추가 작업이다 — 라벨은 남기지 않는다(NULL). 결정론(시드) + 멱등
 * (이미 있으면 skip)으로 안전하게 재실행 가능. 게이트: {@code mydata.unknown-pg.enabled=true}.
 */
@Component
@Order(200)
public class UnknownPgPaymentRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UnknownPgPaymentRunner.class);

    /** 국내 대표 PG(전자지급결제대행)사 — 가맹점 자리에 이 이름이 찍힌다. */
    private static final String[] PG = {"토스페이먼츠", "KG이니시스", "NHN KCP", "나이스페이먼츠", "다날", "헥토파이낸셜"};
    private static final String PAY_SQL = "INSERT INTO mydata_payment " +
            "(mydata_payment_id, mydata_card_id, mydata_payment_date, mydata_payment_category1, " +
            "mydata_payment_category2, mydata_payment_amount, mydata_payment_merchant_name, " +
            "mydata_payment_received_benefit_amount, mydata_payment_channel, mydata_payment_product_name, " +
            "mydata_payment_product_price, mydata_payment_quantity, mydata_payment_waste_label, " +
            "mydata_payment_discretionary_score, mydata_payment_location_address, " +
            "mydata_payment_location_lat, mydata_payment_location_lng) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final long seed;
    private final LocalDate rangeStart = LocalDate.parse("2025-12-15"); // 생성 이력 시작과 정합
    private final LocalDate rangeEnd = LocalDate.parse("2026-09-30");   // 커트오프 이후 일부 미래까지

    public UnknownPgPaymentRunner(JdbcTemplate jdbc,
                                  @Value("${mydata.unknown-pg.enabled:false}") boolean enabled,
                                  @Value("${mydata.seed.seed:20260721}") long seed) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.seed = seed;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        // 멱등: 이 러너의 결제만 식별(결제ID 'u' 접두 — 생성기는 'g' 접두). 생성기의 '간편결제'와 구분된다.
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mydata_payment WHERE mydata_payment_id LIKE 'u%'", Integer.class);
        if (existing != null && existing > 0) {
            log.info("[unknown-pg] 이미 {}건 존재 — 건너뜀(멱등)", existing);
            return;
        }
        // 사용자별 카드 목록을 한 번에 적재(N+1 회피)
        Map<String, List<String>> cardsByUser = new HashMap<>();
        jdbc.query("SELECT mydata_user_id, mydata_card_id FROM mydata_card",
                (RowCallbackHandler) rs -> cardsByUser.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2)));
        log.info("[unknown-pg] 시작 — 사용자 {}명, 범위 {}~{}", cardsByUser.size(), rangeStart, rangeEnd);

        List<Object[]> batch = new ArrayList<>(5000);
        long total = 0;
        for (Map.Entry<String, List<String>> e : cardsByUser.entrySet()) {
            String uid = e.getKey();
            List<String> cards = e.getValue();
            if (cards.isEmpty()) continue;
            Random r = GenSeed.rng(seed, uid.hashCode(), 777L); // 결정론
            LocalDate d = rangeStart.plusDays(r.nextInt(28));   // 첫 결제는 ~4주 내 랜덤일
            int seq = 0;
            while (d.isBefore(rangeEnd)) {
                String cardId = cards.get(r.nextInt(cards.size()));
                int amount = snap(1000 + r.nextInt(150_000), r); // 랜덤 금액, 끝자리 00(1원 단위 없음)
                String pg = PG[r.nextInt(PG.length)];
                String payId = "u" + uid.substring(0, 16) + "-" + (seq++);
                LocalDateTime when = d.atTime(r.nextInt(24), r.nextInt(60));
                batch.add(new Object[]{
                        payId, cardId, Timestamp.valueOf(when), "온라인", "미분류",
                        amount, pg, 0, "ONLINE", "알 수 없는 결제", null, 1, null, null, null, null, null});
                total++;
                if (batch.size() >= 5000) { jdbc.batchUpdate(PAY_SQL, batch); batch.clear(); }
                d = d.plusWeeks(1L + r.nextInt(8)); // 다음 결제는 1~8주 뒤(가끔씩)
            }
        }
        if (!batch.isEmpty()) jdbc.batchUpdate(PAY_SQL, batch);
        log.info("[unknown-pg] 완료 — 알 수 없는 PG 결제 {}건 추가", total);
    }

    /** 끝자리 00원(1원 단위 제거) — 고액은 1000원, 그 외 100원 단위. */
    static int snap(int amt, Random r) {
        int unit = (amt >= 50_000) ? 1000 : 100;
        return Math.max(unit, (int) Math.round((double) amt / unit) * unit);
    }
}
