package com.finntech.service;

import com.finntech.domain.CardAnnualFee;
import com.finntech.domain.CardBenefit;
import com.finntech.domain.CardBenefitTarget;
import com.finntech.domain.CardCombinedCap;
import com.finntech.domain.CardExclusion;
import com.finntech.domain.CardPerformanceTier;
import com.finntech.domain.CardProduct;
import com.finntech.engine.CardExclusionPolicy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 이 카드로 얼마가 남는지 센다 — <b>화면이 아니라 채점을 위한 자(尺)다.</b>
 *
 * <h2>왜 추천 경로 밖에 있나</h2>
 *
 * 화면은 금액을 말하지 않는다(`09_카드추천_판정.md` §1.1, 사용자 결정 2026-08-13) — 개인화
 * 절감액이 규제상 가장 약한 고리이고, 전월실적은 <b>카드 한 장 기준</b>인데 우리가 세는 값은
 * 사용자의 모든 카드 합이라 애초에 전월실적이 아니기 때문이다.
 *
 * <p><b>그렇다고 계산을 지우지는 않았다.</b> 지우면 두 가지를 잴 수 없게 된다.
 *
 * <pre>
 *   파싱이 맞았나    월 최대 혜택(= Σ 한도)을 비교 서비스 표시값과 대조한다
 *   추천이 이득인가   겹침 수로 세운 순위가 금액 순과 얼마나 어긋나는지 잰다
 * </pre>
 *
 * 두 번째는 <b>이 클래스 없이는 알 방법이 없다.</b> 겹침 3곳인 카드가 실제 절감액은 겹침 1곳
 * 카드보다 작을 수 있고, 그것이 얼마나 자주 일어나는지가 추천 품질이다.
 *
 * <p><b>{@link CardRecommendService} 는 이 클래스를 부르지 않는다.</b> 부르지 않으므로 금액이
 * 화면으로 샐 통로가 없고, 매 요청마다 쓰지도 않을 계산이 돌지도 않는다. 부르는 것은 시험과
 * 채점 도구다.
 *
 * <h2>세는 순서 (07 §4.4)</h2>
 *
 * <pre>
 *   1  전달 승인내역 → 실적 제외 항목 빼기        → 전월실적
 *   2  실적으로 카드의 구간(tier) 결정            → 어떤 한도가 열리나
 *   3  소비를 혜택 묶음별로 분배                  → {@link CardMatcher} (브랜드 1순위 · 축 2순위)
 *   4  묶음마다 min(그 묶음 소비 × 요율, 월한도)
 *   5  통합한도로 한 번 더 자름                  → 페이북: 개별 합 15,000 &gt; 통합 13,000
 *   6  Σ × 12 − 연회비                          → 연 절감액
 * </pre>
 *
 * <p><b>이전율 α 같은 임의 상수가 없다.</b> "아직 발급 안 한 카드에 전월실적이 있을 리 없다"는
 * 순환 때문에 α(=0.7 같은 값)를 두려 했는데, 추정하는 대신 <b>가정을 밝혀서</b> 푼다 —
 * "이 소비를 이 카드로 모으면"(사용자 결정 2026-08-10).
 */
@Component
public class CardBenefitEstimator {

    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** 절감액을 100원 단위로 끊는다 — 1원 단위까지 말하면 추정치를 확정치처럼 읽힌다. */
    private static final BigDecimal SAVING_UNIT = BigDecimal.valueOf(100);

    private final CardMatcher matcher;
    private final CardExclusionPolicy exclusions;

    public CardBenefitEstimator(CardMatcher matcher, CardExclusionPolicy exclusions) {
        this.matcher = matcher;
        this.exclusions = exclusions;
    }

    /** 혜택 한 줄의 화면 문구. 금액이 들어가므로 이 클래스가 만든다. */
    public record BenefitLine(String label, String value) {}

    /**
     * @param performance 전월실적으로 <b>가정한</b> 값 — 사용자의 전 카드 합에서 이 카드의
     *                    실적 제외 축을 뺀 것이다. 카드 한 장의 실적이 아니다(위 주석)
     * @param tier        열린 구간. {@code null} 이면 구간 조건이 붙은 혜택이 안 열린다
     * @param yearlySaving 연 절감액(원). 연회비를 뺀 값이고 음수면 0이다
     * @param cappedAt    이 구간에서 한 해에 받을 수 있는 최대 혜택. 한도 없는 혜택이 있으면 {@code null}
     */
    public record Estimate(BigDecimal performance, CardPerformanceTier tier,
                           BigDecimal yearlySaving, BigDecimal cappedAt, BigDecimal annualFee,
                           List<BenefitLine> lines, String performanceLabel) {}

    public Estimate estimate(CardProduct card, CardSpend spend) {
        // 1. 전월실적 = 소비 − 이 카드의 실적 제외 축. **카드마다 목록이 다르다.**
        Set<String> excluded = exclusions.axesToExclude(
                card.exclusionsOn(CardExclusion.Axis.PERFORMANCE).stream()
                        .map(CardExclusion::getCode).toList());
        BigDecimal performance = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : spend.byAxis().entrySet()) {
            if (!excluded.contains(e.getKey())) performance = performance.add(e.getValue());
        }

        // 2. 실적으로 구간을 연다. 못 채웠으면 null.
        CardPerformanceTier tier = card.tierFor(performance.longValue());

        // 3. 분배 — 금액을 세려면 셈할 수 있고 요율이 있고 구간이 열린 혜택만 본다.
        Map<CardBenefit, CardMatcher.Matched> matched = matcher.match(card, spend,
                b -> b.isCountable()
                        && b.getRatePercent() != null && b.getRatePercent().signum() > 0
                        && b.opensAt(tier));

        // 4·5. 묶음마다 자르고, 통합한도로 한 번 더 자른다.
        Map<String, BigDecimal> combinedUsed = new LinkedHashMap<>();
        BigDecimal monthly = BigDecimal.ZERO;
        List<BenefitLine> lines = new ArrayList<>();
        for (Map.Entry<CardBenefit, CardMatcher.Matched> e : matched.entrySet()) {
            CardBenefit benefit = e.getKey();
            BigDecimal earned = e.getValue().amount()
                    .multiply(benefit.getRatePercent())
                    .divide(HUNDRED, 2, RoundingMode.HALF_UP);
            Integer cap = benefit.capFor(tier);
            if (cap != null) earned = earned.min(BigDecimal.valueOf(cap));
            earned = capCombined(card, benefit, tier, earned, combinedUsed);
            if (earned.signum() <= 0) continue;
            monthly = monthly.add(earned);
            lines.add(new BenefitLine(label(benefit), value(benefit, earned)));
        }

        // 6. 연 환산 뒤 연회비를 뺀다 — 빼야 "아낀다"가 참이 된다.
        BigDecimal annualFee = annualFee(card);
        BigDecimal saving = monthly.multiply(MONTHS_PER_YEAR).subtract(annualFee);
        if (saving.signum() < 0) saving = BigDecimal.ZERO;
        saving = saving.divide(SAVING_UNIT, 0, RoundingMode.DOWN).multiply(SAVING_UNIT);

        return new Estimate(performance, tier, saving, cappedAt(card, tier), annualFee, lines,
                performanceLabel(card, performance, tier));
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
     *
     * <p><b>화면은 이 값을 빼지 않는다</b>(§4.5) — 혜택과 연회비는 성격이 다른 값이라 한 숫자로
     * 합치지 않는다. 여기서 빼는 것은 <b>순액을 채점하기 위해서</b>다.
     */
    public BigDecimal annualFee(CardProduct card) {
        return card.getAnnualFees().stream()
                .filter(f -> CardAnnualFee.Scope.DOMESTIC.name().equals(f.getScope()))
                .map(f -> BigDecimal.valueOf(f.getTotal()))
                .min(Comparator.naturalOrder())
                .orElseGet(() -> card.getAnnualFees().stream()
                        .map(f -> BigDecimal.valueOf(f.getTotal()))
                        .min(Comparator.naturalOrder())
                        .orElse(BigDecimal.ZERO));
    }

    /**
     * 이 구간에서 이 카드가 한 해에 줄 수 있는 최대 혜택.
     *
     * <p><b>파싱 채점의 기준값이다</b> — 카드 비교 서비스가 같은 카드를 얼마로 표시하는지
     * 대조하면 우리가 공시를 맞게 읽었는지 채점된다(07 §4.4). 사용자 소비가 필요 없어
     * 카탈로그만으로 잴 수 있다.
     */
    public BigDecimal cappedAt(CardProduct card, CardPerformanceTier tier) {
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

    static String label(CardBenefit benefit) {
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

    static String trimRate(BigDecimal rate) {
        return rate.stripTrailingZeros().toPlainString();
    }

    private static String manwon(BigDecimal won) {
        return won.divide(BigDecimal.valueOf(10000), 0, RoundingMode.DOWN) + "만원";
    }
}
