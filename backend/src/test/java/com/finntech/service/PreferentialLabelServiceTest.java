package com.finntech.service;

import com.finntech.service.SavingsMatchInputs.IssuerScope;
import com.finntech.service.SavingsMatchInputs.PreferentialCondition;
import com.finntech.service.SavingsMatchInputs.RequiredCondition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 우대조건 라벨러의 순수 파싱만 검증한다(LLM 실호출·DB 없음).
 * 정본은 `07_취향분석및추천_Agent_설계.md` §4.5 M6.
 *
 * <p>문구는 전부 <b>금감원 실응답에서 그대로 가져온 것</b>이다(2026-08-11 조회). 지어낸 문장으로
 * 시험하면 실제 표기의 결을 놓친다.
 */
class PreferentialLabelServiceTest {

    // ── 빈 집합 vs 파싱 실패 ─────────────────────────────────────

    /**
     * 실측에 우대조건이 `없음`인 상품이 3건 있다(`퍼스트가계적금`). <b>빈 목록</b>이어야 M6이
     * 곧바로 최고금리를 준다 — 여기서 조건을 하나라도 지어내면 그 상품은 영영 기본금리가 된다.
     */
    @Test
    void 우대조건이_없음이면_빈_목록이다() {
        assertThat(PreferentialLabelService.ruleParse("없음")).isEmpty();
        assertThat(PreferentialLabelService.ruleParse(" 해당없음 ")).isEmpty();
        assertThat(PreferentialLabelService.ruleParse("-")).isEmpty();
        assertThat(PreferentialLabelService.ruleParse("")).isEmpty();
        assertThat(PreferentialLabelService.ruleParse(null)).isEmpty();
    }

    /** 문구는 있는데 사전에 안 걸리면 <b>빈 목록이 아니라</b> `그 밖의 조건`이다 — 둘은 다르다. */
    @Test
    void 사전에_없는_조건은_OTHER로_남는다() {
        List<RequiredCondition> out =
                PreferentialLabelService.ruleParse("이 적금을 우리꿈통장에 연결하여 가입하는 경우");

        assertThat(out).isNotEmpty();
        assertThat(out).extracting(RequiredCondition::type)
                .containsExactly(PreferentialCondition.OTHER);
    }

    // ── 종류 추출 ────────────────────────────────────────────────

    @Test
    void 급여이체와_카드실적을_가려낸다() {
        List<RequiredCondition> out = PreferentialLabelService.ruleParse(
                "가.급여/연금 이체:연 0.7%p 다.우리카드사 신용/체크카드 결제 10만원 이상: 연 0.3%p");

        assertThat(out).extracting(RequiredCondition::type)
                .contains(PreferentialCondition.SALARY_TRANSFER, PreferentialCondition.CARD_PERFORMANCE);
    }

    /** 실측 최빈(41%)이지만 우리가 판정할 수 없는 축이다 — 종류로는 남기되 판정에서 빠진다. */
    @Test
    void 자동이체는_종류로_잡히되_판정_불가로_표시된다() {
        List<RequiredCondition> out =
                PreferentialLabelService.ruleParse("나.공과금 자동이체 출금: 0.3%p");

        assertThat(out).extracting(RequiredCondition::type)
                .contains(PreferentialCondition.AUTO_TRANSFER);
        assertThat(PreferentialCondition.AUTO_TRANSFER.judgeable()).isFalse();
    }

    /** 가산폭은 파싱하지 않는다(검산 일치율 4/25) — 종류만 남는지 확인한다. */
    @Test
    void 가산폭_숫자는_담지_않는다() {
        List<RequiredCondition> out = PreferentialLabelService.ruleParse(
                "*최고우대금리:연0.85%p -전월 총수신 평잔 30만원 이상:연0.10%p");

        assertThat(PreferentialLabelService.encode(out)).doesNotContain("0.85", "0.10", "%");
    }

    // ── 당행 한정 ────────────────────────────────────────────────

    @Test
    void 당행_표현이_있으면_판정_가능한_조건에_OWN이_붙는다() {
        List<RequiredCondition> out = PreferentialLabelService.ruleParse(
                "우리은행 입출식 계좌에서 각 항목별 실적 월 수가 계약기간의 1/2이상인 경우 "
                        + "가.급여/연금 이체:연 0.7%p");

        assertThat(out).filteredOn(c -> c.type() == PreferentialCondition.SALARY_TRANSFER)
                .extracting(RequiredCondition::scope)
                .containsExactly(IssuerScope.OWN);
    }

    /** 금융사 지정이 없으면 좁히지 않는다 — 좁히는 쪽이 더 엄격해서 함부로 붙이면 안 된다. */
    @Test
    void 당행_표현이_없으면_ANY다() {
        List<RequiredCondition> out =
                PreferentialLabelService.ruleParse("급여이체 실적이 있는 경우 연 0.5%p");

        assertThat(out).extracting(RequiredCondition::scope).containsOnly(IssuerScope.ANY);
    }

    // ── 저장 형식 ────────────────────────────────────────────────

    @Test
    void 저장_형식은_왕복해도_같다() {
        List<RequiredCondition> original = List.of(
                new RequiredCondition(PreferentialCondition.CARD_PERFORMANCE, IssuerScope.OWN),
                new RequiredCondition(PreferentialCondition.MARKETING_CONSENT, IssuerScope.ANY));

        String encoded = PreferentialLabelService.encode(original);

        assertThat(encoded).isEqualTo("CARD_PERFORMANCE@OWN,MARKETING_CONSENT@ANY");
        assertThat(PreferentialLabelService.decode(encoded)).isEqualTo(original);
    }

    /** 빈 문자열은 "요구 조건 없음"이다. 이 값이 null(라벨 없음)로 뭉개지면 M6의 3분기가 무너진다. */
    @Test
    void 빈_문자열은_요구조건_없음이다() {
        assertThat(PreferentialLabelService.encode(List.of())).isEmpty();
        assertThat(PreferentialLabelService.decode("")).isEmpty();
        assertThat(PreferentialLabelService.decode(null)).isEmpty();
    }

    /**
     * 못 읽는 토막 하나 때문에 화면이 죽는 것보다 `확인 못한 조건`으로 세는 편이 낫다.
     * 종류는 OTHER로 떨어지되 <b>범위(OWN)는 살린다</b> — 읽어낸 것까지 버릴 이유는 없다.
     * 반대로 못 읽는 범위는 ANY로 떨어진다(좁히지 않는 쪽이 덜 단정적이다).
     */
    @Test
    void 모르는_토막은_OTHER로_떨어진다() {
        assertThat(PreferentialLabelService.decode("사라진코드@OWN,SALARY_TRANSFER@없는범위"))
                .containsExactly(
                        new RequiredCondition(PreferentialCondition.SALARY_TRANSFER, IssuerScope.ANY),
                        new RequiredCondition(PreferentialCondition.OTHER, IssuerScope.OWN));
    }

    /** 같은 입력이 같은 순서를 낸다(설계원칙 3 재현성). */
    @Test
    void 조건_순서는_입력_순서와_무관하게_고정된다() {
        String a = PreferentialLabelService.encode(List.of(
                RequiredCondition.any(PreferentialCondition.MARKETING_CONSENT),
                RequiredCondition.any(PreferentialCondition.CARD_PERFORMANCE)));
        String b = PreferentialLabelService.encode(List.of(
                RequiredCondition.any(PreferentialCondition.CARD_PERFORMANCE),
                RequiredCondition.any(PreferentialCondition.MARKETING_CONSENT)));

        assertThat(a).isEqualTo(b);
    }

    // ── LLM 응답 파싱 ────────────────────────────────────────────

    @Test
    void LLM이_코드펜스를_붙여도_읽는다() {
        String text = """
                ```json
                {"conditions": [{"code": "CARD_PERFORMANCE", "scope": "OWN"}]}
                ```
                """;

        assertThat(PreferentialLabelService.parseJson(text)).containsExactly(
                new RequiredCondition(PreferentialCondition.CARD_PERFORMANCE, IssuerScope.OWN));
    }

    @Test
    void LLM이_빈_배열을_주면_요구조건_없음이다() {
        assertThat(PreferentialLabelService.parseJson("{\"conditions\": []}")).isEmpty();
    }

    /** JSON이 아니면 null을 내서 규칙 파서로 떨어지게 한다 — 빈 목록으로 오해되면 최고금리가 나간다. */
    @Test
    void JSON이_아니면_null이라_규칙파서로_떨어진다() {
        assertThat(PreferentialLabelService.parseJson("조건을 알 수 없습니다")).isNull();
    }
}
