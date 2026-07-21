package com.finntech;

import com.finntech.audit.AuditService;
import com.finntech.domain.AppUser;
import com.finntech.engine.AnalysisEngine;
import com.finntech.engine.AnalysisResult;
import com.finntech.repository.AuditLogRepository;
import com.finntech.seed.SeedGenerator;
import com.finntech.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.ActiveProfiles;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 기술 뼈대 통합 검증 — 시드 → 엔진 → 3서비스 → 감사로그가 실제로 연결되는지 확인한다.
 * 이 테스트가 통과하면 "모든 기술 시스템이 연결됐다"고 말할 수 있다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WalkingSkeletonTest {

    /** 고정 시각. 엔진이 now()를 직접 읽으면 재현성이 깨지므로 Clock을 주입한다. */
    static final LocalDateTime REF = LocalDateTime.of(2026, 7, 20, 12, 0, 0);

    @TestConfiguration
    static class FixedClockConfig {
        /** 이름을 다르게 두고 @Primary로 이긴다 — 프로덕션 빈 정의를 건드리지 않는다. */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(REF.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }
    }

    @Autowired SeedGenerator seedGenerator;
    @Autowired AnalysisEngine engine;
    @Autowired RecommendService recommendService;
    @Autowired ReportService reportService;
    @Autowired AlertService alertService;
    @Autowired ScoreService scoreService;
    @Autowired NarrativeService narrativeService;
    @Autowired AuditService auditService;
    @Autowired AuditLogRepository auditLogRepository;

    @PersistenceContext EntityManager em;
    @Autowired TransactionTemplate transactionTemplate;

    static Long userId;

    /** 카테고리 코드는 테스트가 정한다 — 엔진·생성기에 이름이 하드코딩돼 있지 않음을 증명한다. */
    static final Map<String, Double> MIX = Map.of(
            "CAT_A", 0.40, "CAT_B", 0.25, "CAT_C", 0.20, "CAT_D", 0.15);

    @Test @Order(1)
    @DisplayName("시드 생성 — 파라미터 기반, 6개월치")
    void seed() {
        AppUser user = seedGenerator.generate(new SeedGenerator.SeedSpec(
                "tester", new BigDecimal("3000000"), new BigDecimal("3000000"), 6,
                6, 30, MIX, 0.6, 0.2, 4, 42L), REF);
        seedGenerator.seedProducts(List.copyOf(MIX.keySet()));
        userId = user.getId();
        assertNotNull(userId);
    }

    @Test @Order(2)
    @DisplayName("엔진 — 과소비 판정, 장기 변동성, 단기 이탈이 모두 산출된다")
    void engineProducesAllIndicators() {
        AnalysisResult r = engine.analyze(userId, REF);

        assertTrue(r.totalSpend().signum() > 0, "총지출이 있어야 한다");
        assertEquals(4, r.categoryStats().size(), "카테고리 4개");
        assertFalse(r.categoriesBySpendDesc().isEmpty());
        assertTrue(r.longTermVolatilityIndex() >= 0.0);
        assertFalse(r.deviations().isEmpty(), "단기 이탈 후보가 산출되어야 한다");

        // 비중 40%인 CAT_A는 과소비 임계 30%를 넘는다
        assertTrue(r.overspendingCategories().contains("CAT_A"),
                "실제 과소비 카테고리: " + r.overspendingCategories());
    }

    @Test @Order(3)
    @DisplayName("재현성 — 같은 입력이면 항상 같은 출력 (원칙 3)")
    void reproducibility() {
        AnalysisResult a = engine.analyze(userId, REF);
        AnalysisResult b = engine.analyze(userId, REF);

        assertEquals(a.totalSpend(), b.totalSpend());
        assertEquals(a.overspendingCategories(), b.overspendingCategories());
        assertEquals(a.categoriesBySpendDesc(), b.categoriesBySpendDesc());
        assertEquals(a.longTermVolatilityIndex(), b.longTermVolatilityIndex(), 1e-12);
        assertEquals(a.deviations().size(), b.deviations().size());

        // 추천 순위도 100% 재현되어야 한다
        AppUser u = em.find(AppUser.class, userId);
        List<Long> first = recommendService.recommend(u, a).items().stream()
                .map(s -> s.product().getId()).toList();
        List<Long> second = recommendService.recommend(u, b).items().stream()
                .map(s -> s.product().getId()).toList();
        assertEquals(first, second, "같은 입력 → 같은 추천 순위");
    }

    @Test @Order(4)
    @DisplayName("추천 — Top3, 근거 필드, 게이팅이 동작한다")
    void recommendation() {
        AppUser u = em.find(AppUser.class, userId);
        AnalysisResult r = engine.analyze(userId, REF);
        RecommendService.Recommendations rec = recommendService.recommend(u, r);

        assertEquals(3, rec.items().size(), "Top3를 채워야 한다");
        for (RecommendService.Scored s : rec.items()) {
            assertTrue(s.totalScore() >= 0.0 && s.totalScore() <= 1.0, "점수는 0~1");
            assertTrue(s.periodFit() >= 0.0 && s.periodFit() <= 1.0);
        }
        // 목표기간 6개월 < 최소기간 12·24개월 상품은 게이팅으로 탈락해야 한다
        boolean anyLongProduct = rec.items().stream()
                .anyMatch(s -> s.product().getMinPeriodMonths() > u.getGoalMonths()
                        && s.gateReason() == null);
        assertFalse(anyLongProduct, "가입 불가 상품이 정상 통과하면 안 된다");
    }

    @Test @Order(5)
    @DisplayName("범용상품이 구조적으로 불리하지 않다 — 카테고리 배점 40%가 죽지 않음")
    void neutralCategoryFitPreventsStructuralBias() {
        AppUser u = em.find(AppUser.class, userId);
        AnalysisResult r = engine.analyze(userId, REF);
        RecommendService.Recommendations rec = recommendService.recommend(u, r);

        boolean anyNonCard = rec.items().stream()
                .anyMatch(s -> s.product().getTargetCategoryCode() == null);
        assertTrue(anyNonCard, "Top3가 캐시백 카드로만 채워지면 안 된다: "
                + rec.items().stream().map(s -> s.product().getName()).toList());
    }

    @Test @Order(6)
    @DisplayName("리포트 — 잘한소비/과한소비 분류 + 문장 생성(템플릿 폴백)")
    void report() {
        AnalysisResult r = engine.analyze(userId, REF);
        ReportService.ReportBody body = reportService.build(r);

        assertFalse(body.negative().isEmpty(), "과한 소비가 분류되어야 한다");
        assertEquals(4, body.positive().size() + body.negative().size());

        NarrativeService.Narrative n = narrativeService.summarizeReport(body, r);
        assertNotNull(n.text());
        assertFalse(n.text().isBlank());
        // API 키가 없으면 템플릿으로 폴백한다 — 시연이 죽지 않는다
        assertEquals("TEMPLATE", n.source());
    }

    @Test @Order(7)
    @DisplayName("건전성지수 — 0~100 범위, 등급 산출")
    void score() {
        AppUser u = em.find(AppUser.class, userId);
        ScoreService.ScoreResult s = scoreService.score(u, engine.analyze(userId, REF));

        assertTrue(s.score() >= 0 && s.score() <= 100, "점수 범위: " + s.score());
        assertTrue(List.of("A", "B", "C", "D").contains(s.grade()));
        assertTrue(s.savingsProgress() >= 0 && s.savingsProgress() <= 1);
        assertTrue(s.stability() >= 0 && s.stability() <= 1);
        assertTrue(s.plannedRatio() >= 0 && s.plannedRatio() <= 1);
    }

    @Test @Order(8)
    @DisplayName("FDS — 주입한 이상거래를 탐지하고, 경고가 도배되지 않는다")
    void fdsDetectsWithoutFlooding() {
        AnalysisResult r = engine.analyze(userId, REF);
        List<AlertService.Detection> detections = alertService.detect(r);

        int evaluated = r.deviations().size();
        assertTrue(evaluated > 0);

        // 캘리브레이션 목표: 100건 중 3~5건. 여기서는 "도배되지 않는다"만 확인한다
        // (정확한 계수는 페르소나 확정 후 재역산 — D-09/D-11).
        double rate = (double) detections.size() / evaluated;
        assertTrue(rate < 0.20,
                "경고율이 20%를 넘으면 데모에서 '다 잡는 거 아니냐'는 질문을 받는다. rate=" + rate
                        + " (" + detections.size() + "/" + evaluated + ")");

        for (AlertService.Detection d : detections) {
            assertFalse(d.matchedRules().isEmpty(), "룰 AND 결합 — 룰 없이 경고가 나오면 안 된다");
            assertTrue(d.deviation().exceedsThreshold(), "z 임계를 넘어야 한다");
            assertNotNull(d.explanation());
        }
    }

    /**
     * 아래 두 테스트에 {@code @Transactional}을 붙이면 안 된다.
     * 테스트 트랜잭션은 기본이 롤백이라 감사로그가 커밋되지 않고, 그러면 다음 테스트가
     * 검증할 대상 자체가 사라진다. 실제 시연도 커밋된 DB를 상대로 하므로 이쪽이 현실에 가깝다.
     */
    @Test @Order(9)
    @DisplayName("감사로그 — append/seal 후 검증 통과")
    void auditChainIsValid() {
        AnalysisResult r = engine.analyze(userId, REF);
        alertService.detectAndRecord(r, REF);

        AuditService.VerificationResult v = auditService.verify();
        assertTrue(v.valid(), "검증 실패: " + v.problems());
        assertTrue(v.entryCount() > 0, "감사로그가 기록되어야 한다");
        assertTrue(v.batchCount() > 0, "배치가 봉인되어야 한다");
    }

    @Test @Order(10)
    @DisplayName("변조 시연 — 페이로드를 바꾸면 어느 엔트리에서 깨지는지 지목한다")
    void tamperingIsDetected() {
        AuditService.VerificationResult before = auditService.verify();
        assertTrue(before.valid(), "변조 전에는 유효해야 한다: " + before.problems());
        assertTrue(before.entryCount() > 0, "검증할 감사로그가 있어야 한다");

        // 시연 각본: 운영자가 DB 행을 직접 UPDATE로 바꾼다.
        // 별도 트랜잭션으로 실제 커밋해야 "실물 변조"가 된다.
        Long targetSeq = 1L;
        int updated = transactionTemplate.execute(status ->
                em.createNativeQuery(
                        "update audit_log set payload_json = "
                                + "replace(payload_json, '\"userId\"', '\"userID\"') where seq = :seq")
                        .setParameter("seq", targetSeq)
                        .executeUpdate());
        assertEquals(1, updated, "변조 대상 행이 갱신되어야 한다");

        AuditService.VerificationResult after = auditService.verify();
        assertFalse(after.valid(), "변조가 탐지되어야 한다");
        assertEquals(targetSeq, after.firstBrokenSeq(), "깨진 지점을 정확히 지목해야 한다");
        assertFalse(after.problems().isEmpty());
    }
}
