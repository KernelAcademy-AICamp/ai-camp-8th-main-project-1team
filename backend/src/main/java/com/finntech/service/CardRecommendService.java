package com.finntech.service;

import com.finntech.config.CardRecommendProperties;
import com.finntech.domain.CardAnnualFee;
import com.finntech.domain.CardBenefit;
import com.finntech.domain.CardProduct;
import com.finntech.domain.UserPayment;
import com.finntech.engine.AnalysisResult;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.CardProductRepository;
import com.finntech.repository.UserPaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 카드 추천 (개편안 {@code s-compare}).
 *
 * <p><b>추천 순서는 광고비가 아니라 근거 순이다.</b> 화면이 "이 카드를 추천해요"라고만 말하면
 * 그건 광고다. 근거가 되는 소비 요약을 같은 응답에 싣는 것도 그래서다 — 순위와 근거가 한
 * 화면에 있어야 검증이 된다.
 *
 * <h2>카드는 실제 상품이다</h2>
 *
 * 마스터 원칙 5 재개정(2026-08-10). 예전에는 {@code application.yml} 의 {@code [더미]} 3장을
 * 읽었고 주석도 "실재 상품을 넣는 순간 중개가 된다"고 적혀 있었는데, 카드 추천은 이 프로젝트의
 * 필수 기능이라 더미로는 성립하지 않는다(사용자 결정). 지금은 카드사 상품공시에서 온 실제
 * 상품을 표에서 읽는다({@code V34}).
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
 *   <li>순위를 광고비에 연동하는 것 — 순위의 근거를 같은 응답에 싣는다</li>
 * </ol>
 *
 * <p><b>혜택 개정 추적은 스코프 밖이다</b>(사용자 결정 2026-08-10). 그래서 카드 정보는
 * <b>수집 시점에 고정된 스냅샷</b>이고 시간이 지나면 낡는다 — {@link Offer#asOf} 를 화면에
 * 병기해 "이 시점 공시 기준"임을 밝히는 것으로 처리한다. <b>③ CTA 금지가 이 방어의
 * 전제다</b> — CTA 를 다는 순간 "낡은 정보로 가입을 유도한 것"이 되어 둘이 함께 무너진다.
 *
 * <h2>계산은 세 클래스가 나눠 맡는다</h2>
 *
 * <pre>
 *   CardSpend             승인내역을 축별·가맹점별로 접는다        (입력 정리)
 *   CardMatcher           내 소비를 혜택 묶음에 붙인다              (브랜드 1순위 · 축 2순위)
 *   CardBenefitEstimator  붙은 소비로 절감액을 센다                 (채점용 — 화면 밖)
 * </pre>
 *
 * <p><b>대조 규칙을 {@link CardMatcher} 한 곳에 둔 이유</b>는 추천과 채점이 같은 답을 보게 하기
 * 위해서다. 두 벌로 적으면 "화면은 스타벅스가 걸렸다는데 검산은 안 걸렸다"가 난다.
 */
@Service
public class CardRecommendService {

    private final CardRecommendProperties props;
    private final CardProductRepository cards;
    private final UserPaymentRepository payments;
    private final IndustryCategoryMapper industries;
    private final CardBenefitEstimator estimator;
    private final CardMatcher matcher;

    public CardRecommendService(CardRecommendProperties props, CardProductRepository cards,
                                UserPaymentRepository payments, IndustryCategoryMapper industries,
                                CardBenefitEstimator estimator, CardMatcher matcher) {
        this.props = props;
        this.cards = cards;
        this.payments = payments;
        this.industries = industries;
        this.estimator = estimator;
        this.matcher = matcher;
    }

    @Transactional(readOnly = true)
    public Result recommend(AnalysisResult analysis, LocalDateTime referenceTime) {
        List<Summary> summary = summarize(analysis);

        // 최근 N개월 1일 00:00 (포함) ~ 이번달 1일 00:00 (미포함).
        //
        // **한 달이 아니라 여러 달을 본다**(09 §2.1). 겹침은 "이번 달에 얼마 썼나"가 아니라
        // "계속 가는 곳인가"를 묻는 값이라, 짧은 창은 반복과 우연을 구조적으로 못 가른다.
        // 창을 넓히는 것만으로는 겹침 수만 늘어나므로 방문 횟수 문턱과 짝으로 쓴다.
        LocalDate firstOfThisMonth = referenceTime.toLocalDate().withDayOfMonth(1);
        LocalDate windowStart = firstOfThisMonth.minusMonths(Math.max(1, props.getSpendMonths()));
        List<UserPayment> recent = payments.findInPeriod(analysis.userId(),
                windowStart.atStartOfDay(), firstOfThisMonth.atStartOfDay());
        CardSpend spend = CardSpend.fold(recent, industries);

        List<Offer> offers = new ArrayList<>();
        for (CardProduct card : cards.findRecommendable()) {
            offers.add(evaluate(card, spend));
        }
        // 겹친 곳이 많은 순. 동점은 이름으로 깨서 매번 같은 순서가 나오게 한다(원칙 3).
        //
        // **금액으로 세우지 않는다**(2026-08-13). 절감액은 화면에 안 나가는 값이라 순위의
        // 근거가 될 수 없다 — 보여주지 않는 수로 줄을 세우면 사용자가 순서를 검증할 방법이
        // 없고, 그건 근거 없는 순위와 같다. 겹친 이름은 화면에 그대로 나가므로 반박 가능하다.
        offers.sort(Comparator
                .comparingInt(Offer::matchCount).reversed()
                .thenComparing(Offer::name));
        int max = Math.max(0, props.getMaxCards());
        if (offers.size() > max) offers = new ArrayList<>(offers.subList(0, max));

        // 겹침을 어느 구간에서 셌는지. "2026.04 ~ 2026.06" — 화면이 근거로 병기한다.
        DateTimeFormatter month = DateTimeFormatter.ofPattern("yyyy.MM");
        String window = windowStart.format(month) + " ~ "
                + firstOfThisMonth.minusMonths(1).format(month);
        return new Result(summary, offers, periodLabel(analysis), analysis.monthlySpend().size(),
                window);
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

    /**
     * 카드 한 장이 화면에 어떻게 나가는가 — <b>다섯 줄 중 개인화된 것은 겹침 하나뿐</b>이고
     * 나머지는 카드에 적힌 사실이다(`09_카드추천_판정.md` §5.1).
     *
     * <p><b>여기서 절감액을 부르지 않는다.</b> {@link CardBenefitEstimator} 는 채점용이고
     * 이 경로는 그것을 모른다 — 부르지 않으므로 금액이 화면으로 샐 통로가 없다.
     */
    private Offer evaluate(CardProduct card, CardSpend spend) {
        List<String> matched = overlap(card, spend);

        List<Row> rows = new ArrayList<>();
        rows.add(new Row("혜택", benefitLine(card)));
        if (!matched.isEmpty()) {
            rows.add(new Row("겹치는 곳", names(matched)));
        }
        rows.add(new Row("전월 실적", performanceCondition(card)));
        rows.add(new Row("연회비", annualFeeLine(card)));

        return new Offer(card.getName(), tagline(card), tint(card), mark(card), card.getIssuer(),
                rows, card.getAsOf() == null ? null : card.getAsOf().toString(),
                matched.size(), matched);
    }

    /** 겹친 이름 — 셋까지 보여주고 나머지는 수로 접는다. */
    private String names(List<String> matched) {
        List<String> head = matched.subList(0, Math.min(3, matched.size()));
        String joined = String.join("·", head);
        return matched.size() > head.size()
                ? joined + " 외 " + (matched.size() - head.size()) + "곳" : joined;
    }

    /**
     * 전월 실적 — <b>공시 원문 그대로만</b> 쓴다(사용자 결정 2026-08-14).
     *
     * <p>예전에는 {@code "지난달 실적 59만원 · 충족"} 이라 적었는데 <b>그 값은 전월실적이
     * 아니다</b>. 전월실적은 카드 한 장 기준인데 우리가 센 값은 사용자의 <i>모든</i> 카드
     * 합이다. 설계는 그것을 "이 소비를 이 카드로 모으면"이라는 가정으로 세웠는데, 화면 문구가
     * 그 가정을 지우고 사실처럼 말하고 있었다.
     */
    private String performanceCondition(CardProduct card) {
        if (card.getTiers().isEmpty()) return "실적 조건 없음";
        int need = card.getTiers().get(0).getThresholdKrw();
        return "전월 " + need / 10_000 + "만원 이상";
    }

    /** 연회비 — <b>발급 구분을 함께</b> 보여준다. 계산에는 안 들어간다(§4.5). */
    private String annualFeeLine(CardProduct card) {
        return card.getAnnualFees().stream()
                .filter(f -> CardAnnualFee.Scope.DOMESTIC.name().equals(f.getScope()))
                .min(Comparator.comparingInt(CardAnnualFee::getTotal))
                .or(() -> card.getAnnualFees().stream().min(Comparator.comparingInt(CardAnnualFee::getTotal)))
                .map(f -> f.getTotal() == 0 ? "없음"
                        : String.format("%,d원 (%s)", f.getTotal(),
                                CardAnnualFee.Scope.DOMESTIC.name().equals(f.getScope())
                                        ? "국내전용" : "해외겸용"))
                .orElse("없음");
    }

    /** 혜택 한 줄 — 요율이 가장 높은 묶음의 대상을 보여준다. 요율이 없으면 묶음 이름만. */
    private String benefitLine(CardProduct card) {
        return card.getBenefits().stream()
                .max(Comparator.comparing((CardBenefit b) ->
                        b.getRatePercent() == null ? BigDecimal.ZERO : b.getRatePercent()))
                .map(b -> b.getRatePercent() == null
                        ? CardBenefitEstimator.label(b)
                        : CardBenefitEstimator.label(b) + " "
                                + CardBenefitEstimator.trimRate(b.getRatePercent()) + "%")
                .orElse("혜택 정보 없음");
    }

    /**
     * <b>내가 자주 가는 곳 중 이 카드의 혜택 대상인 것.</b> 판정의 핵심이자 순위의 근거다.
     *
     * <p><b>금액을 안 세므로 요율·한도·구간을 묻지 않는다.</b> 절감액 계산은 {@code countable ·
     * 요율 있음 · 구간 열림}을 요구하지만, 겹침은 아무것도 요구하지 않는다 — 요율이 없어도
     * "그 브랜드가 이 카드의 적립 대상이다"는 참이기 때문이다. 이 완화로 요율을 못 뽑은 카드
     * 수십 장이 후보로 돌아온다(`09_카드추천_판정.md` §1.2).
     *
     * <p><b>브랜드를 먼저, 축을 나중에</b> 놓는다 — 사용자에게 `카페/디저트`보다 `스타벅스`가
     * 훨씬 구체적이다.
     *
     * <p>⚠️ <b>아직 '자주'를 못 가린다.</b> 설계는 최근 3개월의 성역·반복 결제로 지속 브랜드를
     * 정하는데(§2.4), 그 재료는 ①이 아직 안 낸다. 지금은 <b>전달에 한 번이라도 쓴 곳</b>이면
     * 겹침으로 센다 — 어쩌다 간 곳과 매일 가는 곳을 구분하지 못한다(§7.1).
     */
    /**
     * 겹친 이름 — 브랜드 먼저, 축 나중.
     *
     * <p><b>브랜드는 방문 문턱을 넘어야 센다.</b> 겹침은 "이번 달에 얼마 썼나"가 아니라
     * "계속 가는 곳인가"를 묻는 값이다(09 §2.1). 실측(2026-08-14, 3개월 153건)에서 한 번이라도
     * 갔으면 세던 방식은 겹침 16 으로 1위를 만들었는데 그 대부분이 <b>한 번씩만 간 곳</b>이었고,
     * 2회 문턱을 걸자 순위 밖으로 밀렸다.
     *
     * <p><b>축에는 문턱을 걸지 않는다.</b> 축 겹침은 업종 단위라("외식에 쓴다") 가맹점 방문
     * 횟수로 자를 값이 아니다.
     */
    private List<String> overlap(CardProduct card, CardSpend spend) {
        Map<CardBenefit, CardMatcher.Matched> matched = matcher.match(card, spend, b -> true);
        int minVisits = Math.max(1, props.getMinVisits());
        List<String> brands = new ArrayList<>();
        List<String> axes = new ArrayList<>();
        for (CardMatcher.Matched m : matched.values()) {
            for (String b : m.brands()) {
                if (m.brandVisits().getOrDefault(b, 0) < minVisits) continue;
                if (!brands.contains(b)) brands.add(b);
            }
            for (String a : m.axes()) if (!axes.contains(a)) axes.add(a);
        }
        brands.addAll(axes);
        return List.copyOf(brands);
    }

    // ── 화면 문구. **결과만 쓴다** — 계산 과정(제외 항목 내역)은 안 보여준다 ────────────

    /** 카드 성격 한 줄 — 가장 요율이 높은 혜택으로 만든다. 마케팅 문구를 옮기지 않는다. */
    private String tagline(CardProduct card) {
        return card.getBenefits().stream()
                .filter(CardBenefit::isCountable)
                .filter(b -> b.getRatePercent() != null)
                .max(Comparator.comparing(CardBenefit::getRatePercent)
                        .thenComparing(Comparator.comparing(CardBenefit::getSortNo).reversed()))
                .map(b -> CardBenefitEstimator.label(b) + " "
                        + CardBenefitEstimator.trimRate(b.getRatePercent()) + "%")
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

    /**
     * 화면에 나가는 카드 한 장.
     *
     * <p><b>금액 칸이 없는 것이 설계다</b>(2026-08-13). 예전에는 {@code yearlySaving}·
     * {@code cappedAt} 이 있었고 화면이 "연 180,000원 아껴요"를 냈는데, 개인화 절감액은
     * 07 §4.4가 "가장 약한 고리"라 적은 자리다. 금액을 안 내면 고리 자체가 없어진다.
     * 계산은 {@link CardBenefitEstimator} 에 살아 있고 채점에만 쓴다.
     */
    public record Offer(String name, String tagline, String tint, String mark, String footer,
                        List<Row> rows,
                        /**
                         * 공시 기준일(심의필 날짜). <b>화면에 반드시 병기한다</b> — 혜택 개정
                         * 추적이 스코프 밖이라 이 값이 유일한 방어다.
                         */
                        String asOf,
                        /** 내 소비와 겹친 대상 수. <b>순위의 근거이자 우리만 할 수 있는 말이다.</b> */
                        int matchCount,
                        /** 겹친 이름 — 브랜드 먼저, 축 나중. 화면이 "자주 가는 곳"으로 보여준다. */
                        List<String> matched) {}

    public record Result(List<Summary> summary, List<Offer> offers,
                         String periodLabel, int months,
                         /**
                          * 겹침을 어느 구간에서 셌는지. {@code "2026.04 ~ 2026.06"}.
                          *
                          * <p>예전 이름은 {@code performanceMonth} 였는데 <b>둘 다 틀렸다</b> —
                          * 실적은 판정하지 않기로 했고(09 §4.2), 창도 한 달이 아니라 여러 달이다.
                          */
                         String spendWindow) {}
}
