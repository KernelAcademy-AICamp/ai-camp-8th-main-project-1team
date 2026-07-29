package com.finntech.engine;

import com.finntech.domain.Category;
import com.finntech.domain.Consumption;
import com.finntech.domain.Enums;
import com.finntech.repository.CategoryRepository;
import com.finntech.repository.ConsumptionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 카테고리별 관측 개월수 — 월평균의 <b>분모</b>가 카테고리마다 따로 세어지는지 본다.
 *
 * <p>예전에는 소비자 쪽에서 {@code monthlySpend().size()}(사용자가 <b>아무거나</b> 결제한 달의
 * 수)로 나눴다. 분자는 카테고리 하나의 총액인데 분모가 전체 기간이라, 최근 시작한 습관일수록
 * 심하게 과소평가됐다 — 지킴이 챌린지가 시작 직후 한도를 넘기는 원인이었다.
 *
 * <p>이 사실을 검증하는 테스트가 하나도 없었다. 그래서 회귀를 여기서 막는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class CategoryObservedMonthsTest {

    private static final Long USER_ID = 987_654L;

    @Autowired AnalysisEngine engine;
    @Autowired ConsumptionRepository consumptionRepository;
    @Autowired CategoryRepository categoryRepository;

    private Category category(String code) {
        return categoryRepository.findByCode(code)
                .orElseGet(() -> categoryRepository.save(new Category(code, code)));
    }

    private void spend(Category cat, int year, int month, int day, long amount) {
        consumptionRepository.save(new Consumption(USER_ID, cat, BigDecimal.valueOf(amount),
                LocalDateTime.of(year, month, day, 19, 0), false, Enums.DataSource.DUMMY_SEED));
    }

    @Test
    @DisplayName("최근에 시작한 습관은 그 카테고리가 등장한 달로만 나눈다 (전체 관측 개월수가 아니라)")
    void countsMonthsPerCategoryNotAcrossUser() {
        Category longRunning = category("OBS_TRANSPORT");
        Category justStarted = category("OBS_DELIVERY");

        // 교통은 1~6월 내내, 배달은 6월에 처음 시작해 30만원.
        for (int month = 1; month <= 6; month++) {
            spend(longRunning, 2026, month, 5, 50_000);
        }
        spend(justStarted, 2026, 6, 10, 300_000);

        AnalysisResult result = engine.analyze(USER_ID, LocalDateTime.of(2026, 7, 1, 0, 0));

        AnalysisResult.CategoryStat transport = result.categoryStats().get("OBS_TRANSPORT");
        AnalysisResult.CategoryStat delivery = result.categoryStats().get("OBS_DELIVERY");

        assertEquals(6, transport.observedMonths(), "교통은 6개월 관측");
        assertEquals(1, delivery.observedMonths(), "배달은 6월 한 달만 관측");

        // 예전 방식이면 300,000 ÷ 6 = 50,000원으로 잡혀 실제 습관의 1/6이 된다.
        assertEquals(300_000L, delivery.monthlyAmount().longValue(),
                "새로 시작한 습관은 그 달 지출이 그대로 월평균이어야 한다");
        assertEquals(50_000L, transport.monthlyAmount().longValue());
    }

    @Test
    @DisplayName("관측 달의 길이가 달라도 같은 습관이면 같은 30일 환산액이 나온다")
    void convertsToPeriodUsingActualMonthLengths() {
        Category february = category("OBS_FEB_ONLY");
        Category july = category("OBS_JUL_ONLY");

        // 하루 10,000원을 쓰는 같은 습관. 2월(28일)과 7월(31일)에 각각 관측한다.
        for (int day = 1; day <= 28; day++) spend(february, 2026, 2, day, 10_000);
        for (int day = 1; day <= 31; day++) spend(july, 2026, 7, day, 10_000);

        AnalysisResult result = engine.analyze(USER_ID, LocalDateTime.of(2026, 8, 1, 0, 0));

        AnalysisResult.CategoryStat feb = result.categoryStats().get("OBS_FEB_ONLY");
        AnalysisResult.CategoryStat jul = result.categoryStats().get("OBS_JUL_ONLY");

        assertEquals(28, feb.observedMonthDays());
        assertEquals(31, jul.observedMonthDays());

        // 월평균은 달 길이를 물려받아 서로 다르다 — 280,000 vs 310,000.
        assertEquals(280_000L, feb.monthlyAmount().longValue());
        assertEquals(310_000L, jul.monthlyAmount().longValue());

        // 30일로 환산하면 같아진다. 예전에는 월평균을 그대로 챌린지 한도로 써서
        // 같은 습관인데도 관측한 달에 따라 예산이 10.7% 벌어지고 정산 등급까지 갈렸다.
        assertEquals(300_000L, feb.amountOver(30).longValue());
        assertEquals(300_000L, jul.amountOver(30).longValue());
    }
}
