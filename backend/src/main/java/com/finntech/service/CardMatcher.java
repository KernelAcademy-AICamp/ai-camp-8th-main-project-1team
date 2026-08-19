package com.finntech.service;

import com.finntech.domain.CardBenefit;
import com.finntech.domain.CardBenefitTarget;
import com.finntech.domain.CardProduct;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 내 소비를 카드의 혜택 묶음에 붙인다 — <b>판정의 심장</b>이다.
 *
 * <p>추천(겹침)과 채점(절감액)이 <b>이 한 곳을 나눠 쓴다.</b> 두 벌로 적으면 "화면은 스타벅스가
 * 걸렸다는데 검산은 안 걸렸다"가 나온다. 그래서 대조 규칙은 여기에만 둔다.
 *
 * <h2>브랜드가 1순위다</h2>
 *
 * 업종코드로 못 푸는 축이 있기 때문이다 — 배달의민족은 <i>통신판매업</i>으로 등록돼 업종으로는
 * '쇼핑'이 되고, 넷플릭스와 일부 PG 는 같은 코드 724000 을 쓴다. 브랜드로 걸린 가맹점은 축
 * 배분에서 빠진다(두 번 세지 않는다).
 *
 * <h2>가장 긴 브랜드 이름이 이긴다</h2>
 *
 * {@code 쿠팡이츠 결제}는 '쿠팡'에도 걸리는데, 공시가 <i>"쿠팡은 쿠팡이츠 제외"</i>라고 적어 둔
 * 그 자리다. 긴 쪽을 먼저 보면 이 오배정이 안 난다.
 *
 * <h2>한 결제는 한 묶음에만 간다</h2>
 *
 * 그래서 같은 돈을 두 번 아끼는 일이 구조적으로 안 나고, 공시의 배타 관계
 * ({@code exclusive_with})를 계산에 쓸 필요도 없다.
 */
@Component
public class CardMatcher {

    private static final Pattern NON_ALNUM = Pattern.compile("[^0-9a-zA-Z가-힣]");

    /**
     * 혜택 묶음 하나에 붙은 내 소비.
     *
     * @param amount 붙은 금액 — <b>절감액 계산이 쓴다</b>
     * @param brands 걸린 브랜드 이름(공시 표기 그대로) — <b>겹침 표시가 쓴다</b>
     * @param axes   걸린 카드혜택 축
     */
    /**
     * @param brandVisits 브랜드 → 그 브랜드로 걸린 결제 건수. <b>가맹점이 아니라 브랜드 단위로
     *                    센다</b> — '스타벅스 강남점' 한 번과 '스타벅스 역삼점' 한 번은
     *                    스타벅스 두 번이다. 가맹점명으로 세면 지점이 갈릴수록 문턱을 못 넘는다.
     */
    public record Matched(BigDecimal amount, List<String> brands, List<String> axes,
                          Map<String, Integer> brandVisits) {}

    /**
     * @param include 어떤 혜택을 대조에 넣을지. 절감액은 {@code countable · 요율 있음 · 구간 열림}
     *                을 요구하지만, 겹침은 <b>아무것도 요구하지 않는다</b> — 금액을 안 세니
     *                요율이 없어도 "그 브랜드가 대상이다"는 참이기 때문이다.
     */
    public Map<CardBenefit, Matched> match(CardProduct card, CardSpend spend,
                                           Predicate<CardBenefit> include) {
        List<CardBenefit> open = card.getBenefits().stream().filter(include).toList();

        Map<CardBenefit, BigDecimal> amounts = new LinkedHashMap<>();
        Map<CardBenefit, List<String>> brands = new LinkedHashMap<>();
        Map<CardBenefit, List<String>> axes = new LinkedHashMap<>();
        Map<CardBenefit, Map<String, Integer>> visits = new LinkedHashMap<>();
        Set<String> claimed = new java.util.HashSet<>();

        // ① 브랜드 — 긴 이름부터. 한 가맹점은 한 번만 걸린다.
        record Hit(CardBenefit benefit, String brand, String folded) {}
        List<Hit> byBrand = new ArrayList<>();
        for (CardBenefit benefit : open) {
            for (CardBenefitTarget target : benefit.getTargets()) {
                if (CardBenefitTarget.Kind.BRAND.name().equals(target.getKind())) {
                    byBrand.add(new Hit(benefit, target.getValue(), foldBrand(target.getValue())));
                }
            }
        }
        byBrand.sort(Comparator.comparingInt((Hit h) -> h.folded().length()).reversed()
                .thenComparing(h -> h.benefit().getSortNo())
                .thenComparing(Hit::brand));
        Map<String, String> foldedMerchant = new TreeMap<>();
        for (String merchant : spend.byMerchant().keySet()) {
            foldedMerchant.put(merchant, foldBrand(merchant));
        }
        for (Hit hit : byBrand) {
            if (hit.folded().isEmpty()) continue;
            for (Map.Entry<String, BigDecimal> e : spend.byMerchant().entrySet()) {
                if (claimed.contains(e.getKey())) continue;
                if (!foldedMerchant.get(e.getKey()).contains(hit.folded())) continue;
                claimed.add(e.getKey());
                amounts.merge(hit.benefit(), e.getValue(), BigDecimal::add);
                brands.computeIfAbsent(hit.benefit(), k -> new ArrayList<>());
                if (!brands.get(hit.benefit()).contains(hit.brand())) {
                    brands.get(hit.benefit()).add(hit.brand());
                }
                // 지점이 갈려도 브랜드 하나로 합산한다 — 강남점 1회 + 역삼점 1회 = 스타벅스 2회.
                visits.computeIfAbsent(hit.benefit(), k -> new TreeMap<>())
                        .merge(hit.brand(), spend.visitsOfMerchant().getOrDefault(e.getKey(), 1),
                                Integer::sum);
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
                amounts.merge(benefit, rest, BigDecimal::add);
                axes.computeIfAbsent(benefit, k -> new ArrayList<>()).add(target.getValue());
                // 같은 축을 다른 묶음이 또 가져가지 않게 소진 처리한다.
                claimedByAxis.merge(target.getValue(), rest, BigDecimal::add);
            }
        }

        Map<CardBenefit, Matched> out = new LinkedHashMap<>();
        for (CardBenefit benefit : amounts.keySet()) {
            out.put(benefit, new Matched(amounts.get(benefit),
                    List.copyOf(brands.getOrDefault(benefit, List.of())),
                    List.copyOf(axes.getOrDefault(benefit, List.of())),
                    Map.copyOf(visits.getOrDefault(benefit, Map.of()))));
        }
        return out;
    }

    /**
     * 브랜드 대조용 접기 — 띄어쓰기·기호를 지우고 소문자로 만든다.
     *
     * <p>공시와 승인내역이 같은 브랜드를 다르게 적는다. '투썸 플레이스'와 '투썸플레이스',
     * '디즈니+'와 '디즈니플러스', 'LG U+'와 'LGU+', '29CM'과 '29cm'. 글자만 남기면
     * 한 값이 된다. 실측(2026-08-14): 카드 브랜드 257종 중 매칭되는 것이 120종에서
     * 132종으로 늘었다 — 배달의 민족·투썸 플레이스·유튜브 프리미엄 등이 살아났다.
     *
     * <p><b>브랜드를 합치지는 않는다.</b> 접어도 '쿠팡'·'쿠팡이츠'·'쿠팡플레이'는 서로 다른
     * 값으로 남는다. 이 셋은 카드가 실제로 다른 묶음에 넣으므로(BC 바로 ZONE: 쿠팡=LIFE,
     * 쿠팡이츠=EAT) 합치면 한 결제가 엉뚱한 묶음의 한도를 갉아먹는다. 긴 이름부터 거는
     * 규칙이 그래서 있다.
     */
    static String foldBrand(String value) {
        return value == null ? "" : NON_ALNUM.matcher(value).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
