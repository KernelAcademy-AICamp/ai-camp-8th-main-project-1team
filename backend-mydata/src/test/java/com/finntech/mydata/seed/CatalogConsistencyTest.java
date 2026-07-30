package com.finntech.mydata.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시드 카탈로그의 <b>내부 정합</b>을 본다. 값 하나가 어긋나면 앱이 기동조차 못 한다.
 *
 * <p><b>실제로 밟은 사고.</b> 카드 상품을 늘리면서 {@link Catalog#COMPANIES}에 우리카드·하나카드를
 * 넣지 않았다. 시드는 {@code companies.get(cardDef.company())}로 회사를 붙이는데, 목록에 없으니
 * null 이 들어갔고 저장에서 not-null 위반이 났다 — {@code Application run failed}로 마이데이터
 * 서버가 통째로 뜨지 않았다. 컴파일도 테스트도 통과하는데 기동만 죽어서, 스키마를 갈아끼우는
 * CI(운영 중지 검사)에서야 드러났다.
 *
 * <p>여기서 막으면 카탈로그를 늘리는 사람이 즉시 안다.
 */
class CatalogConsistencyTest {

    @Test
    @DisplayName("카드 정의가 쓰는 카드사는 전부 COMPANIES 에 있다")
    void everyCardCompanyIsRegistered() {
        Set<String> known = Set.copyOf(Catalog.COMPANIES);
        List<String> missing = Catalog.CARD_DEFS.stream()
                .map(Catalog.CardDef::company)
                .distinct()
                .filter(c -> !known.contains(c))
                .toList();

        assertThat(missing)
                .as("COMPANIES 에 없는 카드사 — 시드가 null 을 저장해 기동이 실패한다")
                .isEmpty();
    }

    @Test
    @DisplayName("카드사 목록에 중복이 없다")
    void companiesAreUnique() {
        assertThat(Catalog.COMPANIES).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("카드 이름이 겹치지 않는다")
    void cardNamesAreUnique() {
        assertThat(Catalog.CARD_DEFS.stream().map(Catalog.CardDef::name).toList())
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("모든 소비맥락에 상호 풀이 있다")
    void everyContextHasMerchants() {
        // 시드는 CONTEXTS_BY_KSIC 이 고른 맥락으로 MERCHANTS.get(맥락) 을 그대로 쓴다.
        // 없으면 null 이 되어 NPE 로 기동이 죽는다 — 업종코드 재분류 때 맥락 이름을 바꾸면서
        // 한식·양식·분식·대중교통·통신비·화장품·의원 7개가 상호 없이 남아 실제로 밟았다.
        List<String> missing = Catalog.CONTEXTS_BY_KSIC.values().stream()
                .flatMap(List::stream)
                .distinct()
                .filter(ctx -> Catalog.MERCHANTS.get(ctx) == null || Catalog.MERCHANTS.get(ctx).isEmpty())
                .sorted()
                .toList();

        assertThat(missing).as("상호 풀이 없는 소비맥락 — 시드가 NPE 로 죽는다").isEmpty();
    }

    @Test
    @DisplayName("업종코드마다 맥락이 하나 이상 있다")
    void everyKsicHasContext() {
        List<String> empty = Catalog.KSIC_CODES.stream()
                .filter(k -> {
                    List<String> c = Catalog.CONTEXTS_BY_KSIC.get(k);
                    return c == null || c.isEmpty();
                })
                .toList();

        assertThat(empty).as("맥락이 없는 업종코드 — 시드가 여기서도 NPE 로 죽는다").isEmpty();
    }

    @Test
    @DisplayName("혜택의 중분류는 대조표가 아는 이름이다")
    void benefitCategoriesAreKnownMidCategories() {
        // 중분류 15개(+미분류). 여기 없는 이름으로 혜택을 걸면 어떤 결제에도 매칭되지 않아
        // 혜택이 조용히 0원이 된다 — 죽지 않아서 더 늦게 발견된다.
        Set<String> mids = Set.of(
                "식비", "카페/간식", "편의점/잡화", "대형마트", "쇼핑", "교통/자동차",
                "주거/통신", "취미/여가", "미용", "의료", "건강/피트니스", "생활",
                "여행/숙박", "술/유흥", "카테고리없음");

        List<String> unknown = Catalog.CARD_DEFS.stream()
                .flatMap(c -> c.benefits().stream())
                .map(Catalog.BenefitDef::midCategory)
                .distinct()
                .filter(m -> !mids.contains(m))
                .toList();

        assertThat(unknown).as("중분류에 없는 혜택 대상").isEmpty();
    }
}
