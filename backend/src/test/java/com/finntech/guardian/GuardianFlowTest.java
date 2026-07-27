package com.finntech.guardian;

import com.finntech.domain.AppUser;
import com.finntech.domain.Category;
import com.finntech.domain.Consumption;
import com.finntech.domain.Enums;
import com.finntech.guardian.domain.*;
import com.finntech.guardian.domain.GuardianEnums.*;
import com.finntech.guardian.repository.*;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.CategoryRepository;
import com.finntech.repository.ConsumptionRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 지킴이 엔드투엔드 — 챌린지 생성 → 거래 수신 → 되돌리기 → 새벽 배치가 실제로 이어지는지 본다.
 *
 * <p>순수 함수는 {@code GuardianRulesTest}가 검증한다. 여기서는 <b>원장이 실제로 움직이는지</b>와
 * 침묵이 기록되는지, 되돌리기가 한도를 되돌리는지를 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GuardianFlowTest {

    /** 고정 시각 — 오후 2시(야간 침묵에 걸리지 않는 시간대). */
    static final LocalDateTime REF = LocalDateTime.of(2026, 8, 3, 14, 0, 0);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock guardianFixedClock() {
            return Clock.fixed(REF.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }
    }

    @Autowired GuardianService guardianService;
    @Autowired GuardianBatchService batchService;
    @Autowired GuardianRewardService rewardService;
    @Autowired GuardianClock clock;
    @Autowired GuardianChallengeRepository challengeRepository;
    @Autowired GuardianTransactionRepository txRepository;
    @Autowired GuardianNotificationRepository notificationRepository;
    @Autowired DailyVerdictRepository verdictRepository;
    @Autowired AppUserRepository userRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ConsumptionRepository consumptionRepository;

    static Long userId;
    static Long challengeId;

    /**
     * 배달 소비 이력을 심는다 — 기준 지출은 이 이력에서 나온다.
     * 3개월에 걸쳐 750,000원 = 월평균 250,000원, 건당 25,000원.
     */
    @BeforeEach
    void seedOnce() {
        if (userId != null) return;
        AppUser user = userRepository.save(new AppUser(
                "지킴이테스트", new BigDecimal("3000000"), new BigDecimal("1000000"), 12));
        userId = user.getId();

        Category delivery = categoryRepository.findByCode("GTEST_DELIVERY")
                .orElseGet(() -> categoryRepository.save(new Category("GTEST_DELIVERY", "배달")));
        Category cafe = categoryRepository.findByCode("GTEST_CAFE")
                .orElseGet(() -> categoryRepository.save(new Category("GTEST_CAFE", "카페")));

        for (int month = 5; month <= 7; month++) {
            for (int i = 0; i < 10; i++) {
                consumptionRepository.save(new Consumption(userId, delivery, new BigDecimal("25000"),
                        LocalDateTime.of(2026, month, i + 1, 19, 0), false, Enums.DataSource.DUMMY_SEED));
            }
            consumptionRepository.save(new Consumption(userId, cafe, new BigDecimal("5000"),
                    LocalDateTime.of(2026, month, 2, 15, 0), false, Enums.DataSource.DUMMY_SEED));
        }
    }

    @Test
    @Order(1)
    @DisplayName("챌린지 생성 — 기준 지출·한도·버퍼가 기존 분석 결과에서 파생된다")
    void createChallenge() {
        GuardianChallenge ch = guardianService.createChallenge(userId,
                List.of("GTEST_DELIVERY"), List.of(), 100_000L, "에어팟", 179_000L, 30);
        challengeId = ch.getId();

        assertEquals(250_000L, ch.getBaselineAmount(), "월평균 250,000원");
        assertEquals(100_000L, ch.getTargetSaving());
        assertEquals(150_000L, ch.getChallengeCap(), "한도 = 기준 지출 − 지킬 돈");
        assertEquals(0.167, ch.getBufferRatio(), 1e-9, "버퍼 = 25,000/150,000 반올림");
        assertEquals(30, ch.getDaysTotal());
        assertEquals(ChallengeState.ACTIVE, ch.getState());
    }

    @Test
    @Order(2)
    @DisplayName("사용자당 진행 중인 챌린지는 하나뿐")
    void onlyOneRunningChallenge() {
        assertThrows(ResponseStatusException.class, () -> guardianService.createChallenge(
                userId, List.of("GTEST_DELIVERY"), List.of(), 100_000L, null, null, 30));
    }

    @Test
    @Order(3)
    @DisplayName("첫 결제 — 낙관적으로 집계하고 24시간 되돌리기를 준다")
    void firstTransactionIsCountedOptimistically() {
        GuardianService.IngestResult r = guardianService.ingest(userId, new GuardianService.IngestCommand(
                REF, "우아한형제들", "배달의민족", 32_000L, "5812",
                "GTEST_DELIVERY", 0.94, TxType.EXPENSE, true, null));

        assertEquals(TxState.COUNTED, r.transaction().getState());
        assertEquals(REF.plusHours(24), r.transaction().getUndoDeadline());
        assertEquals(32_000L, r.snapshot().spentAmount());
        assertEquals(118_000L, r.snapshot().remainingCap());
        assertEquals(100_000L, r.snapshot().securedSaving(), "아직 지킬 돈은 온전하다");

        assertNotNull(r.notification());
        assertEquals("C1", r.notification().getCaseId());
        assertEquals(PhrasingMode.TENTATIVE, r.notification().getPhrasingMode(),
                "되돌릴 수 있는 결제라 조건부 화법이어야 한다");
        assertTrue(r.notification().getBody().contains("118,000"), "이미 계산된 숫자가 문장에 들어간다");
    }

    @Test
    @Order(4)
    @DisplayName("무관 카테고리는 원장에서 제외하고 침묵을 기록한다 — 침묵도 하나의 결정이다")
    void unrelatedCategoryIsSilentButLogged() {
        long before = notificationRepository.count();
        GuardianService.IngestResult r = guardianService.ingest(userId, new GuardianService.IngestCommand(
                REF, "스타벅스", "스타벅스", 5_500L, null,
                "GTEST_CAFE", 0.95, TxType.EXPENSE, true, null));

        assertEquals(TxState.EXCLUDED, r.transaction().getState());
        assertEquals(32_000L, r.snapshot().spentAmount(), "무관 카테고리는 한도에 영향을 주지 않는다");
        assertEquals(DeliveryKind.SILENT, r.notification().getDelivery());
        assertEquals("C4", r.notification().getCaseId());
        assertEquals(before + 1, notificationRepository.count(), "침묵도 로그로 남는다");
        assertTrue(guardianService.notifications(userId).stream()
                .noneMatch(n -> n.getDelivery() == DeliveryKind.SILENT), "목록에는 침묵이 안 나온다");
    }

    @Test
    @Order(5)
    @DisplayName("분류 신뢰도가 임계 미만이면 집계하지 않고 되묻는다")
    void lowConfidenceIsHeldAndAsked() {
        GuardianService.IngestResult r = guardianService.ingest(userId, new GuardianService.IngestCommand(
                REF, "미상 가맹점", null, 21_000L, null,
                null, null, TxType.EXPENSE, true, null));

        assertEquals(TxState.PENDING_CATEGORY, r.transaction().getState());
        assertEquals(32_000L, r.snapshot().spentAmount(), "분류 전에는 판정할 수 없다");
        assertEquals("C7", r.notification().getCaseId());
        assertEquals(Tone.NEUTRAL_ASK, r.notification().getTone());

        // 사용자가 분류를 달아주면 그때 집계된다
        GuardianService.IngestResult after = guardianService.classifyPending(
                userId, r.transaction().getId(), "GTEST_DELIVERY", 1.0);
        assertEquals(TxState.COUNTED, after.transaction().getState());
        assertEquals(53_000L, after.snapshot().spentAmount());
    }

    @Test
    @Order(6)
    @DisplayName("되돌리기 — 한도를 되돌리고 알림을 만들지 않는다")
    void undoRestoresCap() {
        GuardianTransaction target = txRepository.findByChallenge(challengeId).stream()
                .filter(GuardianTransaction::isCounted)
                .findFirst().orElseThrow();
        long amount = target.getAmount();
        long before = challengeRepository.findById(challengeId).orElseThrow().getSpentAmount();
        long notisBefore = notificationRepository.count();

        GuardianService.UndoResult r = guardianService.undo(userId, target.getId(), UndoReason.NOT_MINE);

        assertEquals(TxState.EXCLUDED, r.transaction().getState());
        assertEquals(before - amount, r.snapshot().spentAmount());
        assertTrue(r.toast().contains(GuardianCopy.won(r.snapshot().remainingCap())));
        assertEquals(notisBefore, notificationRepository.count(),
                "되돌리기는 알림을 만들지 않는다 — 자기가 한 행동을 통보받게 하지 않는다");
    }

    @Test
    @Order(7)
    @DisplayName("유예가 지난 결제는 되돌릴 수 없다")
    void undoExpires() {
        GuardianTransaction target = txRepository.findByChallenge(challengeId).stream()
                .filter(GuardianTransaction::isCounted).findFirst().orElseThrow();
        clock.advance(userId, 2);   // 가상 시계를 이틀 민다
        try {
            assertThrows(ResponseStatusException.class,
                    () -> guardianService.undo(userId, target.getId(), UndoReason.NOT_MINE));
        } finally {
            // 이후 테스트가 영향받지 않게 되돌릴 수는 없다 — 시계는 전진만 한다.
            // 배치 테스트가 이 전진을 그대로 이어받는다.
            assertTrue(clock.isDemoMode(userId));
        }
    }

    @Test
    @Order(8)
    @DisplayName("새벽 배치 — 일 판정을 스냅샷과 함께 저장하고 사물을 지급한다")
    void dailyBatchStoresVerdictWithSnapshot() {
        LocalDate target = REF.toLocalDate();
        GuardianBatchService.BatchResult r = batchService.runDaily(userId, target);

        DailyVerdict v = r.verdict();
        assertEquals(target, v.getVerdictDate());
        assertNotNull(v.getResult());
        // 스냅샷이 없으면 "왜 그날 그랬지"를 나중에 답할 수 없다
        assertTrue(v.getPaceRatio() > 0, "판정 당시 페이스가 박제돼야 한다");
        assertTrue(v.getAllowedRatio() > v.getPaceRatio(), "허용선 = 페이스 + 버퍼");
        assertEquals(v.getPaceRatio() + 0.167, v.getAllowedRatio(), 1e-9);

        if (v.isGrantObject()) {
            assertNotNull(v.getGradeWeights());
            assertEquals(1.0, v.getGradeWeights().values().stream()
                    .mapToDouble(Double::doubleValue).sum(), 1e-9);
            assertNotNull(r.granted(), "지급 대상이면 사물이 나와야 한다");
            assertNotNull(v.getCeremonyMessage(), "세리머니 문구는 배치가 미리 만들어 둔다");
        }
    }

    @Test
    @Order(9)
    @DisplayName("같은 날을 두 번 판정하지 않는다 — 배치는 멱등이다")
    void dailyBatchIsIdempotent() {
        LocalDate target = REF.toLocalDate();
        long before = verdictRepository.count();
        batchService.runDaily(userId, target);
        assertEquals(before, verdictRepository.count());
    }

    @Test
    @Order(10)
    @DisplayName("홈은 프론트가 그릴 값을 전부 계산해 내려준다")
    void homeReturnsEverything() {
        GuardianService.HomeView h = guardianService.home(userId);

        assertNotNull(h.snapshot());
        assertEquals("배달", h.categoryLabel(), "카테고리 이름은 DB에서 온다 — 코드에 박지 않는다");
        assertTrue(h.snapshot().remainingCap() <= 150_000L);
        assertNotNull(h.items());
        assertFalse(h.grass().isEmpty(), "잔디는 판정 이력에서 나온다");
        assertTrue(h.demoMode(), "가상 시계를 밀었으므로 데모 모드");
    }

    @Test
    @Order(11)
    @DisplayName("마이데이터 투영 브리지 — 같은 소비를 두 번 적재하지 않는다")
    void syncFromMyDataIsIdempotent() {
        int first = guardianService.syncFromMyData(userId);
        int second = guardianService.syncFromMyData(userId);
        assertEquals(0, second, "이미 끌어온 소비는 다시 적재하지 않는다");
        assertTrue(first >= 0);
    }

    @Test
    @Order(12)
    @DisplayName("알림 피드백 — 별점이 아니라 사유 태그를 남긴다")
    void feedbackRecordsReason() {
        GuardianNotification n = guardianService.notifications(userId).stream()
                .findFirst().orElseThrow();
        guardianService.feedback(userId, n.getId(), Feedback.NOT_USEFUL, FeedbackReason.TOO_OFTEN);

        GuardianNotification reloaded = notificationRepository.findById(n.getId()).orElseThrow();
        assertEquals(Feedback.NOT_USEFUL, reloaded.getFeedback());
        assertEquals(FeedbackReason.TOO_OFTEN, reloaded.getFeedbackReason(),
                "프롬프트를 어느 방향으로 고칠지는 사유가 정한다");
    }
}
