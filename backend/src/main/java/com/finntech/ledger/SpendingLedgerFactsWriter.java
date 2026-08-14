package com.finntech.ledger;

import com.finntech.config.AnalysisProperties;
import com.finntech.domain.MerchantBrand;
import com.finntech.domain.MerchantCategory;
import com.finntech.domain.SpendingLedger;
import com.finntech.domain.UserPayment;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.MerchantBrandRepository;
import com.finntech.repository.MerchantCategoryRepository;
import com.finntech.repository.SpendingLedgerRepository;
import com.finntech.repository.UserPaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 소비 원장의 <b>사실 칸</b>(1층)을 그 사용자의 결제와 맞춘다.
 *
 * <p>계산이 없다 — 결제·가맹점·분류를 옮겨 적고, 유도되는 값(월·요일·시간대)만 만든다.
 * 그래서 원장이 바뀐 직후 곧바로 돌 수 있고, "업종코드가 처음 판별되는 순간 표에 반영된다"가
 * 여기서 지켜진다.
 *
 * <p><b>고정지출·낭비 칸은 건드리지 않는다.</b> 그것들은 각자의 판정이 돌 때 따라온다
 * ({@code SpendingLedgerFixedRecorder}·{@code SpendingLedgerWasteRecorder}). 사실이 바뀌면
 * {@code facts_updated_at} 이 앞서게 되고, 그 차이가 곧 "그 판정은 지금 사실보다 낡았다"이다.
 *
 * <h2>달 단위로 짧게 쓴다</h2>
 *
 * <p>재연동한 사용자는 결제가 수천 건이다. 한 트랜잭션에 다 실으면 영속 컨텍스트가 그만큼
 * 부풀고 락도 오래 잡는다. 달로 자르면 한 번에 수백 줄이고, 표의 조회축과 쓰기축이 같아진다.
 *
 * <p>대가로 <b>중간 상태가 보인다</b> — 5월은 새 값인데 6월은 아직 옛 값인 순간이 있다.
 * 이 표는 기록용이고 읽는 쪽은 배치 프로그램이라 그 거래를 받는다.
 */
@Service
public class SpendingLedgerFactsWriter {

    private static final Logger log = LoggerFactory.getLogger(SpendingLedgerFactsWriter.class);

    /** 사전에서 부가정보를 찾을 때 쓰는 복합키 구분자 — 사업자번호·가맹점명에 안 나오는 제어문자. */
    private static final char KEY_SEP = (char) 1;

    /** 지워야 할 줄이 많을 때 한 번에 보내는 크기. */
    private static final int DELETE_CHUNK = 500;

    private final UserPaymentRepository payments;
    private final SpendingLedgerRepository ledger;
    private final MerchantCategoryRepository dictionary;
    private final MerchantBrandRepository brands;
    private final AppUserRepository users;
    private final IndustryCategoryMapper industryMapper;
    private final AnalysisProperties props;
    private final Clock clock;

    /**
     * 자기 자신 — <b>프록시를 거쳐</b> 부르기 위해서다.
     *
     * <p>{@code @Transactional} 은 프록시가 걸어 주는 것이라 같은 객체 안에서 부르면 안 걸린다.
     * 달마다 새 트랜잭션을 여는 것이 이 클래스 설계의 핵심인데, 그냥 부르면 그 어노테이션이
     * <b>아무 일도 안 하고</b> 전부 부르는 쪽의 트랜잭션(또는 트랜잭션 없음)에 딸려 간다 —
     * 실패해도 조용하다. {@code MyDataLinkService} 가 같은 이유로 같은 모양을 쓴다.
     */
    private final org.springframework.beans.factory.ObjectProvider<SpendingLedgerFactsWriter> selfProvider;

    public SpendingLedgerFactsWriter(UserPaymentRepository payments, SpendingLedgerRepository ledger,
                                     MerchantCategoryRepository dictionary, MerchantBrandRepository brands,
                                     AppUserRepository users, IndustryCategoryMapper industryMapper,
                                     AnalysisProperties props, Clock clock,
                                     org.springframework.beans.factory.ObjectProvider<SpendingLedgerFactsWriter> selfProvider) {
        this.payments = payments;
        this.ledger = ledger;
        this.dictionary = dictionary;
        this.brands = brands;
        this.users = users;
        this.industryMapper = industryMapper;
        this.props = props;
        this.clock = clock;
        this.selfProvider = selfProvider;
    }

    /** 한 사용자를 다시 쓴 결과 — 로그와 운영 점검이 읽는다. */
    public record Result(long userId, int written, int removed, int months, boolean skipped) {

        static Result skipped(long userId, int removed) {
            return new Result(userId, 0, removed, 0, true);
        }
    }

    /**
     * 그 사용자의 사실 칸을 결제와 맞춘다 — 넣고, 고치고, 없어진 것은 지운다.
     *
     * <p>멱등이다. 두 번 돌려도 같은 값이 되고, 실제로 달라진 줄만 UPDATE 가 나간다
     * (Hibernate 더티체킹). 아무것도 안 바뀐 회차는 SELECT 만 돈다.
     */
    public Result write(Long userId) {
        if (!isRealPerson(userId)) {
            // 더미는 이 표에 들어오지 않는다(설계 원칙). "안 넣는다"가 아니라 "지운다"라야
            // 실사용자였다가 아니게 된 경우에도 잔재가 안 남는다.
            long before = ledger.countByUserId(userId);
            if (before > 0) ledger.deleteByUserId(userId);
            return Result.skipped(userId, (int) before);
        }

        List<UserPayment> rows = payments.findByUserIdOrderByPaymentDateDesc(userId);
        LocalDateTime now = LocalDateTime.now(clock);
        Lookup lookup = lookupFor(rows);

        // 달별로 모은다. TreeMap 이라 처리 순서가 고정된다(마스터 §4 원칙 3).
        Map<String, List<UserPayment>> byMonth = new TreeMap<>();
        for (UserPayment payment : rows) {
            byMonth.computeIfAbsent(java.time.YearMonth.from(payment.getPaymentDate()).toString(),
                    key -> new ArrayList<>()).add(payment);
        }

        SpendingLedgerFactsWriter self = selfProvider.getObject();
        int written = 0;
        for (var month : byMonth.entrySet()) {
            written += self.writeMonth(userId, month.getKey(), month.getValue(), lookup, now);
        }
        int removed = self.removeVanished(userId, rows);
        return new Result(userId, written, removed, byMonth.size(), false);
    }

    /**
     * 실사용자인가 — 칸이 <b>거짓일 때만</b> 결제로 되짚는다.
     *
     * <p>{@code app_user.real_person} 은 적재가 정하는데, 적재를 다시 돌리지 않으면 갱신될 일이
     * 없다. 그래서 결제는 실물인데 칸만 거짓인 상태가 만들어질 수 있고, 그러면 그 사용자의 표가
     * <b>아무 오류 없이 통째로 안 만들어진다.</b> 표시가 틀리는 두 방향 중 이쪽이 훨씬 나쁘다.
     * {@code MyDataLinkService.realPerson} 이 같은 이유로 같은 되짚기를 한다.
     */
    private boolean isRealPerson(Long userId) {
        return users.existsByIdAndRealPersonTrue(userId)
                || payments.existsRealPersonPaymentByUserId(userId);
    }

    /**
     * 한 달치를 쓴다 — <b>새 트랜잭션</b>이라 다른 달의 실패가 이 달을 되돌리지 않는다.
     *
     * <p>{@code REQUIRES_NEW} 인 두 번째 이유는 부르는 쪽(배수)이 트랜잭션 밖이라는 것이다.
     * 그러면 달마다 짧은 트랜잭션이 열리고 닫혀, 한 사용자를 쓰는 동안 락이 이어지지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int writeMonth(Long userId, String monthKey, List<UserPayment> monthRows,
                   Lookup lookup, LocalDateTime now) {
        Map<String, SpendingLedger> existing = new HashMap<>();
        for (SpendingLedger row : ledger.findByUserIdAndMonthKeyOrderByPaidAtAscPaymentIdAsc(userId, monthKey)) {
            existing.put(row.getPaymentId(), row);
        }
        List<SpendingLedger> fresh = new ArrayList<>();
        for (UserPayment payment : monthRows) {
            SpendingLedger.Facts facts = SpendingLedgerRowMapper.factsOf(
                    payment, lookup.merchantFactsOf(payment), props.getDaypart(),
                    industryMapper::isPaymentAgency);
            SpendingLedger row = existing.get(payment.getPaymentId());
            if (row != null) {
                row.applyFacts(facts, now);      // 더티체킹이 실제로 달라진 줄만 UPDATE 한다
            } else {
                fresh.add(new SpendingLedger(payment.getPaymentId(), facts, now));
            }
        }
        if (!fresh.isEmpty()) ledger.saveAll(fresh);
        return monthRows.size();
    }

    /**
     * 원장에서 사라진 결제의 줄을 치운다.
     *
     * <p>재연동은 결제를 통째로 지우고 다시 넣으므로 옛 식별자가 남을 수 있고, 삭제 요청은
     * 아예 다 지운다. 이 정리가 없으면 <b>없는 결제가 표에서 계속 살아 있다.</b>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int removeVanished(Long userId, List<UserPayment> rows) {
        Set<String> alive = new HashSet<>(rows.size());
        for (UserPayment payment : rows) alive.add(payment.getPaymentId());

        List<String> stale = new ArrayList<>();
        for (String paymentId : ledger.findPaymentIdsByUserId(userId)) {
            if (!alive.contains(paymentId)) stale.add(paymentId);
        }
        for (int from = 0; from < stale.size(); from += DELETE_CHUNK) {
            ledger.deleteAllByIdInBatch(stale.subList(from, Math.min(from + DELETE_CHUNK, stale.size())));
        }
        if (!stale.isEmpty()) {
            log.info("소비 원장 — userId={} 에서 사라진 결제 {}건의 줄을 치웠다", userId, stale.size());
        }
        return stale.size();
    }

    // ── 가맹점 부가정보 찾기 ─────────────────────────────────────────────────

    /**
     * 이 사용자의 가맹점들에 대한 브랜드·주소·등록업종명 — <b>한 번에 읽어 둔다.</b>
     *
     * <p>가맹점마다 조회하면 수백 번이 나간다. 이름 목록으로 두 번만 읽고 메모리에서 맞춘다.
     */
    Lookup lookupFor(List<UserPayment> rows) {
        List<String> names = rows.stream()
                .map(UserPayment::getMerchantName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
        if (names.isEmpty()) return Lookup.EMPTY;

        Map<String, MerchantCategory> byCompositeKey = new HashMap<>();
        Map<String, MerchantCategory> byNameOnly = new HashMap<>();
        Set<String> ambiguousNames = new HashSet<>();
        for (MerchantCategory row : dictionary.findByMerchantNameIn(names)) {
            byCompositeKey.put(row.getBusinessNumber() + KEY_SEP + row.getMerchantName(), row);
            // 같은 이름에 행이 여럿이면(사업자번호가 다른 동명 가맹점) 이름만으로는 못 고른다.
            if (byNameOnly.putIfAbsent(row.getMerchantName(), row) != null) {
                ambiguousNames.add(row.getMerchantName());
            }
        }
        for (String ambiguous : ambiguousNames) byNameOnly.remove(ambiguous);

        Map<String, String> pendingBrands = new LinkedHashMap<>();
        for (MerchantBrand brand : brands.findByMerchantNameIn(names)) {
            pendingBrands.put(brand.getMerchantName(), brand.getBrand());
        }
        return new Lookup(byCompositeKey, byNameOnly, pendingBrands);
    }

    /** 미리 읽어 둔 가맹점 부가정보. */
    record Lookup(Map<String, MerchantCategory> byCompositeKey,
                  Map<String, MerchantCategory> byNameOnly,
                  Map<String, String> pendingBrands) {

        static final Lookup EMPTY = new Lookup(Map.of(), Map.of(), Map.of());

        /**
         * 그 결제의 가맹점 부가정보.
         *
         * <p>사전의 키는 (사업자번호, 가맹점 풀네임) 복합키다. 정확히 없으면 <b>이름만으로</b>
         * 물러나되, 같은 이름에 행이 여럿이면 물러나지 않는다 — 엉뚱한 가맹점의 주소를 붙이는
         * 것보다 비워 두는 편이 낫다. 여기서 찾는 값은 전부 장식이라 없어도 줄이 만들어진다.
         */
        SpendingLedgerRowMapper.MerchantFacts merchantFactsOf(UserPayment payment) {
            String name = payment.getMerchantName();
            if (name == null || name.isBlank()) return SpendingLedgerRowMapper.MerchantFacts.EMPTY;
            String biz = MerchantCategory.normalize(payment.getBusinessNumber());
            MerchantCategory row = byCompositeKey.get(biz + KEY_SEP + name);
            if (row == null) row = byNameOnly.get(name);
            return SpendingLedgerRowMapper.MerchantFacts.of(row, pendingBrands.get(name));
        }
    }
}
