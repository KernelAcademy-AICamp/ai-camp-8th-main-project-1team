package com.finntech.service;

import com.finntech.domain.AppUser;
import com.finntech.domain.Category;
import com.finntech.domain.Consumption;
import com.finntech.domain.Enums;
import com.finntech.domain.ImpulseSaverState;
import com.finntech.repository.CategoryRepository;
import com.finntech.repository.ConsumptionRepository;
import com.finntech.repository.ImpulseSaverStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 충동예산 절약통 (마스터 §5-5, 2026-07-21 방향 전환) — <b>수동 '살 뻔했다'를 대체</b>.
 *
 * <p><b>루프.</b> ① 카드내역(=시드된 소비 이력)에서 '충동' 카테고리를 지정 → 예산 = 그 카테고리들의 <b>월 평균 지출</b>.
 * ② 선물상자는 사용자가 들어올 때마다 <b>시간에 따라 자동 성장</b>(하루 할당량을 50→30→20%로, 안 들어온 날은
 * 다음 방문에 합산). ③ 충동소비를 기록하면 그만큼 <b>균열</b>(잔액 차감). ④ 다음달 카드내역을 재업로드하면
 * 지정한 카테고리 지출이 실제로 줄었는지 <b>월대월 재검증</b>한다.
 *
 * <p><b>재현성(§4).</b> 성장은 시간 의존이지만 {@code Clock} 주입 + 저장 상태(마지막 방문일·오늘 드러낸 비율)만으로
 * 결정론적이다. 성장 로직({@link #nextFraction}·{@link #accrueDelta})은 순수 함수라 단위 테스트로 고정한다.
 */
@Service
public class ImpulseSaverService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final ConsumptionRepository consumptionRepository;
    private final CategoryRepository categoryRepository;
    private final ImpulseSaverStateRepository stateRepository;

    public ImpulseSaverService(ConsumptionRepository consumptionRepository,
                               CategoryRepository categoryRepository,
                               ImpulseSaverStateRepository stateRepository) {
        this.consumptionRepository = consumptionRepository;
        this.categoryRepository = categoryRepository;
        this.stateRepository = stateRepository;
    }

    // ======================================================================
    //  순수 계산 (단위 테스트 진입점)
    // ======================================================================

    /** 오늘 하루 할당량 중 다음 방문에서 드러낼 누적 비율. 0→.5→.8→1.0 에서 멈춘다. 순수. */
    static double nextFraction(double f) {
        if (f < 0.5) return 0.5;
        if (f < 0.8) return 0.8;
        return 1.0;
    }

    /**
     * 이번 방문에 절약통에 더할 금액 = f(마지막 방문일, 오늘 드러낸 비율, 오늘, 하루 할당량). 순수·결정론.
     * <p>같은 날: 다음 단계(30%·20%)만큼. 다른 날: <b>어제의 남은 할당량 + 그 사이 안 들어온 날의 전체 할당량 + 오늘 첫 단계(50%)</b>.
     */
    static BigDecimal accrueDelta(LocalDate last, double lastFraction, LocalDate today, BigDecimal dailyQuota) {
        if (dailyQuota == null || dailyQuota.signum() <= 0) return BigDecimal.ZERO;
        if (last == null) return q(dailyQuota, 0.5);                 // 최초 방문 → 오늘 첫 단계
        if (today.isEqual(last)) {                                   // 같은 날 → 다음 단계만큼
            return q(dailyQuota, nextFraction(lastFraction) - lastFraction);
        }
        long span = ChronoUnit.DAYS.between(last, today);            // >= 1
        double finishYesterday = 1.0 - lastFraction;                // 어제 남은 몫 채우기
        double skipped = span - 1;                                  // 완전히 비운 날은 전체 할당량씩
        return q(dailyQuota, finishYesterday + skipped + 0.5);      // + 오늘 첫 단계
    }

    /** 방문 후 '오늘 드러낸 비율' 갱신값. 순수. */
    static double newFraction(LocalDate last, double lastFraction, LocalDate today) {
        if (last == null) return 0.5;
        if (today.isEqual(last)) return nextFraction(lastFraction);
        return 0.5;
    }

    private static BigDecimal q(BigDecimal dailyQuota, double factor) {
        if (factor <= 0) return BigDecimal.ZERO;
        return dailyQuota.multiply(BigDecimal.valueOf(factor)).setScale(0, RoundingMode.HALF_UP);
    }

    // ======================================================================
    //  공개 API
    // ======================================================================

    /** 조회 = 방문. 자동 성장시키고 스냅샷을 돌려준다. */
    @Transactional
    public Snapshot snapshot(AppUser user, LocalDateTime now) {
        ImpulseSaverState st = grow(user.getId(), now, false);
        return build(user.getId(), st, now, "GROW");
    }

    /** 충동 카테고리 지정 → 예산이 다시 계산된다. */
    @Transactional
    public Snapshot setImpulseCategories(AppUser user, List<String> categories, LocalDateTime now) {
        ImpulseSaverState st = state(user.getId());
        st.setImpulseCategories(categories == null || categories.isEmpty() ? null : String.join(",", categories));
        stateRepository.save(st);
        st = grow(user.getId(), now, false);
        return build(user.getId(), st, now, null);
    }

    /** 충동소비 기록 → 소비를 남기고 절약통을 그만큼 깬다(균열). */
    @Transactional
    public Snapshot recordImpulseSpend(AppUser user, String categoryCode, BigDecimal amount, LocalDateTime now) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("금액은 0보다 커야 합니다");
        Category cat = categoryRepository.findByCode(categoryCode)
                .orElseGet(() -> categoryRepository.save(new Category(categoryCode, categoryCode)));
        consumptionRepository.save(new Consumption(user.getId(), cat, amount, now, false, Enums.DataSource.USER_INPUT));
        ImpulseSaverState st = grow(user.getId(), now, true);        // 먼저 오늘치 성장 반영
        st.setGiftBalance(st.getGiftBalance().subtract(amount).max(BigDecimal.ZERO));
        stateRepository.save(st);
        return build(user.getId(), st, now, "UNNECESSARY");
    }

    /** 다음달 카드내역(CSV: 날짜,카테고리코드,금액) 업로드 → 소비로 적재(CARD_UPLOAD). */
    @Transactional
    public Snapshot uploadCard(AppUser user, String csv, LocalDateTime now) {
        int added = 0;
        for (String raw : csv == null ? new String[0] : csv.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] c = line.split(",");
            if (c.length < 3) continue;
            LocalDate d;
            BigDecimal amt;
            try {
                d = LocalDate.parse(c[0].trim());
                amt = new BigDecimal(c[2].trim().replaceAll("[^0-9.]", ""));
            } catch (RuntimeException e) { continue; }
            if (amt.signum() <= 0) continue;
            String code = c[1].trim().toUpperCase();
            Category cat = categoryRepository.findByCode(code)
                    .orElseGet(() -> categoryRepository.save(new Category(code, code)));
            consumptionRepository.save(new Consumption(user.getId(), cat, amt,
                    d.atTime(12, 0), true, Enums.DataSource.CARD_UPLOAD));
            added++;
        }
        ImpulseSaverState st = grow(user.getId(), now, false);
        Snapshot s = build(user.getId(), st, now, null);
        return s.withUploaded(added);
    }

    // ======================================================================
    //  내부 — 성장·예산·재검증
    // ======================================================================

    private ImpulseSaverState state(Long userId) {
        return stateRepository.findByUserId(userId)
                .orElseGet(() -> stateRepository.save(new ImpulseSaverState(userId)));
    }

    /** 방문 시 자동 성장(예산 상한까지). {@code afterSpend}면 이 호출은 성장만(균열은 호출부가). */
    private ImpulseSaverState grow(Long userId, LocalDateTime now, boolean afterSpend) {
        ImpulseSaverState st = state(userId);
        BigDecimal budget = monthlyBudget(userId, st);
        LocalDate today = now.toLocalDate();
        if (budget.signum() > 0) {
            BigDecimal dailyQuota = budget.divide(
                    BigDecimal.valueOf(today.lengthOfMonth()), 0, RoundingMode.HALF_UP);
            BigDecimal delta = accrueDelta(st.getLastVisitDate(), st.getTodayFraction(), today, dailyQuota);
            st.setGiftBalance(st.getGiftBalance().add(delta).min(budget).max(BigDecimal.ZERO));
            st.setTodayFraction(newFraction(st.getLastVisitDate(), st.getTodayFraction(), today));
            if (st.getStartDate() == null) st.setStartDate(today);
            st.setLastVisitDate(today);
        }
        return stateRepository.save(st);
    }

    /** 예산 = 지정한 충동 카테고리들의 월 평균 지출 합. */
    private BigDecimal monthlyBudget(Long userId, ImpulseSaverState st) {
        List<String> cats = parseCsv(st.getImpulseCategories());
        if (cats.isEmpty()) return BigDecimal.ZERO;
        Map<String, BigDecimal> monthly = monthlyAvgByCategory(userId, null);
        BigDecimal sum = BigDecimal.ZERO;
        for (String code : cats) sum = sum.add(monthly.getOrDefault(code, BigDecimal.ZERO));
        return sum;
    }

    /** 카테고리별 월 평균 지출. {@code excludeMonth}(yyyy-MM)이 있으면 그 달은 제외(재검증 기준선용). */
    private Map<String, BigDecimal> monthlyAvgByCategory(Long userId, String excludeMonth) {
        Map<String, BigDecimal> sum = new TreeMap<>();
        Map<String, Set<String>> monthsByCat = new TreeMap<>();
        for (Consumption c : consumptionRepository.findAllForUser(userId)) {
            String m = c.getOccurredAt().format(MONTH);
            if (m.equals(excludeMonth)) continue;
            String code = c.getCategory().getCode();
            sum.merge(code, c.getAmount(), BigDecimal::add);
            monthsByCat.computeIfAbsent(code, k -> new HashSet<>()).add(m);
        }
        Map<String, BigDecimal> avg = new TreeMap<>();
        for (var e : sum.entrySet()) {
            int months = Math.max(1, monthsByCat.get(e.getKey()).size());
            avg.put(e.getKey(), e.getValue().divide(BigDecimal.valueOf(months), 0, RoundingMode.HALF_UP));
        }
        return avg;
    }

    /** 지정한 충동 카테고리에 대해 '가장 최근 업로드 달'의 지출 vs 그 달을 뺀 기준선 월평균을 대조. */
    private List<VerifyRow> verify(Long userId, ImpulseSaverState st) {
        List<String> cats = parseCsv(st.getImpulseCategories());
        String latestMonth = latestUploadMonth(userId);
        if (cats.isEmpty() || latestMonth == null) return List.of();

        Map<String, BigDecimal> latest = new TreeMap<>();     // 최근 달 카테고리 지출
        Map<String, String> names = new TreeMap<>();
        for (Consumption c : consumptionRepository.findAllForUser(userId)) {
            names.putIfAbsent(c.getCategory().getCode(), c.getCategory().getDisplayName());
            if (c.getOccurredAt().format(MONTH).equals(latestMonth)) {
                latest.merge(c.getCategory().getCode(), c.getAmount(), BigDecimal::add);
            }
        }
        Map<String, BigDecimal> baseline = monthlyAvgByCategory(userId, latestMonth);

        List<VerifyRow> out = new ArrayList<>();
        for (String code : cats) {
            BigDecimal base = baseline.getOrDefault(code, BigDecimal.ZERO);
            BigDecimal now = latest.getOrDefault(code, BigDecimal.ZERO);
            double changePct = base.signum() > 0
                    ? now.subtract(base).doubleValue() / base.doubleValue() : 0.0;
            out.add(new VerifyRow(code, names.getOrDefault(code, code),
                    base, now, changePct, now.compareTo(base) < 0));
        }
        return out;
    }

    private String latestUploadMonth(Long userId) {
        String latest = null;
        for (Consumption c : consumptionRepository.findAllForUser(userId)) {
            if (c.getSource() != Enums.DataSource.CARD_UPLOAD) continue;
            String m = c.getOccurredAt().format(MONTH);
            if (latest == null || m.compareTo(latest) > 0) latest = m;
        }
        return latest;
    }

    /** 지정 후보 = 이력에서 지출이 있는 카테고리(월평균 큰 순). */
    private List<CategoryMonthly> options(Long userId) {
        Map<String, BigDecimal> monthly = monthlyAvgByCategory(userId, null);
        Map<String, String> names = new TreeMap<>();
        for (Consumption c : consumptionRepository.findAllForUser(userId)) {
            names.putIfAbsent(c.getCategory().getCode(), c.getCategory().getDisplayName());
        }
        List<CategoryMonthly> out = new ArrayList<>();
        monthly.forEach((k, v) -> out.add(new CategoryMonthly(k, names.getOrDefault(k, k), v)));
        out.sort(Comparator.comparing(CategoryMonthly::monthlyAmount).reversed().thenComparing(CategoryMonthly::categoryCode));
        return out;
    }

    private Snapshot build(Long userId, ImpulseSaverState st, LocalDateTime now, String lastAction) {
        BigDecimal budget = monthlyBudget(userId, st);
        BigDecimal balance = st.getGiftBalance();
        double fill = budget.signum() <= 0 ? 0.0
                : Math.min(1.0, balance.divide(budget, 6, RoundingMode.HALF_UP).doubleValue());
        BigDecimal dailyQuota = budget.signum() <= 0 ? BigDecimal.ZERO
                : budget.divide(BigDecimal.valueOf(now.toLocalDate().lengthOfMonth()), 0, RoundingMode.HALF_UP);
        return new Snapshot(scale(budget), scale(balance), fill, scale(dailyQuota),
                parseCsv(st.getImpulseCategories()), options(userId),
                latestUploadMonth(userId) != null, verify(userId, st), lastAction, 0);
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static BigDecimal scale(BigDecimal v) { return v == null ? null : v.setScale(0, RoundingMode.HALF_UP); }

    // ======================================================================
    //  DTO
    // ======================================================================

    public record CategoryMonthly(String categoryCode, String displayName, BigDecimal monthlyAmount) {}

    /** 재검증 한 줄 — 지정 카테고리의 기준선(월평균) 대비 최근 업로드 달 지출. improved=줄었다. */
    public record VerifyRow(String categoryCode, String displayName,
                            BigDecimal baseline, BigDecimal latest, double changePct, boolean improved) {}

    public record Snapshot(
            /** 충동예산(월 평균) */
            BigDecimal budget,
            /** 현재 절약통 잔액 */
            BigDecimal giftBalance,
            /** 채움 비율 0~1 */
            double giftFill,
            /** 하루 할당량 */
            BigDecimal dailyQuota,
            /** 지정한 충동 카테고리 */
            List<String> impulseCategories,
            /** 지정 후보(카테고리별 월평균) */
            List<CategoryMonthly> options,
            /** 재업로드 이력 존재 여부 */
            boolean hasUpload,
            /** 재검증 결과 */
            List<VerifyRow> verify,
            /** 직전 액션(GROW·UNNECESSARY·null) — 선물상자 애니메이션 */
            String lastAction,
            /** 방금 업로드로 적재된 건수(업로드 응답에서만 >0) */
            int uploaded
    ) {
        Snapshot withUploaded(int n) {
            return new Snapshot(budget, giftBalance, giftFill, dailyQuota, impulseCategories,
                    options, hasUpload, verify, lastAction, n);
        }
    }
}
