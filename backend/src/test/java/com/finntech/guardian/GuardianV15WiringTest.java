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

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v1.5 에서 <b>규칙만 있고 부르는 곳이 없던 셋</b>이 실제로 이어졌는지 본다.
 *
 * <p>순수 함수 자체는 {@code GuardianRulesTest}가 이미 검증한다. 여기서 확인하는 것은
 * <b>배선</b>이다 — 함수가 맞아도 아무도 부르지 않으면 기능은 없는 것과 같고, 그것은
 * 컴파일도 되고 테스트도 통과하기 때문에 <b>조용히</b> 없다.
 *
 * <ul>
 *   <li>{@code resolveOneline} → 홈이 한마디를 내려주는가</li>
 *   <li>{@code missionShare} → 미션이 몫을 갖고 그 몫으로 지급되는가</li>
 *   <li>{@code shouldNudgeAhead} → 반복된 시간대 직전에 C9 가 나가는가</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GuardianV15WiringTest {

    /**
     * 2026-08-03 <b>월요일</b> 18:40.
     *
     * <p>C9 는 "슬롯 직전"이 조건이라 시각이 곧 시나리오다 — 넛지 선행 30분이므로
     * 19시 슬롯의 창은 18:30~19:00 이고, 이 시각은 그 안에 있다. 야간 침묵(22~08시)도 피한다.
     */
    static final LocalDateTime REF = LocalDateTime.of(2026, 8, 3, 18, 40, 0);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock wiringFixedClock() {
            return Clock.fixed(REF.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }
    }

    @Autowired GuardianService guardianService;
    @Autowired GuardianBatchService batchService;
    @Autowired GuardianClock clock;
    @Autowired GuardianProperties props;
    @Autowired GuardianChallengeRepository challengeRepository;
    @Autowired GuardianTransactionRepository txRepository;
    @Autowired WeeklyMissionRepository missionRepository;
    @Autowired GuardianNotificationRepository notificationRepository;
    @Autowired AppUserRepository userRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ConsumptionRepository consumptionRepository;

    static Long userId;
    static Long challengeId;

    @BeforeEach
    void seedOnce() {
        if (userId != null) return;
        AppUser user = userRepository.save(new AppUser(
                "배선테스트", new BigDecimal("3000000"), new BigDecimal("1000000"), 12));
        userId = user.getId();

        Category delivery = categoryRepository.findByCode("WIRE_DELIVERY")
                .orElseGet(() -> categoryRepository.save(new Category("WIRE_DELIVERY", "배달")));

        // 기준 지출의 재료. 최근 30일 창(7/4~8/3)에 7건이 들도록 매달 1~10일에 심는다.
        for (int month = 6; month <= 7; month++) {
            for (int i = 0; i < 10; i++) {
                consumptionRepository.save(new Consumption(userId, delivery, new BigDecimal("20000"),
                        LocalDateTime.of(2026, month, i + 1, 19, 0), false, Enums.DataSource.DUMMY_SEED));
            }
        }

        GuardianChallenge ch = guardianService.createChallenge(userId,
                List.of("WIRE_DELIVERY"), List.of(), 50_000L, null, null, 30);
        challengeId = ch.getId();
    }

    // ======================================================================
    //  ① 홈 한마디 (resolveOneline)
    // ======================================================================

    @Test
    @Order(1)
    @DisplayName("걸린 것이 없어도 홈 한마디는 비지 않는다 — 알림을 안 보낸 것과 홈이 빈 것은 다르다")
    void 조용할_때도_한마디가_온다() {
        GuardianService.Oneline line = guardianService.home(userId).oneline();

        assertEquals(GuardianRules.ONELINE_IDLE, line.caseId());
        assertFalse(line.text().isBlank(), "IDLE 도 문장을 준다");
        assertTrue(line.text().length() <= GuardianCopy.MAX_ONELINE_LEN,
                "두 줄짜리 자리를 넘기면 레이아웃이 깨진다 — 실제: " + line.text());
    }

    @Test
    @Order(2)
    @DisplayName("예산의 80%를 넘기면 홈이 그 사실을 말한다 — 알림 문안이 아니라 현재 상태로")
    void 임계를_넘으면_C3가_걸린다() {
        GuardianChallenge ch = challengeRepository.findById(challengeId).orElseThrow();
        long cap = ch.getChallengeCap();

        // 임계(0.8) 위, 1.0 아래로 올린다.
        guardianService.ingest(userId, new GuardianService.IngestCommand(
                REF, "우아한형제들", "배달의민족", Math.round(cap * 0.85), "5812",
                "WIRE_DELIVERY", 0.95, TxType.EXPENSE, false, null));

        GuardianService.Oneline line = guardianService.home(userId).oneline();

        assertEquals("C3", line.caseId());
        assertTrue(line.text().contains("%"), "몇 %인지 말해야 한다 — 실제: " + line.text());
        assertTrue(line.text().length() <= GuardianCopy.MAX_ONELINE_LEN, line.text());
    }

    @Test
    @Order(3)
    @DisplayName("사용률이 1을 넘으면 상태가 아직 AT_RISK 여도 초과라고 말한다")
    void 초과는_상태가_아니라_비율로_잡는다() {
        GuardianChallenge before = challengeRepository.findById(challengeId).orElseThrow();
        // 늦게 분류된 결제는 사용률이 1을 넘어도 상태를 AT_RISK 에 묶는다(classifyPending).
        // 상태만 보면 그 사람에게 "잘 지키고 있어요"가 뜬다 — 그 구멍을 막았는지 본다.
        guardianService.ingest(userId, new GuardianService.IngestCommand(
                REF, "우아한형제들", "배달의민족", Math.round(before.getChallengeCap() * 0.30), "5812",
                "WIRE_DELIVERY", 0.95, TxType.EXPENSE, false, null));

        GuardianChallenge after = challengeRepository.findById(challengeId).orElseThrow();
        assertTrue((double) after.getSpentAmount() / after.getChallengeCap() > 1.0,
                "이 시험의 전제 — 사용률이 1을 넘어야 한다");

        GuardianService.Oneline line = guardianService.home(userId).oneline();
        assertEquals("C6", line.caseId(),
                "상태가 " + after.getState() + " 여도 넘긴 사실은 말해야 한다");
        assertNotEquals(GuardianRules.ONELINE_IDLE, line.caseId());
    }

    // ======================================================================
    //  ② 위험 시간대 사전 넛지 (shouldNudgeAhead)
    // ======================================================================

    /** 소비 이력 한 줄을 심는다. C9의 근거는 챌린지 원장이 아니라 여기다. */
    private void seedConsumption(LocalDateTime at, String amount) {
        Category delivery = categoryRepository.findByCode("WIRE_DELIVERY").orElseThrow();
        consumptionRepository.save(new Consumption(userId, delivery, new BigDecimal(amount),
                at, false, Enums.DataSource.DUMMY_SEED));
    }

    @Test
    @Order(4)
    @DisplayName("반복이 없으면 넛지도 없다 — 취소된 결제를 습관의 증거로 쓰지 않는다")
    void 근거가_없으면_보내지_않는다() {
        // 창 안의 월요일 19시는 씨앗의 7/6 한 건뿐이라 임계(3)에 못 미친다.
        assertTrue(batchService.runNudges(userId, REF).isEmpty(),
                "월요일 19시 반복이 아직 없다");

        // 취소분을 세 건 넣어도 달라지지 않아야 한다. 안 쓴 돈을 근거로 습관을 지적하면
        // "지난 4주 중 3번"이라는 문장 자체가 거짓이 된다.
        for (int weeksAgo = 1; weeksAgo <= 3; weeksAgo++) {
            seedConsumption(REF.toLocalDate().minusWeeks(weeksAgo).atTime(19, 0), "-20000");
        }
        assertTrue(batchService.runNudges(userId, REF).isEmpty(),
                "취소는 습관이 아니다");
    }

    @Test
    @Order(5)
    @DisplayName("같은 요일·시간대가 4주간 반복되면 그 시간 직전에 한 번 귀띔한다")
    void 반복된_시간대_직전에_넛지가_나간다() {
        // 지난 세 번의 월요일 19시. 넛지 임계(nudge-frequency-4w=3)를 채운다.
        for (int weeksAgo = 1; weeksAgo <= 3; weeksAgo++) {
            LocalDateTime at = REF.toLocalDate().minusWeeks(weeksAgo).atTime(19, 0);
            assertEquals(DayOfWeek.MONDAY, at.getDayOfWeek(), "이 시험의 전제 — 전부 월요일이어야 한다");
            seedConsumption(at, "20000");
        }

        List<GuardianNotification> sent = batchService.runNudges(userId, REF);

        assertEquals(1, sent.size(), "슬롯 하나에 한 건");
        assertEquals("C9", sent.get(0).getCaseId());
        assertEquals(Tone.NUDGE_AHEAD, sent.get(0).getTone());
        assertTrue(sent.get(0).getBody().contains("월요일"),
                "언제인지 말해야 한다 — 실제: " + sent.get(0).getBody());
    }

    @Test
    @Order(6)
    @DisplayName("쿨다운은 주 1회 — 10분마다 도는 배치가 같은 넛지를 반복하지 않는다")
    void 같은_주에_두_번_보내지_않는다() {
        assertTrue(batchService.runNudges(userId, REF.plusMinutes(10)).isEmpty(),
                "방금 보냈으므로 이번 주는 끝이다");
    }

    @Test
    @Order(7)
    @DisplayName("시간대를 벗어나면 보내지 않는다 — 30분 앞이 조건이지 아무 때나가 아니다")
    void 시간대_밖에서는_보내지_않는다() {
        // 쿨다운을 지우고 시각만 바꿔 본다. 지운 이유는 여기서 재려는 것이 쿨다운이 아니라
        // **시각 조건**이기 때문이다 — 쿨다운에 가려지면 무엇이 막았는지 알 수 없다.
        notificationRepository.deleteAll(notificationRepository.findAllSpoken(challengeId));

        assertTrue(batchService.runNudges(userId, REF.toLocalDate().atTime(17, 0)).isEmpty(),
                "두 시간 전은 너무 이르다");
        assertTrue(batchService.runNudges(userId, REF.toLocalDate().atTime(19, 30)).isEmpty(),
                "이미 그 시간대에 들어왔으면 '사전' 넛지가 아니다");
        assertFalse(batchService.runNudges(userId, REF.toLocalDate().atTime(18, 45)).isEmpty(),
                "창 안이면 나간다");
    }

    // ======================================================================
    //  ③ 주간 미션 몫 (missionShare)
    // ======================================================================

    @Test
    @Order(8)
    @DisplayName("미션은 만들어질 때 제 몫을 받는다 — 정산 때 세면 나중에 늘어난 미션이 몫을 깎는다")
    void 미션이_몫을_갖고_태어난다() {
        // 챌린지는 오늘 시작했으므로 판정할 지나간 날이 없다. 데모 시계를 밀어 하루를 만든다.
        // <b>이 시험을 맨 뒤에 두는 이유</b> — 시계를 밀면 앞의 시각 조건들이 전부 어긋난다.
        LocalDateTime moved = clock.advance(userId, 7);
        LocalDate target = moved.toLocalDate().minusDays(1);

        batchService.runDaily(userId, target);

        LocalDate weekStart = GuardianRewardService.weekStart(target);
        List<WeeklyMission> missions = missionRepository.findByUserAndPeriod(userId, weekStart);

        assertFalse(missions.isEmpty(), "주간 미션이 만들어져야 한다");
        assertEquals(GuardianRules.missionShare(missions.size(), props), missions.get(0).getPointShare(),
                "혼자면 총액을 그대로 갖는다");
        assertTrue(missions.get(0).getPointShare() > 0,
                "0 이면 정산에서 아무것도 지급되지 않는다 — 조용한 손해");
    }
}
