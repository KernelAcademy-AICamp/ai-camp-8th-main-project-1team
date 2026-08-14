package com.finntech.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「지킨 돈 굴리기」의 이자 계산만 검증한다(외부 호출·DB 없음).
 * 정본은 `07_취향분석및추천_Agent_설계.md` §4.7.
 */
class KeptMoneyParkingServiceTest {

    /** 일반과세 15.4% (소득세 14% + 지방소득세 1.4%). */
    private static final double TAX = 0.154;

    // ── 목돈 거치 ────────────────────────────────────────────────

    @Test
    void 목돈을_1년_두면_단리_세후_이자가_나온다() {
        // 522,000 × 2.5% = 13,050 세전 → × (1 − 0.154) = 11,040
        assertThat(KeptMoneyParkingService.lumpAfterTaxInterest(522_000, 2.5, 12, TAX))
                .isEqualTo(11_040);
    }

    @Test
    void 기간이_절반이면_이자도_절반이다() {
        assertThat(KeptMoneyParkingService.lumpAfterTaxInterest(1_000_000, 3.0, 6, TAX))
                .isEqualTo(KeptMoneyParkingService.lumpAfterTaxInterest(1_000_000, 3.0, 12, TAX) / 2);
    }

    // ── 매달 넣기 ────────────────────────────────────────────────

    /**
     * <b>여기가 틀리기 쉬운 자리다.</b> 매달 넣은 돈이 1년 내내 있는 게 아니다 —
     * 마지막 달에 넣은 돈은 이자가 거의 안 붙는다.
     */
    @Test
    void 매달_넣는_돈은_총액에_금리를_곱하지_않는다() {
        long actual = KeptMoneyParkingService.monthlyAfterTaxInterest(87_000, 2.5, 12, TAX);

        // 87,000 × 2.5%/12 × (12×11/2=66) = 11,962.5 세전 → 세후 10,120
        assertThat(actual).isEqualTo(10_120);

        // 총액에 그냥 곱하면 2배 넘게 부풀려진다 — 그 값이 나오면 안 된다.
        long inflated = Math.round(87_000L * 12 * 0.025 * (1 - TAX));
        assertThat(inflated).isEqualTo(22_081);
        assertThat(actual).isLessThan(inflated / 2);
    }

    /** 매달 넣는 것은 <b>같은 총액을 한 번에 넣는 것보다 늘 적다</b>. */
    @Test
    void 매달_넣기는_같은_총액_거치보다_이자가_적다() {
        long monthly = KeptMoneyParkingService.monthlyAfterTaxInterest(100_000, 3.0, 12, TAX);
        long lump = KeptMoneyParkingService.lumpAfterTaxInterest(1_200_000, 3.0, 12, TAX);

        assertThat(monthly).isLessThan(lump);
    }

    @Test
    void 한_달만_넣으면_붙을_이자가_없다() {
        // 월말에 넣고 바로 끝나므로 예치 기간이 0이다.
        assertThat(KeptMoneyParkingService.monthlyAfterTaxInterest(87_000, 2.5, 1, TAX)).isZero();
    }

    // ── 경계 ─────────────────────────────────────────────────────

    @Test
    void 금액이나_금리가_0이면_이자도_0이다() {
        assertThat(KeptMoneyParkingService.lumpAfterTaxInterest(0, 2.5, 12, TAX)).isZero();
        assertThat(KeptMoneyParkingService.lumpAfterTaxInterest(100_000, 0, 12, TAX)).isZero();
        assertThat(KeptMoneyParkingService.monthlyAfterTaxInterest(0, 2.5, 12, TAX)).isZero();
        assertThat(KeptMoneyParkingService.monthlyAfterTaxInterest(100_000, 0, 12, TAX)).isZero();
    }

    /** 음수 지킨 돈(취소·환불로 뒤집힌 달)이 이자를 만들어 내면 안 된다. */
    @Test
    void 음수는_이자를_만들지_않는다() {
        assertThat(KeptMoneyParkingService.lumpAfterTaxInterest(-50_000, 2.5, 12, TAX)).isZero();
        assertThat(KeptMoneyParkingService.monthlyAfterTaxInterest(-50_000, 2.5, 12, TAX)).isZero();
    }

    /** 세금을 빼먹으면 사용자가 받을 돈보다 많게 말하게 된다. */
    @Test
    void 세후가_세전보다_적다() {
        long afterTax = KeptMoneyParkingService.lumpAfterTaxInterest(1_000_000, 3.0, 12, TAX);
        long beforeTax = KeptMoneyParkingService.lumpAfterTaxInterest(1_000_000, 3.0, 12, 0.0);

        assertThat(afterTax).isEqualTo(25_380);
        assertThat(beforeTax).isEqualTo(30_000);
    }
}
