package com.finntech.service;

import com.finntech.domain.SpendingLedger;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 소비 원장 몇 달치를 카드 대조에 쓸 모양으로 접은 것.
 *
 * <p><b>③ 은 마이데이터를 직접 훑지 않는다</b>(09 §2.2 · 마스터 §4 원칙 2). 가맹점명에서
 * 브랜드를 뽑고, 결제대행사를 가려내고, 업종코드를 국세청 체계로 옮기는 일은 ① 이 분류·
 * 낭비판정 과정에서 <b>이미 다 했다.</b> ③ 이 같은 계산을 또 하면 두 화면이 서로 다른 답을
 * 말한다. 그래서 {@code user_payment} 가 아니라 <b>소비 원장</b>을 읽는다.
 *
 * <p><b>대조 이름은 브랜드가 먼저다.</b> 원장의 {@code brand} 는 ① 이 확정한 값이라
 * 가맹점명보다 정확하다 — 실측(2026-08-14)에서 승인내역에 {@code (주)우아한형제들} 로 찍힌
 * 결제 10건이 카드의 '배달의민족' 대상에 하나도 안 걸렸다. 법인명이라 이름으로는 못 잇는다.
 * 브랜드가 비면 가맹점명으로 떨어진다.
 *
 * <p><b>결제대행사는 아예 안 담는다.</b> {@code 네이버페이}·{@code 발트페이먼츠} 처럼 찍힌
 * 결제는 <b>어디서 무엇을 샀는지가 남지 않는다</b>(실측 3개월 153건 중 상위 1·3·4위가
 * 그것이었다). 이름을 그대로 대조하면 '네이버페이'라는 가맹점에 간 것이 되어 엉뚱한 카드가
 * 걸린다. 카드사도 같은 이유로 혜택을 안 준다(BC 페이북 {@code PAY_CHANNEL_EXCLUSION}) —
 * 양쪽이 같은 이유로 못 하므로 계산이 어긋나지 않는다.
 *
 * <p><b>몇 번 갔는지는 '서로 다른 날짜 수'로 센다.</b> 결제 건수로 세면 하루에 세 번 들른
 * 것이 사흘 간 것과 같아진다. 원장이 그 값을 이미 갖고 있다({@code groupOccurrenceDays} —
 * "판정이 실제로 세는 값", V36 주석). 없으면 이 창에서 직접 날짜를 세어 채운다.
 *
 * @param byAxis           카드혜택 축 → 금액. 축을 모르는 업종은 여기에만 안 담긴다
 * @param byMerchant       대조 이름(브랜드 우선) → 금액. <b>축을 몰라도 담는다</b>
 * @param visitsOfMerchant 대조 이름 → 결제한 날짜 수. 자주 가는 곳을 가리는 값이다
 * @param axisOfMerchant   대조 이름 → 그 곳의 축. 브랜드로 가져간 몫을 축에서 뺄 때 쓴다
 * @param keptNames        성역으로 선언된 이름 — "못 끊는다"고 사용자가 누른 곳(09 §2.4)
 */
public record CardSpend(Map<String, BigDecimal> byAxis,
                        Map<String, BigDecimal> byMerchant,
                        Map<String, Integer> visitsOfMerchant,
                        Map<String, String> axisOfMerchant,
                        java.util.Set<String> keptNames) {

    /** 성역 — 사용자가 "못 끊는다"고 선언한 가게. 원장이 결제행마다 실어 온다. */
    private static final String STANCE_KEPT = "EXCLUDED";

    /** 순회 순서를 고정한다(원칙 3) — {@code TreeMap} 이라 같은 입력이면 같은 순서가 나온다. */
    public static CardSpend fold(List<SpendingLedger> rows, IndustryAxes axes) {
        Map<String, BigDecimal> byAxis = new TreeMap<>();
        Map<String, BigDecimal> byMerchant = new TreeMap<>();
        Map<String, String> axisOf = new TreeMap<>();
        Map<String, java.util.Set<java.time.LocalDate>> days = new TreeMap<>();
        Map<String, Integer> reported = new TreeMap<>();
        java.util.Set<String> kept = new java.util.TreeSet<>();

        for (SpendingLedger row : rows) {
            // 결제대행사는 어디서 샀는지가 안 남는다 — 이름을 대조하면 엉뚱한 카드가 걸린다.
            if (row.isPaymentAgency()) {
                continue;
            }
            String name = matchName(row);
            if (name == null) {
                continue;
            }
            BigDecimal amount = BigDecimal.valueOf(row.getAmount());
            String axis = axes.cardAxisOf(row.getNtsIndustryCode());
            if (axis != null) {
                byAxis.merge(axis, amount, BigDecimal::add);
                axisOf.put(name, axis);
            }
            byMerchant.merge(name, amount, BigDecimal::add);
            if (row.getPaidOn() != null) {
                days.computeIfAbsent(name, k -> new java.util.TreeSet<>()).add(row.getPaidOn());
            }
            // ① 이 센 값이 있으면 그것을 쓴다. 창 밖의 반복까지 본 값이라 우리 것보다 낫다.
            Integer occurrences = row.getGroupOccurrenceDays();
            if (occurrences != null) {
                reported.merge(name, occurrences, Math::max);
            }
            if (STANCE_KEPT.equals(row.getStance())) {
                kept.add(name);
            }
        }

        Map<String, Integer> visits = new TreeMap<>();
        for (Map.Entry<String, java.util.Set<java.time.LocalDate>> e : days.entrySet()) {
            visits.put(e.getKey(), Math.max(e.getValue().size(),
                    reported.getOrDefault(e.getKey(), 0)));
        }
        return new CardSpend(byAxis, byMerchant, visits, axisOf, kept);
    }

    /**
     * 대조에 쓸 이름 — <b>브랜드가 먼저다.</b>
     *
     * <p>브랜드가 비면 가맹점명으로 떨어진다. 둘 다 없으면 대조할 것이 없어 버린다.
     */
    private static String matchName(SpendingLedger row) {
        String brand = row.getBrand();
        if (brand != null && !brand.isBlank()) {
            return brand.trim();
        }
        String merchant = row.getMerchantName();
        return merchant != null && !merchant.isBlank() ? merchant.trim() : null;
    }

    /** 업종코드 → 카드혜택 축. {@code IndustryCategoryMapper} 가 이 모양을 만족한다. */
    @FunctionalInterface
    public interface IndustryAxes {
        String cardAxisOf(String ntsIndustryCode);
    }
}
