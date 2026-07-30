package com.finntech.engine;

import com.finntech.config.AnalysisProperties;
import com.finntech.domain.UserPayment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 절약 후보에 붙은 <b>ML 낭비확률 게이트</b>.
 *
 * <p><b>왜 필요했나.</b> 등급은 재량성이 정하는데, 재량성은 <b>카테고리의 성질</b>이지
 * <b>그 사람의 습관</b>이 아니다. 취미/여가는 누구에게나 0.63이라 늘 '전액 제거가능'이 됐고,
 * 취미를 아껴 쓰는 사람에게도 "월 115만원을 통째로 줄이세요"가 나갔다. 설정에 있던
 * {@code waste-ratio-threshold}는 그걸 막으라고 둔 값인데 <b>선정에 연결돼 있지 않았다</b>.
 *
 * <p>이제 EBM이 낸 중분류별 낭비 비율로 ① 임계 미만은 후보에서 빼고 ② 제거가능의 절감액을
 * 그 비율만큼으로 잡는다. 모델이 없으면(빈 표) 예전 규칙 그대로 돈다.
 */
class CutCandidateMlGateTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 7, 1, 12, 0);

    /** 재량성: 취미/여가 0.63(제거가능) · 식비 0.52(최적화가능) · 의료 0.10(보호). */
    private static double disc(String cat) {
        return switch (cat) {
            case "취미/여가" -> 0.6325;
            case "식비" -> 0.5174;
            case "의료" -> 0.10;
            default -> 0.5;
        };
    }

    private static AnalysisProperties.CutCandidate cfg() {
        AnalysisProperties.CutCandidate c = new AnalysisProperties.CutCandidate();
        c.setProtectedBelow(0.30);
        c.setRemovableAbove(0.55);
        c.setWasteRatioThreshold(0.5);
        return c;
    }

    /** 같은 카테고리에 같은 금액을 n건. 창은 30일로 둬 월 환산이 1:1에 가깝게 한다. */
    private static List<UserPayment> spend(String cat, int amount, int n) {
        List<UserPayment> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new UserPayment("p" + cat + i, 1L, "card", 1L, T.minusDays(i),
                    "9999", cat, amount, "가맹점", 0, null));
        }
        return out;
    }

    @Test
    @DisplayName("낭비 비율이 임계 미만인 카테고리는 후보에서 빠진다")
    void lowWasteRatioIsExcluded() {
        List<UserPayment> w = spend("취미/여가", 100_000, 10);

        var withoutMl = CutCandidateSelector.selectFrom(w, cfg(), 30, CutCandidateMlGateTest::disc);
        var gated = CutCandidateSelector.selectFrom(w, cfg(), 30, CutCandidateMlGateTest::disc,
                Map.of("취미/여가", 0.20));   // ML: 20%만 낭비 → 임계 0.5 미만

        assertThat(withoutMl).extracting(CutCandidate::category2).contains("취미/여가");
        assertThat(gated).as("그 사람에겐 낭비가 아니다 — 줄이라고 권하지 않는다").isEmpty();
    }

    @Test
    @DisplayName("제거가능의 절감액은 낭비 비율만큼이다 — 전액이 아니다")
    void removableSavingScalesWithWasteRatio() {
        List<UserPayment> w = spend("취미/여가", 100_000, 10);   // 창 100만원

        var gated = CutCandidateSelector.selectFrom(w, cfg(), 30, CutCandidateMlGateTest::disc,
                Map.of("취미/여가", 0.62));

        assertThat(gated).hasSize(1);
        CutCandidate c = gated.get(0);
        assertThat(c.type()).isEqualTo(CutCandidate.Type.REMOVABLE);
        assertThat(c.estimatedSaving())
                .as("월 지출의 62%")
                .isEqualTo(Math.round(c.monthlySpend() * 0.62));
        assertThat(c.estimatedSaving()).isLessThan(c.monthlySpend());
        assertThat(c.reason()).contains("62%");
    }

    @Test
    @DisplayName("모델이 없으면(빈 표) 예전 규칙 그대로 — 전액 제거가능")
    void noModelKeepsOldBehaviour() {
        List<UserPayment> w = spend("취미/여가", 100_000, 10);

        var gated = CutCandidateSelector.selectFrom(w, cfg(), 30, CutCandidateMlGateTest::disc, Map.of());

        assertThat(gated).hasSize(1);
        assertThat(gated.get(0).estimatedSaving()).isEqualTo(gated.get(0).monthlySpend());
        assertThat(gated.get(0).reason()).contains("전액");
    }

    @Test
    @DisplayName("보호 카테고리는 낭비 비율이 높아도 후보가 아니다")
    void protectedStaysProtected() {
        List<UserPayment> w = spend("의료", 200_000, 5);

        var gated = CutCandidateSelector.selectFrom(w, cfg(), 30, CutCandidateMlGateTest::disc,
                Map.of("의료", 0.99));   // ML이 낭비라 해도

        assertThat(gated).as("약값을 줄이라고 권하지 않는다").isEmpty();
    }

    @Test
    @DisplayName("최적화가능도 게이트를 탄다 — 통과하면 중앙값 초과분 그대로")
    void optimizableAlsoGated() {
        List<UserPayment> w = new ArrayList<>(spend("식비", 10_000, 9));
        w.addAll(spend("식비비싼", 0, 0));
        w.add(new UserPayment("big", 1L, "card", 1L, T, "9999", "식비", 90_000, "오마카세", 0, null));

        var blocked = CutCandidateSelector.selectFrom(w, cfg(), 30, CutCandidateMlGateTest::disc,
                Map.of("식비", 0.10));
        var passed = CutCandidateSelector.selectFrom(w, cfg(), 30, CutCandidateMlGateTest::disc,
                Map.of("식비", 0.80));

        assertThat(blocked).isEmpty();
        assertThat(passed).hasSize(1);
        assertThat(passed.get(0).type()).isEqualTo(CutCandidate.Type.OPTIMIZABLE);
        assertThat(passed.get(0).reason()).contains("중앙값");
    }
}
