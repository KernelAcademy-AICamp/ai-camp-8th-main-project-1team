package com.finntech.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 충동예산 절약통의 자동 성장(50/30/20 + 누적)만 순수 함수로 검증한다(레포·시간 실호출 없음). */
class ImpulseSaverServiceTest {

    private static final BigDecimal Q = new BigDecimal("1000");   // 하루 할당량
    private static final LocalDate D1 = LocalDate.of(2026, 7, 21);

    @Test
    void nextFraction은_50_80_100에서_멈춘다() {
        assertThat(ImpulseSaverService.nextFraction(0.0)).isEqualTo(0.5);
        assertThat(ImpulseSaverService.nextFraction(0.5)).isEqualTo(0.8);
        assertThat(ImpulseSaverService.nextFraction(0.8)).isEqualTo(1.0);
        assertThat(ImpulseSaverService.nextFraction(1.0)).isEqualTo(1.0);
    }

    @Test
    void 최초_방문은_하루할당의_절반() {
        assertThat(ImpulseSaverService.accrueDelta(null, 0.0, D1, Q))
                .isEqualByComparingTo("500");
    }

    @Test
    void 같은_날_재방문은_30_그다음_20씩만() {
        assertThat(ImpulseSaverService.accrueDelta(D1, 0.5, D1, Q)).isEqualByComparingTo("300"); // 50→80
        assertThat(ImpulseSaverService.accrueDelta(D1, 0.8, D1, Q)).isEqualByComparingTo("200"); // 80→100
        assertThat(ImpulseSaverService.accrueDelta(D1, 1.0, D1, Q)).isEqualByComparingTo("0");   // 이미 100
    }

    @Test
    void 다음날은_어제_남은몫_더하기_오늘_첫단계() {
        // 어제 50%만 드러냈으면: 어제 남은 50% + 오늘 첫 50% = 하루치(1000)
        assertThat(ImpulseSaverService.accrueDelta(D1, 0.5, D1.plusDays(1), Q))
                .isEqualByComparingTo("1000");
    }

    @Test
    void 며칠_비우면_비운_날의_전체할당량이_합산된다() {
        // 3일 뒤 방문(21→24): 어제 남은 50% + 완전히 비운 2일(100%*2) + 오늘 50% = 3.0*1000
        assertThat(ImpulseSaverService.accrueDelta(D1, 0.5, D1.plusDays(3), Q))
                .isEqualByComparingTo("3000");
    }

    @Test
    void 예산이_0이면_성장하지_않는다() {
        assertThat(ImpulseSaverService.accrueDelta(null, 0.0, D1, BigDecimal.ZERO))
                .isEqualByComparingTo("0");
    }
}
