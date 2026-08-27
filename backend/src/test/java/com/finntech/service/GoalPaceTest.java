package com.finntech.service;

import com.finntech.domain.Enums;
import com.finntech.domain.PointEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>목표 페이스 — "매달 얼마" 와 "실제로 매달 얼마".</b>
 *
 * <h2>왜 시험이 필요한가</h2>
 *
 * <p>이 넷은 전부 <b>사람에게 숫자를 보여 주는</b> 계산이라, 틀려도 예외가 안 나고 화면에
 * 그럴듯하게 그려진다. 특히 두 자리가 위험하다.
 *
 * <ul>
 *   <li><b>이번 달을 평균에 넣으면</b> 매달 1일마다 평균이 바닥을 치고 말일에 회복한다.
 *       숫자가 널뛰는데 그 널뜀은 사실이 아니다.</li>
 *   <li><b>속도가 0 인데 날짜를 만들면</b> "2126년"이 나오고 화면은 그것을 사실처럼 그린다.
 *       모르는 것은 모른다고 해야 한다.</li>
 * </ul>
 */
class GoalPaceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 27, 12, 0);

    private static PointEvent deposit(long goalId, LocalDateTime at, long amount) {
        return new PointEvent(1L, Enums.PointEventType.DEPOSIT, BigDecimal.valueOf(amount),
                null, goalId, "참았어요", at, null);
    }

    // ── 매달 넣어야 하는 돈 ────────────────────────────────────────────

    @Test
    @DisplayName("기한을 달로 나눈다")
    void 매달_넣어야_하는_돈() {
        // 300만원 / 6개월(180일) = 50만원
        assertThat(PointService.monthlyRequired(BigDecimal.valueOf(3_000_000), 180))
                .isEqualByComparingTo("500000");
    }

    @Test
    @DisplayName("기한이 한 달보다 짧아도 한 달로 본다")
    void 최소_한_달() {
        // 0 으로 나눌 수 없고, '한 달 안에 다 모은다'보다 빡센 계획은 없다.
        assertThat(PointService.monthlyRequired(BigDecimal.valueOf(100_000), 3))
                .isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("목표액이 없으면 0")
    void 목표액이_없으면() {
        assertThat(PointService.monthlyRequired(null, 180)).isEqualByComparingTo("0");
        assertThat(PointService.monthlyRequired(BigDecimal.ZERO, 180)).isEqualByComparingTo("0");
        assertThat(PointService.monthlyRequired(BigDecimal.valueOf(100), 0)).isEqualByComparingTo("0");
    }

    // ── 실제로 매달 지킨 돈 ────────────────────────────────────────────

    @Test
    @DisplayName("완결된 달만 평균한다 — 이번 달은 안 센다")
    void 이번_달은_평균에서_뺀다() {
        List<PointEvent> ev = List.of(
                deposit(1, LocalDateTime.of(2026, 6, 10, 9, 0), 200_000),
                deposit(1, LocalDateTime.of(2026, 7, 10, 9, 0), 220_000),
                deposit(1, LocalDateTime.of(2026, 8, 2, 9, 0), 10_000));   // 이번 달 — 아직 안 끝났다

        // (20만 + 22만) / 2 = 21만. 이번 달 1만을 넣었으면 14.3만으로 주저앉는다.
        assertThat(PointService.monthlyAverageSaved(ev, NOW)).isEqualByComparingTo("210000");
    }

    @Test
    @DisplayName("완결된 달이 없으면 이번 달 실적을 쓴다")
    void 가입_첫_달() {
        List<PointEvent> ev = List.of(deposit(1, LocalDateTime.of(2026, 8, 5, 9, 0), 30_000));
        // 0 을 주면 "지금 속도로는 영원히 못 모은다"가 되어 더 틀린다.
        assertThat(PointService.monthlyAverageSaved(ev, NOW)).isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("기록이 없으면 0")
    void 기록이_없으면() {
        assertThat(PointService.monthlyAverageSaved(List.of(), NOW)).isEqualByComparingTo("0");
    }

    // ── 예상 달성일 ────────────────────────────────────────────────────

    @Test
    @DisplayName("남은 돈을 속도로 나눠 달을 더한다")
    void 예상_달성일() {
        // 남은 60만 / 월 20만 = 3개월 → 2026-11-27
        assertThat(PointService.projectedDate(BigDecimal.valueOf(1_000_000),
                BigDecimal.valueOf(400_000), BigDecimal.valueOf(200_000), NOW))
                .isEqualTo("2026-11-27");
    }

    @Test
    @DisplayName("속도가 0 이면 날짜를 만들지 않는다")
    void 속도가_없으면_모른다고_한다() {
        assertThat(PointService.projectedDate(BigDecimal.valueOf(1_000_000),
                BigDecimal.ZERO, BigDecimal.ZERO, NOW)).isNull();
        assertThat(PointService.projectedDate(BigDecimal.valueOf(1_000_000),
                BigDecimal.ZERO, null, NOW)).isNull();
    }

    @Test
    @DisplayName("이미 다 모았으면 오늘")
    void 이미_다_모았으면() {
        assertThat(PointService.projectedDate(BigDecimal.valueOf(100_000),
                BigDecimal.valueOf(120_000), BigDecimal.valueOf(10_000), NOW))
                .isEqualTo("2026-08-27");
    }

    @Test
    @DisplayName("백 년 뒤는 답이 아니라 잡음이다")
    void 너무_먼_날짜는_주지_않는다() {
        // 10억을 월 1만원으로 → 10만 개월. 형식만 맞는 답을 사실처럼 그리게 두지 않는다.
        assertThat(PointService.projectedDate(BigDecimal.valueOf(1_000_000_000),
                BigDecimal.ZERO, BigDecimal.valueOf(10_000), NOW)).isNull();
    }

    // ── 매달 쌓인 기록 ─────────────────────────────────────────────────

    @Test
    @DisplayName("이 목표 것만, 오래된 달이 앞이다")
    void 매달_쌓인_기록() {
        List<PointEvent> ev = List.of(
                deposit(1, LocalDateTime.of(2026, 7, 3, 9, 0), 10_000),
                deposit(2, LocalDateTime.of(2026, 7, 4, 9, 0), 99_000),   // 다른 목표
                deposit(1, LocalDateTime.of(2026, 7, 20, 9, 0), 5_000),
                deposit(1, LocalDateTime.of(2026, 6, 1, 9, 0), 7_000));

        List<PointService.MonthlySaving> h = PointService.monthlyHistory(ev, 1L);

        assertThat(h).extracting(PointService.MonthlySaving::month)
                .as("오래된 달이 앞이어야 그래프가 왼쪽에서 오른쪽으로 자란다")
                .containsExactly("2026-06", "2026-07");
        assertThat(h.get(1).amount()).as("같은 달은 합친다").isEqualByComparingTo("15000");
    }

    @Test
    @DisplayName("다른 목표의 기록은 섞이지 않는다")
    void 목표별로_가른다() {
        List<PointEvent> ev = List.of(deposit(2, LocalDateTime.of(2026, 7, 3, 9, 0), 10_000));
        assertThat(PointService.monthlyHistory(ev, 1L)).isEmpty();
    }
}
