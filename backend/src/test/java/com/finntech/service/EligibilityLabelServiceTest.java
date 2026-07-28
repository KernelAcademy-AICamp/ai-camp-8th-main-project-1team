package com.finntech.service;

import com.finntech.service.EligibilityLabelService.Eligibility;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자격 파서·판정의 순수 함수만 검증한다(LLM·DB 없음).
 * 입력 문구는 전부 2026-07-24 금감원 오픈API 실응답에서 가져온 실제 {@code join_member} 값이다.
 */
class EligibilityLabelServiceTest {

    // ── ruleParse: 범용 ────────────────────────────────────────────────

    @Test
    void 나이_제한이_없으면_전부_null이다() {
        for (String s : new String[]{"실명의 개인", "제한없음", "개인 및 개인사업자",
                                     "실명의 개인 (개인사업자 제외)", "개인, 개인사업자, 임의단체"}) {
            Eligibility e = EligibilityLabelService.ruleParse(s);
            assertThat(e.minAge()).as(s).isNull();
            assertThat(e.maxAge()).as(s).isNull();
            assertThat(e.specialStatus()).as(s).isNull();
        }
    }

    // ── ruleParse: 나이 ────────────────────────────────────────────────

    @Test
    void 이상_미만_이하를_경계까지_읽는다() {
        // "만 17세 이상" → 17세부터
        assertThat(EligibilityLabelService.ruleParse("만 17세 이상 실명의 개인 및 개인사업자").minAge()).isEqualTo(17);
        // "만 17세 미만" → 16세까지 (경계 -1)
        assertThat(EligibilityLabelService.ruleParse("만 17세 미만의 실명의 개인").maxAge()).isEqualTo(16);
        // "만13세 미만어린이" — 띄어쓰기가 없어도 읽는다
        assertThat(EligibilityLabelService.ruleParse("만13세 미만어린이").maxAge()).isEqualTo(12);
    }

    @Test
    void 나이_구간을_읽는다() {
        Eligibility a = EligibilityLabelService.ruleParse("만19세~만34세 개인 및 개인사업자");
        assertThat(a.minAge()).isEqualTo(19);
        assertThat(a.maxAge()).isEqualTo(34);

        Eligibility b = EligibilityLabelService.ruleParse("19~39세");
        assertThat(b.minAge()).isEqualTo(19);
        assertThat(b.maxAge()).isEqualTo(39);

        Eligibility c = EligibilityLabelService.ruleParse("만 19세 이상 ~ 만 39세 이하 개인");
        assertThat(c.minAge()).isEqualTo(19);
        assertThat(c.maxAge()).isEqualTo(39);
    }

    // ── ruleParse: 특수 신분 ───────────────────────────────────────────

    @Test
    void 신분_조건은_나이보다_먼저_잡아_성인을_잘못_거르지_않는다() {
        // 나이만 읽으면 maxAge=18이 되어 성인 부모가 잘못 제외된다. 신분 조건이 먼저다.
        Eligibility e = EligibilityLabelService.ruleParse("만 19세미만 자녀 2명 이상을 둔 부모 및 자녀");
        assertThat(e.specialStatus()).isNotNull();
        assertThat(e.minAge()).isNull();
        assertThat(e.maxAge()).isNull();

        assertThat(EligibilityLabelService.ruleParse("세명 이상의 미성년 자녀를 둔 부모").specialStatus()).isNotNull();
    }

    @Test
    void 절차_조건은_신분_제한이_아니다() {
        // 토스뱅크 통장은 누구나 만들 수 있다 — 신분 제한이 아니므로 제외하지 않는다.
        Eligibility e = EligibilityLabelService.ruleParse("· 토스뱅크 통장 또는 토스뱅크 서브 통장을 보유한 실명의 개인");
        assertThat(e.specialStatus()).isNull();
        assertThat(e.minAge()).isNull();
        assertThat(e.maxAge()).isNull();
    }

    @Test
    void 가입대상이_비면_보수적으로_제외한다() {
        assertThat(EligibilityLabelService.ruleParse("").specialStatus()).isNotNull();
        assertThat(EligibilityLabelService.ruleParse(null).specialStatus()).isNotNull();
    }

    // ── eligible: 판정 ────────────────────────────────────────────────

    @Test
    void 나이_구간_경계에서_정확히_갈린다() {
        Eligibility only1934 = new Eligibility(19, 34, null, "RULE");   // NH1934월복리적금
        assertThat(EligibilityLabelService.eligible(only1934, 18)).isFalse();
        assertThat(EligibilityLabelService.eligible(only1934, 19)).isTrue();
        assertThat(EligibilityLabelService.eligible(only1934, 34)).isTrue();
        assertThat(EligibilityLabelService.eligible(only1934, 35)).isFalse();
    }

    @Test
    void 아동전용_상품은_성인에게_제외된다() {
        Eligibility kids = new Eligibility(null, 16, null, "RULE");     // 마이키즈 적금
        assertThat(EligibilityLabelService.eligible(kids, 31)).isFalse();
        assertThat(EligibilityLabelService.eligible(kids, 10)).isTrue();
    }

    @Test
    void 특수_신분_상품은_나이와_무관하게_제외된다() {
        Eligibility special = new Eligibility(null, null, "자녀 있는 부모", "AI");
        assertThat(EligibilityLabelService.eligible(special, 31)).isFalse();
        assertThat(EligibilityLabelService.eligible(special, null)).isFalse();
    }

    @Test
    void 나이를_모르면_나이조건은_따지지_않고_보여준다() {
        // 마이데이터 미연동 → 출생연도 없음. 판매가 아니라 정보성 비교이므로 감추지 않는다.
        assertThat(EligibilityLabelService.eligible(new Eligibility(19, 34, null, "RULE"), null)).isTrue();
        assertThat(EligibilityLabelService.eligible(new Eligibility(null, null, null, "RULE"), null)).isTrue();
    }

    @Test
    void 라벨이_없으면_보수적으로_제외한다() {
        assertThat(EligibilityLabelService.eligible(null, 31)).isFalse();
    }

    // ── join_deny=3 을 힌트로만 쓰기 ────────────────────────────────────

    @Test
    void 은행이_제한신고했어도_나이조건을_읽었으면_그_판정을_믿는다() {
        // NH1934월복리적금: join_deny=3 이지만 가입대상은 "만19세~만34세" — 25세에게 딱 맞는 상품이다.
        Eligibility e = new Eligibility(19, 34, null, "RULE");
        assertThat(EligibilityLabelService.eligible(e, 25, true)).isTrue();
        assertThat(EligibilityLabelService.eligible(e, 45, true)).isFalse();
    }

    @Test
    void 은행이_제한신고했는데_아무_조건도_못_읽으면_보수적으로_제외한다() {
        // 읽지 못한 조건이 있다는 뜻이므로 뺀다.
        Eligibility unknown = new Eligibility(null, null, null, "RULE");
        assertThat(EligibilityLabelService.eligible(unknown, 31, true)).isFalse();
        assertThat(EligibilityLabelService.eligible(unknown, 31, false)).isTrue();
    }

    // ── 저축은행 실데이터에서 나온 까다로운 문구들 ──────────────────────────

    @Test
    void 되돌릴수_없는_개인속성_조건을_잡는다() {
        for (String s : new String[]{
                "가입연도 12간지띠에 해당되는 고객",                       // 12干支정기적금
                "적금 가입월과 주민등록상 생일월이 일치하는 개인고객",           // 생일축하정기적금
                "반려견을 키우는 고객",                                  // JT쩜피플러스
                "중소기업에서 근무하는 실명의 개인 (개인사업자 제외)",           // IBK중기근로자우대적금
                "개인 (직장인)",                                       // 직장인YES
                "19세 이상 주민등록증 혹은 운전면허증 보유한 무주택 개인",        // 마이홈 정기적금
                "실명의 개인중 첫거래 또는 장기미거래 고객"}) {              // 처음만난적금
            assertThat(EligibilityLabelService.ruleParse(s).specialStatus()).as(s).isNotNull();
        }
    }
}
