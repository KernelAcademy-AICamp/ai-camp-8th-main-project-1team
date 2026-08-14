package com.finntech.ledger;

import com.finntech.domain.SpendingLedger;
import com.finntech.domain.UserMerchantStance;
import com.finntech.domain.UserSpendingOverride;
import com.finntech.ml.WasteScoringService;
import com.finntech.repository.SpendingLedgerRepository;
import com.finntech.repository.UserMerchantStanceRepository;
import com.finntech.repository.UserSpendingOverrideRepository;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 낭비 판정이 <b>이미 낸 답</b>을 소비 원장에 옮겨 적는다 (3층).
 *
 * <p>판정을 시키지 않는다. {@code WasteScoringService.scoreUser} 가 제 볼일로 돌 때
 * ({@code GET /api/ml/waste/{userId}}·온보딩) 통지가 오고, 그 답을 받아 적을 뿐이다.
 *
 * <h2>임계와 성향은 여기서 다시 계산하지 않는다</h2>
 *
 * <p>{@code WasteJudgment} 은 확률과 라벨만 들고 온다. "이 줄에 실제로 적용된 임계"를 적으려면
 * 성향이 필요한데, 그 규칙을 여기 한 벌 더 적으면 {@code lenient-threshold-shift} 를 고쳤을 때
 * 둘이 갈라진다. 성향 표를 읽어 {@link WasteScoringService#thresholdFor} 에 <b>물어본다</b>
 * (마스터 §4 원칙 2: 서비스는 임계치를 재계산하지 않는다).
 *
 * <p>개인화 여부도 문구를 뜯어보지 않고 {@code user_spending_override} 를 직접 읽어 정한다 —
 * {@code explanation} 의 "개인화: …" 를 파싱하면 그 문구를 고치는 날 조용히 깨진다.
 */
@Service
public class SpendingLedgerWasteRecorder {

    private static final Logger log = LoggerFactory.getLogger(SpendingLedgerWasteRecorder.class);

    private final SpendingLedgerRepository ledger;
    private final UserMerchantStanceRepository stances;
    private final UserSpendingOverrideRepository overrides;
    private final WasteScoringService wasteScoring;
    private final Executor executor;
    private final Clock clock;

    /** 프록시를 거쳐 불러야 {@code @Transactional} 이 걸린다. */
    private final org.springframework.beans.factory.ObjectProvider<SpendingLedgerWasteRecorder> selfProvider;

    public SpendingLedgerWasteRecorder(SpendingLedgerRepository ledger,
                                       UserMerchantStanceRepository stances,
                                       UserSpendingOverrideRepository overrides,
                                       WasteScoringService wasteScoring,
                                       @Qualifier(SpendingLedgerExecutorConfig.BEAN) Executor executor,
                                       Clock clock,
                                       org.springframework.beans.factory.ObjectProvider<SpendingLedgerWasteRecorder> selfProvider) {
        this.ledger = ledger;
        this.stances = stances;
        this.overrides = overrides;
        this.wasteScoring = wasteScoring;
        this.executor = executor;
        this.clock = clock;
        this.selfProvider = selfProvider;
    }

    /** 통지를 받아 배경에서 적는다 — 판정을 부르는 쪽이 대개 읽기 경로라 기다리게 하지 않는다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWasteJudged(LedgerJudgmentEvents.WasteJudged event) {
        executor.execute(() -> {
            try {
                record(event.userId(), event.judgments(), event.modelThreshold(), event.modelFingerprint());
            } catch (RuntimeException e) {
                log.warn("소비 원장 낭비 기록 실패 — userId={} (다음 판정 때 다시 온다)", event.userId(), e);
            }
        });
    }

    /**
     * 그 사용자의 줄에 낭비 칸을 적는다.
     *
     * <p>판정에 없는 결제는 <b>{@code UNJUDGED}</b> 다 — 분류가 없어({@code 카테고리없음}·
     * {@code 기타}) 판정을 건너뛴 것이고, "낭비가 아니다"와 다른 사실이다.
     *
     * @return 손댄 줄 수
     */
    public int record(Long userId, List<WasteScoringService.WasteJudgment> judgments,
                      double modelThreshold, String modelFingerprint) {
        if (!ledger.hasStaleWaste(userId, modelFingerprint)) return 0;

        Map<String, WasteScoringService.WasteJudgment> byPaymentId = new HashMap<>();
        for (WasteScoringService.WasteJudgment judgment : judgments) {
            byPaymentId.put(judgment.paymentId(), judgment);
        }
        Map<String, UserMerchantStance.Stance> stanceByBusinessNumber = new HashMap<>();
        for (UserMerchantStance stance : stances.findByUserId(userId)) {
            stanceByBusinessNumber.put(stance.getBusinessNumber(), stance.getStance());
        }
        Set<String> overriddenCategories = new HashSet<>();
        for (UserSpendingOverride override : overrides.findByUserId(userId)) {
            overriddenCategories.add(override.getCategory2());
        }
        LocalDateTime now = LocalDateTime.now(clock);

        SpendingLedgerWasteRecorder self = selfProvider.getObject();
        int touched = 0;
        for (String monthKey : ledger.findDistinctMonthKeysByUserId(userId)) {
            touched += self.recordMonth(userId, monthKey, byPaymentId, stanceByBusinessNumber,
                    overriddenCategories, modelThreshold, modelFingerprint, now);
        }
        log.info("소비 원장 낭비 기록 — userId={} 판정 {}건, 줄 {} (모델 {})",
                userId, judgments.size(), touched, shortFingerprint(modelFingerprint));
        return touched;
    }

    /** 한 달치 — 새 트랜잭션. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordMonth(Long userId, String monthKey,
                           Map<String, WasteScoringService.WasteJudgment> byPaymentId,
                           Map<String, UserMerchantStance.Stance> stanceByBusinessNumber,
                           Set<String> overriddenCategories,
                           double modelThreshold, String modelFingerprint, LocalDateTime now) {
        List<SpendingLedger> rows =
                ledger.findByUserIdAndMonthKeyOrderByPaidAtAscPaymentIdAsc(userId, monthKey);
        for (SpendingLedger row : rows) {
            WasteScoringService.WasteJudgment judgment = byPaymentId.get(row.getPaymentId());
            UserMerchantStance.Stance stance = stanceByBusinessNumber.get(row.getBusinessNumber());
            OptionalDouble threshold = wasteScoring.thresholdFor(stance);
            boolean overridden = row.getCategory2() != null
                    && overriddenCategories.contains(row.getCategory2());
            row.applyWaste(SpendingLedgerRowMapper.wasteOf(judgment, stance, threshold, overridden,
                    modelThreshold, modelFingerprint), now);
        }
        return rows.size();
    }

    /** 로그에 지문 전체를 찍으면 줄이 길어진다 — 회차를 가릴 만큼만 남긴다. */
    private static String shortFingerprint(String fingerprint) {
        return fingerprint == null ? "(없음)" : fingerprint.substring(0, Math.min(8, fingerprint.length()));
    }
}
