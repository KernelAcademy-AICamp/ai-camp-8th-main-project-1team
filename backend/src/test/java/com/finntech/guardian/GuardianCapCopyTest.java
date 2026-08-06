package com.finntech.guardian;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>예산을 말하는 알림은 합계를 가리킨다</b> — 카테고리를 "예산"에 붙이지 않는다.
 *
 * <p>C3·C6이 쓰는 {@code cap}·{@code remaining} 은 <b>챌린지 합계</b> 기준인데
 * (`GuardianRules.computeSnapshot`), 예전 문안은 "<b>식비</b> 예산의 80%예요"라고 말했다.
 * 거기 붙은 카테고리는 <b>방금 결제의 꼬리표</b>일 뿐이라 예산과 아무 상관이 없었다.
 *
 * <p>§8-T로 카테고리별 예산이 <b>실제로 생긴 뒤</b>로는 이 문장이 명백히 틀린 말이 된다 —
 * 홈이 "식비 198%"를 보여주는데 알림은 "식비 예산의 80%"라고 하면 두 숫자가 정면충돌한다.
 * 실측 예: 합계 708,630/844,081(84%)인데 식비만 198%.
 *
 * <p>알림을 카테고리별로 <b>쪼개지 않기로</b> 한 이유는 따로 있다 — 푸시 예산이 하루 2건이라
 * 쪼개도 알림 수는 안 늘고 침묵만 늘며, 판정이 합계인데 카테고리로 예고하면 <b>거짓 예고</b>가 된다.
 * 대신 "어디서 샜는지"를 같은 문장 안에서 지목한다.
 */
class GuardianCapCopyTest {

    private static Map<String, Object> vars(String topCategory) {
        Map<String, Object> v = new TreeMap<>();
        v.put("remaining", 118_000L);
        v.put("cap", 844_081L);
        v.put("secured", 120_000L);
        v.put("amount", 23_000L);
        v.put("category", "식비");          // 방금 결제한 거래의 꼬리표 — 예산과 무관하다
        if (topCategory != null) v.put("topCategory", topCategory);
        return v;
    }

    @Test
    @DisplayName("C3 본문이 카테고리를 예산에 붙이지 않는다")
    void C3_예산은_합계다() {
        String body = GuardianCopy.fallback("C3", vars(null));
        assertThat(body).contains("예산의 80%");
        assertThat(body).doesNotContain("식비 예산");   // 옛 문안이 여기서 걸린다
    }

    @Test
    @DisplayName("C6 본문이 카테고리를 예산에 붙이지 않는다")
    void C6_예산은_합계다() {
        String body = GuardianCopy.fallback("C6", vars(null));
        assertThat(body).contains("예산 844,081원을 넘었어요");
        assertThat(body).doesNotContain("식비 예산");
    }

    @Test
    @DisplayName("제목에도 카테고리를 안 붙인다 — 20자 제한 때문에 제목이 더 오해되기 쉽다")
    void 제목() {
        assertThat(GuardianCopy.fallbackTitle("C3", vars(null))).isEqualTo("예산 80%");
        assertThat(GuardianCopy.fallbackTitle("C6", vars(null))).isEqualTo("예산 초과");
    }

    @Test
    @DisplayName("카테고리는 '어디서 샜는지' 지목하는 자리로 옮긴다 — 알림 수는 안 늘린다")
    void 지목_문장() {
        String body = GuardianCopy.fallback("C3", vars("쇼핑"));
        assertThat(body).contains("쇼핑에서 가장 많이 나갔어요");
        assertThat(body).doesNotContain("쇼핑 예산");
        assertThat(GuardianCopy.fallback("C6", vars("교통/자동차")))
                .contains("교통/자동차에서 가장 많이 나갔어요");
    }

    @Test
    @DisplayName("지목할 게 없으면 아무 말도 안 붙인다 — 군더더기 문장을 만들지 않는다")
    void 지목할_게_없으면_침묵() {
        assertThat(GuardianCopy.fallback("C3", vars(null))).doesNotContain("가장 많이 나갔어요");
        assertThat(GuardianCopy.fallback("C6", vars(null))).doesNotContain("가장 많이 나갔어요");
    }

    @Test
    @DisplayName("받침이 있든 없든 문장이 안 깨진다 — 조사는 '에서'만 쓴다")
    void 조사() {
        // '식비'(받침 없음)·'쇼핑'(받침 ㅇ) 둘 다 같은 조사로 자연스러워야 한다.
        for (String c : new String[]{"식비", "쇼핑", "편의점/잡화", "주거/통신"}) {
            assertThat(GuardianCopy.fallback("C3", vars(c))).contains(c + "에서 가장 많이 나갔어요");
        }
    }

    @Test
    @DisplayName("지목 문장이 길어져도 본문 길이 제한을 안 넘는다")
    void 길이() {
        for (String c : new String[]{"교통/자동차", "편의점/잡화", "취미/여가"}) {
            assertThat(GuardianCopy.fallback("C3", vars(c)).length())
                    .as("C3 + " + c).isLessThanOrEqualTo(GuardianCopy.MAX_BODY_LEN);
            assertThat(GuardianCopy.fallback("C6", vars(c)).length())
                    .as("C6 + " + c).isLessThanOrEqualTo(GuardianCopy.MAX_BODY_LEN);
        }
    }

    @Test
    @DisplayName("지목 문장은 고정구다 — 반복 감지가 이걸 반복으로 세면 안 된다")
    void 고정구() {
        assertThat(GuardianRules.stripFixedPhrases(
                java.util.List.of("에서 가장 많이 나갔어요", "예산의 80%")))
                .containsExactly("예산의 80%");
    }

    @Test
    @DisplayName("결제를 설명하는 다른 케이스는 카테고리를 그대로 쓴다 — 거기선 맞는 말이다")
    void 다른_케이스는_그대로() {
        assertThat(GuardianCopy.fallback("C1", vars(null))).startsWith("식비 23,000원 결제가");
        assertThat(GuardianCopy.fallbackTitle("C1", vars(null))).isEqualTo("식비 첫 결제");
    }
}
