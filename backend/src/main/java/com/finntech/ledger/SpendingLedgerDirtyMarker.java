package com.finntech.ledger;

import com.finntech.domain.SpendingLedgerDirty;
import com.finntech.repository.SpendingLedgerDirtyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "이 사용자의 소비 원장을 다시 써야 한다"를 적는 <b>유일한 창구</b>.
 *
 * <h2>적기만 한다 — 여기서 다시 쓰지 않는다</h2>
 *
 * <p>부르는 자리 중 가장 큰 것이 {@code MerchantDictionaryRecomputeService.recompute} 다.
 * 사전 수천 행을 훑으며 {@code applyToLedger} 가 실사용자 전원의 결제를 <b>한 트랜잭션에서</b>
 * 고친다. 그 안에서 재작성을 하면 같은 사용자를 수천 번 다시 쓴다. 표시는 사용자당 한 줄로
 * 접히고, 실제 재작성은 커밋 뒤에 한 번 돈다.
 *
 * <h2>커밋 직전에 쓰되, 놓친 것은 커밋 직후에 쓴다</h2>
 *
 * <p>표시가 바꾼 트랜잭션과 <b>같은 커밋</b>에 들어가면, 그 트랜잭션이 되돌려질 때 표시도 함께
 * 되돌려진다. 그래서 {@code beforeCommit} 을 먼저 쓴다.
 *
 * <p><b>그것만으로는 안 된다.</b> 표시를 부르는 쪽 대부분이 JPA 엔티티 콜백인데, 그 콜백은
 * flush 때 뜨고 flush 는 {@code doCommit} 안에서 일어난다 — 즉 {@code beforeCommit} 이
 * <b>이미 지나간 뒤</b>다(2026-08-14 시험에서 실측: 표시가 하나도 안 남았다). 트랜잭션 도중에
 * 질의가 있어 Hibernate 가 미리 flush 하면 그때는 {@code beforeCommit} 이 잡는다. 두 시점이
 * 실제로 다 일어나므로 둘 다 받는다.
 *
 * <p>뒤늦게 오는 것들은 커밋이 끝난 뒤 <b>새 트랜잭션</b>으로 적는다. 그 사이에 프로세스가
 * 죽으면 그 표시는 잃는다 — 밀리초짜리 창이고, 그렇게 잃은 어긋남은 운영 점검
 * ({@code /api/ops/spending-ledger/health}·{@code verify})이 찾아낸다. 대안은 콜백 안에서
 * 바로 쓰는 것인데, flush 한복판에 쓰기를 걸면 Hibernate 의 작업 큐가 재귀한다.
 *
 * <h2>왜 저장소를 늦게 찾나</h2>
 *
 * <p>이 빈을 부르는 쪽이 <b>JPA 엔티티 리스너</b>({@link LedgerDirtyListener})다. 리스너는
 * EntityManagerFactory 를 세우는 도중에 만들어지는데, 그때 저장소를 곧바로 요구하면
 * <i>EMF → 리스너 → 표시기 → 저장소 → EMF</i> 로 도는 순환이 된다. {@link ObjectProvider} 로
 * 받아 실제로 쓸 때(= 기동이 끝난 뒤) 찾는다.
 */
@Component
public class SpendingLedgerDirtyMarker {

    private static final Logger log = LoggerFactory.getLogger(SpendingLedgerDirtyMarker.class);

    /** 트랜잭션에 매다는 자원 키 — 같은 트랜잭션 안의 표시를 사용자별로 접어 모은다. */
    private static final String PENDING_KEY = SpendingLedgerDirtyMarker.class.getName() + ".pending";

    private final ObjectProvider<SpendingLedgerDirtyRepository> dirtyRepositories;
    private final ObjectProvider<SpendingLedgerDrainer> drainers;
    private final ObjectProvider<org.springframework.transaction.PlatformTransactionManager> transactionManagers;
    private final Clock clock;

    public SpendingLedgerDirtyMarker(ObjectProvider<SpendingLedgerDirtyRepository> dirtyRepositories,
                                     ObjectProvider<SpendingLedgerDrainer> drainers,
                                     ObjectProvider<org.springframework.transaction.PlatformTransactionManager> transactionManagers,
                                     Clock clock) {
        this.dirtyRepositories = dirtyRepositories;
        this.drainers = drainers;
        this.transactionManagers = transactionManagers;
        this.clock = clock;
    }

    /**
     * 표시한다. 트랜잭션 안이면 커밋 직전에 한 줄로 접혀 쓰이고, 밖이면 곧바로 쓴다.
     *
     * <p>같은 트랜잭션에서 같은 사용자를 여러 번 표시해도 <b>첫 사유 하나만</b> 남는다.
     * 사유는 처리 분기에 쓰지 않고 로그·점검에만 쓰므로 그것으로 충분하다.
     */
    public void mark(Long userId, SpendingLedgerDirty.Reason reason) {
        if (userId == null) return;

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 트랜잭션 밖 — 접을 대상도 되돌릴 대상도 없다. 바로 적고 바로 알린다.
            Map<Long, SpendingLedgerDirty.Reason> single = new LinkedHashMap<>();
            single.put(userId, reason);
            write(single);
            nudge();
            return;
        }
        pending().putIfAbsent(userId, reason);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, SpendingLedgerDirty.Reason> pending() {
        Map<Long, SpendingLedgerDirty.Reason> bound =
                (Map<Long, SpendingLedgerDirty.Reason>) TransactionSynchronizationManager.getResource(PENDING_KEY);
        if (bound != null) return bound;

        Map<Long, SpendingLedgerDirty.Reason> fresh = new LinkedHashMap<>();
        TransactionSynchronizationManager.bindResource(PENDING_KEY, fresh);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

            @Override
            public void beforeCommit(boolean readOnly) {
                // 이 트랜잭션 안에서 이미 flush 가 한 번 돌아 표시가 모여 있으면 여기서 쓴다 —
                // **같은 커밋**이라 되돌려질 때 함께 되돌려진다.
                // 읽기 전용은 flush 하지 않으므로 여기서 써도 조용히 사라진다. 애초에 읽기
                // 전용에서는 엔티티가 안 바뀌어 표시가 생길 일도 없다.
                if (!readOnly) write(fresh);
            }

            @Override
            public void afterCommit() {
                // 커밋 도중(= 마지막 flush)에 뜬 콜백들이 여기 남아 있다. 새 트랜잭션으로 적는다.
                if (!fresh.isEmpty()) writeInNewTransaction(fresh);
            }

            @Override
            public void afterCompletion(int status) {
                // **반드시 푼다.** 안 풀면 톰캣 풀 스레드에 이 맵이 남아 다음 요청의 표시가
                // 엉뚱한 사용자로 샌다.
                TransactionSynchronizationManager.unbindResourceIfPossible(PENDING_KEY);
                if (status == STATUS_COMMITTED) nudge();
            }
        });
        return fresh;
    }

    /**
     * 커밋이 끝난 뒤 남은 표시를 <b>새 트랜잭션</b>으로 적는다.
     *
     * <p>{@code REQUIRES_NEW} 인 것이 요점이다. 여기는 원래 트랜잭션이 이미 커밋된 자리라,
     * 그냥 쓰면 아직 매여 있는 자원에 얹혀 <b>커밋되지 않은 채</b> 사라질 수 있다.
     */
    private void writeInNewTransaction(Map<Long, SpendingLedgerDirty.Reason> marks) {
        var template = new org.springframework.transaction.support.TransactionTemplate(
                transactionManagers.getObject());
        template.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> write(marks));
    }

    private void write(Map<Long, SpendingLedgerDirty.Reason> marks) {
        if (marks.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now(clock);
        List<SpendingLedgerDirty> rows = new ArrayList<>(marks.size());
        for (var entry : marks.entrySet()) {
            rows.add(new SpendingLedgerDirty(entry.getKey(), entry.getValue(), now));
        }
        dirtyRepositories.getObject().saveAll(rows);
        marks.clear();
    }

    /**
     * 배수를 깨운다 — <b>실패해도 조용히 넘어간다.</b>
     *
     * <p>이것은 신호일 뿐이라 놓쳐도 15초 뒤 배치가 같은 일을 한다. 여기서 예외를 위로 던지면
     * 이미 커밋된 트랜잭션의 뒤처리가 실패로 보이는데, 정작 데이터는 멀쩡하다.
     */
    private void nudge() {
        try {
            drainers.getObject().nudge();
        } catch (RuntimeException e) {
            log.debug("소비 원장 배수를 깨우지 못했다 — 다음 배치가 이어받는다", e);
        }
    }
}
