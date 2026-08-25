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
    @Autowired SpendingLedgerRepository spendingLedgerRepository;

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

    /**
     * <b>화면에 실려 나가는 정본이 저장소의 정본과 같은 글자인지</b> 지킨다.
     *
     * <p>화면은 {@code legal/} 를 직접 읽지 못한다. 운영이 도커로 도는데 백엔드 이미지의 빌드
     * 맥락이 {@code ./backend} 라 {@code ../legal} 이 이미지 안으로 안 들어오기 때문이다
     * (맥락을 저장소 루트로 넓히면 4.7GB 를 도커 데몬에 보내게 된다). 그래서 정본을
     * {@code backend/src/main/resources/legal/} 에 <b>함께 싣는다.</b>
     *
     * <p>사본이 하나 생겼으니 <b>갈라질 수 있다.</b> 그 갈라짐을 사람 눈에 맡기지 않는다 —
     * 한 글자라도 다르면 여기서 깨진다. 예전에 손으로 옮겨 적던 요약이 정확히 그렇게 갈라졌다
     * (2026-08-10: 정본은 이름·CI·계좌번호를 수집한다고 적는데 화면은 "수집하지 않습니다"라고
     * 말하고 있었다 — 이용자가 운영사가 쓰지도 않은 방침에 동의하는 셈이었다).
     */
    @Test
    @DisplayName("이미지에 실린 법무 정본이 저장소 정본과 한 글자도 다르지 않다")
    void shippedLegalDocumentsMatchTheCanonicalFiles() throws java.io.IOException {
        // 시험의 작업 디렉터리는 `backend/` 다(OneDoorTest 와 같은 관습).
        for (String name : List.of("privacy-policy", "terms-of-service",
                "consent-credit-info", "consent-unique-id", "consent-marketing")) {
            String canonical = java.nio.file.Files.readString(
                    java.nio.file.Path.of("../legal/" + name + ".md"));
            String shipped = java.nio.file.Files.readString(
                    java.nio.file.Path.of("src/main/resources/legal/" + name + ".md"));
            assertEquals(canonical, shipped,
                    name + ".md 가 정본과 다르다 — `cp legal/" + name + ".md "
                            + "backend/src/main/resources/legal/` 로 맞춰라");
        }
    }

    /**
     * <b>정본이 절째로 화면까지 간다</b> — 요약해서 덜어내지 않는다.
     *
     * <p>가입 화면의 '상세보기'가 읽는 것이 이 응답이다. 조문이 하나라도 빠지면 이용자는 못 본
     * 조문에 동의하게 된다.
     */
    @Test
    @DisplayName("방침 머리글+7절·약관 9조가 빠짐없이 내려간다")
    void everySectionReachesTheScreen() {
        PrivacyService.PrivacyPolicy p = privacyService.policy();
        assertEquals("모아 서비스 개인정보처리방침", p.title());
        // 첫 덩이는 표제 없는 머리글("운영사는 …을 준수하며")이다. 이걸 버리면 화면에서 통째로
        // 사라지므로 절로 싣는다 — 그래서 7절 + 머리글 = 8이다.
        assertEquals(8, p.clauses().size(), "머리글 한 덩이와 1~7절");
        assertEquals("", p.clauses().get(0).title(), "머리글에는 표제가 없다");
        assertTrue(p.clauses().get(1).title().startsWith("1."), "절 번호가 정본 그대로여야 한다");

        PrivacyService.Terms t = privacyService.terms();
        assertEquals("모아 서비스 이용약관", t.title());
        assertEquals(9, t.clauses().size(), "약관은 제1조~제9조다");
        assertTrue(t.clauses().get(0).title().startsWith("제1조"), "장이 아니라 조로 쪼개야 한다");

        // 빈 절이 화면에 뜨면 안 된다 — 약관의 `## 제1장` 같은 묶음 표제가 그대로 새면 그렇게 된다.
        for (var c : t.clauses()) assertFalse(c.body().isBlank(), "빈 조문: " + c.title());
        for (var c : p.clauses()) assertFalse(c.body().isBlank(), "빈 절: " + c.title());

        // 개정으로 새로 생긴 위탁 고지 — 사업자번호·가맹점명이 밖으로 나간다는 사실이다.
        String all = p.clauses().stream().map(PrivacyService.Clause::body)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(all.contains("가맹점명") && all.contains("위탁"),
                "가맹점명·사업자등록번호의 외부 위탁 고지가 화면까지 가야 한다");
    }

    /**
     * <b>동의 항목 셋이 각자 제 문서를 편다</b> — 그리고 그 문서에 <b>받은 문장만</b> 들어 있다.
     *
     * <p>예전에는 셋 다 개인정보 처리방침을 폈는데, 그 방침에는 고유식별정보도 마케팅 수신도
     * 한 번도 안 나온다. 동의 전에 그 내용을 읽게 하려고 만든 '상세보기'가 자기 얘기 없는
     * 문서를 펴고 있었다.
     */
    @Test
    @DisplayName("동의 문서 셋이 제 내용을 펴고, 덧붙은 문장이 없다")
    void consentDocumentsCarryOnlyWhatWasGiven() {
        var credit = privacyService.consent("credit-info");
        assertEquals("개인(신용)정보 수집·이용 동의", credit.title());
        assertTrue(credit.clauses().get(0).body().startsWith("수집·이용 항목"));
        assertTrue(credit.clauses().get(0).body().contains("내외국인 정보"));

        var uniqueId = privacyService.consent("unique-id");
        assertEquals("고유식별정보 처리 동의", uniqueId.title());
        assertTrue(uniqueId.clauses().get(0).body().contains("주민등록번호, 여권번호, 운전면허번호, 외국인등록번호"));

        var marketing = privacyService.consent("marketing");
        assertEquals("지킴이 알림, 혜택 수신", marketing.title());
        assertTrue(marketing.clauses().get(0).body().contains("마이페이지 설정"));

        // **덧붙이지 않는다.** 주석·해설 한 줄도 붙이지 않기로 한 문서다(사용자 지시 2026-08-11).
        // `notice` 가 비어 있어야 화면 아래 회색 줄이 아예 안 뜬다.
        for (var d : List.of(credit, uniqueId, marketing,
                privacyService.policy())) {
            assertEquals("", d.notice(), "정본 밖의 문장을 붙이면 안 된다");
        }
        assertEquals("", privacyService.terms().notice());
    }

    /**
     * <b>오늘의 스키마가 실제로 무엇을 들고 있는지</b> 지킨다 — 방침이 허용하는 범위와는 별개다.
     *
     * <p>새 정본은 이름·휴대폰번호·계좌번호까지 수집할 수 있다고 적지만, <b>지금 구현은 그것들을
     * 저장하지 않는다.</b> 닉네임과 가상 CI, 출생연도만 든다. 그 상태를 못 박아 두어, 식별정보
     * 칸이 <b>모르는 사이에</b> 생기는 것을 막는다. 일부러 넣는 날에는 이 시험을 같이 고치면 된다.
     */
    @Test
    @DisplayName("소비·사용자 엔티티에 식별정보 칸이 슬그머니 생기지 않는다")
    void entitiesHoldNoIdentifiers() {
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
                NOW.minusDays(1), "온라인", "카페", 5000, "이디야커피", "2088612340"));
        consumptionRepository.save(new Consumption(uid, category, new BigDecimal("5000"),
                NOW.minusDays(1), false, Enums.DataSource.MYDATA));
        overrideRepository.save(new UserSpendingOverride(uid, "카페", false, NOW));
        // 정리된 소비 원장(V34)은 위 결제의 **사본**이다 — 원본만 지우면 개인정보가 그대로 남는다.
        spendingLedgerRepository.save(new com.finntech.domain.SpendingLedger("p-erase-1",
                new com.finntech.domain.SpendingLedger.Facts(uid, "2026-07",
                        NOW.minusDays(1), NOW.minusDays(1).toLocalDate(), 19, 6, "저녁", "REAL",
                        "2088612340", false, "이디야커피", "이디야커피", null, "BIZ:2088612340",
                        5000, "552301", null, "카페", "DICT", null, null, "커피", "NAME"),
                NOW));

        privacyService.eraseUserData(uid, NOW);

        assertNull(userRepository.findById(uid).orElseThrow().getCi(), "삭제 후 CI가 null이어야 한다");
        assertEquals(0, userCardRepository.findByUserIdOrderByIdAsc(uid).size(), "연동 카드 0");
        assertEquals(0, userPaymentRepository.findByUserIdOrderByPaymentDateDesc(uid).size(), "연동 결제 0");
        assertEquals(0, overrideRepository.findByUserId(uid).size(), "개인화 override 0");
        assertEquals(0, spendingLedgerRepository.countByUserId(uid), "정리된 소비 원장 0");
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
