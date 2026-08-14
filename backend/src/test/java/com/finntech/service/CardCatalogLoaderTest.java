package com.finntech.service;

import com.finntech.domain.CardBenefit;
import com.finntech.domain.CardBenefitTarget;
import com.finntech.domain.CardExclusion;
import com.finntech.domain.CardProduct;
import com.finntech.repository.CardProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카탈로그가 <b>표까지 실제로 도달하는가</b> — 기동 적재의 사슬을 못 박는다.
 *
 * <p>이 사슬은 조용히 끊긴다. {@code card-catalog.json} 은 멀쩡한데 칸 이름이 어긋나 값이
 * {@code null} 로 들어가거나, 자식 표가 통째로 비어도 <b>기동은 성공하고 화면은 "아낄 게
 * 없어요"만 띄운다.</b> 크래시가 안 나므로 시험이 아니면 아무도 모른다.
 *
 * <p>그래서 여기서 확인하는 것은 "몇 장 들어왔나"가 아니라 <b>구조가 살아서 왔는가</b>다 —
 * 실적 구간·구간별 한도·제외 목록·혜택 대상까지. 하나라도 안 실리면 절감액이 0 이 된다.
 */
@SpringBootTest
@ActiveProfiles("test")
class CardCatalogLoaderTest {

    @Autowired
    private CardProductRepository cards;

    @Test
    @Transactional
    @DisplayName("기동하면 카탈로그가 표에 실린다 — 자식 표까지")
    void loadsCatalogIntoTables() {
        List<CardProduct> all = cards.findAll();
        assertThat(all).as("card-catalog.json 이 비었거나 적재가 안 돌았다").isNotEmpty();

        CardProduct zone = all.stream().filter(c -> c.getName().contains("ZONE")).findFirst()
                .orElseThrow(() -> new AssertionError("BC 바로 ZONE 이 안 실렸다"));

        // 기준일 — 이게 없으면 화면에 "이 시점 공시 기준"을 못 쓰고 참고 모드로 떨어진다.
        assertThat(zone.getAsOf()).as("심의필 날짜").isNotNull();
        assertThat(zone.isPrecise()).as("게이트 3 을 통과한 카드여야 숫자를 보여준다").isTrue();

        // 실적 구간 — 가변 길이라 표로 뺀 자리다.
        assertThat(zone.getTiers()).as("실적 구간이 안 실리면 한도가 통째로 안 열린다")
                .hasSizeGreaterThanOrEqualTo(2);

        // 실적 제외 — 5개 미만이면 게이트 3 이 참고로 떨어뜨린다. 축이 갈려 실린다.
        assertThat(zone.exclusionsOn(CardExclusion.Axis.PERFORMANCE))
                .as("실적 제외를 안 빼면 실적이 과대 계산된다").hasSizeGreaterThanOrEqualTo(5);
        assertThat(zone.exclusionsOn(CardExclusion.Axis.BENEFIT))
                .as("혜택 제외는 실적 제외와 **다른 목록**이다").isNotEmpty();

        // 혜택 — 요율·한도·대상이 함께 와야 계산이 선다.
        CardBenefit eat = zone.getBenefits().stream()
                .filter(b -> b.getGroupName().equals("EAT-ZONE")).findFirst()
                .orElseThrow(() -> new AssertionError("EAT-ZONE 혜택이 안 실렸다"));
        assertThat(eat.getRatePercent()).isNotNull();
        assertThat(eat.getRequiresTier()).as("실적 구간과 이어져야 '열린다'를 판정한다").isNotNull();
        assertThat(eat.getCaps()).as("구간별 월한도").isNotEmpty();
        assertThat(eat.getTargets()).extracting(CardBenefitTarget::getValue)
                .as("공시가 나열한 브랜드가 그대로 와야 매칭이 된다").contains("스타벅스");

        // 셀 수 없는 혜택도 버리지 않는다 — 표시는 하고 계산에서만 뺀다.
        assertThat(zone.getBenefits()).anyMatch(b -> !b.isCountable());
    }

    /**
     * 추천 후보 조회가 <b>실제로 실행되는가</b>.
     *
     * <p>이 시험이 없어서 API 가 통째로 500 인 채로 시험은 전부 통과했다(2026-08-13).
     * 원인은 {@code @EntityGraph} 가 한 부모에 달린 {@code List} 둘을 같이 당긴 것이고
     * ({@code MultipleBagFetchException}), <b>다른 시험들은 카드를 직접 만들어 넣느라
     * 이 쿼리를 한 번도 안 탔다.</b> 조회를 부르고 딸린 표까지 만져 봐야 잡힌다.
     */
    @Test
    @Transactional
    @DisplayName("추천 후보 조회가 딸린 표까지 읽는다 — 쿼리가 실행되는지 확인")
    void findRecommendableActuallyRuns() {
        List<CardProduct> candidates = cards.findRecommendable();
        assertThat(candidates).as("ACTIVE + PRECISE 카드가 하나도 없다").isNotEmpty();

        // 지연 로딩이 실제로 풀리는지 만져 본다 — 여기서 bag 두 개가 같이 걸리면 터진다.
        for (CardProduct card : candidates) {
            card.getTiers().size();
            card.getAnnualFees().size();
            card.getCombinedCaps().size();
            card.exclusionsOn(CardExclusion.Axis.PERFORMANCE).size();
            for (CardBenefit benefit : card.getBenefits()) {
                benefit.getCaps().size();
                benefit.getTargets().size();
            }
        }

        assertThat(candidates).allMatch(CardProduct::isPrecise);
        assertThat(candidates).as("정렬을 이름으로 고정한다(원칙 3)")
                .isSortedAccordingTo(java.util.Comparator.comparing(CardProduct::getName));
    }
}
