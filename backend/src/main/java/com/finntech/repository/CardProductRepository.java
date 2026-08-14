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
     * 추천 후보 — <b>발급 중이고 숫자를 보여줘도 되는 것</b>만.
     *
     * <p>{@code STOPPED}(발급중단)는 표에 남기지만 추천하지 않는다. 남기는 이유는 혜택 비교의
     * 과거 축이 사라지지 않게 하려는 것이고, 추천하지 않는 이유는 가입할 수 없기 때문이다.
     * {@code REFERENCE}(게이트 3 불통과)도 뺀다 — 숫자를 못 믿는 카드로 절감액을 말하면 안 된다.
     *
     * <p><b>정렬을 이름으로 고정한다</b>(원칙 3 재현성). 절감액순 정렬은 계산이 끝난 뒤에 한다.
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
    @Query("select distinct c from CardProduct c "
            + "where c.status = 'ACTIVE' and c.grade = 'PRECISE' order by c.name")
    List<CardProduct> findRecommendable();
}
