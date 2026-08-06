package com.finntech.engine;

import com.finntech.domain.Category;
import com.finntech.domain.Consumption;
import com.finntech.domain.Enums;
import com.finntech.repository.CategoryRepository;
import com.finntech.repository.ConsumptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>분석 창(window)</b> — 온보딩이 보는 금액과 사용자가 훑을 수 있는 결제 목록이 같은 구간이어야 한다.
 *
 * <p>2026-07-31 이전에는 화면마다 기준이 달랐다. 온보딩1은 최근 90일을 월로 환산했고,
 * 온보딩2는 전 기간을 카테고리별 관측 개월수로 나눴으며, 서버의 챌린지 기준은 또 그 둘과 달랐다.
 * 같은 '취미/여가'가 691,150원과 745,118원으로 갈렸고, 사용자는 어느 쪽이 진짜인지 알 수 없었다.
 *
 * <p>그래서 창을 도입했다. 이 테스트는 <b>창이 실제로 자른다</b>는 것과 <b>전 기간 동작이
 * 그대로 남아 있다</b>는 것을 함께 못박는다 — 리포트·점수·취향은 여전히 전 기간을 본다.
 */
@SpringBootTest
@ActiveProfiles("test")   // 인메모리 H2 — 파일 DB 를 쓰면 낡은 스키마가 남는다
@Transactional
class AnalysisWindowTest {

    private static final LocalDateTime REF = LocalDateTime.of(2026, 8, 3, 14, 0);
    private static final Long USER = 987_654L;

    @Autowired AnalysisEngine engine;
    @Autowired ConsumptionRepository consumptionRepository;
    @Autowired CategoryRepository categoryRepository;

    private Category cat;

    @BeforeEach
    void seed() {
        cat = categoryRepository.findByCode("WTEST_FOOD")
                .orElseGet(() -> categoryRepository.save(new Category("WTEST_FOOD", "식비")));
        consumptionRepository.deleteByUserIdAndSource(USER, Enums.DataSource.DUMMY_SEED);
        // 창(7/4 14:00 ~ 8/3 14:00) 안 3건 = 30,000원
        for (int d : new int[]{5, 20, 30}) {
            consumptionRepository.save(new Consumption(USER, cat, new BigDecimal("10000"),
                    LocalDateTime.of(2026, 7, d, 12, 0), false, Enums.DataSource.DUMMY_SEED));
        }
        // 창 밖 2건 = 20,000원 (5월·6월)
        consumptionRepository.save(new Consumption(USER, cat, new BigDecimal("10000"),
                LocalDateTime.of(2026, 5, 10, 12, 0), false, Enums.DataSource.DUMMY_SEED));
        consumptionRepository.save(new Consumption(USER, cat, new BigDecimal("10000"),
                LocalDateTime.of(2026, 6, 10, 12, 0), false, Enums.DataSource.DUMMY_SEED));
    }

    @Test
    @DisplayName("창을 주면 그 구간의 결제만 센다 — 화면 금액과 펼칠 목록이 같아진다")
    void 창은_구간을_자른다() {
        AnalysisResult r = engine.analyze(USER, REF, 30);
        AnalysisResult.CategoryStat s = r.categoryStats().get("WTEST_FOOD");
        assertThat(s).as("창 안에 결제가 있으니 통계가 나와야 한다").isNotNull();
        assertThat(s.totalAmount()).as("창 안 3건 합계").isEqualByComparingTo("30000");
        assertThat(s.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("창을 안 주면 전 기간이다 — 리포트·점수·취향은 그대로 돌아야 한다")
    void 창이_없으면_전기간이다() {
        AnalysisResult r = engine.analyze(USER, REF);
        AnalysisResult.CategoryStat s = r.categoryStats().get("WTEST_FOOD");
        assertThat(s.totalAmount()).as("전 5건 합계").isEqualByComparingTo("50000");
        assertThat(s.count()).isEqualTo(5);
    }

    @Test
    @DisplayName("창이 비면 '내역 없음'이지 예외가 아니다 — 신규 사용자가 화면을 못 열면 안 된다")
    void 창이_비어도_죽지_않는다() {
        // 씨앗보다 한참 전으로 기준을 옮기면 창 안이 비어 있다.
        AnalysisResult r = engine.analyze(USER, LocalDateTime.of(2026, 1, 5, 14, 0), 30);
        assertThat(r.categoryStats()).isEmpty();
    }
}
