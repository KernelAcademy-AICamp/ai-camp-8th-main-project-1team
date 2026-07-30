package com.finntech.ml;

import com.finntech.domain.UserPayment;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 낭비 ML 특징 추출 — Python 학습 정의와 일치하는지(순수, 모델 불필요). */
class WasteFeatureExtractorTest {

    /** 첫 인자는 업종코드, cat2는 우리 소비 중분류다. */
    private UserPayment pay(String cat2, int amount, LocalDateTime when) {
        return new UserPayment("p" + amount + when, 1L, "0000-0000-0000-0001", 1L, when,
                "5622", cat2, amount, "가맹점", 0, null);
    }

    @Test
    void userStatsComputedAsDefined() {
        var payments = List.of(
                pay("카페/간식", 5000, LocalDateTime.of(2026, 7, 10, 14, 0)),
                pay("카페/간식", 7000, LocalDateTime.of(2026, 7, 11, 15, 0)),
                // 필수 중분류는 대조표가 정한다(재량성 < 0.30) — 의료·주거/통신·교통/자동차.
                // 대형마트(0.42)는 더는 필수가 아니다. 목록을 손으로 옮겨 적던 시절의 잔재였다.
                pay("의료", 20000, LocalDateTime.of(2026, 7, 12, 11, 0)));
        var s = WasteFeatureExtractor.userStats(payments);
        assertThat(s.categoryMedian().get("카페/간식")).isEqualTo(6000.0);   // (5000+7000)/2
        assertThat(s.categoryMedian().get("의료")).isEqualTo(20000.0);
        assertThat(s.discRatio()).isEqualTo(2.0 / 3.0);                  // 카페 재량 2, 의료 필수 1
        assertThat(s.meanLogAmount()).isGreaterThan(0);
    }

    @Test
    void transactionFeaturesMatchTrainingKeysAndValues() {
        var payments = List.of(
                pay("카페", 5000, LocalDateTime.of(2026, 7, 10, 14, 0)),
                pay("카페", 7000, LocalDateTime.of(2026, 7, 11, 15, 0)));
        var s = WasteFeatureExtractor.userStats(payments);
        // 심야(2시) + 평소(중앙값 6000) 대비 2배 금액
        Map<String, Object> f = WasteFeatureExtractor.features("카페", 12000,
                LocalDateTime.of(2026, 7, 15, 2, 30), s);
        assertThat(f).containsKeys("cat2", "log_amount", "hour_sin", "hour_cos", "night",
                "dow_sin", "dow_cos", "weekend", "amt_vs_typical", "user_mean_log_amount", "user_disc_ratio");
        assertThat(f.get("cat2")).isEqualTo("카페");
        assertThat((int) f.get("night")).isEqualTo(1);                  // 2시 = 심야
        assertThat((double) f.get("amt_vs_typical")).isEqualTo(2.0);    // 12000/6000
        // 주간 결제는 night=0
        Map<String, Object> day = WasteFeatureExtractor.features("카페", 6000,
                LocalDateTime.of(2026, 7, 15, 14, 0), s);
        assertThat((int) day.get("night")).isZero();
        assertThat((double) day.get("amt_vs_typical")).isEqualTo(1.0);
    }
}
