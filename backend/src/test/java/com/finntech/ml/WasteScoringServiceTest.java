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
        return new UserPayment(id, uid, "0000-0000-0000-0001", 1L, when, c1, c2, amt, "가맹점", 0, null);
    }

    @Test
    void ML_판정이_배선되고_상식적으로_동작한다() {
        assumeTrue(wasteScoringService.modelReady(), "모델 미배치 → skip");
        long uid = 990001L;
        userPaymentRepository.deleteByUserId(uid);
        userPaymentRepository.saveAll(List.of(
                pay("w-ess", uid, "온라인", "대형마트", 20000, LocalDateTime.of(2026, 7, 13, 11, 0)),   // 필수
                pay("w-day", uid, "쇼핑", "의류패션", 30000, LocalDateTime.of(2026, 7, 11, 14, 0)),     // 재량·평소·주간
                pay("w-day2", uid, "쇼핑", "의류패션", 32000, LocalDateTime.of(2026, 7, 12, 15, 0)),
                pay("w-night", uid, "쇼핑", "의류패션", 300000, LocalDateTime.of(2026, 7, 12, 2, 0))));  // 재량·심야·과다

        List<WasteJudgment> js = wasteScoringService.scoreUser(uid);
        assertThat(js).hasSize(4);
        Map<String, WasteJudgment> by = js.stream().collect(Collectors.toMap(WasteJudgment::paymentId, j -> j));

        // 필수(대형마트)는 낭비 확률 낮음
        assertThat(by.get("w-ess").wasteProbability()).isLessThan(0.10);
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
}
