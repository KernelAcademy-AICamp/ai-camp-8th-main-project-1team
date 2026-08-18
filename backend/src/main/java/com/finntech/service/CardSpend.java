package com.finntech.service;

import com.finntech.domain.UserPayment;
import com.finntech.engine.IndustryCategoryMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 승인내역 한 달치를 카드 대조에 쓸 모양으로 접은 것.
 *
 * <p><b>브랜드를 여기서 확정하지 않는다.</b> 어떤 이름이 브랜드인지는 <b>카드가 정한다</b> —
 * 공시가 혜택 대상 브랜드를 직접 나열하기 때문이다(스타벅스·배달의민족·CU·CGV…). 그래서
 * 가맹점명을 그대로 들고 있다가 카드마다 대조한다.
 *
 * <p><b>축을 못 찾아도 가맹점명은 담는다.</b> 예전에는 축이 {@code null} 이면 결제를 통째로
 * 버렸는데, 브랜드 매칭은 업종코드가 아니라 가맹점명으로 하므로 축이 없다는 이유로 브랜드까지
 * 같이 죽었다. 2026-08-13 실측에서 그대로 터졌다 — 승인내역의 업종코드가 표와 자릿수가 안 맞아
 * 전건이 {@code null} 이 됐고, 결제 248건이 있는데도 추천이 전부 0 이 나왔다. 축은 2순위
 * 신호일 뿐이고, 1순위(브랜드)를 2순위 실패로 끌어내리면 안 된다.
 *
 * <p><b>가맹점마다 몇 번 갔는지 함께 센다.</b> 겹침은 "이번 달에 얼마 썼나"가 아니라
 * <b>"계속 가는 곳인가"</b>를 묻는 값이다(09 §2.1). 한 번 들른 곳과 열 번 간 곳을 똑같이
 * 겹침 1 로 세면 순위가 뒤집힌다 — 실측(2026-08-14, 3개월 153건)에서 겹침 16 으로 1위였던
 * 카드가 <b>대부분 한 번씩만 간 곳</b>이었고, 2회 이상만 세자 순위 밖으로 밀렸다.
 *
 * @param byAxis         카드혜택 축 → 금액. 축을 모르는 업종은 여기에만 안 담긴다
 * @param byMerchant     가맹점 풀네임 → 금액. <b>축을 몰라도 담는다</b> — 브랜드 매칭의 재료다
 * @param visitsOfMerchant 가맹점 풀네임 → 결제 건수. 자주 가는 곳을 가리는 값이다
 * @param axisOfMerchant 가맹점 풀네임 → 그 가맹점의 축. 브랜드로 가져간 몫을 축에서 뺄 때 쓴다.
 *                       축을 모르는 가맹점은 여기 없다 — {@code byAxis} 에도 없으니 뺄 몫이 없다
 */
public record CardSpend(Map<String, BigDecimal> byAxis,
                        Map<String, BigDecimal> byMerchant,
                        Map<String, Integer> visitsOfMerchant,
                        Map<String, String> axisOfMerchant) {

    /** 순회 순서를 고정한다(원칙 3) — {@code TreeMap} 이라 같은 입력이면 같은 순서가 나온다. */
    public static CardSpend fold(List<UserPayment> rows, IndustryCategoryMapper industries) {
        Map<String, BigDecimal> byAxis = new TreeMap<>();
        Map<String, BigDecimal> byMerchant = new TreeMap<>();
        Map<String, Integer> visits = new TreeMap<>();
        Map<String, String> axisOf = new TreeMap<>();
        for (UserPayment p : rows) {
            BigDecimal amount = BigDecimal.valueOf(p.getAmount());
            String axis = industries.cardAxisOf(p.getKsicCode());
            if (axis != null) {
                byAxis.merge(axis, amount, BigDecimal::add);
            }
            String merchant = p.getMerchantName();
            if (merchant != null && !merchant.isBlank()) {
                byMerchant.merge(merchant, amount, BigDecimal::add);
                visits.merge(merchant, 1, Integer::sum);
                if (axis != null) {
                    axisOf.put(merchant, axis);
                }
            }
        }
        return new CardSpend(byAxis, byMerchant, visits, axisOf);
    }
}
