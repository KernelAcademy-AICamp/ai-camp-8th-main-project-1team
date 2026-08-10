package com.finntech.service;

import com.finntech.config.CardRecommendProperties;
import com.finntech.engine.AnalysisResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 카드 추천 (개편안 {@code s-compare}).
 *
 * <p><b>추천 순서는 광고비가 아니라 절감액 순이다.</b> 화면이 "이 카드를 추천해요"라고만 말하면
 * 그건 광고다. 여기서는 <i>당신이 이미 쓴 곳</i>에 그 카드의 요율을 곱해 연 얼마가 남는지를
 * 세고, 그 수치 순으로 세운다. 근거가 되는 소비 요약(상위 카테고리 건수·금액)을 같은 응답에
 * 실어 보내는 것도 그래서다 — 순위와 근거가 한 화면에 있어야 검증이 된다.
 *
 * <p><b>월평균을 다시 계산하지 않는다</b>(원칙 2). 카테고리별 총액과 관측 개월수는
 * {@link AnalysisResult.CategoryStat} 이 이미 갖고 있다. 여기서 총액을 개월수로 다시 나누면
 * 리포트와 추천이 서로 다른 '월평균'을 말하게 된다.
 *
 * <p><b>카드는 실제 상품이다</b>(마스터 원칙 5 재개정 2026-08-10). 예전에는 전부 더미였고 주석도
 * "실재 상품을 넣는 순간 중개가 된다"고 적혀 있었는데, 카드 추천은 이 프로젝트의 필수 기능이라
 * 더미로는 성립하지 않는다(사용자 결정). 예적금·펀드는 그대로 더미다.
 *
 * <p><b>그래서 "더미라서 영업이 아니다"라는 방패가 이 화면에는 없다.</b> 대신 금융위·금감원
 * 유권해석(2022.6.15)의 네 요건으로 선다 — 단순 정보제공 · 판매 목적 아님 · 제휴/광고 계약 없음 ·
 * 가입 편의 미제공. 예적금({@link SavingsCompareService})이 이미 쓰는 것과 같은 논거다.
 *
 * <p><b>이 클래스가 하면 안 되는 것 넷.</b> 하나라도 하면 금소법상 중개업 등록 대상이 된다
 * (제67조: 5년 이하 징역 또는 2억원 이하 벌금).
 * <ol>
 *   <li>카드사와 제휴·광고 계약을 맺는 것</li>
 *   <li>수수료를 받는 것</li>
 *   <li>가입·신청 버튼이나 발급 링크를 두는 것 — 발급은 각 카드사로 보낸다</li>
 *   <li>순위를 광고비에 연동하는 것 — 순위는 절감액순이고 그 근거를 같은 응답에 싣는다</li>
 * </ol>
 *
 * <p><b>가장 약한 고리는 개인화 절감액이다.</b> 예적금은 금리라는 만인 공통 수치를 보여 주지만
 * 여기는 "당신은 연 얼마"를 낸다 — '단순 정보제공'보다 권유로 읽힐 여지가 있다. 그래서 단일값이
 * 아니라 밴드로 말하고, 근거(내 소비 요약)를 같은 화면에 두고, 기준일을 함께 적는다.
 * 남는 위험의 전체 목록은 마스터 §제외 항목의 🔁 개정(2026-08-10).
 */
@Service
public class CardRecommendService {

    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** 절감액을 100원 단위로 끊는다 — 1원 단위까지 말하면 추정치를 확정치처럼 읽힌다. */
    private static final BigDecimal SAVING_UNIT = BigDecimal.valueOf(100);

    private final CardRecommendProperties props;

    public CardRecommendService(CardRecommendProperties props) {
        this.props = props;
    }

    public Result recommend(AnalysisResult analysis) {
        List<Summary> summary = summarize(analysis);

        List<Offer> offers = new ArrayList<>();
        for (CardRecommendProperties.Card c : props.getCards()) {
            offers.add(evaluate(c, analysis));
        }
        // 절감액 내림차순. 동점은 이름으로 깨서 매번 같은 순서가 나오게 한다(원칙 3).
        offers.sort(Comparator
                .comparing(Offer::yearlySaving, Comparator.reverseOrder())
                .thenComparing(Offer::name));
        int max = Math.max(0, props.getMaxCards());
        if (offers.size() > max) offers = new ArrayList<>(offers.subList(0, max));

        return new Result(summary, offers, periodLabel(analysis), analysis.monthlySpend().size());
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
     * 카드 한 장의 예상 절감액과 혜택 줄.
     *
     * <p>혜택이 붙은 카테고리는 그 요율로, 나머지 지출은 기본 요율로 센다. 두 곳을 겹쳐 세지
     * 않도록 혜택 카테고리의 월평균은 '나머지'에서 뺀다 — 안 그러면 같은 돈을 두 번 아낀다.
     */
    private Offer evaluate(CardRecommendProperties.Card c, AnalysisResult analysis) {
        Map<String, AnalysisResult.CategoryStat> stats = analysis.categoryStats();
        BigDecimal saving = BigDecimal.ZERO;
        BigDecimal covered = BigDecimal.ZERO;      // 혜택 카테고리의 연 지출 합
        List<Row> rows = new ArrayList<>();

        for (CardRecommendProperties.Benefit b : c.getBenefits()) {
            AnalysisResult.CategoryStat s = stats.get(b.getCategory());
            BigDecimal yearly = s == null ? BigDecimal.ZERO : yearlyOf(s);
            covered = covered.add(yearly);
            saving = saving.add(yearly.multiply(b.getRate()).divide(HUNDRED, 2, RoundingMode.HALF_UP));
            String label = (s == null ? b.getCategory() : s.displayName()) + " 결제";
            rows.add(new Row(label, trimRate(b.getRate()) + "% " + b.getKind()));
        }

        BigDecimal totalYearly = BigDecimal.ZERO;
        for (AnalysisResult.CategoryStat s : stats.values()) totalYearly = totalYearly.add(yearlyOf(s));
        BigDecimal rest = totalYearly.subtract(covered);
        if (rest.signum() < 0) rest = BigDecimal.ZERO;
        if (c.getBaseRate().signum() > 0) {
            saving = saving.add(rest.multiply(c.getBaseRate()).divide(HUNDRED, 2, RoundingMode.HALF_UP));
            rows.add(new Row("전 가맹점", trimRate(c.getBaseRate()) + "% 적립"));
        }

        boolean capped = c.getYearlyCap().signum() > 0 && saving.compareTo(c.getYearlyCap()) > 0;
        if (capped) saving = c.getYearlyCap();

        // 연회비는 절감이 아니라 비용이다 — 빼고 말해야 "아낀다"가 참이 된다.
        saving = saving.subtract(c.getAnnualFee());
        if (saving.signum() < 0) saving = BigDecimal.ZERO;
        saving = saving.divide(SAVING_UNIT, 0, RoundingMode.DOWN).multiply(SAVING_UNIT);

        rows.add(new Row("전월 실적 조건", c.getMonthlyRequirement().signum() == 0
                ? "없음" : manwon(c.getMonthlyRequirement()) + " 이상"));
        rows.add(new Row("연회비", c.getAnnualFee().signum() == 0
                ? "없음" : String.format("%,d원", c.getAnnualFee().toBigInteger())));

        return new Offer(c.getName(), c.getTagline(), c.getTint(), c.getMark(), c.getFooter(),
                saving, capped ? c.getYearlyCap() : null, rows);
    }

    /** 이 카테고리의 <b>연</b> 지출 추정 = 월평균 × 12. 월평균은 엔진이 낸 것을 쓴다. */
    private BigDecimal yearlyOf(AnalysisResult.CategoryStat s) {
        int months = Math.max(1, s.observedMonths());
        return s.totalAmount()
                .divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP)
                .multiply(MONTHS_PER_YEAR);
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
                        List<Row> rows) {}

    public record Result(List<Summary> summary, List<Offer> offers,
                         String periodLabel, int months) {}
}
