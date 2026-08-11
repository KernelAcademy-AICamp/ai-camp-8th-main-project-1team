package com.finntech.ml;

import com.finntech.domain.UserPayment;
import com.finntech.ml.WasteScoringService.WasteJudgment;
import com.finntech.repository.UserPaymentRepository;
import com.finntech.repository.UserSpendingOverrideRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** D3 통합 — 실 컨텍스트에서 ML 판정이 배선되고(모델 로드) 상식적 결과를 내는지. */
@SpringBootTest
@ActiveProfiles("test")
class WasteScoringServiceTest {

    @Autowired WasteScoringService wasteScoringService;
    @Autowired UserPaymentRepository userPaymentRepository;
    @Autowired UserSpendingOverrideRepository overrideRepository;

    private UserPayment pay(String id, long uid, String c1, String c2, int amt, LocalDateTime when) {
        return new UserPayment(id, uid, "0000-0000-0000-0001", 1L, when, c1, c2, amt, "가맹점", null);
    }

    @Test
    void ML_판정이_배선되고_상식적으로_동작한다() {
        assumeTrue(wasteScoringService.modelReady(), "모델 미배치 → skip");
        long uid = 990001L;
        userPaymentRepository.deleteByUserId(uid);
        // 카테고리는 **업종코드 + 우리 중분류**다. 예전에는 '온라인'·'의류패션' 같은 옛 축을 넣었는데,
        // 그 이름들은 이제 어느 표에도 없어 모델이 미분류로 읽는다.
        // 필수 중분류는 대조표가 정한다(재량성 < 0.30) — 대형마트는 0.42라 필수가 아니다.
        userPaymentRepository.saveAll(List.of(
                pay("w-ess", uid, "4781", "의료", 20000, LocalDateTime.of(2026, 7, 13, 11, 0)),     // 필수(약국)
                pay("w-day", uid, "4741", "쇼핑", 30000, LocalDateTime.of(2026, 7, 11, 14, 0)),     // 재량·평소·주간
                pay("w-day2", uid, "4741", "쇼핑", 32000, LocalDateTime.of(2026, 7, 12, 15, 0)),
                pay("w-night", uid, "4741", "쇼핑", 300000, LocalDateTime.of(2026, 7, 12, 2, 0))));  // 재량·심야·과다

        List<WasteJudgment> js = wasteScoringService.scoreUser(uid);
        assertThat(js).hasSize(4);
        Map<String, WasteJudgment> by = js.stream().collect(Collectors.toMap(WasteJudgment::paymentId, j -> j));

        // 필수(의료)는 낭비 확률이 낮다. 임계는 모델이 내는 값이라 여유를 둔다 —
        // 0.10처럼 빡빡하게 박으면 재학습마다 깨져 테스트가 신호가 아니라 잡음이 된다.
        assertThat(by.get("w-ess").wasteProbability()).isLessThan(0.25);
        // 심야·과다 재량 > 주간·평소 재량
        assertThat(by.get("w-night").wasteProbability()).isGreaterThan(by.get("w-day").wasteProbability());
        // 확률은 유효 범위, 낭비 판정엔 설명이 붙는다
        assertThat(js).allSatisfy(j -> {
            assertThat(j.wasteProbability()).isBetween(0.0, 1.0);
            assertThat(j.explanation()).isNotBlank();
        });
    }

    @Test
    void personalOverrideBeatsMlJudgment() {
        assumeTrue(wasteScoringService.modelReady(), "모델 미배치 → skip");
        long uid = 990002L;
        userPaymentRepository.deleteByUserId(uid);
        overrideRepository.deleteByUserId(uid);
        userPaymentRepository.save(
                pay("o1", uid, "쇼핑", "의류패션", 300000, LocalDateTime.of(2026, 7, 12, 2, 0)));

        // 본인이 '의류패션=필수'로 지정 → 통념상 낭비여도 보호
        wasteScoringService.setOverride(uid, "의류패션", false);
        WasteJudgment j = wasteScoringService.scoreUser(uid).get(0);
        assertThat(j.waste()).isFalse();
        assertThat(j.explanation()).contains("개인화");

        // 같은 category2 재지정(낭비로) → upsert(갱신)
        wasteScoringService.setOverride(uid, "의류패션", true);
        assertThat(wasteScoringService.scoreUser(uid).get(0).waste()).isTrue();
    }

    // ======================================================================
    //  '줄이면 좋은 소비'(소비 중분류) 임계 — 설정을 따라야 한다
    //
    //  이 값이 코드에 0.5로 박혀 있던 동안, 낭비 비율이 40% 안팎인 절약형 사용자는 후보가 0개였다.
    //  절약 리포트는 "쏠린 지출이 없다"고 말하고 챌린지의 '뭘 줄여볼까요?'엔 AI 추천이 하나도 안 붙었다.
    //  다시 코드에 박으면 아래 두 테스트가 갈라지지 않는다(설계 원칙 4 — 임계치는 application.yml).
    // ======================================================================

    /**
     * 취미/여가 100만원 중 40만원(40%)이 낭비로 판정되는 상황을 임계만 바꿔가며 만든다.
     *
     * <p>판정과 집계가 <b>같은 축(중분류)</b>이라, 같은 중분류 안에서 낭비인 것과 아닌 것이
     * 갈리려면 금액·시각 같은 다른 특징이 갈라야 한다. 여기서는 스텁이 금액으로 가른다.
     */
    private WasteScoringService withThreshold(double threshold) {
        SpendingClassifier classifier = org.mockito.Mockito.mock(SpendingClassifier.class);
        org.mockito.Mockito.when(classifier.isReady()).thenReturn(true);
        org.mockito.Mockito.when(classifier.threshold()).thenReturn(0.5);
        // 40만원짜리는 낭비, 60만원짜리는 필수 — 같은 중분류 안에서 금액으로 갈린다.
        org.mockito.Mockito.when(classifier.wasteProbability(org.mockito.ArgumentMatchers.anyMap()))
                .thenAnswer(inv -> {
                    Object amt = ((Map<?, ?>) inv.getArgument(0)).get("log_amount");
                    return amt instanceof Number n && n.doubleValue() < Math.log1p(500_000) ? 0.9 : 0.1;
                });

        UserPaymentRepository payments = org.mockito.Mockito.mock(UserPaymentRepository.class);
        org.mockito.Mockito.when(payments.findByUserIdOrderByPaymentDateDesc(1L)).thenReturn(List.of(
                pay("t1", 1L, "5914", "취미/여가", 400_000, LocalDateTime.of(2026, 7, 20, 19, 0)),
                pay("t2", 1L, "9011", "취미/여가", 600_000, LocalDateTime.of(2026, 7, 21, 15, 0))));

        UserSpendingOverrideRepository overrides = org.mockito.Mockito.mock(UserSpendingOverrideRepository.class);
        org.mockito.Mockito.when(overrides.findByUserId(1L)).thenReturn(List.of());

        // 가맹점 성향은 이 테스트의 관심사가 아니다 — 빈 저장소를 준다(전부 NORMAL).
        var stances = org.mockito.Mockito.mock(
                com.finntech.repository.UserMerchantStanceRepository.class);
        org.mockito.Mockito.when(stances.findByUserId(1L)).thenReturn(List.of());

        return new WasteScoringService(classifier, payments, overrides, stances,
                java.time.Clock.systemDefaultZone(), threshold, 0.20);
    }

    @Test
    void 낭비비율이_임계_이상이면_줄이면_좋은_소비가_된다() {
        var summary = withThreshold(0.35).summarize(1L).orElseThrow();
        assertThat(summary.wasteRatioByCategory1().get("취미/여가")).isEqualTo(0.4);
        assertThat(summary.wasteCategories()).contains("취미/여가");
    }

    @Test
    void 낭비비율이_임계_미만이면_후보가_아니다() {
        assertThat(withThreshold(0.5).summarize(1L).orElseThrow().wasteCategories()).isEmpty();
    }
}
