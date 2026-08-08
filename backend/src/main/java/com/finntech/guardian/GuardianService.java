package com.finntech.guardian;

import com.finntech.domain.Category;
import com.finntech.domain.Consumption;
import com.finntech.domain.UserPayment;
import com.finntech.engine.AnalysisEngine;
import com.finntech.engine.AnalysisResult;
import com.finntech.guardian.domain.*;
import com.finntech.guardian.repository.GuardianChallengeCategoryRepository;
import com.finntech.guardian.domain.GuardianEnums.*;
import com.finntech.guardian.repository.*;
import com.finntech.repository.CategoryRepository;
import com.finntech.repository.ConsumptionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.finntech.domain.AppUser;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import org.springframework.web.server.ResponseStatusException;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 지킴이 Agent — 챌린지 원장과 거래 개입 (지킴이 Agent 설계서 v1.2).
 *
 * <p><b>낙관적 판정(설계서 D1-A).</b> 거래가 들어오면 즉시 집계하고 24시간 되돌리기를 준다.
 * "확인받고 차감"은 사용자가 매번 확인 버튼을 눌러야 해서 알림 피로가 커진다.
 *
 * <p><b>판단은 규칙이, 표현은 AI가(마스터 §4 원칙 1).</b> 이 클래스는 원장을 움직이고
 * {@link GuardianRules}에 판정을 물어볼 뿐이며, 문장은 {@link GuardianNarrative}가 맨 마지막에 만든다.
 *
 * <p><b>시각은 반드시 {@link GuardianClock}으로.</b> {@code LocalDateTime.now()}를 직접 부르면
 * 데모의 "다음 날로 이동"이 동작하지 않고 재현성도 깨진다(마스터 §4 원칙 3).
 */
@Service
public class GuardianService {

    private final GuardianChallengeRepository challengeRepository;
    private final GuardianTransactionRepository txRepository;
    private final GuardianNotificationRepository notificationRepository;
    private final DailyVerdictRepository verdictRepository;
    private final GuardianRewardService rewardService;
    private final GuardianNarrative narrative;
    private final GuardianClock clock;
    private final GuardianCatalog catalog;
    /** 사용자가 정한 말수 상한을 읽으려고 둔다 — 사람마다 다른 값은 설정 파일에 못 둔다. */
    private final com.finntech.repository.AppUserRepository userRepository;
    private final GuardianProperties props;
    private final AnalysisEngine analysisEngine;
    private final ConsumptionRepository consumptionRepository;
    private final CategoryRepository categoryRepository;
    private final com.finntech.repository.UserPaymentRepository userPaymentRepository;
    private final com.finntech.repository.UserMerchantStanceRepository stanceRepository;
    private final GuardianChallengeCategoryRepository challengeCategoryRepository;

    public GuardianService(GuardianChallengeRepository challengeRepository,
                           GuardianTransactionRepository txRepository,
                           GuardianNotificationRepository notificationRepository,
                           DailyVerdictRepository verdictRepository,
                           GuardianRewardService rewardService,
                           GuardianNarrative narrative,
                           GuardianClock clock,
                           GuardianProperties props,
                           AnalysisEngine analysisEngine,
                           ConsumptionRepository consumptionRepository,
                           CategoryRepository categoryRepository,
                           com.finntech.repository.UserPaymentRepository userPaymentRepository,
                           com.finntech.repository.UserMerchantStanceRepository stanceRepository,
                           GuardianChallengeCategoryRepository challengeCategoryRepository,
                           GuardianCatalog catalog,
                           com.finntech.repository.AppUserRepository userRepository) {
        this.catalog = catalog;
        this.userRepository = userRepository;
        this.challengeRepository = challengeRepository;
        this.txRepository = txRepository;
        this.notificationRepository = notificationRepository;
        this.verdictRepository = verdictRepository;
        this.rewardService = rewardService;
        this.narrative = narrative;
        this.clock = clock;
        this.props = props;
        this.analysisEngine = analysisEngine;
        this.consumptionRepository = consumptionRepository;
        this.categoryRepository = categoryRepository;
        this.userPaymentRepository = userPaymentRepository;
        this.stanceRepository = stanceRepository;
        this.challengeCategoryRepository = challengeCategoryRepository;
    }

    // ======================================================================
    //  1. 챌린지 시작
    // ======================================================================

    /**
     * 챌린지를 시작한다. 기준 지출과 평균 결제액은 <b>기존 분석 결과에서 파생</b>한다 —
     * 서비스가 임계치를 다시 계산하지 않는다(마스터 §4 원칙 2).
     *
     * @param categories   줄이기로 한 카테고리 코드
     * @param targetSaving 지킬 돈. 기준 지출보다 작아야 한다.
     */
    /** 제외 목록 없이 부르는 옛 호출부·테스트용. */
    @Transactional
    public GuardianChallenge createChallenge(Long userId, List<String> categories, List<String> sanctuary,
                                             Long targetSaving, String rewardName, Long rewardPrice,
                                             Integer durationDays) {
        return createChallenge(userId, categories, sanctuary, targetSaving, rewardName,
                rewardPrice, durationDays, List.of(), Map.of());
    }

    /** 카테고리별 목표 없이 부르는 호출부용 — 균등분할한다. */
    @Transactional
    public GuardianChallenge createChallenge(Long userId, List<String> categories, List<String> sanctuary,
                                             Long targetSaving, String rewardName, Long rewardPrice,
                                             Integer durationDays, List<String> keptPaymentIds) {
        return createChallenge(userId, categories, sanctuary, targetSaving, rewardName,
                rewardPrice, durationDays, keptPaymentIds, Map.of());
    }

    /**
     * @param keptPaymentIds 온보딩에서 <b>"이건 낭비가 아니다"</b>로 뺀 결제 id. 기준 지출에서 뺀다.
     *                       화면이 그만큼 줄여 보여줬는데 서버가 안 빼면 예산만 넉넉해져,
     *                       사용자가 고른 의미가 사라진다.
     */
    @Transactional
    public GuardianChallenge createChallenge(Long userId, List<String> categories, List<String> sanctuary,
                                             Long targetSaving, String rewardName, Long rewardPrice,
                                             Integer durationDays, List<String> keptPaymentIds,
                                             Map<String, Long> categoryTargets) {
        if (categories == null || categories.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "줄일 카테고리를 하나 이상 골라주세요");
        }
        if (challengeRepository.findRunning(userId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 진행 중인 챌린지가 있어요");
        }

        LocalDateTime now = clock.now(userId);
        // 기준 지출을 기간에 맞춰 환산해야 하므로 일수를 먼저 정한다.
        int days = durationDays == null ? props.getDefaultDurationDays() : durationDays;
        Baseline baseline = baselineFor(userId, categories, now, days, keptPaymentIds);
        if (baseline.periodAmount() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "이 카테고리의 소비 이력이 없어 기준 지출을 잡을 수 없어요");
        }
        long target = targetSaving == null ? baseline.periodAmount() / 3 : targetSaving;
        if (target <= 0 || target >= baseline.periodAmount()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "지킬 돈은 0보다 크고 기준 지출(" + GuardianCopy.won(baseline.periodAmount()) + "원)보다 작아야 해요");
        }

        long cap = baseline.periodAmount() - target;
        double bufferRatio = GuardianRules.computeBufferRatio(baseline.avgTransactionAmount(), cap);
        LocalDate start = now.toLocalDate();

        GuardianChallenge ch = new GuardianChallenge(userId, categories, sanctuary,
                baseline.periodAmount(), target, bufferRatio,
                start, start.plusDays(days - 1L), rewardName, rewardPrice, now);
        rewardService.items(userId, now);   // 보유 아이템 레코드를 미리 만들어 둔다
        GuardianChallenge saved = challengeRepository.save(ch);
        saveCategoryCaps(saved, categories, now, days, keptPaymentIds, categoryTargets, target);
        // 뺀 결제가 가리킨 가맹점의 성향을 한 칸 올린다 — 다음 달에 같은 것을 또 빼지 않도록.
        promoteStances(userId, keptPaymentIds, now);
        return saved;
    }

    /**
     * 기준 지출과 평균 결제액.
     *
     * @param monthlyAmount 카테고리 월평균의 합(설계서 §1). 안내 문구·화면 표시에 쓴다.
     * @param periodAmount  그것을 <b>챌린지 일수로 환산</b>한 값. 예산·달성률 산수는 이쪽을 쓴다.
     */
    record Baseline(long monthlyAmount, long periodAmount, Long avgTransactionAmount) {}

    /**
     * 기존 {@link AnalysisResult}에서 기준선을 파생한다. 설계서 §1: "기준 지출은 분석이 낸
     * <b>카테고리 월평균</b>의 합이다. 서비스가 다시 계산하지 않는다."
     *
     * <p>예전에는 분모로 {@code monthlySpend().size()}(사용자가 <b>아무거나</b> 결제한 달의 수)를
     * 썼다. 분자는 카테고리 하나의 총액인데 분모가 전체 기간이라, 최근 시작한 습관일수록
     * 크게 과소평가됐다 — 12개월 이력자가 지난달 배달 30만원을 썼으면 기준이 2.5만원이 되어
     * 챌린지 시작 직후 예산을 넘겼다. 이제 분모를 엔진이 카테고리별로 세어 준다.
     *
     * <p><b>창을 챌린지 기간과 같게 잡는다</b>(2026-07-31). 전 기간 월평균을 쓰면 화면이
     * 보여준 금액과 사용자가 훑을 수 있는 결제 목록이 어긋난다 — 온보딩에서 "이 결제는 낭비가
     * 아니다"를 골라도 그 금액이 기준의 어디에서 빠지는지 대응되지 않는다. 최근 {@code days}일
     * <b>실측</b>이면 목록과 금액이 1:1로 맞는다.
     *
     * <p>부작용도 정직하게 적어 둔다 — 최근 한 달이 튀면 기준도 튄다. 그래도 숨겨진 평균보다
     * 보이는 실측이 낫다는 것이 이 화면의 판단이다(사용자 결정 2026-07-31).
     */
    Baseline baselineFor(Long userId, List<String> categories, LocalDateTime now, int days) {
        return baselineFor(userId, categories, now, days, List.of());
    }

    /**
     * @param keptPaymentIds 사용자가 <b>"이건 낭비가 아니다"</b>로 뺀 결제 id. 그 금액을 기준에서 뺀다.
     *                       화면은 이미 뺀 금액으로 '지킬 돈'을 계산해 보여줬으므로, 서버가 안 빼면
     *                       예산만 넉넉해져 사용자가 고른 의미가 사라진다.
     */
    Baseline baselineFor(Long userId, List<String> categories, LocalDateTime now, int days,
                         List<String> keptPaymentIds) {
        AnalysisResult analysis = analysisEngine.analyze(userId, now, days);

        long total = 0L;
        long count = 0L;
        for (String code : categories) {
            AnalysisResult.CategoryStat stat = analysis.categoryStats().get(code);
            if (stat == null) continue;
            total += stat.totalAmount().setScale(0, RoundingMode.HALF_UP).longValue();
            count += stat.count();
        }
        // 사용자가 뺀 결제만큼 기준에서 덜어낸다. 창 안·고른 카테고리 안의 것만 센다 —
        // 화면이 보여준 목록이 정확히 그 범위였기 때문이다.
        Kept kept = keptOf(userId, categories, now, days, keptPaymentIds);
        total = Math.max(0L, total - kept.amount());
        count = Math.max(0L, count - kept.count());

        // 창이 곧 챌린지 기간이므로 **창 안의 실제 합계**가 그대로 기준 지출이다.
        // 예전에는 monthlyAmount(총액÷관측월)와 amountOver(days)를 따로 냈는데, 창을 쓰는
        // 지금은 둘 다 같은 값이 되고 환산이 오히려 오차를 만든다.
        return new Baseline(total, total, count > 0 ? total / count : null);
    }

    /** NORMAL → LENIENT 로 가는 데 필요한 '낭비 아님' 횟수. 설계 원칙 4 — 임계치는 yml. */
    @org.springframework.beans.factory.annotation.Value("${finntech.ml.stance-to-lenient:1}")
    private int toLenient;
    /** LENIENT → EXCLUDED 로 가는 데 필요한 누적 횟수. */
    @org.springframework.beans.factory.annotation.Value("${finntech.ml.stance-to-excluded:3}")
    private int toExcluded;

    /**
     * 뺀 결제가 가리킨 가맹점의 성향을 한 칸 올린다.
     *
     * <p><b>왜 여기인가.</b> 뺀 것을 그 챌린지에만 반영하면 다음 달에 같은 가게가 또 낭비로 떠서
     * 사용자가 같은 판단을 되풀이해야 한다. 그렇다고 한 번에 제외해 버리면 <b>같은 가게에서
     * 낭비 목적으로 사는 경우</b>를 영영 못 잡는다(사용자 지적 2026-07-31). 그래서 한 칸씩만
     * 올리고, 사용자가 "역시 낭비였다"고 하면 되돌아간다.
     *
     * <p>사업자번호가 없는 결제(해외 등)는 건너뛴다 — 묶을 신원이 없다.
     */
    /**
     * 원장에서 온 "이건 낭비가 아니다"를 성향으로 올린다 (2026-08-02).
     *
     * <p><b>왜 뒤늦게 붙었나.</b> 온보딩에서 뺀 결제는 처음부터 성향으로 쌓였는데(§8-S),
     * <b>같은 뜻의 신호가 원장에서는 버려지고 있었다</b> — {@code undo(NOT_MINE)} 은 거래를
     * 챌린지에서 빼기만 했고, 알림 피드백은 컬럼에만 남았다. 사용자는 매달 같은 판단을
     * 되풀이해야 했다. 원장이 사업자번호를 갖게 된 뒤에야(V15) 이을 수 있게 됐다.
     *
     * <p><b>{@code EXEMPTION}은 부르지 않는다.</b> "인정하지만 이번은 봐달라"는
     * <b>낭비임을 인정하는</b> 말이다. 이걸 관대함으로 세면, 면제권을 쓸수록 그 가게가
     * 낭비에서 빠지는 정반대 결과가 된다. 되돌리기 두 사유는 <b>뜻이 반대다.</b>
     */
    private void promoteStanceOf(Long userId, GuardianTransaction tx, LocalDateTime now) {
        String biz = tx.getBusinessNumber();
        if (biz == null || biz.isBlank()) return;   // 묶을 신원이 없다
        var st = stanceRepository.findByUserIdAndBusinessNumber(userId, biz)
                .orElseGet(() -> new com.finntech.domain.UserMerchantStance(
                        userId, biz, tx.getMerchantName(), now));
        st.kept(toLenient, toExcluded, now);
        stanceRepository.save(st);
    }

    private void promoteStances(Long userId, List<String> keptPaymentIds, LocalDateTime now) {
        if (keptPaymentIds == null || keptPaymentIds.isEmpty()) return;
        for (var p : userPaymentRepository.findAllById(keptPaymentIds)) {
            if (!userId.equals(p.getUserId())) continue;
            String biz = p.getBusinessNumber();
            if (biz == null || biz.isBlank()) continue;
            var st = stanceRepository.findByUserIdAndBusinessNumber(userId, biz)
                    .orElseGet(() -> new com.finntech.domain.UserMerchantStance(
                            userId, biz, p.getMerchantName(), now));
            st.kept(toLenient, toExcluded, now);
            stanceRepository.save(st);
        }
    }

    /**
     * 카테고리별 예산을 적재한다.
     *
     * <p>온보딩3은 카테고리마다 다른 강도를 받는다 — 배달은 50%, 카페는 20%처럼. 그런데 서버는
     * 지금까지 <b>지킬 돈 하나</b>만 받았고, 화면이 카테고리별로 보여줄 때는 전체 캡을 균등분할했다.
     * 사용자가 정한 것과 화면이 보여준 것이 달랐다(2026-07-31).
     *
     * <p>{@code categoryTargets}가 없으면(옛 클라이언트·테스트) 예전처럼 균등분할한다 —
     * 그때는 값이 바뀌지 않으므로 안전하다.
     *
     * <p>여기서 만든 예산은 <b>판정에 쓰지 않는다.</b> 챌린지 성공/실패와 잔디는 합계 기준
     * 그대로다(사용자 결정). 이 값은 어디서 새는지 보여주고 알리는 데 쓴다.
     */
    private void saveCategoryCaps(GuardianChallenge ch, List<String> categories, LocalDateTime now,
                                  int days, List<String> keptPaymentIds,
                                  Map<String, Long> categoryTargets, long totalTarget) {
        Map<String, Long> baselines = baselineByCategory(ch.getUserId(), categories, now, days, keptPaymentIds);
        boolean perCategory = categoryTargets != null && !categoryTargets.isEmpty();
        int n = Math.max(1, categories.size());
        List<GuardianChallengeCategory> rows = new ArrayList<>(categories.size());
        for (String code : categories) {
            long base = baselines.getOrDefault(code, 0L);
            long tgt = perCategory
                    ? categoryTargets.getOrDefault(code, 0L)
                    : totalTarget / n;
            // 지킬 돈이 기준을 넘으면 예산이 음수가 된다 — 한 칸 낮춰 0원 예산을 만들지 않는다.
            if (tgt >= base && base > 0) tgt = base - 1;
            rows.add(new GuardianChallengeCategory(ch.getId(), code, base, Math.max(0L, tgt), now));
        }
        challengeCategoryRepository.saveAll(rows);
    }

    /** 카테고리별 창 안 실측 — 뺀 결제는 그 카테고리에서 뺀다. */
    private Map<String, Long> baselineByCategory(Long userId, List<String> categories,
                                                 LocalDateTime now, int days,
                                                 List<String> keptPaymentIds) {
        AnalysisResult analysis = analysisEngine.analyze(userId, now, days);
        Map<String, Long> out = new LinkedHashMap<>();
        for (String code : categories) {
            AnalysisResult.CategoryStat stat = analysis.categoryStats().get(code);
            out.put(code, stat == null ? 0L
                    : stat.totalAmount().setScale(0, RoundingMode.HALF_UP).longValue());
        }
        // 뺀 결제를 그 카테고리에서 덜어낸다.
        if (keptPaymentIds != null && !keptPaymentIds.isEmpty()) {
            LocalDateTime from = now.minusDays(days);
            for (var p : userPaymentRepository.findAllById(keptPaymentIds)) {
                if (!userId.equals(p.getUserId())) continue;
                if (p.getPaymentDate().isBefore(from) || p.getPaymentDate().isAfter(now)) continue;
                String c = p.getCategory2();
                if (c == null || !out.containsKey(c)) continue;
                out.merge(c, -(long) p.getAmount(), Long::sum);
            }
            out.replaceAll((k, v) -> Math.max(0L, v));
        }
        return out;
    }

    /** 사용자가 뺀 결제의 합계와 건수. */
    private record Kept(long amount, long count) {}

    /**
     * 뺀 결제들 중 <b>창 안에 있고 고른 카테고리에 속한 것</b>만 센다.
     *
     * <p>화면이 보여준 목록이 정확히 그 범위였다. 범위를 넓히면 화면에 없던 결제까지 빠져
     * 기준이 설명 불가능해지고, 좁히면 사용자가 뺀 것이 반영되지 않는다.
     *
     * <p>기준은 {@code Consumption}에서 나오는데 결제 id는 {@code UserPayment}에만 있다.
     * 마이데이터 소비는 결제에서 투영된 것이라 금액이 1:1로 대응한다.
     */
    private Kept keptOf(Long userId, List<String> categories, LocalDateTime now, int days,
                        List<String> keptPaymentIds) {
        if (keptPaymentIds == null || keptPaymentIds.isEmpty()) return new Kept(0L, 0L);
        LocalDateTime from = now.minusDays(days);
        Set<String> cats = new HashSet<>(categories);
        long amount = 0L, count = 0L;
        for (var p : userPaymentRepository.findAllById(keptPaymentIds)) {
            if (!userId.equals(p.getUserId())) continue;                 // 남의 결제는 세지 않는다
            if (p.getPaymentDate().isBefore(from) || p.getPaymentDate().isAfter(now)) continue;
            if (p.getCategory2() != null && !cats.contains(p.getCategory2())) continue;
            amount += p.getAmount();
            count++;
        }
        return new Kept(amount, count);
    }

    // ======================================================================
    //  2. 거래 수신 (설계서 §API 1)
    // ======================================================================

    public record IngestCommand(LocalDateTime occurredAt, String merchantName, String merchantDisplayName,
                                long amount, String mcc, String category, Double categoryConfidence,
                                TxType txType, boolean demo, Long sourceConsumptionId,
                                /** 가맹점 사업자번호 — 판정 성향(§8-S)이 붙는 키. 모르면 null. */
                                String businessNumber) {

        /** 사업자번호를 모르는 옛 호출부용. 그때는 성향에 묶지 않는다. */
        public IngestCommand(LocalDateTime occurredAt, String merchantName, String merchantDisplayName,
                             long amount, String mcc, String category, Double categoryConfidence,
                             TxType txType, boolean demo, Long sourceConsumptionId) {
            this(occurredAt, merchantName, merchantDisplayName, amount, mcc, category,
                    categoryConfidence, txType, demo, sourceConsumptionId, null);
        }
    }

    public record IngestResult(GuardianTransaction transaction, GuardianRules.Snapshot snapshot,
                               ChallengeState state, GuardianNotification notification) {}

    /**
     * 거래 한 건을 원장에 넣고 개입 여부를 판정한다.
     *
     * <p>순서가 고정이다: ① 환불 복원 ② 분류 미확정 보류 ③ 성역·무관 제외 ④ 낙관적 집계
     * ⑤ 개입 판정 ⑥ 문장. 분류 신뢰도가 임계 미만이면 <b>집계하지 않고</b> 되묻기만 한다 —
     * 분류 전에는 판정할 수 없다.
     */
    @Transactional
    public IngestResult ingest(Long userId, IngestCommand cmd) {
        LocalDateTime now = clock.now(userId);
        LocalDate today = now.toLocalDate();
        GuardianChallenge ch = challengeRepository.findRunning(userId).orElse(null);

        boolean micro = cmd.amount() < props.getMicroTxThreshold();
        GuardianTransaction tx = new GuardianTransaction(userId, ch == null ? null : ch.getId(),
                cmd.occurredAt() == null ? now : cmd.occurredAt(), now,
                cmd.merchantName(), cmd.merchantDisplayName(), cmd.amount(), cmd.mcc(),
                cmd.category(), cmd.categoryConfidence(), cmd.txType(), micro, cmd.demo());
        tx.setSourceConsumptionId(cmd.sourceConsumptionId());
        tx.setBusinessNumber(cmd.businessNumber());

        // 챌린지가 없거나 정산 단계면 원장만 남기고 조용히 끝낸다.
        if (ch == null || !ch.isRunning()) {
            tx.exclude();
            txRepository.save(tx);
            return new IngestResult(tx, null, ch == null ? null : ch.getState(), null);
        }

        boolean counted = classify(ch, tx, now);
        if (counted) {
            ch.setSpentAmount(ch.getSpentAmount() + tx.getAmount());
            // 초과 확정은 배치의 몫이다 — 거래 순간에 EXCEEDED로 넘기면 24시간 안에
            // "챌린지랑 상관없어요"로 되돌렸을 때 이미 초과 알림이 나간 뒤가 된다.
            ChallengeState next = GuardianRules.nextStateOnSpend(
                    ch.getState(), ratio(ch), props.getAtRiskRatio());
            ch.setState(next == ChallengeState.EXCEEDED ? ChallengeState.AT_RISK : next);
        }
        txRepository.save(tx);
        challengeRepository.save(ch);

        GuardianRules.Snapshot snap = snapshotOf(ch, today);
        GuardianRules.InterventionDecision decision = GuardianRules.evaluateIntervention(
                context(ch, snap, tx, today, now), props);
        GuardianNotification noti = deliver(ch, tx, decision, snap, today, now);

        return new IngestResult(tx, snap, ch.getState(), noti);
    }

    /**
     * 거래를 분류해 상태를 세운다. 집계했으면 true.
     *
     * <p><b>판정 축은 {@link GuardianRules#resolveKind} 하나다</b>(스펙 v1.5 §5.1).
     * 예전에는 여기와 {@code evaluateIntervention}이 같은 조건을 각자 적어 두 곳이
     * 조금씩 어긋날 수 있었다. 이제 종류는 순수 함수가 정하고, 원장을 실제로 움직이는
     * 일만 여기서 한다.
     */
    private boolean classify(GuardianChallenge ch, GuardianTransaction tx, LocalDateTime now) {
        if (tx.getTxType() == TxType.REFUND) {
            restoreRefund(ch, tx);
            return false;
        }

        TxKind kind = GuardianRules.resolveKind(
                new GuardianRules.TxView(tx.getCategory(), tx.getCategoryConfidence(),
                        tx.getTxType(), tx.getAmount(), tx.isFixedExpense()),
                viewOf(ch), props.getCategoryConfidenceThreshold());
        tx.setKind(kind);

        // 분류가 확정되지 않았으면 보류. 나중에 분류가 붙으면 그때 집계한다.
        if (kind == TxKind.UNKNOWN) return false;

        // 성역·고정지출·관리 밖 지출은 원장에 남기되 예산에서 빼지 않는다.
        if (!kind.countsAgainstCap()) {
            tx.exclude();
            return false;
        }
        // 챌린지 기간 밖의 거래는 이 챌린지의 지출이 아니다. 예전에는 발생일을 보지 않아
        // 2년 전 결제 한 건으로도 예산을 태울 수 있었다(수신 API가 occurredAt을 검증하지 않는다).
        LocalDate occurredOn = tx.getOccurredAt().toLocalDate();
        if (occurredOn.isBefore(ch.getStartDate()) || occurredOn.isAfter(ch.getEndDate())) {
            tx.exclude();
            return false;
        }
        // 집계일은 **거래가 일어난 날**이다. 예전에는 '가상 오늘'로 찍어서, 데모로 시계를 민 뒤
        // 과거 날짜 결제를 넣으면 그 돈이 오늘 지출로 잡혔다. 그 날의 일 판정은 이미
        // '무지출'로 확정돼 사물까지 지급된 뒤였고, 멱등 조기 반환 때문에 되돌려지지도 않았다.
        tx.count(occurredOn, now.plusHours(props.getUndoWindowHours()));
        return true;
    }

    /** 환불 — 원 거래를 찾아 예산을 조용히 복원한다(C12). 알림은 만들지 않는다. */
    private void restoreRefund(GuardianChallenge ch, GuardianTransaction refund) {
        refund.exclude();
        txRepository.findByChallenge(ch.getId()).stream()
                .filter(GuardianTransaction::isCounted)
                .filter(t -> t.getAmount() == refund.getAmount()
                        && Objects.equals(t.getCategory(), refund.getCategory()))
                .reduce((first, second) -> second)   // 가장 최근 것
                .ifPresent(original -> {
                    original.exclude();
                    refund.setOriginalTxId(original.getId());
                    ch.setSpentAmount(ch.getSpentAmount() - original.getAmount());
                    txRepository.save(original);
                });
    }

    // ======================================================================
    //  3. 되돌리기 (설계서 §API 2)
    // ======================================================================

    public record UndoResult(GuardianTransaction transaction, GuardianRules.Snapshot snapshot,
                             ChallengeState state, String toast, GuardianItems items) {}

    /**
     * 되돌리기. 유예가 지났으면 거절한다.
     *
     * <p>결과로 알림을 만들지 않는다 — 화면 숫자만 조용히 갱신한다. 되돌린 것까지 알림이 오면
     * 사용자는 자기가 한 행동을 통보받는 셈이 된다.
     */
    @Transactional
    public UndoResult undo(Long userId, Long transactionId, UndoReason reason) {
        LocalDateTime now = clock.now(userId);
        GuardianTransaction tx = txRepository.findById(transactionId)
                .filter(t -> t.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "거래를 찾을 수 없어요"));

        if (!tx.isUndoable(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, GuardianCopy.UNDO_EXPIRED);
        }

        GuardianItems items = rewardService.items(userId, now);
        if (reason == UndoReason.EXEMPTION && !items.useExemption(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "면제권이 없어요");
        }

        GuardianChallenge ch = challengeRepository.findById(tx.getChallengeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "챌린지를 찾을 수 없어요"));

        tx.undo(reason, now);
        // "내 소비가 아니다"는 §8-S가 받는 것과 같은 신호다 — 다음 달에 또 묻지 않도록 성향에 쌓는다.
        // EXEMPTION은 부르지 않는다. 그건 낭비를 인정하고 봐달라는 뜻이라 방향이 반대다.
        if (reason == UndoReason.NOT_MINE) promoteStanceOf(userId, tx, now);
        ch.setSpentAmount(ch.getSpentAmount() - tx.getAmount());
        // 이 거래 때문에 일어난 상태 전이를 취소한다.
        ch.setState(GuardianRules.nextStateOnSpend(
                ch.getState() == ChallengeState.EXCEEDED ? ChallengeState.ACTIVE : ch.getState(),
                ratio(ch), props.getAtRiskRatio()));

        txRepository.save(tx);
        challengeRepository.save(ch);

        GuardianRules.Snapshot snap = snapshotOf(ch, now.toLocalDate());
        return new UndoResult(tx, snap, ch.getState(),
                GuardianCopy.undoToast(snap.remainingCap()), items);
    }

    /** 늦게 붙은 분류를 반영한다 — PENDING_CATEGORY를 풀고 집계까지 이어간다(라벨링 포인트 대상). */
    @Transactional
    public IngestResult classifyPending(Long userId, Long transactionId, String category, Double confidence) {
        LocalDateTime now = clock.now(userId);
        LocalDate today = now.toLocalDate();
        GuardianTransaction tx = txRepository.findById(transactionId)
                .filter(t -> t.getUserId().equals(userId))
                .filter(t -> t.getState() == TxState.PENDING_CATEGORY)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "분류 대기 중인 거래가 아니에요"));

        GuardianChallenge ch = challengeRepository.findById(tx.getChallengeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "챌린지를 찾을 수 없어요"));

        tx.assignCategory(category, confidence == null ? 1.0 : confidence);
        if (classify(ch, tx, now)) {
            ch.setSpentAmount(ch.getSpentAmount() + tx.getAmount());
            ChallengeState next = GuardianRules.nextStateOnSpend(ch.getState(), ratio(ch), props.getAtRiskRatio());
            ch.setState(next == ChallengeState.EXCEEDED ? ChallengeState.AT_RISK : next);
        }
        txRepository.save(tx);
        challengeRepository.save(ch);

        rewardService.award(userId, ch.getId(), PointType.LABELING, today, tx.getId(), now);
        return new IngestResult(tx, snapshotOf(ch, today), ch.getState(), null);
    }

    // ======================================================================
    //  4. 마이데이터 브리지
    // ======================================================================

    /**
     * 마이데이터 투영({@code Consumption(MYDATA)})에서 아직 원장에 안 들어온 결제를 끌어온다.
     *
     * <p>{@code MyDataLinkService}를 고치지 않고 <b>당겨오는</b> 방식을 택했다 — 연동 서비스에
     * 지킴이 호출을 심으면 리포트·점수·FDS와 얽힌 경로에 지킴이 장애가 번진다.
     *
     * @return 새로 적재한 건수
     */
    @Transactional
    public int syncFromMyData(Long userId) {
        GuardianChallenge ch = challengeRepository.findRunning(userId).orElse(null);
        if (ch == null) return 0;

        LocalDateTime from = ch.getStartDate().atStartOfDay();
        LocalDateTime to = ch.getEndDate().plusDays(1).atStartOfDay();
        int added = 0;
        for (Consumption c : consumptionRepository.findInRange(userId, from, to)) {
            if (txRepository.existsByUserIdAndSourceConsumptionId(userId, c.getId())) continue;
            Category cat = c.getCategory();
            /* 가맹점을 되찾는다. 예전에는 가맹점명 자리에 <b>카테고리 이름</b>을 넣었다 —
               화면에 "식비"가 가게 이름으로 떴고, 사업자번호가 없어 사용자의 "이 결제는
               챌린지랑 상관없어요"가 성향(§8-S)으로 이어지지 못했다. 결제 키를 달아 뒀으므로
               (V15) 역산 없이 정확히 찾는다. 못 찾으면 예전처럼 카테고리 이름으로 둔다. */
            UserPayment src = c.getSourcePaymentId() == null ? null
                    : userPaymentRepository.findById(c.getSourcePaymentId()).orElse(null);
            String merchant = src != null && src.getMerchantName() != null
                    ? src.getMerchantName() : cat.getDisplayName();
            ingest(userId, new IngestCommand(
                    c.getOccurredAt(), merchant, merchant,
                    c.getAmount().setScale(0, RoundingMode.HALF_UP).longValue(),
                    null, cat.getCode(), 1.0, TxType.EXPENSE, false, c.getId(),
                    src == null ? null : src.getBusinessNumber()));
            added++;
        }
        return added;
    }

    // ======================================================================
    //  5. 알림 전달
    // ======================================================================

    /** 결정을 실제 알림으로 만든다. 침묵이면 사유와 함께 로그만 남긴다. */
    GuardianNotification deliver(GuardianChallenge ch, GuardianTransaction tx,
                                 GuardianRules.InterventionDecision decision,
                                 GuardianRules.Snapshot snap, LocalDate today, LocalDateTime now) {
        return deliver(ch, tx, decision, snap, today, now, Map.of());
    }

    /**
     * 문장에 넣을 값을 호출부가 <b>덧붙이는</b> 전달.
     *
     * <p>{@link #numbersFor}는 챌린지·거래·스냅샷에서 나오는 값만 안다. C9(위험 시간대 넛지)의
     * 요일·시간대·4주 횟수처럼 <b>그 케이스에서만 나오는 값</b>은 계산한 쪽이 넘긴다 —
     * 반대로 numbersFor 가 모든 케이스의 사정을 알게 만들면 그 메서드가 케이스 목록이 된다.
     */
    GuardianNotification deliver(GuardianChallenge ch, GuardianTransaction tx,
                                 GuardianRules.InterventionDecision decision,
                                 GuardianRules.Snapshot snap, LocalDate today, LocalDateTime now,
                                 Map<String, Object> extras) {
        Long txId = tx == null ? null : tx.getId();
        if (decision.silent()) {
            return notificationRepository.save(GuardianNotification.silent(
                    ch.getUserId(), ch.getId(), txId, decision.caseId(), decision.reason(), now));
        }

        GuardianRules.CaseDef def = GuardianRules.caseById(decision.caseId());
        // 야간에는 미루되, 예산 초과 통보(C6)처럼 미룰 수 없는 건은 예외다.
        if (!def.bypassBudget() && GuardianRules.isNight(now, props)) {
            return notificationRepository.save(GuardianNotification.silent(
                    ch.getUserId(), ch.getId(), txId, decision.caseId(), SuppressedReason.NIGHT, now));
        }

        /*
         * 하루 말수 상한 (C13).
         *
         * **이 판정이 죽어 있었다.** 규칙은 `GuardianRules` 안에 쓰여 있었는데 그 경로(`Ctx`)를
         * 부르는 곳이 없어, 설정값 `daily-push-limit` 도 사용자 설정도 아무 효력이 없었다.
         * 실제로 알림을 만드는 자리는 여기 하나뿐이므로 여기서 센다.
         *
         * 사람마다 정한 값이 우선이고(0이면 '설정 안 함'), 없으면 전역 기본값을 따른다.
         * 예산 초과 통보처럼 미룰 수 없는 건은 상한을 넘겨도 나간다 — 말수를 줄인 것이지
         * 위험을 알리지 말라고 한 것이 아니다.
         */
        if (!def.bypassBudget()) {
            int limit = userRepository.findById(ch.getUserId())
                    .map(u -> u.getNotifyDailyLimit())
                    .filter(v -> v > 0)
                    .orElse(props.getNotification().getDailyPushLimit());
            int sent = notificationRepository.countPushToday(
                    ch.getUserId(), today.atStartOfDay(), today.plusDays(1).atStartOfDay());
            if (sent >= limit) {
                return notificationRepository.save(GuardianNotification.silent(
                        ch.getUserId(), ch.getId(), txId, decision.caseId(),
                        SuppressedReason.BUDGET, now));
            }
        }

        Map<String, Object> numbers = numbersFor(ch, tx, snap, today);
        if (extras != null) numbers.putAll(extras);
        GuardianNarrative.Message msg = narrative.compose(
                decision.caseId(), decision.tone(), decision.phrasingMode(),
                numbers, recentKeyPhrases(ch.getId(), now), false,
                userRepository.existsByIdAndRealPersonTrue(ch.getUserId()));

        return notificationRepository.save(GuardianNotification.spoken(
                ch.getUserId(), ch.getId(), txId, decision.caseId(),
                decision.tone(), decision.phrasingMode(), DeliveryKind.PUSH,
                msg.title(), msg.body(), GuardianRules.stripFixedPhrases(msg.keyPhrases()),
                msg.fallback(), GuardianCopy.PROMPT_VERSION, now));
    }

    /** 문장에 넣을 값 — 전부 이미 계산이 끝난 것이다. LLM은 여기 있는 것만 쓴다. */
    private Map<String, Object> numbersFor(GuardianChallenge ch, GuardianTransaction tx,
                                           GuardianRules.Snapshot snap, LocalDate today) {
        Map<String, Object> v = new TreeMap<>();
        v.put("remaining", Math.max(0L, snap.remainingCap()));
        v.put("cap", ch.getChallengeCap());
        v.put("secured", snap.securedSaving());
        v.put("daysLeft", snap.daysLeft());
        v.put("days", ch.getNoSpendStreak());
        topCategory(ch.getId()).ifPresent(top -> v.put("topCategory", top));
        if (tx != null) {
            v.put("amount", tx.getAmount());
            v.put("category", categoryLabel(tx.getCategory()));
            v.put("count", txRepository.countCountedByCategory(ch.getId(), tx.getCategory()));
            v.put("total", txRepository.sumMicroOnDate(ch.getId(), today));
        }
        return v;
    }

    /**
     * 이번 챌린지에서 가장 많이 쓴 카테고리 — 예산을 말하는 알림(C3·C6)이 "어디서 새는지" 지목하는 데 쓴다.
     *
     * <p>알림을 카테고리별로 쪼개지 않기로 한 대신 넣는 값이다(2026-08-02). 판정은 합계 기준이므로
     * (tech_log §8-T) <b>발화 단위는 합계 하나</b>로 두고, 카테고리는 본문 안에서 지목만 한다.
     *
     * <p><b>카테고리가 하나뿐이면 비운다.</b> 지목할 것이 없는데 "가장 많이 쓴 건 식비예요"라고
     * 말하면 정보가 아니라 군더더기다. 동점이면 카테고리 코드 순으로 하나를 고른다 —
     * 조회 정렬은 결정론이어야 한다(마스터 §4 원칙 3).
     */
    private Optional<String> topCategory(Long challengeId) {
        List<Object[]> sums = txRepository.sumCountedByCategory(challengeId);
        if (sums.size() < 2) return Optional.empty();
        return sums.stream()
                .filter(row -> row[0] != null)
                .max(Comparator
                        .<Object[]>comparingLong(row -> ((Number) row[1]).longValue())
                        .thenComparing(row -> String.valueOf(row[0]), Comparator.reverseOrder()))
                .map(row -> categoryLabel(String.valueOf(row[0])));
    }

    /** 카테고리 코드 → 사람이 읽는 이름. 코드에 카테고리 이름을 박지 않는다(마스터 §4 원칙 4). */
    String categoryLabel(String code) {
        if (code == null) return "";
        return categoryRepository.findByCode(code).map(Category::getDisplayName).orElse(code);
    }

    /** 최근 쓴 특징 표현 — 지킴이가 같은 말을 반복하지 않게 한다. */
    List<String> recentKeyPhrases(Long challengeId, LocalDateTime now) {
        List<String> out = new ArrayList<>();
        for (GuardianNotification n : notificationRepository.findSpokenSince(challengeId, now.minusDays(7))) {
            out.addAll(n.getKeyPhraseList());
            if (out.size() >= 12) break;
        }
        return out;
    }

    /** 케이스별 최근 발송 시각 — 쿨다운 판정의 재료. 말한 것만 센다. */
    Map<String, List<LocalDateTime>> caseSentAt(Long challengeId) {
        Map<String, List<LocalDateTime>> m = new TreeMap<>();
        for (GuardianNotification n : notificationRepository.findAllSpoken(challengeId)) {
            m.computeIfAbsent(n.getCaseId(), k -> new ArrayList<>()).add(n.getSentAt());
        }
        return m;
    }

    // ======================================================================
    //  6. 스냅샷 · 컨텍스트
    // ======================================================================

    /** 판정 함수에 넘길 챌린지 뷰. */
    GuardianRules.ChallengeView viewOf(GuardianChallenge ch) {
        return new GuardianRules.ChallengeView(ch.getState(), ch.getCategorySet(), ch.getSanctuarySet(),
                ch.getBaselineAmount(), ch.getTargetSaving(), ch.getChallengeCap(),
                ch.getBufferRatio(), ch.getDaysTotal(), ch.getSpentAmount());
    }

    public GuardianRules.Snapshot snapshotOf(GuardianChallenge ch, LocalDate onDate) {
        return GuardianRules.computeSnapshot(viewOf(ch), ch.daysElapsedOn(onDate));
    }

    private GuardianRules.InterventionContext context(GuardianChallenge ch, GuardianRules.Snapshot snap,
                                                      GuardianTransaction tx, LocalDate today, LocalDateTime now) {
        GuardianRules.TxView txView = tx == null ? null : new GuardianRules.TxView(
                tx.getCategory(), tx.getCategoryConfidence(), tx.getTxType(), tx.getAmount(),
                tx.isFixedExpense());

        String category = tx == null ? null : tx.getCategory();
        int weekly = category == null ? 0 : txRepository.countCountedByCategoryInRange(
                ch.getId(), category, today.minusDays(6), today);
        int total = category == null ? 0 : txRepository.countCountedByCategory(ch.getId(), category);

        LocalDateTime dayStart = today.atStartOfDay();
        int pushToday = notificationRepository.countPushToday(ch.getUserId(), dayStart, dayStart.plusDays(1));

        return new GuardianRules.InterventionContext(viewOf(ch), snap, txView, weekly, total,
                txRepository.sumMicroOnDate(ch.getId(), today), pushToday, caseSentAt(ch.getId()), now);
    }

    private double ratio(GuardianChallenge ch) {
        return ch.getChallengeCap() > 0 ? (double) ch.getSpentAmount() / ch.getChallengeCap() : 0.0;
    }

    private static double orZero(Double v) { return v == null ? 0.0 : v; }

    // ======================================================================
    //  7. 홈 (설계서 §API 3) — 프론트는 다시 계산하지 않는다
    // ======================================================================

    // ======================================================================
    //  설정 (마이 > 설정)
    // ======================================================================

    /**
     * 성역을 다시 정한다.
     *
     * <p><b>줄이기로 한 곳은 성역이 될 수 없다.</b> 둘 다이면 "줄이라고 하면서 침묵한다"가 되어
     * 앞뒤가 안 맞는다. 화면도 그 칸을 막아 두지만, 서버가 다시 본다 — 화면만 막으면 규칙이
     * 화면에 있는 셈이다.
     */
    @Transactional
    public java.util.Set<String> setSanctuary(Long userId, List<String> categories) {
        GuardianChallenge ch = challengeRepository.findRunning(userId).orElseThrow(
                () -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "진행 중인 챌린지가 없어요"));
        List<String> want = categories == null ? List.of() : categories;
        List<String> clash = want.stream().filter(ch.getCategorySet()::contains).toList();
        if (!clash.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    String.join("·", clash) + "은(는) 이번에 줄이기로 한 곳이라 성역으로 둘 수 없어요");
        }
        ch.setSanctuaryCategories(want);
        challengeRepository.save(ch);
        return ch.getSanctuarySet();
    }

    /**
     * 챌린지 카테고리 한 줄 — 관리 화면이 읽는 값.
     *
     * @param baseline 기준 지출(실측). 사용자가 정할 값이 아니다.
     * @param target   지키기로 한 돈. 이 값만 사용자가 옮긴다.
     * @param cap      예산 = 기준 − 지킬 돈.
     * @param spent    지금까지 그 카테고리에서 쓴 돈 — 목표를 얼마나 낮출 수 있는지의 바닥이다.
     */
    public record ChallengeCategoryView(String category, String label,
                                        long baseline, long target, long cap, long spent) {}

    /** 그 챌린지에서 카테고리별로 집계된 사용액. 홈과 관리 화면이 같은 수를 본다. */
    private Map<String, Long> spentByCategory(GuardianChallenge ch) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object[] row : txRepository.sumCountedByCategory(ch.getId())) {
            if (row[0] == null) continue;
            out.put((String) row[0], ((Number) row[1]).longValue());
        }
        return out;
    }

    /** 진행 중 챌린지의 카테고리들. 없으면 빈 목록 — 화면이 "없다"를 스스로 말한다. */
    @Transactional(readOnly = true)
    public List<ChallengeCategoryView> challengeCategories(Long userId) {
        GuardianChallenge ch = challengeRepository.findRunning(userId).orElse(null);
        if (ch == null) return List.of();
        Map<String, Long> spentBy = spentByCategory(ch);
        List<ChallengeCategoryView> out = new ArrayList<>();
        for (GuardianChallengeCategory c : challengeCategoryRepository.findByChallenge(ch.getId())) {
            out.add(new ChallengeCategoryView(c.getCategory(), categoryLabel(c.getCategory()),
                    c.getBaseline(), c.getTarget(), c.getCap(),
                    spentBy.getOrDefault(c.getCategory(), 0L)));
        }
        return out;
    }

    /**
     * 한 카테고리의 지킬 돈을 다시 정한다.
     *
     * <p><b>이미 쓴 돈보다 예산을 낮출 수는 없다.</b> 그러면 저장하는 순간 예산 초과가 되어
     * 사용자가 한 적 없는 실패가 만들어진다. 바닥을 알려주고 거기서 멈춘다.
     */
    @Transactional
    public List<ChallengeCategoryView> retarget(Long userId, String category, long target) {
        GuardianChallenge ch = challengeRepository.findRunning(userId).orElseThrow(
                () -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "진행 중인 챌린지가 없어요"));
        GuardianChallengeCategory row = challengeCategoryRepository.findByChallenge(ch.getId())
                .stream().filter(c -> c.getCategory().equals(category)).findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "이번 챌린지에 없는 카테고리예요"));

        long spent = spentByCategory(ch).getOrDefault(category, 0L);
        long maxTarget = Math.max(0L, row.getBaseline() - spent);
        if (target > maxTarget) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "이미 " + GuardianCopy.won(spent) + "원을 써서 그만큼은 못 지켜요");
        }
        row.retarget(target);
        challengeCategoryRepository.save(row);
        return challengeCategories(userId);
    }

    /**
     * 진행 중 챌린지에 줄일 카테고리를 하나 더한다 (마이 &gt; 챌린지 관리 &gt; 새 챌린지 만들기).
     *
     * <p><b>새 챌린지를 만들지 않고 기존 것에 붙인다.</b> 카테고리마다 챌린지를 따로 만들면
     * 기간이 제각각이 되어 "이번 달"이라는 말이 뜻을 잃고, 월말 결산도 여러 번 일어난다.
     * 화면에서는 줄이 하나 느는 것으로 보이지만 안에서는 같은 챌린지가 넓어진 것이다.
     *
     * <p>기준 지출은 <b>남은 기간으로 환산</b>한다 — 한 달짜리 기준을 열흘 남은 챌린지에 그대로
     * 얹으면 예산이 통째로 남아돌아 아무것도 지키지 않은 게 된다.
     */
    @Transactional
    public List<ChallengeCategoryView> addCategory(Long userId, String category, Long targetSaving) {
        GuardianChallenge ch = challengeRepository.findRunning(userId).orElseThrow(
                () -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "진행 중인 챌린지가 없어요"));
        if (ch.getCategorySet().contains(category)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "이미 줄이고 있는 곳이에요");
        }
        if (ch.getSanctuarySet().contains(category)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "성역으로 둔 곳이에요. 성역 관리에서 먼저 빼주세요");
        }

        LocalDateTime now = clock.now(userId);
        int remain = Math.max(1, (int) (ch.getEndDate().toEpochDay() - clock.today(userId).toEpochDay()) + 1);
        long base = baselineByCategory(userId, List.of(category), now, remain, List.of())
                .getOrDefault(category, 0L);
        if (base <= 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "그 카테고리에는 줄일 만한 소비가 없어요");
        }
        long target = targetSaving != null && targetSaving > 0
                ? Math.min(targetSaving, Math.max(0L, base - 1))
                : base / 2;

        challengeCategoryRepository.save(
                new GuardianChallengeCategory(ch.getId(), category, base, target, now));
        ch.addCategory(category);
        ch.growBaseline(base, target);
        challengeRepository.save(ch);
        return challengeCategories(userId);
    }

    /** 지금 말수 설정과 전역 기본값. 0이면 '설정 안 함'이라 기본값을 따른다. */
    @Transactional(readOnly = true)
    public Map<String, Object> voice(Long userId) {
        int mine = userRepository.findById(userId).map(AppUser::getNotifyDailyLimit).orElse(0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dailyLimit", mine);
        m.put("defaultLimit", props.getNotification().getDailyPushLimit());
        m.put("effectiveLimit", mine > 0 ? mine : props.getNotification().getDailyPushLimit());
        return m;
    }

    /**
     * 말수를 정한다.
     *
     * <p>0은 '설정 안 함'이며 전역 기본값을 따른다. 위쪽은 막지 않는다 — 많이 듣고 싶다는
     * 사람에게 우리가 정한 수를 강요할 이유가 없다. 다만 음수는 뜻이 없으므로 0으로 접는다.
     */
    @Transactional
    public Map<String, Object> setVoice(Long userId, int dailyLimit) {
        AppUser u = userRepository.findById(userId).orElseThrow(
                () -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "사용자를 찾지 못했어요"));
        u.setNotifyDailyLimit(Math.max(0, dailyLimit));
        userRepository.save(u);
        return voice(userId);
    }

    public record GrassCell(LocalDate date, DailyResult result, boolean granted, boolean protectedDay) {}

    /**
      * 아침 세리머니 한 장.
      *
      * <p><b>이름과 그림을 함께 보낸다.</b> 예전에는 {@code objectId} 만 보냈는데, 그 코드가
      * 화면에 그대로 나가 "mug_01 이 도착했어요"가 됐다. 코드는 서버의 말이지 사용자의 말이
      * 아니다. 카탈로그를 아는 쪽이 서버이므로 여기서 사람 말로 바꿔 보낸다.
      */
     public record CeremonyView(LocalDate verdictDate, DailyResult result, String objectId,
                                String objectName, String glyph,
                                Grade grade, String message, boolean rerollAvailable) {}

    /**
     * 카테고리 한 줄 — 홈의 '지킴 현황'을 갈라 보여준다.
     *
     * @param spent 그 카테고리에서 지금까지 집계된 금액
     * @param share 챌린지 전체 사용액에서 이 카테고리가 차지하는 비율(0~1). 예산은 묶음 하나로
     *              관리하므로 <b>카테고리별 예산은 없다</b> — 있는 척하면 화면이 거짓말을 한다.
     */
    public record CategorySpend(String code, String label, long spent, double share,
                                long cap, long remaining, double ratio) {}

    /**
     * 홈 한마디 — 지금 이 사람에게 걸린 케이스 하나와 그 문장.
     *
     * <p>개입이 아니라 <b>상태 표시</b>다. 알림 예산을 쓰지 않고 알림함에도 남지 않는다.
     */
    public record Oneline(String caseId, String text) {}

    public record HomeView(LocalDateTime asOf, GuardianChallenge challenge, String categoryLabel,
                           GuardianRules.Snapshot snapshot, int pendingCount, String pendingBadge,
                           CeremonyView ceremony, List<GrassCell> grass, GuardianItems items,
                           int unreadNotifications, boolean demoMode,
                           List<CategorySpend> categorySpend, Oneline oneline) {}

    /** 홈 한 방 — 프론트가 그릴 값을 전부 계산해 내려준다. */
    @Transactional
    public HomeView home(Long userId) {
        LocalDateTime now = clock.now(userId);
        LocalDate today = now.toLocalDate();
        GuardianChallenge ch = challengeRepository.findRunning(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "진행 중인 챌린지가 없어요"));

        GuardianRules.Snapshot snap = snapshotOf(ch, today);
        int pending = txRepository.findPendingCategory(ch.getId()).size();

        List<GrassCell> grass = new ArrayList<>();
        for (DailyVerdict v : verdictRepository.findSince(ch.getId(), today.minusDays(29))) {
            grass.add(new GrassCell(v.getVerdictDate(), v.getResult(), v.isGrantObject(), false));
        }

        CeremonyView ceremony = verdictRepository.findUnseenCeremonies(userId).stream().findFirst()
                .map(v -> {
                    GuardianCatalog.Item item = v.getGrantedObjectId() == null ? null
                            : catalog.find(v.getGrantedObjectId());
                    return new CeremonyView(v.getVerdictDate(), v.getResult(), v.getGrantedObjectId(),
                            item == null ? null : item.name(), item == null ? null : item.glyph(),
                            v.getGrantedGrade(), v.getCeremonyMessage(), !v.isRerolled());
                })
                .orElse(null);

        String label = ch.getCategorySet().stream()
                .map(this::categoryLabel).reduce((a, b) -> a + "·" + b).orElse("");

        // 카테고리별 사용액 — 고른 카테고리는 하나도 빠뜨리지 않는다(0원이어도 줄을 만든다).
        // 아직 안 쓴 곳이 화면에서 사라지면 "왜 없지"가 되고, 무엇을 지키고 있는지가 흐려진다.
        Map<String, Long> spentByCat = new HashMap<>();
        for (Object[] row : txRepository.sumCountedByCategory(ch.getId())) {
            if (row[0] == null) continue;
            spentByCat.put((String) row[0], ((Number) row[1]).longValue());
        }
        // 카테고리별 예산 — 없으면(옛 챌린지) 전체 캡을 균등분할해 예전과 같은 값을 보인다.
        Map<String, Long> capByCat = new HashMap<>();
        for (var cc : challengeCategoryRepository.findByChallenge(ch.getId())) {
            capByCat.put(cc.getCategory(), cc.getCap());
        }
        long evenCap = ch.getCategorySet().isEmpty() ? 0
                : ch.getChallengeCap() / ch.getCategorySet().size();
        long spentTotal = Math.max(1L, snap.spentAmount());
        List<CategorySpend> categorySpend = new ArrayList<>();
        for (String code : ch.getCategorySet()) {
            long spent = spentByCat.getOrDefault(code, 0L);
            long cap = capByCat.getOrDefault(code, evenCap);
            categorySpend.add(new CategorySpend(code, categoryLabel(code), spent,
                    Math.min(1.0, (double) spent / spentTotal),
                    cap, Math.max(0L, cap - spent),
                    cap > 0 ? (double) spent / cap : 0.0));
        }
        categorySpend.sort(Comparator.comparingLong(CategorySpend::spent).reversed());

        return new HomeView(now, ch, label, snap, pending,
                pending > 0 ? GuardianCopy.pendingBadge(pending) : null,
                ceremony, grass, rewardService.items(userId, now),
                notificationRepository.countUnread(userId), clock.isDemoMode(userId),
                categorySpend, oneline(ch, snap));
    }

    /**
     * 홈 한마디를 고른다 — 어느 케이스가 이기는지는 {@link GuardianRules#resolveOneline}이 정한다.
     *
     * <p><b>여기 있는 값만 후보로 올린다.</b> C1(첫 결제)·C2(주 3회 반복)는 우선순위표에 있지만
     * 카테고리별 건수를 세야 나오고, 홈은 카테고리마다 질의를 더 하지 않는다. 둘은 결제가 들어온
     * 순간 알림으로 이미 전달됐고, 홈이 며칠 뒤까지 "첫 결제예요"를 붙들고 있을 이유도 없다.
     *
     * <p>초과는 <b>상태가 아니라 비율로</b> 잡는다. 늦게 분류된 결제는 사용률이 1을 넘어도 상태를
     * {@code AT_RISK}에 묶어 두는데({@code classifyPending}), 상태만 보면 그 사람에게
     * "잘 지키고 있어요"가 뜬다.
     */
    private Oneline oneline(GuardianChallenge ch, GuardianRules.Snapshot snap) {
        Map<String, Double> candidates = new LinkedHashMap<>();
        if (ch.getState() == ChallengeState.EXCEEDED || snap.spentRatio() >= 1.0) {
            candidates.put("C6", snap.spentRatio());
        }
        if (snap.spentRatio() >= props.getAtRiskRatio()) {
            candidates.put("C3", snap.spentRatio());
        }
        if (snap.daysLeft() > 0 && snap.daysLeft() <= props.getEndingSoonDaysLeft()
                && snap.spentRatio() >= props.getEndingSoonRatio()) {
            candidates.put("C11", snap.spentRatio());
        }
        if (ch.getNoSpendStreak() > 0
                && ch.getNoSpendStreak() % props.getNoSpendPraiseInterval() == 0) {
            candidates.put("C5", snap.spentRatio());
        }

        String caseId = GuardianRules.resolveOneline(candidates);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("cap", ch.getChallengeCap());
        v.put("percent", Math.round(snap.spentRatio() * 100));
        v.put("remaining", Math.max(0L, snap.remainingCap()));
        v.put("daysLeft", snap.daysLeft());
        v.put("secured", snap.securedSaving());
        v.put("days", ch.getNoSpendStreak());
        return new Oneline(caseId, GuardianCopy.oneline(caseId, v));
    }

    /** 세리머니를 열었다 — 이 시각이 기록돼야 홈의 미개봉 뱃지가 꺼진다. */
    @Transactional
    public void markCeremonySeen(Long userId, Long verdictId) {
        DailyVerdict v = verdictRepository.findById(verdictId)
                .filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "판정을 찾을 수 없어요"));
        v.setCeremonySeenAt(clock.now(userId));
        verdictRepository.save(v);
    }

    // ======================================================================
    //  8. 알림 목록 · 피드백 (설계서 §API 5)
    // ======================================================================

    /** 침묵 기록은 빼고 내려준다 — 지표 계산용이지 사용자에게 보일 것이 아니다. */
    public List<GuardianNotification> notifications(Long userId) {
        return notificationRepository.findVisible(userId);
    }

    /** 별점보다 이 태그가 중요하다 — 프롬프트를 어느 방향으로 고칠지는 사유가 정한다. */
    @Transactional
    public void feedback(Long userId, Long notificationId, Feedback feedback, FeedbackReason reason) {
        GuardianNotification n = notificationRepository.findById(notificationId)
                .filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "알림을 찾을 수 없어요"));
        LocalDateTime now = clock.now(userId);
        n.recordFeedback(feedback, reason, now);
        notificationRepository.save(n);

        /* 피드백을 판정으로 되먹인다 (2026-08-02). 예전에는 여기서 끝났다 — 컬럼에만 남고
           아무것도 안 바꿨다. 그런데 온보딩의 "이건 낭비 아님"은 성향으로 쌓이고 있었다.
           같은 신호인데 한쪽만 쓰고 있던 것이다.

           <b>사유를 가려서 받는다.</b> NOT_MINE("내 소비가 아님")만이 판정에 대한 반박이고,
           TIMING·TONE·TOO_OFTEN·ALREADY_KNEW는 <b>전달 방식</b>에 대한 불만이다.
           "밤에 보내지 마세요"를 "이 가게는 낭비가 아니다"로 읽으면, 알림이 성가실수록
           판정이 무뎌지는 엉뚱한 고리가 생긴다. */
        if (feedback == Feedback.NOT_USEFUL && reason == FeedbackReason.NOT_MINE
                && n.getTransactionId() != null) {
            txRepository.findById(n.getTransactionId())
                    .filter(tx -> userId.equals(tx.getUserId()))
                    .ifPresent(tx -> promoteStanceOf(userId, tx, now));
        }
    }
}
