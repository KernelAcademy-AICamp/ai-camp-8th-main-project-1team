package com.finntech;

import com.finntech.audit.AuditService;
import com.finntech.domain.AppUser;
import com.finntech.domain.Category;
import com.finntech.domain.Consumption;
import com.finntech.domain.Enums;
import com.finntech.engine.AnalysisEngine;
import com.finntech.repository.*;
import com.finntech.domain.Alert;
import com.finntech.domain.Report;
import com.finntech.domain.UserCard;
import com.finntech.domain.UserPayment;
import com.finntech.domain.UserSpendingOverride;
import com.finntech.service.PrivacyService;
import com.finntech.service.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 개인정보 처리방침이 <b>고지만 하고 구현되지 않은 약속</b>이 되지 않도록 검증한다 (문서 §5-3).
 * 방침 문안의 각 조항이 실제 코드 동작과 1:1로 대응하는지 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PrivacyFlowTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 20, 12, 0);

    @Autowired PrivacyService privacyService;
    @Autowired AppUserRepository userRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ConsumptionRepository consumptionRepository;
    @Autowired AuditService auditService;
    @Autowired AnalysisEngine engine;
    @Autowired ReportService reportService;
    @Autowired AlertRepository alertRepository;
    @Autowired ReportRepository reportRepository;
    @Autowired UserCardRepository userCardRepository;
    @Autowired UserPaymentRepository userPaymentRepository;
    @Autowired UserSpendingOverrideRepository overrideRepository;

    private AppUser user;
    private Category category;

    @BeforeEach
    void setUp() {
        alertRepository.deleteAll();
        reportRepository.deleteAll();
        consumptionRepository.deleteAll();
        user = userRepository.save(new AppUser(
                "privacy-" + System.nanoTime(), new BigDecimal("3000000"),
                new BigDecimal("1000000"), 6));
        category = categoryRepository.findByCode("PT_CAT")
                .orElseGet(() -> categoryRepository.save(new Category("PT_CAT", "PT_CAT")));
    }

    private Consumption input(LocalDateTime at) {
        return consumptionRepository.save(new Consumption(
                user.getId(), category, new BigDecimal("10000"), at, true,
                Enums.DataSource.USER_INPUT));
    }

    @Test
    @DisplayName("방침 1번 — 수집 항목이 4개를 넘지 않고, 식별정보 필드가 존재하지 않는다")
    void policyDeclaresOnlyFourFields() {
        PrivacyService.PrivacyPolicy p = privacyService.policy();
        assertEquals(7, p.clauses().size(), "7개 조항 전부 고지되어야 한다");

        String collected = p.clauses().get(0).body();
        for (String forbidden : List.of("실명", "계좌번호", "카드번호", "주민등록번호")) {
            assertTrue(collected.contains(forbidden),
                    "수집하지 않는다는 사실을 명시해야 한다: " + forbidden);
        }
        // 엔티티에 실제로 그런 필드가 없는지 — 문서와 스키마가 어긋나면 방침이 거짓말이 된다
        for (var f : Consumption.class.getDeclaredFields()) {
            String n = f.getName().toLowerCase();
            assertFalse(n.contains("name") || n.contains("account") || n.contains("card")
                            || n.contains("ssn") || n.contains("email") || n.contains("phone"),
                    "Consumption에 식별정보 필드가 있으면 안 된다: " + f.getName());
        }
        for (var f : AppUser.class.getDeclaredFields()) {
            String n = f.getName().toLowerCase();
            assertFalse(n.contains("realname") || n.contains("account") || n.contains("card")
                            || n.contains("ssn") || n.contains("email") || n.contains("phone"),
                    "AppUser에 식별정보 필드가 있으면 안 된다: " + f.getName());
        }
    }

    @Test
    @DisplayName("방침 3·4번 — 보유기간 초과분은 파기되고, 삭제 사실이 감사로그에 남는다")
    void retentionPurgeIsAuditedAndSelective() {
        input(NOW.minusDays(privacyService.getRetentionDays() + 5));   // 만료
        input(NOW.minusDays(1));                                       // 유효
        // 더미 시드는 개인정보가 아니므로 오래돼도 파기 대상이 아니다
        consumptionRepository.save(new Consumption(user.getId(), category,
                new BigDecimal("5000"), NOW.minusDays(365), true, Enums.DataSource.DUMMY_SEED));

        long before = auditService.verify().entryCount();
        PrivacyService.PurgeReport report = privacyService.purgeExpired(NOW);

        assertEquals(1, report.deletedCount(), "만료된 USER_INPUT 1건만 파기되어야 한다");
        assertEquals(2, consumptionRepository.findAllForUser(user.getId()).size(),
                "유효한 USER_INPUT과 DUMMY_SEED는 남아야 한다");

        AuditService.VerificationResult after = auditService.verify();
        assertTrue(after.entryCount() > before, "파기 사실이 감사로그에 기록되어야 한다");
        assertTrue(after.valid(), "감사로그는 여전히 유효해야 한다: " + after.problems());
    }

    @Test
    @DisplayName("방침 6번 — 정보주체는 자기 기록만 열람하고 삭제할 수 있다")
    void subjectRightsCoverOnlyOwnInput() {
        input(NOW.minusDays(1));
        input(NOW.minusDays(2));
        consumptionRepository.save(new Consumption(user.getId(), category,
                new BigDecimal("5000"), NOW.minusDays(3), true, Enums.DataSource.DUMMY_SEED));

        assertEquals(2, privacyService.exportUserData(user.getId()).size(),
                "열람 대상은 USER_INPUT만 — 더미는 개인정보가 아니다");

        int deleted = privacyService.eraseUserData(user.getId(), NOW);
        assertEquals(2, deleted);
        assertEquals(0, privacyService.exportUserData(user.getId()).size());
        assertEquals(1, consumptionRepository.findAllForUser(user.getId()).size(),
                "더미 시드는 삭제 대상이 아니다");
    }

    @Test
    @DisplayName("§13 W7-5b — 삭제 요청은 CI를 null로 만들고 마이데이터 연동물(카드·결제·투영·개인화)까지 지운다")
    void erasureNullsCiAndCascadesMydata() {
        privacyService.setConsent(user.getId(), true, NOW);
        Long uid = user.getId();
        user.setCi("test-ci-abc123");
        userRepository.save(user);
        userCardRepository.save(new UserCard(uid, "1111-2222-3333-4444", 9101L,
                "삼성 taptap O", "#1428A0", "삼성카드", 500000, 300000, 300000));
        userPaymentRepository.save(new UserPayment("p-erase-1", uid, "1111-2222-3333-4444", 9101L,
                NOW.minusDays(1), "온라인", "카페", 5000, "이디야커피", 0, "2088612340"));
        consumptionRepository.save(new Consumption(uid, category, new BigDecimal("5000"),
                NOW.minusDays(1), false, Enums.DataSource.MYDATA));
        overrideRepository.save(new UserSpendingOverride(uid, "카페", false, NOW));

        privacyService.eraseUserData(uid, NOW);

        assertNull(userRepository.findById(uid).orElseThrow().getCi(), "삭제 후 CI가 null이어야 한다");
        assertEquals(0, userCardRepository.findByUserIdOrderByIdAsc(uid).size(), "연동 카드 0");
        assertEquals(0, userPaymentRepository.findByUserIdOrderByPaymentDateDesc(uid).size(), "연동 결제 0");
        assertEquals(0, overrideRepository.findByUserId(uid).size(), "개인화 override 0");
        assertEquals(0, consumptionRepository.findAllForUser(uid).stream()
                .filter(c -> c.getSource() == Enums.DataSource.MYDATA).count(), "MYDATA 소비 투영 0");
    }

    @Test
    @DisplayName("동의를 철회하면 이미 수집된 기록이 즉시 파기된다")
    void withdrawingConsentErasesData() {
        privacyService.setConsent(user.getId(), true, NOW);
        input(NOW.minusDays(1));
        input(NOW.minusDays(2));
        assertEquals(2, privacyService.exportUserData(user.getId()).size());

        AppUser after = privacyService.setConsent(user.getId(), false, NOW);

        assertFalse(after.isConsentGiven());
        assertEquals(0, privacyService.exportUserData(user.getId()).size(),
                "철회 후에도 데이터가 남으면 방침 위반이다");
    }

    @Test
    @DisplayName("ESTIMATED 리포트는 캐시되지 않는다 — 캐시하면 '더 기록하면 정확해집니다'가 거짓말이 된다")
    void estimatedReportsAreNotCached() {
        privacyService.setConsent(user.getId(), true, NOW);
        input(NOW.minusDays(1));   // 1건뿐 → 임계치(30건/14일) 미달 → ESTIMATED

        var analysis = engine.analyze(user.getId(), NOW);
        assertEquals(Enums.DataSourceMode.ESTIMATED, analysis.dataSourceMode());
        assertNotNull(analysis.estimationReason(), "부족 사유를 안내해야 한다");

        var first = reportService.buildCached(user.getId(), "2026-07", analysis, NOW);
        assertNotNull(first);

        // 데이터가 늘면 결과가 바뀌어야 한다
        input(NOW.minusDays(2));
        var analysis2 = engine.analyze(user.getId(), NOW);
        var second = reportService.buildCached(user.getId(), "2026-07", analysis2, NOW);

        assertNotEquals(first.totalSpend(), second.totalSpend(),
                "ESTIMATED가 캐시되면 새 입력이 반영되지 않는다");
    }

    /** 잔재를 심는다: Alert는 amount·occurredAt·categoryCode를 자기 테이블에 복사해 갖는다. */
    private void seedResidues(java.time.LocalDateTime at) {
        Consumption c = input(at);
        alertRepository.save(new Alert(user.getId(), c.getId(), category.getCode(),
                new BigDecimal("380000"), at, 4.2, "NIGHT_HIGH_AMOUNT", NOW));
        reportRepository.save(new Report(user.getId(), "2026-07",
                "{\"totalSpend\":380000,\"negative\":[{\"categoryCode\":\"PT_CAT\",\"amount\":380000}]}", NOW));
    }

    @Test
    @DisplayName("삭제 요청은 Alert·Report 잔재까지 지운다 — Consumption만 지우면 개인정보가 남는다")
    void erasureRemovesAlertAndReportResidues() {
        privacyService.setConsent(user.getId(), true, NOW);
        seedResidues(NOW.minusDays(1));

        assertEquals(1, alertRepository.findByUserIdOrderByOccurredAtDescIdDesc(user.getId()).size());
        assertTrue(reportRepository.findByUserIdAndPeriod(user.getId(), "2026-07").isPresent());

        privacyService.eraseUserData(user.getId(), NOW);

        assertEquals(0, privacyService.exportUserData(user.getId()).size(), "소비내역");
        assertEquals(0, alertRepository.findByUserIdOrderByOccurredAtDescIdDesc(user.getId()).size(),
                "Alert에 amount·occurredAt·categoryCode가 남으면 '삭제했다'가 거짓말이 된다");
        assertTrue(reportRepository.findByUserIdAndPeriod(user.getId(), "2026-07").isEmpty(),
                "Report는 카테고리별·월별 지출 프로필을 담고 있다");
    }

    @Test
    @DisplayName("동의 철회도 Alert·Report 잔재까지 지운다")
    void withdrawalRemovesResidues() {
        privacyService.setConsent(user.getId(), true, NOW);
        seedResidues(NOW.minusDays(1));

        privacyService.setConsent(user.getId(), false, NOW);

        assertEquals(0, alertRepository.findByUserIdOrderByOccurredAtDescIdDesc(user.getId()).size());
        assertTrue(reportRepository.findByUserIdAndPeriod(user.getId(), "2026-07").isEmpty());
    }

    @Test
    @DisplayName("보유기간 파기도 만료 건에 딸린 Alert를 함께 지운다")
    void retentionPurgeRemovesLinkedAlerts() {
        java.time.LocalDateTime old = NOW.minusDays(privacyService.getRetentionDays() + 5);
        Consumption expired = input(old);
        alertRepository.save(new Alert(user.getId(), expired.getId(), category.getCode(),
                new BigDecimal("500000"), old, 5.0, "NIGHT_HIGH_AMOUNT", NOW));

        PrivacyService.PurgeReport r = privacyService.purgeExpired(NOW);

        assertEquals(1, r.deletedCount());
        assertEquals(0, alertRepository.findByUserIdOrderByOccurredAtDescIdDesc(user.getId()).size(),
                "파기했다고 해놓고 경고에 금액·시각이 남으면 안 된다");
    }
}
