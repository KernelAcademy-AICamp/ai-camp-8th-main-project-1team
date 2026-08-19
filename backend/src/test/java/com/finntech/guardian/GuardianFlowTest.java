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
 * 침묵이 기록되는지, 되돌리기가 예산을 되돌리는지를 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GuardianFlowTest {

    /** 고정 시각 — 오후 2시(야간 침묵에 걸리지 않는 시간대). */
    static final LocalDateTime REF = LocalDateTime.of(2026, 8, 3, 14, 0, 0);

    /**
     * 30일 챌린지의 기준 지출 — <b>최근 30일 실측</b>이다(2026-07-31 변경).
     *
     * <p>기준 시각 2026-08-03 14:00에서 30일을 되짚으면 7/4 14:00부터다. 씨앗 데이터의 배달은
     * 매달 1~10일 19:00이므로 그 창에 드는 것은 <b>7/4~7/10의 7건</b> = 175,000원이다.
     *
     * <p>예전에는 전 기간(5·6·7월 750,000원)을 92일로 나눠 30일로 환산했다(244,565원). 그러면
     * 화면이 보여준 금액과 사용자가 훑을 수 있는 결제 목록이 어긋난다 — 온보딩에서 "이 결제는
     * 낭비가 아니다"를 골라도 그 금액이 기준의 어디에서 빠지는지 대응되지 않는다.
     * 최근 30일 실측이면 목록과 금액이 1:1로 맞는다.
     */
    static final long BASELINE_30D = 25_000L * 7;   // 175,000

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
    @Autowired com.finntech.repository.UserPaymentRepository userPaymentRepository;

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
    @DisplayName("챌린지 생성 — 기준 지출·예산·버퍼가 기존 분석 결과에서 파생된다")
    void createChallenge() {
        GuardianChallenge ch = guardianService.createChallenge(userId,
                List.of("GTEST_DELIVERY"), List.of(), 100_000L, "에어팟", 179_000L, 30);
        challengeId = ch.getId();

        // 기준 지출은 월평균이 아니라 **챌린지 일수로 환산한 값**이다.
        // 배달 25,000원 × 10건 × 3개월 = 750,000원을 5·6·7월(31+30+31 = 92일)로 나눠
        // 하루 8,152원, 30일이면 244,565원. 관측한 달이 무엇이든 같은 습관이면 같은 값이 나온다.
        assertEquals(BASELINE_30D, ch.getBaselineAmount(), "최근 30일 실측 = 25,000 × 7건");
        assertEquals(100_000L, ch.getTargetSaving());
        assertEquals(BASELINE_30D - 100_000L, ch.getChallengeCap(), "예산 = 기준 지출 − 지킬 돈");
        assertEquals(0.2, ch.getBufferRatio(), 1e-9,
                "버퍼 = 25,000/75,000 = 0.333 → 상한 0.2 (MAX_BUFFER_RATIO)");
        assertEquals(30, ch.getDaysTotal());
        assertEquals(ChallengeState.ACTIVE, ch.getState());
    }

    @Test
    @Order(1)
    @DisplayName("뺀 결제만큼 기준 지출이 줄어든다 — 화면이 보여준 '지킬 돈'과 서버 예산이 맞아야 한다")
    void keptPaymentsReduceBaseline() {
        // 창 안(7/4~7/10) 배달 7건 중 2건을 '낭비가 아니다'로 뺀다.
        var kept = userPaymentRepository.findByUserIdOrderByPaymentDateDesc(userId).stream()
                .filter(p -> "GTEST_DELIVERY".equals(p.getCategory2()))
                .filter(p -> !p.getPaymentDate().isBefore(REF.minusDays(30))
                        && !p.getPaymentDate().isAfter(REF))
                .limit(2).map(p -> p.getPaymentId()).toList();
        // 마이데이터 투영이 없는 테스트 환경이면 뺄 결제가 없다 — 그때는 기준이 그대로여야 한다.
        var b = guardianService.baselineFor(userId, List.of("GTEST_DELIVERY"), REF, 30, kept);
        long expected = BASELINE_30D - kept.size() * 25_000L;
        assertEquals(expected, b.periodAmount(),
                "뺀 " + kept.size() + "건(건당 25,000원)만큼 기준에서 빠져야 한다");
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
        assertEquals(BASELINE_30D - 100_000L - 32_000L, r.snapshot().remainingCap());
        assertEquals(100_000L, r.snapshot().securedSaving(), "아직 지킬 돈은 온전하다");

        assertNotNull(r.notification());
        assertEquals("C1", r.notification().getCaseId());
        assertEquals(PhrasingMode.TENTATIVE, r.notification().getPhrasingMode(),
                "되돌릴 수 있는 결제라 조건부 화법이어야 한다");
        String remaining = String.format("%,d", BASELINE_30D - 100_000L - 32_000L);
        assertTrue(r.notification().getBody().contains(remaining),
                "이미 계산된 숫자가 문장에 들어간다 — 본문: " + r.notification().getBody());
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
        assertEquals(32_000L, r.snapshot().spentAmount(), "무관 카테고리는 예산에 영향을 주지 않는다");
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
    @DisplayName("되돌리기 — 예산을 되돌리고 알림을 만들지 않는다")
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
        assertEquals(v.getPaceRatio() + 0.2, v.getAllowedRatio(), 1e-9);

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
    @Order(101)
    @DisplayName("종료일을 넣어도 미리 정산되지 않는다 — 1일차 완주 보상 구멍")
    void cannotSettleEarlyByPassingEndDate() {
        GuardianChallenge ch = challengeRepository.findById(challengeId).orElseThrow();

        // 예전에는 이 한 번의 호출로 최종 정산에 들어갔다. 집계 지출이 0이면 달성률이 1.0이라
        // (설계서 §1: 확보 절약액 = min(지킬 돈, 기준 지출 − 0) = 지킬 돈)
        // 한 푼도 아끼지 않고 SUCCESS + 완주 100P를 받았다. 인증은 ?userId= 뿐이었다.
        assertThrows(ResponseStatusException.class,
                () -> batchService.runDaily(userId, ch.getEndDate()),
                "아직 오지 않은 종료일은 판정 대상이 아니다");

        assertTrue(challengeRepository.findById(challengeId).orElseThrow().isRunning(),
                "챌린지는 그대로 진행 중이어야 한다");
    }

    @Test
    @Order(102)
    @DisplayName("챌린지 시작 전 날짜는 판정하지 않는다 — 없던 날에 사물이 지급되던 구멍")
    void doesNotJudgeBeforeStart() {
        GuardianChallenge ch = challengeRepository.findById(challengeId).orElseThrow();
        long before = verdictRepository.count();

        GuardianBatchService.BatchResult r = batchService.runDaily(userId, ch.getStartDate().minusDays(1));

        assertNull(r.verdict(), "설계서 §2 — 시작 전이면 판정하지 않는다");
        assertEquals(before, verdictRepository.count(), "판정이 저장되면 안 된다");
    }

    @Test
    @Order(103)
    @DisplayName("챌린지 기간 밖의 거래는 예산을 태우지 않는다")
    void ignoresTransactionsOutsideChallengeWindow() {
        GuardianChallenge ch = challengeRepository.findById(challengeId).orElseThrow();
        long spentBefore = ch.getSpentAmount();

        // 2년 전 결제. 예전에는 발생일을 보지 않아 이 한 건으로도 예산이 깎였다.
        GuardianService.IngestResult r = guardianService.ingest(userId, new GuardianService.IngestCommand(
                ch.getStartDate().minusYears(2).atTime(19, 0), "우아한형제들", "배달의민족",
                90_000L, "5812", "GTEST_DELIVERY", 0.99, TxType.EXPENSE, true, null));

        assertEquals(TxState.EXCLUDED, r.transaction().getState());
        assertEquals(spentBefore, challengeRepository.findById(challengeId).orElseThrow().getSpentAmount(),
                "기간 밖 거래는 집계에 들어가지 않는다");
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
    @Order(104)
    @DisplayName("환불 한 건이 그 뒤의 소비 전부를 막지 않는다")
    void refundDoesNotHaltTheWholeSync() {
        GuardianChallenge ch = challengeRepository.findById(challengeId).orElseThrow();
        Category cafe = categoryRepository.findByCode("GTEST_CAFE").orElseThrow();

        // 환불을 **먼저** 넣는다. 고치기 전에는 여기서 IllegalArgumentException 이 나면서
        // 뒤의 정상 소비까지 통째로 안 들어왔다 — 운영에서 실제로 그랬다(KTX 취소 건).
        consumptionRepository.save(new Consumption(userId, cafe, new BigDecimal("-50500"),
                ch.getStartDate().atTime(9, 0), false, Enums.DataSource.DUMMY_SEED));
        consumptionRepository.save(new Consumption(userId, cafe, new BigDecimal("7000"),
                ch.getStartDate().atTime(10, 0), false, Enums.DataSource.DUMMY_SEED));

        int added = assertDoesNotThrow(() -> guardianService.syncFromMyData(userId),
                "환불이 섞여 있어도 동기화가 멈추면 안 된다");

        assertEquals(1, added, "환불은 건너뛰고 정상 소비 한 건만 담는다");
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
