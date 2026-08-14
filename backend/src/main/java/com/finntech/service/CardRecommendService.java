package com.finntech.service;

import com.finntech.config.CardRecommendProperties;
import com.finntech.domain.CardAnnualFee;
import com.finntech.domain.CardBenefit;
import com.finntech.domain.CardBenefitTarget;
import com.finntech.domain.CardCombinedCap;
import com.finntech.domain.CardExclusion;
import com.finntech.domain.CardPerformanceTier;
import com.finntech.domain.CardProduct;
import com.finntech.domain.UserPayment;
import com.finntech.engine.AnalysisResult;
import com.finntech.engine.CardExclusionPolicy;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.CardProductRepository;
import com.finntech.repository.UserPaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 카드 추천 (개편안 {@code s-compare}).
 *
 * <p><b>추천 순서는 광고비가 아니라 절감액 순이다.</b> 화면이 "이 카드를 추천해요"라고만 말하면
 * 그건 광고다. 여기서는 <i>당신이 이미 쓴 곳</i>에 그 카드의 요율을 곱해 연 얼마가 남는지를
 * 세고, 그 수치 순으로 세운다. 근거가 되는 소비 요약을 같은 응답에 싣는 것도 그래서다 —
 * 순위와 근거가 한 화면에 있어야 검증이 된다.
 *
 * <h2>카드는 실제 상품이다</h2>
 *
 * 마스터 원칙 5 재개정(2026-08-10). 예전에는 {@code application.yml} 의 {@code [더미]} 3장을
 * 읽었고 주석도 "실재 상품을 넣는 순간 중개가 된다"고 적혀 있었는데, 카드 추천은 이 프로젝트의
 * 필수 기능이라 더미로는 성립하지 않는다(사용자 결정). 지금은 카드사 상품공시에서 온 실제
 * 상품을 표에서 읽는다({@code V34}). 예적금·펀드는 그대로 더미다.
 *
 * <p><b>그래서 "더미라서 영업이 아니다"라는 방패가 이 화면에는 없다.</b> 대신 금융위·금감원
 * 유권해석(2022.6.15)의 네 요건으로 선다 — 단순 정보제공 · 판매 목적 아님 · 제휴/광고 계약 없음 ·
 * 가입 편의 미제공.
 *
 * <p><b>이 클래스가 하면 안 되는 것 넷.</b> 하나라도 하면 금소법상 중개업 등록 대상이 된다
 * (제67조: 5년 이하 징역 또는 2억원 이하 벌금).
 * <ol>
 *   <li>카드사와 제휴·광고 계약을 맺는 것</li>
 *   <li>수수료를 받는 것</li>
 *   <li><b>카드사로 넘어가는 CTA 버튼·신청 링크를 두는 것</b> — 여기는 혜택 비교까지다</li>
 *   <li>순위를 광고비에 연동하는 것 — 순위는 절감액순이고 그 근거를 같은 응답에 싣는다</li>
 * </ol>
 *
 * <p><b>혜택 개정 추적은 스코프 밖이다</b>(사용자 결정 2026-08-10). 그래서 카드 정보는
 * <b>수집 시점에 고정된 스냅샷</b>이고 시간이 지나면 낡는다 — {@link Offer#asOf} 를 화면에
 * 병기해 "이 시점 공시 기준"임을 밝히는 것으로 처리한다. <b>③ CTA 금지가 이 방어의
 * 전제다</b> — CTA 를 다는 순간 "낡은 정보로 가입을 유도한 것"이 되어 둘이 함께 무너진다.
 *
 * <h2>절감액을 어떻게 세나 (07 §4.4)</h2>
 *
 * <pre>
 *   1  전달 승인내역 → 실적 제외 항목 빼기        → 전월실적
 *   2  실적으로 카드의 구간(tier) 결정            → 어떤 한도가 열리나
 *   3  전달 소비를 혜택 묶음별로 분배             → 브랜드 1순위 · 축 2순위
 *   4  묶음마다 min(그 묶음 소비 × 요율, 월한도)
 *   5  통합한도로 한 번 더 자름                  → 페이북: 개별 합 15,000 &gt; 통합 13,000
 *   6  Σ × 12 − 연회비                          → 연 절감액
 * </pre>
 *
 * <p><b>이전율 α 같은 임의 상수가 없다.</b> "아직 발급 안 한 카드에 전월실적이 있을 리 없다"는
 * 순환 때문에 α(=0.7 같은 값)를 두려 했는데, <b>전달에 실제로 쓴 돈이 곧 전월실적</b>이라
 * 추정할 것이 없다(사용자 결정 2026-08-10). 여러 카드에 나뉘어 있는 문제는 α로 추정하지 않고
 * <b>가정을 밝혀서</b> 푼다 — "이 소비를 이 카드로 모으면".
 *
 * <p><b>한 결제는 한 묶음에만 간다.</b> 그래서 같은 돈을 두 번 아끼는 일이 구조적으로 안 나고,
 * 공시의 배타 관계({@code exclusive_with})를 계산에 쓸 필요도 없다.
 *
 * <p><b>미분류는 축 집계에서만 뺀다.</b> 실적과 축 매칭은 과소 계산되지만 <b>하한 방향</b>이라
 * "채운 줄 알았는데 못 채웠다"가 구조적으로 안 난다. <b>가맹점명은 축과 무관하게 담는다</b> —
 * 브랜드 매칭이 1순위인데 2순위(업종축)가 실패했다고 같이 죽으면 안 된다(2026-08-13).
 */
@Service
public class CardRecommendService {

    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** 절감액을 100원 단위로 끊는다 — 1원 단위까지 말하면 추정치를 확정치처럼 읽힌다. */
    private static final BigDecimal SAVING_UNIT = BigDecimal.valueOf(100);

    private final CardRecommendProperties props;
    private final CardProductRepository cards;
    private final UserPaymentRepository payments;
    private final IndustryCategoryMapper industries;
    private final CardExclusionPolicy exclusions;

    public CardRecommendService(CardRecommendProperties props, CardProductRepository cards,
                                UserPaymentRepository payments, IndustryCategoryMapper industries,
                                CardExclusionPolicy exclusions) {
        this.props = props;
        this.cards = cards;
        this.payments = payments;
        this.industries = industries;
        this.exclusions = exclusions;
    }

    @Transactional(readOnly = true)
    public Result recommend(AnalysisResult analysis, LocalDateTime referenceTime) {
        List<Summary> summary = summarize(analysis);

        // 전달 1일 00:00 (포함) ~ 이번달 1일 00:00 (미포함).
        LocalDate firstOfThisMonth = referenceTime.toLocalDate().withDayOfMonth(1);
        LocalDate firstOfLastMonth = firstOfThisMonth.minusMonths(1);
        List<UserPayment> lastMonth = payments.findInPeriod(analysis.userId(),
                firstOfLastMonth.atStartOfDay(), firstOfThisMonth.atStartOfDay());
        Spend spend = fold(lastMonth);

        List<Offer> offers = new ArrayList<>();
        for (CardProduct card : cards.findRecommendable()) {
            offers.add(evaluate(card, spend));
        }
        // 절감액 내림차순. 동점은 이름으로 깨서 매번 같은 순서가 나오게 한다(원칙 3).
        offers.sort(Comparator
                .comparing(Offer::yearlySaving, Comparator.reverseOrder())
                .thenComparing(Offer::name));
        int max = Math.max(0, props.getMaxCards());
        if (offers.size() > max) offers = new ArrayList<>(offers.subList(0, max));

        return new Result(summary, offers, periodLabel(analysis), analysis.monthlySpend().size(),
                firstOfLastMonth.format(DateTimeFormatter.ofPattern("yyyy.MM")));
    }

    /** 지출 상위 카테고리 — 개편안 {@code .cr-row} 세 줄. */
    private List<Summary> summarize(AnalysisResult analysis) {
        List<Summary> out = new ArrayList<>();
        for (String code : analysis.categoriesBySpendDesc()) {
            if (out.size() >= props.getSummaryTop()) break;
            AnalysisResult.CategoryStat s = analysis.categoryStats().get(code);
            if (s == null) continue;
            out.add(new Summary(out.size() + 1, code, s.displayName(), s.count(), s.totalAmount()));
        }
        return out;
    }

    // ── 1·3단계의 재료: 전달 소비를 축별·가맹점명별로 접는다 ────────────────────────

    /**
     * 전달 소비 한 달치를 접은 것.
     *
     * <p><b>브랜드를 여기서 확정하지 않는다.</b> 어떤 이름이 브랜드인지는 <b>카드가 정한다</b> —
     * 공시가 혜택 대상 브랜드를 직접 나열하기 때문이다(스타벅스·배달의민족·CU·CGV…). 그래서
     * 가맹점명을 그대로 들고 있다가 카드마다 대조한다.
     *
     * @param byAxis    카드혜택 축 → 금액. 축을 모르는 업종은 여기에만 안 담긴다
     * @param byMerchant 가맹점 풀네임 → 금액. <b>축을 몰라도 담는다</b> — 브랜드 매칭의 재료다
     * @param axisOfMerchant 가맹점 풀네임 → 그 가맹점의 축(브랜드가 안 걸렸을 때 쓴다).
     *                       축을 모르는 가맹점은 없다 — {@code byAxis} 에도 없으니 뺄 몫이 없다
     */
    private record Spend(Map<String, BigDecimal> byAxis,
                         Map<String, BigDecimal> byMerchant,
                         Map<String, String> axisOfMerchant) {}

    private Spend fold(List<UserPayment> rows) {
        Map<String, BigDecimal> byAxis = new TreeMap<>();
        Map<String, BigDecimal> byMerchant = new TreeMap<>();
        Map<String, String> axisOf = new TreeMap<>();
        for (UserPayment p : rows) {
            BigDecimal amount = BigDecimal.valueOf(p.getAmount());
            String axis = industries.cardAxisOf(p.getKsicCode());
            // 축을 못 찾아도 **가맹점명은 살린다**. 예전에는 여기서 `continue` 로 결제를 통째로
            // 버렸는데, 브랜드 매칭은 업종코드가 아니라 가맹점명으로 하므로 축이 없다는 이유로
            // 브랜드까지 같이 죽었다. 2026-08-13 실측에서 그대로 터졌다 — 승인내역의 업종코드가
            // 표와 자릿수가 안 맞아 전건이 null 이 됐고, 결제 248건이 있는데도 추천이 전부 0 이
            // 나왔다. 축은 2순위 신호일 뿐이고, 1순위(브랜드)를 2순위 실패로 끌어내리면 안 된다.
            if (axis != null) {
                byAxis.merge(axis, amount, BigDecimal::add);
            }
            String merchant = p.getMerchantName();
            if (merchant != null && !merchant.isBlank()) {
                byMerchant.merge(merchant, amount, BigDecimal::add);
                // 축이 없으면 넣지 않는다 — byAxis 에도 안 들어갔으니 뺄 몫이 없다.
                if (axis != null) {
                    axisOf.put(merchant, axis);
                }
            }
        }
        return new Spend(byAxis, byMerchant, axisOf);
    }

    // ── 카드 한 장 ────────────────────────────────────────────────────────────

    private Offer evaluate(CardProduct card, Spend spend) {
        // 1. 전월실적 = 전달 소비 − 이 카드의 실적 제외 축. **카드마다 목록이 다르다.**
        Set<String> excluded = exclusions.axesToExclude(
                card.exclusionsOn(CardExclusion.Axis.PERFORMANCE).stream()
                        .map(CardExclusion::getCode).toList());
        BigDecimal performance = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : spend.byAxis().entrySet()) {
            if (!excluded.contains(e.getKey())) performance = performance.add(e.getValue());
        }

        // 2. 실적으로 구간을 연다. 못 채웠으면 null — 구간 조건이 붙은 혜택은 안 열린다.
        CardPerformanceTier tier = card.tierFor(performance.longValue());

        // 3. 전달 소비를 혜택 묶음별로 분배 (브랜드 1순위 · 축 2순위).
        Map<CardBenefit, BigDecimal> allocated = allocate(card, spend, tier);

        // 4·5. 묶음마다 자르고, 통합한도로 한 번 더 자른다.
        Map<String, BigDecimal> combinedUsed = new LinkedHashMap<>();
        BigDecimal monthly = BigDecimal.ZERO;
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<CardBenefit, BigDecimal> e : allocated.entrySet()) {
            CardBenefit benefit = e.getKey();
            BigDecimal earned = e.getValue()
                    .multiply(benefit.getRatePercent())
                    .divide(HUNDRED, 2, RoundingMode.HALF_UP);
            Integer cap = benefit.capFor(tier);
            if (cap != null) earned = earned.min(BigDecimal.valueOf(cap));
            earned = capCombined(card, benefit, tier, earned, combinedUsed);
            if (earned.signum() <= 0) continue;
            monthly = monthly.add(earned);
            rows.add(new Row(label(benefit), value(benefit, earned)));
        }

        // 6. 연 환산 뒤 연회비를 뺀다 — 빼야 "아낀다"가 참이 된다.
        BigDecimal annualFee = annualFee(card);
        BigDecimal saving = monthly.multiply(MONTHS_PER_YEAR).subtract(annualFee);
        if (saving.signum() < 0) saving = BigDecimal.ZERO;
        saving = saving.divide(SAVING_UNIT, 0, RoundingMode.DOWN).multiply(SAVING_UNIT);

        rows.add(new Row("지난달 실적", performanceLabel(card, performance, tier)));
        rows.add(new Row("연회비", annualFee.signum() == 0
                ? "없음" : String.format("%,d원", annualFee.toBigInteger())));

        return new Offer(card.getName(), tagline(card), tint(card), mark(card), card.getIssuer(),
                saving, cappedAt(card, tier), rows,
                card.getAsOf() == null ? null : card.getAsOf().toString());
    }

    /**
     * 전달 소비를 혜택 묶음에 나눈다 — <b>한 결제는 한 묶음에만 간다.</b>
     *
     * <p><b>브랜드가 1순위다.</b> 업종코드로 못 푸는 축이 있기 때문이다 — 배달의민족은
     * <i>통신판매업</i>으로 등록돼 업종으로는 '쇼핑'이 되고, 넷플릭스와 일부 PG 는 같은 코드
     * 724000 을 쓴다. 브랜드로 걸린 가맹점은 축 배분에서 빠진다(두 번 세지 않는다).
     *
     * <p><b>가장 긴 브랜드 이름이 이긴다.</b> {@code 쿠팡이츠 결제}는 '쿠팡'에도 걸리는데,
     * 공시가 <i>"쿠팡은 쿠팡이츠 제외"</i>라고 적어 둔 그 자리다. 긴 쪽을 먼저 보면 이 오배정이
     * 안 난다.
     */
    private Map<CardBenefit, BigDecimal> allocate(CardProduct card, Spend spend,
                                                  CardPerformanceTier tier) {
        List<CardBenefit> open = card.getBenefits().stream()
                .filter(CardBenefit::isCountable)
                .filter(b -> b.getRatePercent() != null && b.getRatePercent().signum() > 0)
                .filter(b -> b.opensAt(tier))
                .toList();

        Map<CardBenefit, BigDecimal> out = new LinkedHashMap<>();
        Set<String> claimed = new java.util.HashSet<>();

        // ① 브랜드 — 긴 이름부터. 한 가맹점은 한 번만 걸린다.
        record Hit(CardBenefit benefit, String brand) {}
        List<Hit> byBrand = new ArrayList<>();
        for (CardBenefit benefit : open) {
            for (CardBenefitTarget target : benefit.getTargets()) {
                if (CardBenefitTarget.Kind.BRAND.name().equals(target.getKind())) {
                    byBrand.add(new Hit(benefit, target.getValue()));
                }
            }
        }
        byBrand.sort(Comparator.comparingInt((Hit h) -> h.brand().length()).reversed()
                .thenComparing(h -> h.benefit().getSortNo())
                .thenComparing(Hit::brand));
        for (Hit hit : byBrand) {
            for (Map.Entry<String, BigDecimal> e : spend.byMerchant().entrySet()) {
                if (claimed.contains(e.getKey())) continue;
                if (!e.getKey().contains(hit.brand())) continue;
                claimed.add(e.getKey());
                out.merge(hit.benefit(), e.getValue(), BigDecimal::add);
            }
        }

        // ② 축 — 브랜드로 안 걸린 나머지. 축 소비에서 브랜드로 가져간 몫을 뺀다.
        Map<String, BigDecimal> claimedByAxis = new TreeMap<>();
        for (String merchant : claimed) {
            String axis = spend.axisOfMerchant().get(merchant);
            if (axis != null) {
                claimedByAxis.merge(axis, spend.byMerchant().get(merchant), BigDecimal::add);
            }
        }
        for (CardBenefit benefit : open) {
            for (CardBenefitTarget target : benefit.getTargets()) {
                if (!CardBenefitTarget.Kind.AXIS.name().equals(target.getKind())) continue;
                BigDecimal total = spend.byAxis().get(target.getValue());
                if (total == null) continue;
                BigDecimal rest = total.subtract(
                        claimedByAxis.getOrDefault(target.getValue(), BigDecimal.ZERO));
                if (rest.signum() <= 0) continue;
                out.merge(benefit, rest, BigDecimal::add);
                // 같은 축을 다른 묶음이 또 가져가지 않게 소진 처리한다.
                claimedByAxis.merge(target.getValue(), rest, BigDecimal::add);
            }
        }
        return out;
    }

    /**
     * 통합한도로 한 번 더 자른다.
     *
     * <p>페이북 실측: 종합몰·패션몰·생활몰이 각 5,000원인데 통합한도는 13,000원이다.
     * <b>개별 합(15,000)이 통합(13,000)을 넘으므로 절삭 순서가 결과를 바꾼다</b> —
     * 개별로 먼저 자르고, 남은 통합 여유만큼만 받는다.
     */
    private BigDecimal capCombined(CardProduct card, CardBenefit benefit, CardPerformanceTier tier,
                                   BigDecimal earned, Map<String, BigDecimal> used) {
        String group = benefit.getCombinedCapGroup();
        if (group == null || tier == null) return earned;
        Integer cap = null;
        for (CardCombinedCap row : card.getCombinedCaps()) {
            // 구간을 id 가 아니라 금액으로 맞춘다 — CardBenefit.capFor 와 같은 이유다.
            if (row.getGroupName().equals(group)
                    && row.getTier().getThresholdKrw() == tier.getThresholdKrw()) {
                cap = row.getCapKrw();
            }
        }
        if (cap == null) return earned;
        BigDecimal room = BigDecimal.valueOf(cap)
                .subtract(used.getOrDefault(group, BigDecimal.ZERO));
        BigDecimal taken = earned.min(room).max(BigDecimal.ZERO);
        used.merge(group, taken, BigDecimal::add);
        return taken;
    }

    /**
     * 연회비 — <b>국내전용 중 가장 싼 것</b>을 쓴다.
     *
     * <p>해외겸용을 고르면 안 쓸 수도 있는 비용을 얹어 절감액이 실제보다 작아진다. 반대로
     * 연회비를 아예 빼먹으면 "아낀다"가 거짓이 된다. 국내전용이 없으면 있는 것 중 최저를 쓴다.
     */
    private BigDecimal annualFee(CardProduct card) {
        return card.getAnnualFees().stream()
                .filter(f -> CardAnnualFee.Scope.DOMESTIC.name().equals(f.getScope()))
                .map(f -> BigDecimal.valueOf(f.getTotal()))
                .min(Comparator.naturalOrder())
                .orElseGet(() -> card.getAnnualFees().stream()
                        .map(f -> BigDecimal.valueOf(f.getTotal()))
                        .min(Comparator.naturalOrder())
                        .orElse(BigDecimal.ZERO));
    }

    /** 이 구간에서 이 카드가 한 달에 줄 수 있는 최대 혜택 — 한도에 걸렸는지 화면이 말하는 근거. */
    private BigDecimal cappedAt(CardProduct card, CardPerformanceTier tier) {
        if (tier == null) return null;
        BigDecimal total = BigDecimal.ZERO;
        boolean any = false;
        for (CardBenefit benefit : card.getBenefits()) {
            if (!benefit.isCountable()) continue;
            Integer cap = benefit.capFor(tier);
            if (cap == null) return null;      // 한도 없는 혜택이 있으면 상한이 없다
            any = true;
            total = total.add(BigDecimal.valueOf(cap));
        }
        return any ? total.multiply(MONTHS_PER_YEAR) : null;
    }

    // ── 화면 문구. **결과만 쓴다** — 계산 과정(제외 항목 내역)은 안 보여준다 ────────────

    private String performanceLabel(CardProduct card, BigDecimal performance,
                                    CardPerformanceTier tier) {
        String amount = manwon(performance);
        if (card.getTiers().isEmpty()) return amount + " · 실적 조건 없음";
        if (tier == null) {
            int need = card.getTiers().get(0).getThresholdKrw();
            return amount + " · " + manwon(BigDecimal.valueOf(need)) + " 필요";
        }
        return amount + " · 충족";
    }

    private String label(CardBenefit benefit) {
        List<String> groups = benefit.getTargets().stream()
                .map(CardBenefitTarget::getTargetGroup)
                .filter(g -> !g.isBlank()).distinct().limit(3).toList();
        return groups.isEmpty() ? benefit.getGroupName() : String.join("·", groups);
    }

    private String value(CardBenefit benefit, BigDecimal earned) {
        String kind = CardBenefit.Kind.DISCOUNT.name().equals(benefit.getKind()) ? "할인" : "적립";
        return trimRate(benefit.getRatePercent()) + "% " + kind
                + " · 월 " + String.format("%,d원", earned.setScale(0, RoundingMode.DOWN).toBigInteger());
    }

    /** 카드 성격 한 줄 — 가장 요율이 높은 혜택으로 만든다. 마케팅 문구를 옮기지 않는다. */
    private String tagline(CardProduct card) {
        return card.getBenefits().stream()
                .filter(CardBenefit::isCountable)
                .filter(b -> b.getRatePercent() != null)
                .max(Comparator.comparing(CardBenefit::getRatePercent)
                        .thenComparing(Comparator.comparing(CardBenefit::getSortNo).reversed()))
                .map(b -> label(b) + " " + trimRate(b.getRatePercent()) + "%")
                .orElse(card.getIssuer());
    }

    /** 카드 그림 색 — 화면이 아는 값만 쓴다. 카드사별로 고정해 매번 같은 그림이 나오게 한다. */
    private String tint(CardProduct card) {
        String[] tints = {"blue", "gold", "navy"};
        return tints[Math.floorMod(card.getIssuer().hashCode(), tints.length)];
    }

    private String mark(CardProduct card) {
        return card.getName().isBlank() ? "C" : card.getName().substring(0, 1);
    }

    /** "5.0" 이 아니라 "5" 로 — 화면에 소수점이 필요할 때만 남긴다. */
    private static String trimRate(BigDecimal rate) {
        return rate.stripTrailingZeros().toPlainString();
    }

    private static String manwon(BigDecimal won) {
        return won.divide(BigDecimal.valueOf(10000), 0, RoundingMode.DOWN) + "만원";
    }

    /** "2026.05 ~ 2026.07" — 무엇을 근거로 셌는지 밝힌다. */
    private static String periodLabel(AnalysisResult analysis) {
        var keys = analysis.monthlySpend().keySet();
        if (keys.isEmpty()) return "";
        String first = keys.iterator().next();
        String last = first;
        for (String k : keys) last = k;
        return first.replace('-', '.') + " ~ " + last.replace('-', '.');
    }

    public record Summary(int rank, String categoryCode, String displayName,
                          long count, BigDecimal amount) {}

    public record Row(String label, String value) {}

    public record Offer(String name, String tagline, String tint, String mark, String footer,
                        /** 연 예상 절감액(원). 연회비를 뺀 값이다. */
                        BigDecimal yearlySaving,
                        /** 한도에 걸렸으면 그 한도, 아니면 null. */
                        BigDecimal cappedAt,
                        List<Row> rows,
                        /**
                         * 공시 기준일(심의필 날짜). <b>화면에 반드시 병기한다</b> — 혜택 개정
                         * 추적이 스코프 밖이라 이 값이 유일한 방어다.
                         */
                        String asOf) {}

    public record Result(List<Summary> summary, List<Offer> offers,
                         String periodLabel, int months,
                         /** 실적을 어느 달로 셌는지. "2026.07". */
                         String performanceMonth) {}
}
