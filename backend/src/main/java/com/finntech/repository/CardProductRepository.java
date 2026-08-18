package com.finntech.repository;

import com.finntech.domain.CardProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * 카드 상품 카탈로그 — 카드사 상품공시에서 온 <b>실제 상품</b>이다.
 *
 * <p>키는 {@code (카드사, 상품번호)} 다. 이름은 카드사가 마케팅으로 바꾸지만 상품번호는 안 바꾼다.
 */
public interface CardProductRepository extends JpaRepository<CardProduct, Long> {

    Optional<CardProduct> findByIssuerAndProductId(String issuer, String productId);

    /**
     * 추천 후보 — <b>발급 중이고, 겹칠 대상이 있고, 기준일을 말할 수 있는 것</b>만.
     *
     * <p>{@code STOPPED}(발급중단)는 표에 남기지만 추천하지 않는다. 남기는 이유는 혜택 비교의
     * 과거 축이 사라지지 않게 하려는 것이고, 추천하지 않는 이유는 가입할 수 없기 때문이다.
     *
     * <p><b>{@code grade} 는 더 이상 자격이 아니다</b>(2026-08-13 개정). 게이트 3은 여전히
     * 등급을 매기지만 그 규칙 대부분(실적 제외 5개 미만 · 한도 구간 키 불일치 · 이중 추출
     * 숫자 불일치)은 <b>절감액을 계산할 때만</b> 의미가 있다. 화면이 금액을 말하지 않게 되면서
     * (`09_카드추천_판정.md` §1.1) 요율·한도가 흔들려도 "그 브랜드가 대상이다"는 참이라,
     * 자격에서 뺐다. 실측으로 후보가 <b>28장에서 52장</b>이 됐다.
     *
     * <p>대신 둘이 자격이 된다.
     * <ul>
     *   <li><b>혜택 대상이 하나라도 있을 것</b> — 겹칠 것이 없으면 할 말이 없다</li>
     *   <li><b>{@code as_of} 가 있을 것</b> — 혜택 개정 추적이 스코프 밖이라 "이 시점 공시
     *       기준"이 유일한 방어다. 금액을 내든 안 내든 이건 같다</li>
     * </ul>
     *
     * <p><b>정렬을 이름으로 고정한다</b>(원칙 3 재현성). 겹침순 정렬은 계산이 끝난 뒤에 한다.
     *
     * <p><b>딸린 표를 fetch join 으로 당기지 않는다.</b> 예전에는
     * {@code @EntityGraph({"tiers","benefits","benefits.targets","benefits.caps"})} 였는데
     * <b>운영에서 이 API 가 통째로 500 이었다</b> — 한 부모에 달린 {@code List} 둘을 한 쿼리로
     * 못 가져온다({@code MultipleBagFetchException: CardBenefit.caps, CardBenefit.targets}).
     * {@code @OrderBy} 는 정렬만 정할 뿐 bag 을 없애지 않으므로 {@code tiers}+{@code benefits} 도
     * 같은 문제다. 컬렉션을 {@code Set} 으로 바꾸면 한 방에 되지만 엔티티 동등성을 건드려야 해서,
     * <b>{@code @BatchSize} 로 나눠 읽는다</b>(각 컬렉션 {@code CardProduct}·{@code CardBenefit}).
     * 후보가 수십 장이라 쿼리 몇 번이 더 나가는 편이 싸다.
     */
    @Query("""
            select distinct c from CardProduct c
            where c.status = 'ACTIVE'
              and c.asOf is not null
              and exists (select 1 from CardBenefit b join b.targets t
                          where b.card = c and t.kind in ('BRAND', 'AXIS'))
            order by c.name
            """)
    List<CardProduct> findRecommendable();
}
