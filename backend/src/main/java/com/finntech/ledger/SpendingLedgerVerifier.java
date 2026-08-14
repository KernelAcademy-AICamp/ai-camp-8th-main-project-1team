package com.finntech.ledger;

import com.finntech.config.AnalysisProperties;
import com.finntech.domain.AppUser;
import com.finntech.domain.SpendingLedger;
import com.finntech.domain.SpendingLedgerDirty;
import com.finntech.domain.UserPayment;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.SpendingLedgerRepository;
import com.finntech.repository.UserPaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 표와 원장이 <b>벌어졌는지</b> 본다 — 쓰지 않고 다시 만들어 견준다.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>표시를 놓치는 창이 있다. 엔티티 콜백은 flush 때 뜨는데 그때는 커밋이 이미 진행 중이라,
 * 표시가 <b>커밋 직후 별도 트랜잭션</b>으로 적힌다({@link SpendingLedgerDirtyMarker}).
 * 그 사이에 프로세스가 죽으면 그 사용자의 표는 조용히 낡은 채로 남는다. 어긋남을 알아챌
 * 방법이 없으면 그 상태가 영원히 간다.
 *
 * <h2>사실 칸만 본다</h2>
 *
 * <p>고정지출·낭비를 대조하려면 판정을 다시 돌려야 하는데, 그것은 <b>표를 위해 계산을
 * 일으키는 일</b>이라 이 표의 첫 번째 원칙에 어긋난다. 그 두 층의 낡음은 값을 견주는 대신
 * {@code *_recorded_at} 과 {@code facts_updated_at} 의 시각 비교로 본다.
 */
@Service
public class SpendingLedgerVerifier {

    private static final Logger log = LoggerFactory.getLogger(SpendingLedgerVerifier.class);

    /** 보고에 담는 어긋남 표본 상한 — 전부 담으면 응답이 원장만큼 커진다. */
    private static final int SAMPLE_LIMIT = 20;

    private final AppUserRepository users;
    private final UserPaymentRepository payments;
    private final SpendingLedgerRepository ledger;
    private final SpendingLedgerFactsWriter factsWriter;
    private final SpendingLedgerDirtyMarker marker;
    private final IndustryCategoryMapper industryMapper;
    private final AnalysisProperties props;

    public SpendingLedgerVerifier(AppUserRepository users, UserPaymentRepository payments,
                                  SpendingLedgerRepository ledger,
                                  SpendingLedgerFactsWriter factsWriter,
                                  SpendingLedgerDirtyMarker marker,
                                  IndustryCategoryMapper industryMapper, AnalysisProperties props) {
        this.users = users;
        this.payments = payments;
        this.ledger = ledger;
        this.factsWriter = factsWriter;
        this.marker = marker;
        this.industryMapper = industryMapper;
        this.props = props;
    }

    /** 어긋난 한 자리. */
    public record Mismatch(String paymentId, String column, String stored, String expected) {}

    /** 대조 결과. {@code mismatched} 가 0이 아니면 그 사용자들은 스스로 낫도록 표시된다. */
    public record Result(int checkedUsers, int checkedRows, int mismatched,
                         List<Long> mismatchedUsers, List<Mismatch> samples) {}

    /**
     * 실사용자 최대 {@code limit} 명의 사실 칸을 다시 만들어 저장된 줄과 견준다.
     *
     * <p>어긋난 사용자는 <b>표시만 남기고 고치지는 않는다</b> — 고치는 일은 배수 하나가 한다.
     * 여기서 직접 쓰면 표를 고치는 자리가 둘이 되고, 그 둘이 갈라질 자리가 생긴다.
     */
    @Transactional(readOnly = true)
    public Result verify(int limit) {
        List<AppUser> targets = new ArrayList<>();
        for (AppUser user : users.findAll()) {
            if (user.isRealPerson()) targets.add(user);
        }
        targets.sort(java.util.Comparator.comparing(AppUser::getId));   // 정렬 고정(원칙 3)
        if (targets.size() > limit) targets = targets.subList(0, limit);

        int checkedRows = 0;
        int mismatched = 0;
        List<Long> mismatchedUsers = new ArrayList<>();
        List<Mismatch> samples = new ArrayList<>();

        for (AppUser user : targets) {
            List<UserPayment> rows = payments.findByUserIdOrderByPaymentDateDesc(user.getId());
            SpendingLedgerFactsWriter.Lookup lookup = factsWriter.lookupFor(rows);
            Map<String, SpendingLedger> stored = new HashMap<>();
            for (SpendingLedger row : ledger.findByUserIdOrderByPaidAtAscPaymentIdAsc(user.getId())) {
                stored.put(row.getPaymentId(), row);
            }
            int userMismatches = 0;
            for (UserPayment payment : rows) {
                checkedRows++;
                SpendingLedger row = stored.remove(payment.getPaymentId());
                if (row == null) {
                    userMismatches += note(samples, payment.getPaymentId(), "(줄 없음)", "-", "있어야 한다");
                    continue;
                }
                SpendingLedger.Facts expected = SpendingLedgerRowMapper.factsOf(
                        payment, lookup.merchantFactsOf(payment), props.getDaypart(),
                        industryMapper::isPaymentAgency);
                userMismatches += compare(samples, payment.getPaymentId(), row, expected);
            }
            // 원장에 없는데 표에 남은 줄 — 재연동·파기 뒤 정리가 안 된 경우다.
            for (String orphan : stored.keySet()) {
                userMismatches += note(samples, orphan, "(유령 줄)", "있다", "없어야 한다");
            }
            if (userMismatches > 0) {
                mismatched += userMismatches;
                mismatchedUsers.add(user.getId());
                marker.mark(user.getId(), SpendingLedgerDirty.Reason.BACKFILL);
            }
        }
        if (mismatched > 0) {
            log.warn("소비 원장 대조 — 사용자 {}명에서 {}자리가 어긋났다. 표시를 남겼으니 배수가 고친다",
                    mismatchedUsers.size(), mismatched);
        }
        return new Result(targets.size(), checkedRows, mismatched, mismatchedUsers, samples);
    }

    /** 사실 칸을 하나하나 견준다 — 장식 칸(브랜드·주소·등록업종명)은 늦게 붙어도 되므로 뺀다. */
    private static int compare(List<Mismatch> samples, String paymentId,
                               SpendingLedger row, SpendingLedger.Facts expected) {
        int found = 0;
        found += check(samples, paymentId, "month_key", row.getMonthKey(), expected.monthKey());
        found += check(samples, paymentId, "paid_at", row.getPaidAt(), expected.paidAt());
        found += check(samples, paymentId, "amount", row.getAmount(), expected.amount());
        found += check(samples, paymentId, "daypart", row.getDaypart(), expected.daypart());
        found += check(samples, paymentId, "origin", row.getOrigin(), expected.origin());
        found += check(samples, paymentId, "business_number", row.getBusinessNumber(), expected.businessNumber());
        found += check(samples, paymentId, "merchant_name", row.getMerchantName(), expected.merchantName());
        found += check(samples, paymentId, "merchant_key", row.getMerchantKey(), expected.merchantKey());
        found += check(samples, paymentId, "nts_industry_code", row.getNtsIndustryCode(), expected.ntsIndustryCode());
        found += check(samples, paymentId, "category2", row.getCategory2(), expected.category2());
        found += check(samples, paymentId, "category2_source", row.getCategory2Source(), expected.category2Source());
        found += check(samples, paymentId, "category2_llm", row.getCategory2Llm(), expected.category2Llm());
        return found;
    }

    private static int check(List<Mismatch> samples, String paymentId, String column,
                             Object stored, Object expected) {
        if (java.util.Objects.equals(stored, expected)) return 0;
        return note(samples, paymentId, column, String.valueOf(stored), String.valueOf(expected));
    }

    private static int note(List<Mismatch> samples, String paymentId, String column,
                            String stored, String expected) {
        if (samples.size() < SAMPLE_LIMIT) {
            samples.add(new Mismatch(paymentId, column, stored, expected));
        }
        return 1;
    }
}
