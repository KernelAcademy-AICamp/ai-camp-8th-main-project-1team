package com.finntech.engine;

import com.finntech.config.AnalysisProperties;
import com.finntech.domain.UserPayment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 절약 후보 선정(⑤) 검증 — 3등급 규칙·절감액 산정·정렬·보호 제외. */
class CutCandidateSelectorTest {

    private final AnalysisProperties.CutCandidate cut = new AnalysisProperties.CutCandidate();

    /**
     * 재량성 스텁 — 등급이 이름 목록이 아니라 <b>재량성</b>으로 정해진다.
     * 목록 시절에는 카테고리 체계를 바꾸면 하나도 안 겹쳐 후보가 통째로 사라졌다.
     */
    private static double disc(String mid) {
        return switch (mid) {
            case "약국", "공과금" -> 0.10;      // 보호(0.30 미만)
            case "한식" -> 0.45;               // 최적화(중앙값 초과분만)
            default -> 0.70;                   // 제거가능(0.55 이상)
        };
    }

    private static UserPayment tx(String cat2, int amount) {
        return new UserPayment(cat2 + "|" + amount, 1L, "S1", 9001L,
                LocalDateTime.of(2026, 2, 10, 12, 0), "생활", cat2, amount, "가맹점", 0, "1234567890");
    }

    /** 창 길이 = 한 달(평균 30.436875일). 환산비가 1이라 창 합계가 그대로 월 금액이 된다. */
    private static final int ONE_MONTH_WINDOW = 30;

    /** 30일 창에 담긴 금액을 월 환산했을 때의 기대값 — 프로덕션 상수와 같은 비율을 쓴다. */
    private static long monthly(long windowTotal) {
        return Math.round(windowTotal * 30.436875 / ONE_MONTH_WINDOW);
    }

    @Test
    @DisplayName("제거가능=전액·최적화가능=중앙값초과분, 절감액 순 정렬, 보호·미분류 제외")
    void ranksBySavingWithTierRules() {
        List<UserPayment> w = List.of(
                tx("카페", 5000), tx("카페", 5000), tx("카페", 5000),     // 제거가능 15,000
                tx("배달", 10000), tx("배달", 10000),                    // 제거가능 20,000
                tx("한식", 8000), tx("한식", 12000), tx("한식", 20000),   // 최적화, median 12,000 → 초과분 8,000
                tx("공과금", 50000),                                     // 보호 → 제외
                // 모르는 카테고리는 재량성 기본값(0.5)이라 최적화가능으로 들어온다.
                // 목록 시절에는 "미분류 → 후보 아님"이었는데, 그 보수성이 체계를 바꿀 때
                // 후보를 전멸시킨 원인이기도 했다. 이제는 판단을 못 하겠으면 중간으로 둔다.
                tx("잡화미분류", 3000));

        List<CutCandidate> c = CutCandidateSelector.selectFrom(w, cut, ONE_MONTH_WINDOW, CutCandidateSelectorTest::disc);

        assertEquals(4, c.size(), c.toString());
        assertEquals("배달", c.get(0).category2());
        assertEquals(CutCandidate.Type.REMOVABLE, c.get(0).type());
        assertEquals(monthly(20000), c.get(0).estimatedSaving());
        assertEquals("카페", c.get(1).category2());
        assertEquals(monthly(15000), c.get(1).estimatedSaving());

        CutCandidate han = c.get(2);
        assertEquals("한식", han.category2());
        assertEquals(CutCandidate.Type.OPTIMIZABLE, han.type());
        assertEquals(monthly(8000), han.estimatedSaving());
        assertEquals(monthly(40000), han.monthlySpend());

        assertTrue(c.stream().noneMatch(x -> x.category2().equals("공과금")),
                "재량성 0.10 — 보호 카테고리는 후보에서 원천 제외");
    }

    @Test
    @DisplayName("창 길이가 달라도 같은 습관이면 같은 월 환산액이 나온다 (온보딩 1↔2 금액 불일치 회귀)")
    void convertsWindowTotalToMonthlyRegardlessOfWindowLength() {
        // 같은 하루 1만원 습관을 30일 창과 90일 창으로 각각 본다.
        List<UserPayment> oneMonth = java.util.stream.IntStream.range(0, 30)
                .mapToObj(i -> tx("배달", 10000)).toList();
        List<UserPayment> threeMonths = java.util.stream.IntStream.range(0, 90)
                .mapToObj(i -> tx("배달", 10000)).toList();

        long from30 = CutCandidateSelector.selectFrom(oneMonth, cut, 30, CutCandidateSelectorTest::disc).get(0).monthlySpend();
        long from90 = CutCandidateSelector.selectFrom(threeMonths, cut, 90, CutCandidateSelectorTest::disc).get(0).monthlySpend();

        assertEquals(from30, from90, "창 길이가 3배여도 월 환산액은 같아야 한다");
        assertEquals(monthly(300000), from30);
    }
}
