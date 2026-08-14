package com.finntech.ledger;

import com.finntech.domain.SpendingLedger;
import com.finntech.engine.FixedGroup;
import com.finntech.repository.SpendingLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 고정지출 판정이 <b>이미 낸 답</b>을 소비 원장에 옮겨 적는다 (2층).
 *
 * <p>판정을 시키지 않는다. {@code RecurringPaymentDetector.detect} 가 제 볼일로 돌 때
 * ({@code GET /api/analysis/summary}·적금 매칭·프로필) 통지가 오고, 그 답을 받아 적을 뿐이다.
 * 그래서 아무도 분석을 안 열면 이 칸은 계속 비어 있다 — 그것이 사실이고, 감추지 않는다.
 *
 * <h2>바뀔 것이 없으면 안 쓴다</h2>
 *
 * <p>화면을 열 때마다 판정이 도는데 그때마다 수천 줄을 다시 쓰면 <b>표를 위해 일을 만드는</b>
 * 셈이다. 그래서 쓰기 전에 "지금 사실보다 낡은 줄이 있나"부터 묻는다(질의 하나). 없으면
 * 곧바로 돌아간다.
 */
@Service
public class SpendingLedgerFixedRecorder {

    private static final Logger log = LoggerFactory.getLogger(SpendingLedgerFixedRecorder.class);

    /**
     * 고정지출 유도 규칙의 판.
     *
     * <p><b>{@code RecurringPaymentDetector} 의 판정 규칙이나 여기 옮겨 담는 방식이 바뀌면
     * 올린다.</b> 그러면 이미 쓰인 줄이 전부 낡은 것으로 보여 다음 판정 때 다시 써지고,
     * 운영 점검이 "옛 판으로 쓰인 줄"을 한 질의로 찾아낸다.
     */
    public static final String DETECTOR_VERSION = "fixed-1";

    private final SpendingLedgerRepository ledger;
    private final Executor executor;
    private final Clock clock;

    /** 프록시를 거쳐 불러야 {@code @Transactional} 이 걸린다 — 달마다 새 트랜잭션이 이 설계의 핵심이다. */
    private final org.springframework.beans.factory.ObjectProvider<SpendingLedgerFixedRecorder> selfProvider;

    public SpendingLedgerFixedRecorder(SpendingLedgerRepository ledger,
                                       @Qualifier(SpendingLedgerExecutorConfig.BEAN) Executor executor,
                                       Clock clock,
                                       org.springframework.beans.factory.ObjectProvider<SpendingLedgerFixedRecorder> selfProvider) {
        this.ledger = ledger;
        this.executor = executor;
        this.clock = clock;
        this.selfProvider = selfProvider;
    }

    /**
     * 통지를 받아 <b>배경에서</b> 적는다.
     *
     * <p>{@code AFTER_COMMIT} 인 것은 판정을 낸 트랜잭션이 되돌려질 수도 있기 때문이고,
     * {@code fallbackExecution} 을 켠 것은 판정이 트랜잭션 밖에서도 돌기 때문이다.
     *
     * <p>배경으로 미루는 이유는 판정을 부르는 쪽이 대개 <b>읽기 경로</b>라서다 — 화면이
     * 표 쓰기를 기다릴 이유가 없다. 일꾼 큐가 차서 이 일이 버려지면 칸이 잠시 낡을 뿐이고,
     * 다음 판정 때 다시 온다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onFixedGroupsDetected(LedgerJudgmentEvents.FixedGroupsDetected event) {
        executor.execute(() -> {
            try {
                record(event.userId(), event.groups());
            } catch (RuntimeException e) {
                log.warn("소비 원장 고정지출 기록 실패 — userId={} (다음 판정 때 다시 온다)", event.userId(), e);
            }
        });
    }

    /**
     * 그 사용자의 줄에 고정지출 칸을 적는다.
     *
     * <p>묶음에 든 결제는 그 묶음의 값으로, 안 든 결제는 <b>"고정지출이 아니다"</b>로 적는다.
     * 판정이 전 기간을 보고 났으므로 빠졌다는 것 자체가 답이다 — 비워 두면 "아직 모른다"와
     * 구별이 안 된다.
     *
     * @return 손댄 줄 수
     */
    public int record(Long userId, List<FixedGroup> groups) {
        if (!ledger.hasStaleFixed(userId, DETECTOR_VERSION)) return 0;

        Map<String, SpendingLedger.FixedFacts> byPaymentId = new HashMap<>();
        for (FixedGroup group : groups) {
            SpendingLedger.FixedFacts facts = SpendingLedgerRowMapper.fixedOf(group, DETECTOR_VERSION);
            for (String paymentId : group.paymentIds()) byPaymentId.put(paymentId, facts);
        }
        SpendingLedger.FixedFacts notFixed = SpendingLedger.FixedFacts.notFixed(DETECTOR_VERSION);
        LocalDateTime now = LocalDateTime.now(clock);

        SpendingLedgerFixedRecorder self = selfProvider.getObject();
        int touched = 0;
        for (String monthKey : ledger.findDistinctMonthKeysByUserId(userId)) {
            touched += self.recordMonth(userId, monthKey, byPaymentId, notFixed, now);
        }
        log.info("소비 원장 고정지출 기록 — userId={} 묶음 {}개, 줄 {}", userId, groups.size(), touched);
        return touched;
    }

    /** 한 달치 — 새 트랜잭션이라 한 사용자를 적는 동안 락이 이어지지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordMonth(Long userId, String monthKey,
                           Map<String, SpendingLedger.FixedFacts> byPaymentId,
                           SpendingLedger.FixedFacts notFixed, LocalDateTime now) {
        List<SpendingLedger> rows =
                ledger.findByUserIdAndMonthKeyOrderByPaidAtAscPaymentIdAsc(userId, monthKey);
        for (SpendingLedger row : rows) {
            row.applyFixed(byPaymentId.getOrDefault(row.getPaymentId(), notFixed), now);
        }
        return rows.size();
    }
}
