package com.finntech.guardian;

import com.finntech.guardian.domain.GuardianChallenge;
import com.finntech.guardian.domain.GuardianEnums.MissionType;
import com.finntech.guardian.domain.GuardianTransaction;
import com.finntech.guardian.domain.WeeklyMission;
import com.finntech.guardian.repository.GuardianChallengeRepository;
import com.finntech.guardian.repository.GuardianTransactionRepository;
import com.finntech.guardian.repository.WeeklyMissionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 다음 주 미션 고르기 (개편안 {@code s-myroom} 의 미션 시트, 설계서 §9).
 *
 * <p><b>미션을 지어내지 않는다.</b> 후보는 전부 <b>지난주에 실제로 있었던 일</b>에서 나온다.
 * "배달 주 2회 이하"는 지난주에 배달을 3번 썼기 때문에 나오는 말이고, "금 19~22시 배달 안 쓰기"는
 * 그 칸에서 가장 자주 샜기 때문에 나오는 말이다. 근거 없는 미션은 지키래서 지키는 숙제가 된다.
 *
 * <p><b>한 주 앞을 고른다.</b> 이번 주 미션은 이미 진행 중이라 조건을 바꾸면 판정이 어긋난다.
 * 개편안이 "다음 주 미션 고르기"라고 적은 것도 같은 이유다 — 미래 시제는 이 시트 안에만 있다.
 *
 * <p><b>고른 것을 다시 고를 수 있다.</b> 다음 주가 시작되기 전이라면 바꿔도 아무 일도 일어나지
 * 않는다. 이미 만든 다음 주 미션을 지우고 새로 만든다.
 *
 * <p>조건 유형과 판정은 이미 있는 것을 그대로 쓴다({@link MissionType} · {@link GuardianRules}).
 * 새 개념을 만들면 판정 코드도 새로 써야 하고, 그러면 고른 미션이 정산되지 않는다.
 */
@Service
public class GuardianMissionService {

    /** 후보를 뽑을 때 되돌아보는 주 수. 한 주만 보면 그 주의 우연이 미션이 된다. */
    private static final int LOOKBACK_WEEKS = 4;
    /** 슬롯 미션의 폭(시간). 개편안의 "19~22시"와 같은 세 시간짜리 칸. */
    private static final int SLOT_HOURS = 3;

    private final GuardianChallengeRepository challengeRepository;
    private final GuardianTransactionRepository txRepository;
    private final WeeklyMissionRepository missionRepository;
    private final GuardianClock clock;
    private final GuardianProperties props;

    public GuardianMissionService(GuardianChallengeRepository challengeRepository,
                                  GuardianTransactionRepository txRepository,
                                  WeeklyMissionRepository missionRepository,
                                  GuardianClock clock,
                                  GuardianProperties props) {
        this.challengeRepository = challengeRepository;
        this.txRepository = txRepository;
        this.missionRepository = missionRepository;
        this.clock = clock;
        this.props = props;
    }

    // =====================================================================
    //  조회
    // =====================================================================

    @Transactional(readOnly = true)
    public Board board(Long userId) {
        LocalDate today = clock.today(userId);
        LocalDate thisWeek = GuardianRewardService.weekStart(today);
        LocalDate nextWeek = thisWeek.plusWeeks(1);

        List<Line> active = new ArrayList<>();
        for (WeeklyMission m : missionRepository.findByUserAndPeriod(userId, thisWeek)) {
            active.add(line(m));
        }
        List<Line> next = new ArrayList<>();
        for (WeeklyMission m : missionRepository.findByUserAndPeriod(userId, nextWeek)) {
            next.add(line(m));
        }

        GuardianChallenge ch = challengeRepository.findRunning(userId).orElse(null);
        List<Candidate> candidates = ch == null ? List.of() : candidates(ch, today);

        return new Board(active, next, candidates, nextWeek,
                props.getPoint().getWeeklyMission());
    }

    private Line line(WeeklyMission m) {
        String status = m.getAchieved() == null ? "ONGOING" : m.getAchieved() ? "SUCCESS" : "FAILED";
        return new Line(m.getId(), GuardianCopy.missionText(m), status,
                m.getPointShare(), m.getConditionType().name(), m.getCategory(), candidateKey(m));
    }

    /**
     * 담아 둔 미션이 어느 후보에서 왔는지.
     *
     * <p>시트를 다시 열었을 때 <b>지금 담긴 것에 표시가 가 있어야</b> 무엇을 바꾸는 중인지
     * 안다. 후보는 매번 다시 계산돼 id 가 없으므로, 후보를 만들 때와 <b>같은 규칙</b>으로
     * 키를 짓는다 — 규칙이 갈리면 담아 둔 미션이 시트에서 선택되지 않는다.
     */
    private static String candidateKey(WeeklyMission m) {
        return switch (m.getConditionType()) {
            case MAX_COUNT -> "max:" + m.getCategory();
            case AVOID_SLOT -> m.getAvoidWeekday() == null ? "slot:" + m.getCategory()
                    : "slot:" + m.getCategory() + ":"
                      + ((m.getAvoidWeekday().getValue() - 1) * 24 + m.getAvoidHourStart());
            case NO_SPEND_STREAK_MIN -> "streak";
            case LABELING_COUNT_MIN -> "label";
        };
    }

    // =====================================================================
    //  후보 만들기
    // =====================================================================

    /**
     * 후보 셋 — 횟수 줄이기 · 시간대 피하기 · 무지출 잇기.
     *
     * <p>셋이 <b>서로 다른 종류의 노력</b>을 요구하도록 골랐다. 같은 결의 미션 셋을 내놓으면
     * 고르는 일이 의미가 없다. 데이터가 모자라 만들 수 없는 후보는 <b>빼고 낸다</b> —
     * 지난주에 배달을 한 번도 안 썼는데 "배달 주 0회 이하"를 권하는 건 놀리는 말이다.
     */
    private List<Candidate> candidates(GuardianChallenge ch, LocalDate today) {
        LocalDate thisWeek = GuardianRewardService.weekStart(today);
        LocalDate from = thisWeek.minusWeeks(LOOKBACK_WEEKS);
        List<GuardianTransaction> txs = txRepository.findCountedBetween(ch.getId(), from, thisWeek.minusDays(1));

        // 카테고리별 주당 평균 건수 — 키 순서를 고정해 같은 입력에 같은 후보가 나오게 한다(원칙 3).
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Map<Integer, Integer>> slots = new TreeMap<>();   // 카테고리 → (요일*24+시작시) → 건수
        // **성역은 후보에서 뺀다.** 지킴이가 침묵하기로 한 곳에 미션을 걸면 앞뒤가 안 맞는다 —
        // "여기는 신경 쓰지 않을게요" 해 놓고 "여기를 줄이세요"가 된다.
        java.util.Set<String> sanctuary = ch.getSanctuarySet();
        for (GuardianTransaction t : txs) {
            String cat = t.getCategory();
            if (cat == null || cat.isBlank() || sanctuary.contains(cat)) continue;
            counts.merge(cat, 1, Integer::sum);
            int key = (t.getOccurredAt().getDayOfWeek().getValue() - 1) * 24
                    + (t.getOccurredAt().getHour() / SLOT_HOURS) * SLOT_HOURS;
            slots.computeIfAbsent(cat, k -> new TreeMap<>()).merge(key, 1, Integer::sum);
        }

        List<Candidate> out = new ArrayList<>();

        // ① 가장 자주 쓴 카테고리의 횟수를 한 번 줄인다.
        String top = counts.entrySet().stream()
                .max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey).orElse(null);
        if (top != null) {
            int perWeek = Math.max(1, Math.round((float) counts.get(top) / LOOKBACK_WEEKS));
            // **한 번 줄이기가 아니라 비율로 줄인다.** 주 18회 쓰는 사람에게 "17회 이하"는
            // 미션이 아니라 통계다. 반대로 주 2회 쓰는 사람에게 20% 는 1회가 되어 여전히 뜻이 있다.
            int target = Math.max(1, (int) Math.round(perWeek * (1 - props.getWeeklyMissionCutRatio())));
            if (target < perWeek) {
                out.add(new Candidate("max:" + top, MissionType.MAX_COUNT.name(), top, target,
                        null, null, null,
                        GuardianCopy.missionText(MissionType.MAX_COUNT, top, target, null, null, null),
                        "최근 " + LOOKBACK_WEEKS + "주 동안 주 " + perWeek + "회쯤 쓰셨어요"));
            }
        }

        // ② 그 카테고리가 가장 자주 새는 요일·시간 칸을 막는다.
        if (top != null) {
            Map<Integer, Integer> s = slots.getOrDefault(top, Map.of());
            // **건수가 가장 많은 칸이 아니라, 같은 시간대의 다른 요일보다 튀는 칸을 고른다.**
            // 그냥 최다를 고르면 언제나 점심이 뽑힌다 — 주 5일 있으니까. 그건 습관이 아니라
            // 끼니고, "화 9~12시 식비 안 쓰기"는 점심을 굶으라는 말이 된다. 금요일 밤 배달처럼
            // **그 요일에만** 튀는 칸이라야 줄일 수 있는 습관이다.
            Map<Integer, Integer> hourTotal = new TreeMap<>();   // 시작시 → 요일 통틀어 건수
            for (Map.Entry<Integer, Integer> e : s.entrySet()) {
                hourTotal.merge(e.getKey() % 24, e.getValue(), Integer::sum);
            }
            Integer peak = s.entrySet().stream()
                    .max(Comparator.<Map.Entry<Integer, Integer>>comparingDouble(
                                    e -> e.getValue() - hourTotal.get(e.getKey() % 24) / 7.0)
                            .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                    .map(Map.Entry::getKey).orElse(null);
            // 한 번뿐인 칸은 습관이 아니라 우연이다.
            if (peak != null && s.get(peak) >= 2) {
                DayOfWeek dow = DayOfWeek.of(peak / 24 + 1);
                int hour = peak % 24;
                int end = Math.min(23, hour + SLOT_HOURS);
                out.add(new Candidate("slot:" + top + ":" + peak, MissionType.AVOID_SLOT.name(), top, 0,
                        dow.name(), hour, end,
                        GuardianCopy.missionText(MissionType.AVOID_SLOT, top, 0, dow, hour, end),
                        GuardianCopy.weekday(dow) + "요일 그 시간에 " + s.get(peak) + "번 쓰셨어요"));
            }
        }

        // ③ 무지출 잇기 — 카테고리와 무관해 언제나 만들 수 있다.
        int days = props.getWeeklyMissionNoSpendDays();
        if (days > 0) {
            out.add(new Candidate("streak", MissionType.NO_SPEND_STREAK_MIN.name(), null, days,
                    null, null, null,
                    GuardianCopy.missionText(MissionType.NO_SPEND_STREAK_MIN, null, days, null, null, null),
                    "카테고리와 상관없이 " + days + "일을 이어서 지키면 돼요"));
        }
        return out;
    }

    // =====================================================================
    //  고르기
    // =====================================================================

    /**
     * 다음 주 미션을 정한다.
     *
     * <p><b>다음 주 것만 손댄다.</b> 이번 주 미션은 판정이 걸려 있어 바꾸면 이미 쌓인 진행이
     * 무엇을 향한 것이었는지 알 수 없게 된다.
     */
    @Transactional
    public Board pick(Long userId, String candidateKey) {
        LocalDate today = clock.today(userId);
        LocalDate nextWeek = GuardianRewardService.weekStart(today).plusWeeks(1);
        GuardianChallenge ch = challengeRepository.findRunning(userId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "진행 중인 챌린지가 없어요"));

        Candidate c = candidates(ch, today).stream()
                .filter(x -> x.key().equals(candidateKey)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "고를 수 없는 미션이에요"));

        // 다시 고르면 앞서 담아 둔 것을 지운다 — 두 개가 함께 남으면 포인트 몫이 쪼개진다.
        missionRepository.deleteAll(missionRepository.findByUserAndPeriod(userId, nextWeek));

        LocalDateTime now = clock.now(userId);
        MissionType type = MissionType.valueOf(c.type());
        WeeklyMission m = type == MissionType.AVOID_SLOT
                ? WeeklyMission.avoidSlot(userId, ch.getId(), c.category(),
                        DayOfWeek.valueOf(c.weekday()), c.hourStart(), c.hourEnd(),
                        nextWeek, nextWeek.plusDays(6), now)
                : new WeeklyMission(userId, ch.getId(), type, c.category(), c.threshold(),
                        nextWeek, nextWeek.plusDays(6), now);
        // 몫은 만들 때 박아 둔다 — 정산 때 세면 사용자가 본 포인트와 받는 포인트가 달라진다.
        m.setPointShare(GuardianRules.missionShare(1, props));
        missionRepository.save(m);

        return board(userId);
    }

    // =====================================================================
    //  응답 모양
    // =====================================================================

    /** 보드에 걸린 미션 한 줄. */
    public record Line(Long id, String text, String status, int reward,
                       String type, String category,
                       /** 이 미션을 만든 후보의 키 — 시트를 다시 열 때 표시를 되살린다. */
                       String candidateKey) {}

    /**
     * 고를 수 있는 미션 하나.
     *
     * @param key  고를 때 서버로 돌려보내는 값. 후보는 매번 다시 계산되므로 id 가 아니다.
     * @param why  왜 이걸 권하는지 — 근거 없는 추천은 숙제가 된다.
     */
    public record Candidate(String key, String type, String category, int threshold,
                            String weekday, Integer hourStart, Integer hourEnd,
                            String text, String why) {}

    public record Board(List<Line> active, List<Line> next, List<Candidate> candidates,
                        LocalDate nextWeekStart, int weeklyPointPool) {}
}
