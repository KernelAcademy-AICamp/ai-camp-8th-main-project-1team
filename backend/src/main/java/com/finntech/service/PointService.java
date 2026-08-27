package com.finntech.service;

import com.finntech.domain.AppUser;
import com.finntech.domain.Category;
import com.finntech.domain.Consumption;
import com.finntech.domain.Coupon;
import com.finntech.domain.Enums;
import com.finntech.domain.GoalMilestone;
import com.finntech.domain.PointEvent;
import com.finntech.domain.SavingsGoal;
import com.finntech.domain.WishlistItem;
import com.finntech.engine.AnalysisEngine;
import com.finntech.repository.CategoryRepository;
import com.finntech.repository.ConsumptionRepository;
import com.finntech.repository.CouponRepository;
import com.finntech.repository.GoalMilestoneRepository;
import com.finntech.repository.PointEventRepository;
import com.finntech.repository.SavingsGoalRepository;
import com.finntech.repository.WishlistItemRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 게임화 저축 루프 (문서 §5-5) — Qapital 벤치마크 + 치팅데이 쿠폰(Phase 3).
 *
 * <p><b>모델(전부 가상 — 실 송금·결제 아님).</b> 충동을 참으면("살 뻔했다") 그 돈이 <b>즉시 랜덤 목표 버킷에
 * 자동 입금(DEPOSIT)</b>된다 — 대기 풀·수동 이체 과정은 없다. 예산을 초과 소비하면 잔액 큰 목표에서
 * 강제차감(WITHDRAWAL)되고 선물상자가 크게 깨진다. 참은 저축이 일정 이상 쌓이면 치팅데이 쿠폰이 제안된다.
 *
 * <p>{@code pointsRemaining = 월급 − 이번달 소비 − 이번달 입금}, {@code goalBalance = ΣDEPOSIT→g − ΣWITHDRAWAL←g}.
 * "랜덤 목표"는 재현성(§4)을 위해 입금 순번 회전(depositCount % 목표수)으로 결정론적으로 분산한다.
 */
@Service
public class PointService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final int SUGGESTION_LIMIT = 4;
    private static final int RECENT_LIMIT = 10;
    /** 참은 저축 누적이 이 금액을 넘길 때마다 치팅데이 쿠폰 1장. */
    static final BigDecimal COUPON_THRESHOLD = new BigDecimal("100000");
    /** 쿠폰 자유이용권 = 임계치의 이 비율. */
    private static final BigDecimal COUPON_BENEFIT_RATE = new BigDecimal("0.3");

    private final ConsumptionRepository consumptionRepository;
    private final PointEventRepository pointEventRepository;
    private final SavingsGoalRepository goalRepository;
    private final CategoryRepository categoryRepository;
    private final CouponRepository couponRepository;
    private final WishlistItemRepository wishlistRepository;
    private final GoalMilestoneRepository milestoneRepository;
    private final AnalysisEngine analysisEngine;
    private final ScoreService scoreService;

    public PointService(ConsumptionRepository consumptionRepository,
                        PointEventRepository pointEventRepository,
                        SavingsGoalRepository goalRepository,
                        CategoryRepository categoryRepository,
                        CouponRepository couponRepository,
                        WishlistItemRepository wishlistRepository,
                        GoalMilestoneRepository milestoneRepository,
                        AnalysisEngine analysisEngine,
                        ScoreService scoreService) {
        this.consumptionRepository = consumptionRepository;
        this.pointEventRepository = pointEventRepository;
        this.goalRepository = goalRepository;
        this.categoryRepository = categoryRepository;
        this.couponRepository = couponRepository;
        this.wishlistRepository = wishlistRepository;
        this.milestoneRepository = milestoneRepository;
        this.analysisEngine = analysisEngine;
        this.scoreService = scoreService;
    }

    // ======================================================================
    //  스냅샷
    // ======================================================================

    @Transactional
    public PointSnapshot snapshot(AppUser user, LocalDateTime now) {
        ensureGoals(user.getId());
        return build(user, now, null, BigDecimal.ZERO, null);
    }

    private PointSnapshot build(AppUser user, LocalDateTime now,
                                String lastAction, BigDecimal lastAmount, ForcedWithdrawal forced) {
        return build(user, now, lastAction, lastAmount, forced, null);
    }

    private PointSnapshot build(AppUser user, LocalDateTime now, String lastAction, BigDecimal lastAmount,
                                ForcedWithdrawal forced, GoalGain gain) {
        Long userId = user.getId();
        LocalDateTime monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay();
        LocalDateTime monthEnd = monthStart.plusMonths(1);

        BigDecimal spentThisMonth = consumptionRepository.findInRange(userId, monthStart, monthEnd)
                .stream().map(Consumption::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal depositedThisMonth = pointEventRepository
                .sumByTypeInRange(userId, Enums.PointEventType.DEPOSIT, monthStart, monthEnd);

        BigDecimal monthlyBudget = user.getMonthlyIncome();
        BigDecimal pointsRemaining = monthlyBudget.subtract(spentThisMonth).subtract(depositedThisMonth);

        // 습관(미계획) 소비의 카테고리별 월평균 — 저축 계획에서 '줄일 소비'의 절약액 기준.
        List<CutOption> cutOptions = cutOptions(userId);
        Map<String, BigDecimal> monthlyByCat = new TreeMap<>();
        for (CutOption o : cutOptions) monthlyByCat.put(o.categoryCode(), o.monthlyAmount());

        // 월별 재료는 **루프 밖에서 한 번만** 읽는다 — 목표마다 읽으면 같은 표를 N 번 훑는다.
        List<PointEvent> deposits = pointEventRepository.findByTypeSince(
                userId, Enums.PointEventType.DEPOSIT, monthStart.minusMonths(HISTORY_MONTHS));
        BigDecimal avgSaved = monthlyAverageSaved(deposits, now);

        List<GoalView> goals = new ArrayList<>();
        BigDecimal totalSavings = BigDecimal.ZERO;
        BigDecimal totalTarget = BigDecimal.ZERO;
        for (SavingsGoal g : goalRepository.findByUserIdOrderBySortOrderAscIdAsc(userId)) {
            BigDecimal balance = goalBalance(userId, g.getId());
            double progress = g.getTargetAmount().signum() <= 0 ? 0.0
                    : balance.divide(g.getTargetAmount(), 6, RoundingMode.HALF_UP).doubleValue();
            // '가는 날 N일 단축' = 잔액이 커버한 기한일수 (잔액/목표 × 기한일)
            int fundedDays = g.getTargetAmount().signum() <= 0 ? 0
                    : (int) Math.round(balance.doubleValue() / g.getTargetAmount().doubleValue() * g.getDeadlineDays());
            // 저축 계획 — 줄이기로 한 소비들의 월 절약액과 달성 개월수
            List<String> cuts = parseCsv(g.getPlanCutCategories());
            BigDecimal monthlySaving = cutsMonthlySaving(cuts, monthlyByCat);
            int planMonths = monthsToGoal(g.getTargetAmount(), monthlySaving);
            // 기한 안에 맞추려면 매달 얼마인가 — 목표 세우기(s-goalD)가 이 값으로 페이스를 보여준다.
            BigDecimal monthlyRequired = monthlyRequired(g.getTargetAmount(), g.getDeadlineDays());
            List<MonthlySaving> history = monthlyHistory(deposits, g.getId());
            goals.add(new GoalView(g.getId(), g.getName(), g.getEmoji(), scale(g.getTargetAmount()),
                    scale(balance), progress, g.isPriority(),
                    milestoneViews(g.getId(), balance), g.getDeadlineDays(), fundedDays,
                    cuts, scale(monthlySaving), planMonths,
                    g.getAccountBank(), g.getAccountProduct(), g.getAccountNumber(),
                    g.getRewardCode(), monthlyRequired, scale(avgSaved),
                    projectedDate(g.getTargetAmount(), balance, avgSaved, now), history));
            totalSavings = totalSavings.add(balance);
            totalTarget = totalTarget.add(g.getTargetAmount());
        }
        double giftFill = totalTarget.signum() <= 0 ? 0.0
                : Math.min(1.0, totalSavings.divide(totalTarget, 6, RoundingMode.HALF_UP).doubleValue());

        CouponView coupon = couponRepository
                .findFirstByUserIdAndStatusOrderByIdDesc(userId, Enums.CouponStatus.OFFERED)
                .map(c -> new CouponView(c.getId(), c.getCategoryCode(), scale(c.getBenefitAmount())))
                .orElse(null);

        List<WishlistView> wishlist = new ArrayList<>();
        BigDecimal savedByNotBuying = BigDecimal.ZERO;
        for (WishlistItem w : wishlistRepository.findByUserIdOrderByIdDesc(userId)) {
            if (w.getStatus() == Enums.WishlistStatus.CONSIDERING) {
                wishlist.add(new WishlistView(w.getId(), w.getName(), scale(w.getPrice()),
                        w.getCategoryCode(), w.getImageUrl()));
            } else if (w.getStatus() == Enums.WishlistStatus.NOT_BOUGHT) {
                savedByNotBuying = savedByNotBuying.add(w.getPrice());
            }
        }

        // FDS 재프레이밍 — 실제 소비건전성지수 + 미계획 소비 연속 + '다짐 어김' 행동 경고.
        // 연속(streak)은 사용자가 앱에서 실제 기록한 소비(USER_INPUT)만 본다 — 더미 시드 이력이 아니라 '최근 내 행동'.
        List<Consumption> allCons = consumptionRepository.findAllForUser(userId);
        ScoreService.ScoreResult sc = scoreService.score(user, analysisEngine.analyze(userId, now));
        int streak = trailingUnnecessaryStreak(allCons.stream()
                .filter(c -> c.getSource() == Enums.DataSource.USER_INPUT)
                .map(Consumption::isPlanned).toList());
        List<String> behaviorAlerts = behaviorAlerts(userId, allCons, streak);

        return new PointSnapshot(userId, now.format(MONTH),
                scale(monthlyBudget), scale(spentThisMonth), scale(depositedThisMonth), scale(pointsRemaining),
                scale(totalSavings), scale(totalTarget), giftFill,
                lastAction, scale(lastAmount), forced, coupon,
                goals, suggestions(userId), recentEvents(userId),
                wishlist, scale(savedByNotBuying),
                sc.score(), sc.grade(), streak, behaviorAlerts, gain, cutOptions, scale(avgSaved));
    }

    // ======================================================================
    //  액션
    // ======================================================================

    /** "살 뻔했다" — 참은 즉시 랜덤 목표 버킷에 자동 입금(예산 한도 검증). 대기 풀·수동 이체 없음. */
    @Transactional
    public PointSnapshot avoid(AppUser user, String categoryCode, BigDecimal amount, LocalDateTime now) {
        validateSave(amount, pointsRemaining(user, now));
        return deposit(user, categoryCode, amount, "MANUAL", now);
    }

    /**
     * 금액을 즉시 랜덤 목표(순번 회전)에 자동 입금한다. <b>예산 캡을 걸지 않는다</b> — 위시리스트의
     * '구체적으로 안 산 상품'은 이번 달 예산과 무관한 실제 회피 금액이므로 그대로 적립한다.
     */
    @Transactional
    public PointSnapshot deposit(AppUser user, String categoryCode, BigDecimal amount, String reason, LocalDateTime now) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("금액은 0보다 커야 합니다");
        ensureGoals(user.getId());
        List<SavingsGoal> goals = goalRepository.findByUserIdOrderBySortOrderAscIdAsc(user.getId());
        long depositCount = pointEventRepository.countByUserIdAndType(user.getId(), Enums.PointEventType.DEPOSIT);
        SavingsGoal target = goals.get((int) (depositCount % goals.size()));
        GoalGain gain = goalGain(user.getId(), target, amount);   // 참는 순간 진척 번역 (획득 프레이밍)
        BigDecimal before = totalDeposited(user.getId());
        pointEventRepository.save(new PointEvent(user.getId(), Enums.PointEventType.DEPOSIT,
                amount, categoryCode, target.getId(), reason, now, null));
        maybeOfferCoupon(user.getId(), before, before.add(amount), now);
        return build(user, now, "SAVED", amount, null, gain);
    }

    /**
     * 실제 소비. 예산 초과 시 잔액 큰 목표에서 강제차감 + 선물상자 반응. (저축 규칙은 제거됨)
     * necessary=false(필요없는 소비)면 선물상자가 깨진다.
     */
    @Transactional
    public PointSnapshot spend(AppUser user, String categoryCode, BigDecimal amount,
                               boolean necessary, LocalDateTime now) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("금액은 0보다 커야 합니다");
        }
        if (!user.isConsentGiven()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "개인정보 수집에 동의해야 소비를 기록할 수 있어요. (동의 없이도 데모는 이용 가능)");
        }
        Category cat = categoryRepository.findByCode(categoryCode).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown category: " + categoryCode));
        consumptionRepository.save(new Consumption(
                user.getId(), cat, amount, now, necessary, Enums.DataSource.USER_INPUT));

        BigDecimal budget = user.getMonthlyIncome();
        BigDecimal spentThisMonth = monthConsumption(user.getId(), now);
        BigDecimal over = incrementalOverspend(spentThisMonth, budget, amount);

        String lastAction;
        ForcedWithdrawal forced = null;
        if (over.signum() > 0) {
            forced = forceWithdraw(user.getId(), over, now);
            lastAction = "OVERSPEND";
        } else if (!necessary) {
            lastAction = "UNNECESSARY";
        } else {
            lastAction = "SPEND";
        }
        return build(user, now, lastAction, amount, forced);
    }

    // ---- 치팅데이 쿠폰 (Phase 3) -------------------------------------------

    @Transactional
    public PointSnapshot useCoupon(AppUser user, Long couponId, LocalDateTime now) {
        resolveCoupon(user.getId(), couponId, Enums.CouponStatus.USED);
        return build(user, now, "COUPON_USED", BigDecimal.ZERO, null);
    }

    @Transactional
    public PointSnapshot declineCoupon(AppUser user, Long couponId, LocalDateTime now) {
        resolveCoupon(user.getId(), couponId, Enums.CouponStatus.DECLINED);
        return build(user, now, "COUPON_DECLINED", BigDecimal.ZERO, null);
    }

    private void resolveCoupon(Long userId, Long couponId, Enums.CouponStatus status) {
        Coupon c = couponRepository.findById(couponId)
                .filter(x -> x.getUserId().equals(userId) && x.getStatus() == Enums.CouponStatus.OFFERED)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "coupon not found"));
        c.setStatus(status);
        couponRepository.save(c);
    }

    /** 참은 저축 누적이 임계치를 새로 넘겼고 대기 중 쿠폰이 없으면, 가장 많이 참은 분류로 쿠폰을 제안한다. */
    private void maybeOfferCoupon(Long userId, BigDecimal before, BigDecimal after, LocalDateTime now) {
        if (couponMilestone(after) <= couponMilestone(before)) return;
        if (couponRepository.findFirstByUserIdAndStatusOrderByIdDesc(userId, Enums.CouponStatus.OFFERED).isPresent()) return;
        BigDecimal benefit = COUPON_THRESHOLD.multiply(COUPON_BENEFIT_RATE).setScale(0, RoundingMode.HALF_UP);
        couponRepository.save(new Coupon(userId, mostAvoidedCategory(userId), benefit, now));
    }

    // ---- 목표 CRUD ---------------------------------------------------------

    @Transactional
    public PointSnapshot createGoal(AppUser user, String name, String emoji, BigDecimal target, LocalDateTime now) {
        return createGoal(user, name, emoji, target, null, null, now);
    }

    /**
     * 목표 만들기 — 기간과 보상까지 받는다(프로토타입_0825 목표 세우기 4단계).
     *
     * <p>{@code months} 와 {@code rewardCode} 는 <b>둘 다 없어도 된다.</b> 예전 호출부(위의
     * 4-인자 판)와 시험이 그대로 돌아야 하고, 기간을 안 고른 목표는 기존 기본값(90일)을 쓴다.
     */
    @Transactional
    public PointSnapshot createGoal(AppUser user, String name, String emoji, BigDecimal target,
                                    Integer months, String rewardCode, LocalDateTime now) {
        int order = (int) goalRepository.countByUserId(user.getId());
        SavingsGoal g = new SavingsGoal(user.getId(), name, emoji == null ? "🎯" : emoji,
                target == null ? BigDecimal.ZERO : target, order, false);
        // 기간은 달로 고르고 날로 저장한다 — 칼럼(deadline_days)이 이미 날이라 그대로 쓴다.
        if (months != null && months > 0) g.setDeadlineDays(months * 30);
        if (rewardCode != null && !rewardCode.isBlank()) g.setRewardCode(rewardCode.trim());
        // 이 목표를 위한 자유입출금통장 발급(§13-11) — 목표에 모으는 돈을 담는 계좌.
        AccountCatalog.Account acct = AccountCatalog.random();
        g.setAccount(acct.bank(), acct.product(), acct.accountNumber());
        goalRepository.save(g);
        return build(user, now, null, BigDecimal.ZERO, null);
    }

    @Transactional
    public PointSnapshot updateGoal(AppUser user, Long goalId, String name, String emoji,
                                    BigDecimal target, Boolean priority, LocalDateTime now) {
        return updateGoal(user, goalId, name, emoji, target, priority, null, null, now);
    }

    /** 목표 고치기 — 기간·보상까지. 넘기지 않은 값은 건드리지 않는다('목표 다시 설정하기'). */
    @Transactional
    public PointSnapshot updateGoal(AppUser user, Long goalId, String name, String emoji,
                                    BigDecimal target, Boolean priority,
                                    Integer months, String rewardCode, LocalDateTime now) {
        SavingsGoal g = ownedGoal(user.getId(), goalId);
        if (name != null) g.setName(name);
        if (emoji != null) g.setEmoji(emoji);
        if (target != null) g.setTargetAmount(target);
        if (priority != null) g.setPriority(priority);
        if (months != null && months > 0) g.setDeadlineDays(months * 30);
        if (rewardCode != null && !rewardCode.isBlank()) g.setRewardCode(rewardCode.trim());
        goalRepository.save(g);
        return build(user, now, null, BigDecimal.ZERO, null);
    }

    /** 목표 삭제 — 그 목표의 입출금 이벤트도 지운다(잔액 소멸). 최소 1개는 남긴다. */
    @Transactional
    public PointSnapshot deleteGoal(AppUser user, Long goalId, LocalDateTime now) {
        if (goalRepository.countByUserId(user.getId()) <= 1) {
            throw new IllegalArgumentException("목표는 최소 1개 있어야 해요");
        }
        SavingsGoal g = ownedGoal(user.getId(), goalId);
        pointEventRepository.deleteByGoalId(g.getId());
        milestoneRepository.deleteByGoalId(g.getId());
        goalRepository.delete(g);
        return build(user, now, null, BigDecimal.ZERO, null);
    }

    // ---- 마일스톤 CRUD -----------------------------------------------------

    @Transactional
    public PointSnapshot addMilestone(AppUser user, Long goalId, String name, String emoji,
                                      BigDecimal cost, LocalDateTime now) {
        SavingsGoal g = ownedGoal(user.getId(), goalId);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("단계 이름을 입력해 주세요");
        if (cost == null || cost.signum() <= 0) throw new IllegalArgumentException("금액을 입력해 주세요");
        int order = (int) milestoneRepository.countByGoalId(g.getId());
        milestoneRepository.save(new GoalMilestone(g.getId(), user.getId(), name.trim(),
                emoji == null || emoji.isBlank() ? "⭐" : emoji, cost, order));
        return build(user, now, null, BigDecimal.ZERO, null);
    }

    @Transactional
    public PointSnapshot deleteMilestone(AppUser user, Long milestoneId, LocalDateTime now) {
        GoalMilestone m = milestoneRepository.findById(milestoneId)
                .filter(x -> x.getUserId().equals(user.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "milestone not found"));
        milestoneRepository.delete(m);
        return build(user, now, null, BigDecimal.ZERO, null);
    }

    // ======================================================================
    //  저축 계획
    // ======================================================================

    /** 목표에 '줄일 습관 소비' 카테고리(CSV)를 저장한다. 월 절약액·달성 개월수는 스냅샷이 파생 계산. */
    @Transactional
    public PointSnapshot setGoalPlan(AppUser user, Long goalId, List<String> cutCategories, LocalDateTime now) {
        SavingsGoal g = ownedGoal(user.getId(), goalId);
        g.setPlanCutCategories(cutCategories == null || cutCategories.isEmpty()
                ? null : String.join(",", cutCategories));
        goalRepository.save(g);
        return build(user, now, null, BigDecimal.ZERO, null);
    }

    /** 습관(미계획) 소비의 카테고리별 월평균 금액 — 계획에서 '줄일 소비'의 절약액 근거. */
    private List<CutOption> cutOptions(Long userId) {
        Map<String, BigDecimal> sum = new TreeMap<>();
        Map<String, String> names = new TreeMap<>();
        Set<String> months = new HashSet<>();
        for (Consumption c : consumptionRepository.findAllForUser(userId)) {
            if (c.isPlanned()) continue;   // 습관(미계획) 소비만
            String code = c.getCategory().getCode();
            sum.merge(code, c.getAmount(), BigDecimal::add);
            names.putIfAbsent(code, c.getCategory().getDisplayName());
            months.add(c.getOccurredAt().format(MONTH));
        }
        int m = Math.max(1, months.size());
        List<CutOption> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : sum.entrySet()) {
            out.add(new CutOption(e.getKey(), names.get(e.getKey()),
                    e.getValue().divide(BigDecimal.valueOf(m), 0, RoundingMode.HALF_UP)));
        }
        out.sort(Comparator.comparing(CutOption::monthlyAmount).reversed().thenComparing(CutOption::categoryCode));
        return out;
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    // ======================================================================
    //  순수 계산 (단위 테스트 진입점)
    // ======================================================================

    /** 줄이기로 한 카테고리들의 월 절약액 합. 순수. */
    static BigDecimal cutsMonthlySaving(List<String> cutCategories, Map<String, BigDecimal> monthlyByCat) {
        BigDecimal sum = BigDecimal.ZERO;
        for (String c : cutCategories) sum = sum.add(monthlyByCat.getOrDefault(c, BigDecimal.ZERO));
        return sum;
    }

    /** 월 절약액으로 목표금액을 모으는 데 걸리는 개월수 = ⌈목표/월절약⌉. 절약이 0이면 0. 순수. */
    static int monthsToGoal(BigDecimal target, BigDecimal monthlySaving) {
        if (target == null || target.signum() <= 0 || monthlySaving == null || monthlySaving.signum() <= 0) {
            return 0;
        }
        return target.divide(monthlySaving, 0, RoundingMode.CEILING).intValue();
    }

    static void validateSave(BigDecimal amount, BigDecimal pointsRemaining) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("금액은 0보다 커야 합니다");
        }
        if (amount.compareTo(pointsRemaining) > 0) {
            throw new IllegalArgumentException("이번 달 쓸 수 있는 돈(" + pointsRemaining.toPlainString()
                    + "원)보다 커서 지금은 담기 어려워요. 다음 달에 다시 도전해요 🙂");
        }
    }

    /** 이번 소비가 예산을 넘긴 '증분' 초과분 = min(이번금액, 이번달총지출 − 예산). */
    static BigDecimal incrementalOverspend(BigDecimal spentThisMonth, BigDecimal budget, BigDecimal thisAmount) {
        BigDecimal over = spentThisMonth.subtract(budget);
        if (over.signum() <= 0) return BigDecimal.ZERO;
        return over.min(thisAmount);
    }

    /** 참은 저축 누적액이 몇 번째 쿠폰 임계치를 넘겼는가(floor). */
    static long couponMilestone(BigDecimal totalDeposited) {
        return totalDeposited.divideToIntegralValue(COUPON_THRESHOLD).longValueExact();
    }

    // ======================================================================
    //  내부 헬퍼
    // ======================================================================

    private BigDecimal pointsRemaining(AppUser user, LocalDateTime now) {
        return user.getMonthlyIncome()
                .subtract(monthConsumption(user.getId(), now))
                .subtract(monthDeposit(user.getId(), now));
    }

    private BigDecimal monthConsumption(Long userId, LocalDateTime now) {
        LocalDateTime s = now.toLocalDate().withDayOfMonth(1).atStartOfDay();
        return consumptionRepository.findInRange(userId, s, s.plusMonths(1))
                .stream().map(Consumption::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal monthDeposit(Long userId, LocalDateTime now) {
        LocalDateTime s = now.toLocalDate().withDayOfMonth(1).atStartOfDay();
        return pointEventRepository.sumByTypeInRange(userId, Enums.PointEventType.DEPOSIT, s, s.plusMonths(1));
    }

    private BigDecimal totalDeposited(Long userId) {
        return pointEventRepository.sumByType(userId, Enums.PointEventType.DEPOSIT);
    }

    private BigDecimal goalBalance(Long userId, Long goalId) {
        return pointEventRepository.sumByTypeAndGoal(userId, Enums.PointEventType.DEPOSIT, goalId)
                .subtract(pointEventRepository.sumByTypeAndGoal(userId, Enums.PointEventType.WITHDRAWAL, goalId));
    }

    /**
     * 이 목표에 {@code amount}를 넣었을 때의 진척 변화를 계산한다 — 참는 순간 "62% → 68% · D-N 단축"으로
     * 번역해 보여주는 <b>획득 프레이밍</b>의 핵심. 반드시 입금 이벤트를 저장하기 <b>전에</b> 호출한다(before 잔액 기준).
     */
    private GoalGain goalGain(Long userId, SavingsGoal g, BigDecimal amount) {
        BigDecimal tgt = g.getTargetAmount();
        BigDecimal before = goalBalance(userId, g.getId());
        BigDecimal after = before.add(amount);
        if (tgt.signum() <= 0) {
            return new GoalGain(g.getName(), g.getEmoji(), 0.0, 0.0, 0, scale(after));
        }
        double pBefore = before.divide(tgt, 6, RoundingMode.HALF_UP).doubleValue();
        double pAfter = after.divide(tgt, 6, RoundingMode.HALF_UP).doubleValue();
        int dBefore = (int) Math.round(before.doubleValue() / tgt.doubleValue() * g.getDeadlineDays());
        int dAfter = (int) Math.round(after.doubleValue() / tgt.doubleValue() * g.getDeadlineDays());
        return new GoalGain(g.getName(), g.getEmoji(), pBefore, pAfter, dAfter - dBefore, scale(after));
    }

    /** 목표 잔액이 마일스톤 누적 비용을 넘는 순서대로 획득. 현재 진행 중 단계의 진행률·남은 금액도 채운다. */
    private List<MilestoneView> milestoneViews(Long goalId, BigDecimal balance) {
        List<MilestoneView> out = new ArrayList<>();
        BigDecimal cum = BigDecimal.ZERO;
        for (GoalMilestone m : milestoneRepository.findByGoalIdOrderBySortOrderAscIdAsc(goalId)) {
            BigDecimal end = cum.add(m.getCost());
            boolean acquired = balance.compareTo(end) >= 0;
            double progress;
            BigDecimal remaining;
            if (acquired) {
                progress = 1.0;
                remaining = BigDecimal.ZERO;
            } else {
                BigDecimal into = balance.subtract(cum).max(BigDecimal.ZERO);
                progress = m.getCost().signum() <= 0 ? 1.0
                        : into.divide(m.getCost(), 4, RoundingMode.HALF_UP).doubleValue();
                remaining = end.subtract(balance).max(BigDecimal.ZERO);
            }
            out.add(new MilestoneView(m.getId(), m.getName(), m.getEmoji(), scale(m.getCost()),
                    acquired, Math.min(1.0, progress), scale(remaining)));
            cum = end;
        }
        return out;
    }

    /** 잔액이 획득한 마일스톤 개수(누적 비용 ≤ 잔액). 순수 — 단위 테스트용. */
    static int acquiredCount(BigDecimal balance, List<BigDecimal> costs) {
        BigDecimal cum = BigDecimal.ZERO;
        int n = 0;
        for (BigDecimal c : costs) {
            cum = cum.add(c);
            if (balance.compareTo(cum) >= 0) n++;
            else break;
        }
        return n;
    }

    /** 시간순 소비의 '계획 여부' 목록 끝에서부터 이어지는 미계획(false) 연속 횟수. 순수 — 테스트용. */
    static int trailingUnnecessaryStreak(List<Boolean> plannedInOrder) {
        int n = 0;
        for (int i = plannedInOrder.size() - 1; i >= 0; i--) {
            if (Boolean.FALSE.equals(plannedInOrder.get(i))) n++;
            else break;
        }
        return n;
    }

    /** 초과분을 잔액 큰 목표부터 강제차감(총저축 한도). 대표 목표명·총차감액을 반환. */
    private ForcedWithdrawal forceWithdraw(Long userId, BigDecimal overspend, LocalDateTime now) {
        List<SavingsGoal> goals = goalRepository.findByUserIdOrderBySortOrderAscIdAsc(userId);
        record GB(SavingsGoal g, BigDecimal bal) {}
        List<GB> byBalance = new ArrayList<>();
        for (SavingsGoal g : goals) byBalance.add(new GB(g, goalBalance(userId, g.getId())));
        byBalance.sort(Comparator.comparing((GB x) -> x.bal()).reversed().thenComparing(x -> x.g().getId()));

        BigDecimal toWithdraw = overspend;
        BigDecimal total = BigDecimal.ZERO;
        String primaryGoal = null;
        for (GB x : byBalance) {
            if (toWithdraw.signum() <= 0 || x.bal().signum() <= 0) break;
            BigDecimal take = x.bal().min(toWithdraw);
            pointEventRepository.save(new PointEvent(userId, Enums.PointEventType.WITHDRAWAL,
                    take, null, x.g().getId(), "OVERSPEND", now, null));
            if (primaryGoal == null) primaryGoal = x.g().getName();
            total = total.add(take);
            toWithdraw = toWithdraw.subtract(take);
        }
        return total.signum() > 0 ? new ForcedWithdrawal(primaryGoal, scale(total)) : null;
    }

    /** 목표가 하나도 없으면 예시 3개 + 각 마일스톤을 만들어 준다(사용자가 수정·삭제 가능). */
    private void ensureGoals(Long userId) {
        if (goalRepository.countByUserId(userId) > 0) return;
        SavingsGoal trip = goalRepository.save(new SavingsGoal(userId, "여행 가기", "✈️", new BigDecimal("2000000"), 0, false));
        milestoneRepository.save(new GoalMilestone(trip.getId(), userId, "비행기표", "✈️", new BigDecimal("800000"), 0));
        milestoneRepository.save(new GoalMilestone(trip.getId(), userId, "호텔", "🏨", new BigDecimal("700000"), 1));
        milestoneRepository.save(new GoalMilestone(trip.getId(), userId, "교통패스", "🎫", new BigDecimal("500000"), 2));

        SavingsGoal laptop = goalRepository.save(new SavingsGoal(userId, "새 노트북", "💻", new BigDecimal("1800000"), 1, false));
        milestoneRepository.save(new GoalMilestone(laptop.getId(), userId, "본체", "💻", new BigDecimal("1500000"), 0));
        milestoneRepository.save(new GoalMilestone(laptop.getId(), userId, "주변기기", "🖱️", new BigDecimal("300000"), 1));

        SavingsGoal fund = goalRepository.save(new SavingsGoal(userId, "비상금", "🛟", new BigDecimal("1000000"), 2, false));
        milestoneRepository.save(new GoalMilestone(fund.getId(), userId, "1개월치", "🛟", new BigDecimal("500000"), 0));
        milestoneRepository.save(new GoalMilestone(fund.getId(), userId, "2개월치", "🛟", new BigDecimal("500000"), 1));
    }

    private SavingsGoal ownedGoal(Long userId, Long goalId) {
        return goalRepository.findById(goalId)
                .filter(g -> g.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "goal not found"));
    }

    /** 가장 많이 참은(입금한) 카테고리 — 없으면 미계획 지출이 가장 많은 카테고리. */
    private String mostAvoidedCategory(Long userId) {
        Map<String, BigDecimal> byCat = new TreeMap<>();
        for (PointEvent e : pointEventRepository.findAllForUser(userId)) {
            if (e.getType() == Enums.PointEventType.DEPOSIT && e.getCategoryCode() != null) {
                byCat.merge(e.getCategoryCode(), e.getAmount(), BigDecimal::add);
            }
        }
        String top = byCat.entrySet().stream()
                .max(Map.Entry.<String, BigDecimal>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).orElse(null);
        if (top != null) return top;
        List<Suggestion> s = suggestions(userId);
        return s.isEmpty() ? null : s.get(0).categoryCode();
    }

    private List<Suggestion> suggestions(Long userId) {
        Map<String, BigDecimal> sumByCat = new TreeMap<>();
        Map<String, Long> countByCat = new TreeMap<>();
        Map<String, String> names = new TreeMap<>();
        for (Consumption c : consumptionRepository.findAllForUser(userId)) {
            if (c.isPlanned()) continue;
            String code = c.getCategory().getCode();
            // **모르는 칸은 줄이라고 권하지 않는다.** "카테고리없음을 줄이세요"는 실행할 수
            // 없는 조언이고, `간편결제` 는 원리적으로 무엇을 샀는지 모르는 돈이라 더 그렇다 —
            // 실사용자 한 명은 그 금액이 290,642원이라 제안 1위로 올라올 수 있었다.
            if (com.finntech.engine.IndustryCategoryMapper.isUnknown(code)) continue;
            sumByCat.merge(code, c.getAmount(), BigDecimal::add);
            countByCat.merge(code, 1L, Long::sum);
            names.putIfAbsent(code, c.getCategory().getDisplayName());
        }
        List<Suggestion> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : sumByCat.entrySet()) {
            long count = countByCat.getOrDefault(e.getKey(), 1L);
            BigDecimal typical = e.getValue().divide(BigDecimal.valueOf(count), 0, RoundingMode.HALF_UP);
            out.add(new Suggestion(e.getKey(), names.get(e.getKey()), typical, scale(e.getValue())));
        }
        out.sort(Comparator.comparing(Suggestion::totalUnplanned).reversed()
                .thenComparing(Suggestion::categoryCode));
        return out.size() > SUGGESTION_LIMIT ? out.subList(0, SUGGESTION_LIMIT) : out;
    }

    private List<EventView> recentEvents(Long userId) {
        List<PointEvent> all = pointEventRepository.findAllForUser(userId);
        List<EventView> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && out.size() < RECENT_LIMIT; i--) {
            PointEvent e = all.get(i);
            out.add(new EventView(e.getType().name(), e.getReason(), scale(e.getAmount()),
                    e.getCategoryCode(), e.getOccurredAt().toString()));
        }
        return out;
    }

    /**
     * 행동 신호 — 미계획 소비 연속(streak≥3) + '다짐 어김'. 로직은 신호 탐지 그대로 두되, 문구는
     * <b>지적·통제가 아니라 공감·응원</b>으로 낸다(비전: 손실 프레이밍 회피). 자책을 유발하지 않고
     * '다음 한 번'의 획득 기회로 돌린다.
     */
    private List<String> behaviorAlerts(Long userId, List<Consumption> cons, int streak) {
        List<String> out = new ArrayList<>();
        if (streak >= 3) {
            out.add("요즘 마음 가는 대로 쓴 소비가 몇 번 있었네요. 다음 한 번은 참아서 목표에 담아볼까요? 💪");
        }
        for (WishlistItem w : wishlistRepository.findByUserIdAndStatusOrderByIdDesc(userId, Enums.WishlistStatus.NOT_BOUGHT)) {
            if (w.getCategoryCode() == null) continue;
            boolean broke = cons.stream().anyMatch(c -> !c.isPlanned()
                    && w.getCategoryCode().equals(c.getCategory().getCode())
                    && c.getOccurredAt().isAfter(w.getCreatedAt()));
            if (broke) {
                out.add("‘" + w.getName() + "’ 대신 비슷한 걸 골랐네요. 괜찮아요 — 다음엔 그 돈을 목표에 담아봐요 🌱");
                if (out.size() >= 4) break;
            }
        }
        return out;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? null : v.setScale(0, RoundingMode.HALF_UP);
    }

    // ======================================================================
    //  페이스 — "매달 얼마" 와 "실제로 매달 얼마" 를 나란히 놓는다
    //
    //  목표 세우기(프로토타입_0825 s-goalD)는 기간을 고르면 **매달 넣어야 할 돈**을 보여주고
    //  그것을 **이 사람이 실제로 지켜 온 돈**과 견준다. 지금까지의 계산은 방향이 반대였다 —
    //  '줄일 카테고리'에서 절약액을 구해 개월수를 냈다(planMonths). 그 길은 그대로 두고,
    //  기간에서 금액을 내는 길을 하나 더 낸다. 둘은 서로를 대체하지 않는다.
    // ======================================================================

    /** 평균을 낼 때 거슬러 보는 달 수. 이보다 오래된 기록은 지금 속도를 말해 주지 않는다. */
    private static final int HISTORY_MONTHS = 6;

    /** 기한 안에 맞추려면 매달 넣어야 하는 돈 = 목표액 ÷ 기한개월. 기한이 없으면 0. */
    static BigDecimal monthlyRequired(BigDecimal target, int deadlineDays) {
        if (target == null || target.signum() <= 0 || deadlineDays <= 0) return BigDecimal.ZERO;
        // 기한은 날로 저장돼 있다(기존 칼럼). 달로 바꾸되 **최소 1달**이다 — 0 으로 나눌 수 없고,
        // "한 달 안에 다 모은다"가 가장 빡센 계획이라 그보다 더 빡셀 수는 없다.
        int months = Math.max(1, Math.round(deadlineDays / 30.0f));
        return target.divide(BigDecimal.valueOf(months), 0, RoundingMode.HALF_UP);
    }

    /**
     * 이 사람이 매달 지켜 온 돈의 평균.
     *
     * <p><b>이번 달은 세지 않는다.</b> 아직 안 끝난 달을 평균에 넣으면 매달 1일마다 평균이
     * 바닥을 치고 말일에 회복한다 — 사람이 보기에 숫자가 널뛰고, 그 널뜀은 사실이 아니다.
     * 완결된 달이 하나도 없으면(가입 첫 달) 이번 달 실적을 그대로 쓴다 — 0 을 보여 주면
     * "지금 속도로는 영원히 못 모은다"가 되어 더 틀린다.
     */
    static BigDecimal monthlyAverageSaved(List<PointEvent> deposits, LocalDateTime now) {
        java.time.YearMonth thisMonth = java.time.YearMonth.from(now);
        Map<java.time.YearMonth, BigDecimal> byMonth = new TreeMap<>();
        for (PointEvent e : deposits) {
            if (e.getAmount() == null || e.getOccurredAt() == null) continue;
            byMonth.merge(java.time.YearMonth.from(e.getOccurredAt()), e.getAmount(), BigDecimal::add);
        }
        List<BigDecimal> closed = new ArrayList<>();
        for (Map.Entry<java.time.YearMonth, BigDecimal> e : byMonth.entrySet()) {
            if (e.getKey().isBefore(thisMonth)) closed.add(e.getValue());
        }
        if (closed.isEmpty()) {
            BigDecimal cur = byMonth.get(thisMonth);
            return cur == null ? BigDecimal.ZERO : cur;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : closed) sum = sum.add(v);
        return sum.divide(BigDecimal.valueOf(closed.size()), 0, RoundingMode.HALF_UP);
    }

    /**
     * 지금 속도로 갔을 때의 달성일.
     *
     * <p>속도가 0 이면 <b>날짜를 만들지 않고 null 을 준다.</b> "2126년"처럼 형식만 맞는 답을
     * 내놓으면 화면은 그것을 사실처럼 그린다 — 모르는 것은 모른다고 해야 한다.
     */
    static String projectedDate(BigDecimal target, BigDecimal balance,
                                        BigDecimal monthlyAverage, LocalDateTime now) {
        if (target == null || monthlyAverage == null || monthlyAverage.signum() <= 0) return null;
        BigDecimal remaining = target.subtract(balance == null ? BigDecimal.ZERO : balance);
        if (remaining.signum() <= 0) return now.toLocalDate().toString();   // 이미 다 모았다
        int months = remaining.divide(monthlyAverage, 0, RoundingMode.CEILING).intValue();
        // 100년 뒤는 답이 아니라 잡음이다. 그 이상은 "모른다"로 둔다.
        if (months > 1200) return null;
        return now.toLocalDate().plusMonths(months).toString();
    }

    /** 이 목표에 매달 쌓인 돈 — 오래된 달이 앞이다. */
    static List<MonthlySaving> monthlyHistory(List<PointEvent> deposits, Long goalId) {
        Map<java.time.YearMonth, BigDecimal> byMonth = new TreeMap<>();
        for (PointEvent e : deposits) {
            if (!java.util.Objects.equals(e.getGoalId(), goalId)) continue;
            if (e.getAmount() == null || e.getOccurredAt() == null) continue;
            byMonth.merge(java.time.YearMonth.from(e.getOccurredAt()), e.getAmount(), BigDecimal::add);
        }
        List<MonthlySaving> out = new ArrayList<>();
        byMonth.forEach((m, v) -> out.add(new MonthlySaving(m.toString(), scale(v))));
        return out;
    }

    // ======================================================================
    //  DTO
    // ======================================================================

    public record GoalView(Long id, String name, String emoji, BigDecimal targetAmount,
                           BigDecimal balance, double progress, boolean priority,
                           List<MilestoneView> milestones, int deadlineDays, int fundedDays,
                           /** 저축 계획 — 줄이기로 한 습관 소비 카테고리 코드 */
                           List<String> planCutCategories,
                           /** 그 소비들의 월 절약액 */
                           BigDecimal planMonthlySaving,
                           /** 그 절약액으로 이 목표를 달성하는 개월수 (계획 없으면 0) */
                           int planMonths,
                           /** 이 목표의 자유입출금통장(§13-11) — 은행·통장명·계좌번호 */
                           String accountBank, String accountProduct, String accountNumber,
                           /** 이루면 마이룸에 도착할 소품 코드. 안 골랐으면 null */
                           String rewardCode,
                           /** 기한 안에 맞추려면 <b>매달 넣어야 하는 돈</b> = 목표액 ÷ 기한개월 */
                           BigDecimal monthlyRequired,
                           /** 이 사람이 <b>실제로</b> 매달 지켜 온 돈 — 위와 견주라고 함께 준다 */
                           BigDecimal monthlyAverageSaved,
                           /** 지금 속도로 갔을 때의 달성일(yyyy-MM-dd). 속도가 0이면 null */
                           String projectedDate,
                           /** 매달 쌓인 기록 — 오래된 달이 앞이다 */
                           List<MonthlySaving> monthlyHistory) {}

    /**
     * 한 달에 이 목표로 들어온 돈.
     *
     * @param month  {@code yyyy-MM}
     */
    public record MonthlySaving(String month, BigDecimal amount) {}

    /** 계획에서 '줄일 수 있는' 습관 소비 후보 — 카테고리별 월평균. */
    public record CutOption(String categoryCode, String displayName, BigDecimal monthlyAmount) {}

    public record MilestoneView(Long id, String name, String emoji, BigDecimal cost,
                                boolean acquired, double progress, BigDecimal remaining) {}

    public record ForcedWithdrawal(String goalName, BigDecimal amount) {}

    /** 참는 순간의 목표 진척 변화 — "62% → 68% · D-N 단축" (획득 프레이밍). */
    public record GoalGain(String goalName, String emoji,
                           double progressBefore, double progressAfter, int daysAdded, BigDecimal balanceAfter) {}

    public record CouponView(Long id, String categoryCode, BigDecimal benefitAmount) {}

    public record Suggestion(String categoryCode, String displayName,
                             BigDecimal typicalAmount, BigDecimal totalUnplanned) {}

    public record EventView(String type, String reason, BigDecimal amount,
                            String categoryCode, String occurredAt) {}

    public record WishlistView(Long id, String name, BigDecimal price,
                               String categoryCode, String imageUrl) {}

    public record PointSnapshot(
            Long userId,
            String month,
            BigDecimal monthlyBudget,
            BigDecimal thisMonthSpent,
            /** 이번 달 목표에 입금한 총액 */
            BigDecimal thisMonthSaved,
            /** 이번 달 아직 쓸 수 있는 돈 (음수면 예산 초과) */
            BigDecimal pointsRemaining,
            /** 목표에 실제로 모인 총액 (ΣDEPOSIT − ΣWITHDRAWAL) */
            BigDecimal totalSavings,
            BigDecimal totalTarget,
            /** 선물상자 채움 비율 (totalSavings / totalTarget, 0~1) */
            double giftFill,
            /** 직전 액션: SAVED · SPEND · UNNECESSARY · OVERSPEND · COUPON_* · null */
            String lastAction,
            BigDecimal lastAmount,
            /** 초과 강제차감 정보 (없으면 null) */
            ForcedWithdrawal forcedWithdrawal,
            /** 대기 중인 치팅데이 쿠폰 (없으면 null) */
            CouponView coupon,
            List<GoalView> goals,
            List<Suggestion> suggestions,
            List<EventView> recentEvents,
            /** 고민 목록 — 아직 살까 말까 고민 중인 상품 (문서 §5-5 폴센트 응용) */
            List<WishlistView> wishlist,
            /** 안 사서 아낀 총액 (NOT_BOUGHT 상품 가격 합) */
            BigDecimal savedByNotBuying,
            /** 소비건전성지수 (FDS 재프레이밍 — 미계획 소비 지속 시 하락) */
            int healthScore,
            String healthGrade,
            /** 최근 연속 미계획(필요없는) 소비 횟수 */
            int unnecessaryStreak,
            /** 행동 경고 — 미계획 소비 연속·다짐 어김 */
            List<String> behaviorAlerts,
            /** 직전 '살 뻔했다/안 샀어요'가 어느 목표를 얼마나 진척시켰는가 (획득 프레이밍, 없으면 null) */
            GoalGain gain,
            /** 저축 계획에서 줄일 수 있는 습관 소비 후보 (카테고리별 월평균) */
            List<CutOption> cutOptions,
            /**
             * 이 사람이 <b>실제로</b> 매달 지켜 온 돈.
             *
             * <p>목표 안({@link GoalView#monthlyAverageSaved})에도 같은 값이 있지만 <b>여기가
             * 제자리다</b> — 사람 단위 값이지 목표 단위가 아니다. 목표 세우기(4걸음)는
             * <b>첫 목표를 만드는 순간</b> 이 값이 필요한데, 그때는 꺼내 볼 목표가 없다.
             * 목표 안의 사본은 상세 화면이 한 번 더 조회하지 않게 하려고 남긴다.
             */
            BigDecimal monthlyAverageSaved
    ) {}
}
