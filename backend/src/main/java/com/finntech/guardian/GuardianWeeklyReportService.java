package com.finntech.guardian;

import com.finntech.guardian.domain.DailyVerdict;
import com.finntech.guardian.domain.GuardianChallenge;
import com.finntech.guardian.domain.GuardianEnums.DailyResult;
import com.finntech.guardian.domain.GuardianEnums.TxState;
import com.finntech.guardian.domain.GuardianTransaction;
import com.finntech.guardian.repository.DailyVerdictRepository;
import com.finntech.guardian.repository.GuardianChallengeRepository;
import com.finntech.guardian.domain.WeeklyMission;
import com.finntech.guardian.repository.GuardianTransactionRepository;
import com.finntech.guardian.repository.WeeklyMissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.List;

/**
 * 주간 리포트 (개편안 `s-report`) — 이번 주를 잘 지켰는지, 그리고 추세.
 *
 * <p><b>왜 주 단위인가.</b> 하루는 우연이고 한 달은 너무 늦다. 주는 "지난주보다 나아졌나"를
 * 물을 수 있는 가장 짧은 단위다 — 그래서 방어율 하나만 보여주지 않고 <b>4주 추이</b>를 함께 준다.
 * 숫자 하나는 잘한 건지 못한 건지 알 수 없지만, 오르는 선은 그 자체로 답이다.
 *
 * <p><b>소비 성격</b>은 결제 상태에서 읽는다. {@code COUNTED}=줄이려던 소비를 실제로 썼다,
 * {@code EXCLUDED}=챌린지와 무관(계획했던 소비), {@code EXEMPTED}=면제권으로 뺀 불가피한 소비.
 * 라벨을 따로 저장하지 않고 이미 있는 상태에서 유도하므로, 판정과 리포트가 갈라질 수 없다.
 */
@Service
public class GuardianWeeklyReportService {

    /** 추이에 보여줄 주 수 — 4주면 한 달치라 '이번 달 흐름'으로 읽힌다. */
    private static final int TREND_WEEKS = 4;

    /** 지난 챌린지를 몇 회차까지 보여줄지. 개편안이 두 줄만 그리고, 더 내려가면 옛일이라 참고가 안 된다. */
    private static final int PAST_CHALLENGES = 3;

    private final GuardianChallengeRepository challengeRepository;
    private final DailyVerdictRepository verdictRepository;
    private final GuardianTransactionRepository txRepository;
    private final WeeklyMissionRepository missionRepository;
    private final GuardianClock clock;
    /** 미션 1개 성공당 지급 포인트. 값은 application.yml — 코드에 박지 않는다(원칙 4). */
    private final int missionPoint;

    public GuardianWeeklyReportService(GuardianChallengeRepository challengeRepository,
                                       DailyVerdictRepository verdictRepository,
                                       GuardianTransactionRepository txRepository,
                                       WeeklyMissionRepository missionRepository,
                                       GuardianProperties props,
                                       GuardianClock clock) {
        this.challengeRepository = challengeRepository;
        this.verdictRepository = verdictRepository;
        this.txRepository = txRepository;
        this.missionRepository = missionRepository;
        this.missionPoint = props.getPoint().getWeeklyMission();
        this.clock = clock;
    }

    /**
     * 한 주의 성적.
     *
     * @param defenseRate 지킨 날 ÷ 판정한 날. 판정이 없는 주는 0
     * @param current     이번 주인가 — 화면이 막대를 강조하는 데 쓴다
     */
    public record WeekPoint(LocalDate weekStart, String label, int keptDays, int judgedDays,
                            double defenseRate, boolean current) {}

    /** @param count 건수, @param ratio 그 주 라벨링 전체 대비 비율 */
    public record LabelSlice(String key, String label, int count, double ratio) {}

    /**
     * 주간 미션 한 줄. {@code achieved} 가 null 이면 아직 기간 중이다(일요일 배치가 정산한다).
     * 개편안 s-report 의 '주간 미션 정산'.
     */
    public record MissionLine(String text, String status, Integer reward) {}

    /**
     * 그 주 하루치 (프로토타입_0806 '요일별' 토글).
     *
     * <p><b>왜 주별만으로는 부족한가.</b> 4주 추이는 "나아지고 있나"에는 답하지만 "언제 무너지나"에는
     * 답하지 못한다. 금요일마다 새는 사람과 주말에 몰아 쓰는 사람은 주별 막대가 똑같이 생겼는데
     * 고쳐야 할 것이 다르다. 요일이 드러나야 다음 주 미션을 어디에 걸지 정할 수 있다.
     *
     * @param amount   그날 집계된 지출. 판정 대상(COUNTED)만 센다
     * @param kept     그날을 지켰는가. 판정이 없는 날(미래·시작 전)은 false 이고 {@code judged} 가 가른다
     * @param judged   판정이 있었는가 — 없으면 화면이 막대를 비워 둔다
     */
    public record DayPoint(LocalDate date, String label, long amount, boolean kept, boolean judged) {}

    /**
     * 끝난 챌린지 한 줄 (프로토타입_0806 '지난 챌린지 달성률').
     *
     * @param label     "6월" 처럼 사람이 읽는 이름
     * @param period    "6.1 ~ 6.30" 기간
     * @param keptDays  지킨 날 수
     * @param totalDays 판정한 날 수
     * @param rate      달성률 — 서버가 계산해 내려준다(프론트는 계산하지 않는다)
     */
    public record PastChallenge(Long challengeId, String label, String period,
                                int keptDays, int totalDays, double rate) {}

    /**
     * '지킴이가 본 이번 주' — 잘한 점 하나, 함께 볼 점 하나.
     *
     * <p><b>규칙이 만든다.</b> 지난주 대비 건수가 가장 많이 줄어든 카테고리가 잘한 점이고,
     * 가장 많이 늘어난 쪽이 함께 볼 점이다(마스터 §4 원칙 1 — 판단은 설명가능한 모델이,
     * 표현은 AI가). 문장에 쓰는 숫자는 전부 여기서 센 값이다.
     */
    public record Coaching(String good, String watch) {}

    /**
     * @param days           그 주 7일치 — 화면의 '요일별' 토글이 쓴다. 늘 7칸이다(빈 날도 자리를 지킨다)
     * @param pastChallenges 끝난 챌린지 달성률. 최근 것부터, 없으면 빈 목록이라 화면이 절을 감춘다
     */
    public record WeeklyReport(LocalDate weekStart, LocalDate weekEnd, String weekLabel,
                               double defenseRate, Double deltaFromLastWeek,
                               List<WeekPoint> trend, List<DayPoint> days, List<LabelSlice> labels,
                               int labeledCount, long exemptedAmount, String headline,
                               List<MissionLine> missions, int missionReward, Coaching coaching,
                               List<PastChallenge> pastChallenges) {}

    @Transactional(readOnly = true)
    public WeeklyReport report(Long userId, int weeksAgo) {
        GuardianChallenge ch = latest(userId);
        // 데모 시계는 사용자별이다 — 시간을 밀면 리포트의 "이번 주"도 같이 움직여야 한다.
        LocalDate today = clock.today(userId);
        LocalDate thisWeekStart = today.with(DayOfWeek.MONDAY);
        LocalDate start = thisWeekStart.minusWeeks(Math.max(0, weeksAgo));
        LocalDate end = start.plusDays(6);

        List<WeekPoint> trend = new ArrayList<>();
        for (int i = TREND_WEEKS - 1; i >= 0; i--) {
            LocalDate ws = start.minusWeeks(i);
            trend.add(weekPoint(ch.getId(), ws, ws.equals(start)));
        }
        WeekPoint cur = trend.get(trend.size() - 1);
        WeekPoint prev = trend.size() >= 2 ? trend.get(trend.size() - 2) : null;
        Double delta = prev == null || prev.judgedDays() == 0
                ? null : cur.defenseRate() - prev.defenseRate();

        // ── 소비 성격 — 그 주에 확정된 결제를 상태별로 센다
        int counted = 0, excluded = 0, exempted = 0;
        long exemptedAmount = 0;
        for (GuardianTransaction t : txRepository.findByChallenge(ch.getId())) {
            LocalDate d = t.getOccurredAt().toLocalDate();
            if (d.isBefore(start) || d.isAfter(end)) continue;
            switch (t.getState()) {
                case COUNTED -> counted++;
                case EXCLUDED -> excluded++;
                case EXEMPTED -> { exempted++; exemptedAmount += t.getAmount(); }
                default -> { /* PENDING_CATEGORY는 아직 사용자가 답하지 않아 성격이 정해지지 않았다 */ }
            }
        }
        int labeled = counted + excluded + exempted;
        List<LabelSlice> labels = List.of(
                slice("PLANNED", "계획했던 소비", excluded, labeled),
                slice("TARGET", "줄이려던 소비", counted, labeled),
                slice("UNAVOIDABLE", "불가피한 소비", exempted, labeled));

        // ── 주간 미션 정산 · 이번 주 코칭
        List<MissionLine> missions = missionLines(userId, start);
        int reward = (int) missions.stream().filter(m -> "SUCCESS".equals(m.status())).count() * missionPoint;
        Coaching coaching = coaching(ch.getId(), start, end);

        return new WeeklyReport(start, end, weekLabel(start), cur.defenseRate(), delta,
                trend, dayPoints(ch.getId(), start), labels, labeled, exemptedAmount,
                headline(trend, delta), missions, reward, coaching, pastChallenges(userId, ch.getId()));
    }

    /**
     * 그 주 7일치 — 요일별 막대의 재료.
     *
     * <p><b>빈 날도 자리를 지킨다.</b> 판정이 없는 날을 빼면 막대가 여섯 개가 되어 '월~일'이
     * 어긋난다. 대신 {@code judged=false} 로 표시해 화면이 비워 그리게 한다.
     *
     * <p>금액은 <b>집계된 것만</b> 센다. 되돌린 결제·성역·환불까지 넣으면 "지킨 날인데 금액이 크다"가
     * 되어 막대와 판정이 서로 다른 말을 한다.
     */
    private List<DayPoint> dayPoints(Long challengeId, LocalDate weekStart) {
        LocalDate weekEnd = weekStart.plusDays(6);
        Map<LocalDate, DailyVerdict> byDate = new TreeMap<>();
        for (DailyVerdict v : verdictRepository.findRange(challengeId, weekStart, weekEnd)) {
            byDate.put(v.getVerdictDate(), v);
        }
        Map<LocalDate, Long> spent = new TreeMap<>();
        for (GuardianTransaction t : txRepository.findCountedBetween(challengeId, weekStart, weekEnd)) {
            if (t.getCountedDate() != null) spent.merge(t.getCountedDate(), t.getAmount(), Long::sum);
        }

        List<DayPoint> out = new ArrayList<>(7);
        for (int i = 0; i < 7; i++) {
            LocalDate d = weekStart.plusDays(i);
            DailyVerdict v = byDate.get(d);
            boolean judged = v != null && v.getResult() != DailyResult.NO_GRANT;
            boolean kept = judged && v != null
                    && (v.getResult() == DailyResult.NO_SPEND_DAY || v.getResult() == DailyResult.ON_PACE_DAY);
            out.add(new DayPoint(d, weekdayLabel(d.getDayOfWeek()), spent.getOrDefault(d, 0L), kept, judged));
        }
        return out;
    }

    /**
     * 끝난 챌린지의 달성률 — "6월 6.1~6.30 / 21일 지킴".
     *
     * <p><b>진행 중인 챌린지는 빼고</b> 최근 것부터 준다. 아직 안 끝난 회차를 달성률로 보여주면
     * 지금까지의 성적이 최종 성적처럼 읽힌다 — 남은 날이 있는데 확정된 것처럼 말하면 안 된다.
     *
     * <p>이름은 <b>시작 달</b>로 짓는다. 한 회차가 달을 걸치면(6/20~7/19) '6월'이 되는데,
     * 사용자가 "6월에 시작한 그거"로 기억하기 때문이다.
     */
    private List<PastChallenge> pastChallenges(Long userId, Long currentId) {
        List<PastChallenge> out = new ArrayList<>();
        for (GuardianChallenge c : challengeRepository.findByUserIdOrderByIdDesc(userId)) {
            if (c.getId().equals(currentId) || c.isRunning()) continue;
            List<DailyVerdict> vs = verdictRepository.findRange(c.getId(), c.getStartDate(), c.getEndDate());
            int kept = 0, judged = 0;
            for (DailyVerdict v : vs) {
                if (v.getResult() == DailyResult.NO_GRANT) continue;
                judged++;
                if (v.getResult() == DailyResult.NO_SPEND_DAY || v.getResult() == DailyResult.ON_PACE_DAY) kept++;
            }
            out.add(new PastChallenge(c.getId(), c.getStartDate().getMonthValue() + "월",
                    period(c.getStartDate(), c.getEndDate()), kept, judged,
                    judged == 0 ? 0.0 : (double) kept / judged));
            if (out.size() >= PAST_CHALLENGES) break;
        }
        return out;
    }

    /** "6.1 ~ 6.30". 연도는 뺀다 — 최근 것만 보여주므로 헷갈릴 일이 없고 자리는 좁다. */
    private static String period(LocalDate from, LocalDate to) {
        return from.getMonthValue() + "." + from.getDayOfMonth()
                + " ~ " + to.getMonthValue() + "." + to.getDayOfMonth();
    }

    // ======================================================================
    //  내부
    // ======================================================================

    /** 요일 한 글자. 화면 문구는 44자 제한이 빡빡해 "금요일"보다 "금"이 낫다. */
    private static String weekdayLabel(java.time.DayOfWeek d) {
        return switch (d) {
            case MONDAY -> "월"; case TUESDAY -> "화"; case WEDNESDAY -> "수"; case THURSDAY -> "목";
            case FRIDAY -> "금"; case SATURDAY -> "토"; case SUNDAY -> "일";
        };
    }

    /** 그 주의 미션을 사람이 읽는 문장으로. 없으면 빈 목록이라 화면이 절을 통째로 감춘다. */
    private List<MissionLine> missionLines(Long userId, LocalDate weekStart) {
        List<MissionLine> out = new ArrayList<>();
        for (WeeklyMission m : missionRepository.findByUserAndPeriod(userId, weekStart)) {
            String text = switch (m.getConditionType()) {
                case MAX_COUNT -> m.getCategory() + " 주 " + m.getThreshold() + "회 이하";
                case AVOID_SLOT -> m.getAvoidWeekday() == null ? m.getCategory() + " 시간대 피하기"
                        : weekdayLabel(m.getAvoidWeekday()) + " "
                          + m.getAvoidHourStart() + "~" + m.getAvoidHourEnd() + "시 " + m.getCategory() + " 안 쓰기";
                case NO_SPEND_STREAK_MIN -> "무지출 " + m.getThreshold() + "일 연속";
                case LABELING_COUNT_MIN -> "소비 성격 " + m.getThreshold() + "건 답하기";
            };
            String status = m.getAchieved() == null ? "ONGOING"
                    : m.getAchieved() ? "SUCCESS" : "FAILED";
            out.add(new MissionLine(text, status, missionPoint));
        }
        return out;
    }

    /**
     * 지난주와 이번 주의 카테고리별 건수를 견줘 한 문장씩 만든다.
     *
     * <p>비교할 것이 없으면(첫 주이거나 결제가 없으면) null 을 담아 화면이 그 줄을 감추게 한다 —
     * 없는 근거로 칭찬하거나 나무라지 않는다.
     */
    private Coaching coaching(Long challengeId, LocalDate start, LocalDate end) {
        Map<String, Integer> now = countByCategory(challengeId, start, end);
        Map<String, Integer> before = countByCategory(challengeId, start.minusWeeks(1), start.minusDays(1));
        if (now.isEmpty() && before.isEmpty()) return new Coaching(null, null);

        String bestDown = null, bestUp = null;
        int down = 0, up = 0;
        for (String cat : new TreeSet<>(union(now, before))) {
            int diff = now.getOrDefault(cat, 0) - before.getOrDefault(cat, 0);
            if (diff < down) { down = diff; bestDown = cat; }
            if (diff > up) { up = diff; bestUp = cat; }
        }
        String good = bestDown == null ? null
                : bestDown + " 지출이 지난주보다 " + (-down) + "번 줄었어요.";
        String watch = bestUp == null ? null
                : bestUp + " 지출이 지난주보다 " + up + "번 늘었어요. 이번 주에 한 번 더 살펴봐요.";
        return new Coaching(good, watch);
    }

    private Map<String, Integer> countByCategory(Long challengeId, LocalDate from, LocalDate to) {
        Map<String, Integer> m = new TreeMap<>();   // 재현성 — 순서가 고정돼야 같은 문장이 나온다
        for (GuardianTransaction t : txRepository.findByChallenge(challengeId)) {
            LocalDate d = t.getOccurredAt().toLocalDate();
            if (d.isBefore(from) || d.isAfter(to)) continue;
            if (t.getState() != TxState.COUNTED) continue;   // 성역·계획 소비는 잔소리 대상이 아니다
            if (t.getCategory() == null) continue;
            m.merge(t.getCategory(), 1, Integer::sum);
        }
        return m;
    }

    private static Set<String> union(Map<String, Integer> a, Map<String, Integer> b) {
        Set<String> s = new TreeSet<>(a.keySet());
        s.addAll(b.keySet());
        return s;
    }

    private WeekPoint weekPoint(Long challengeId, LocalDate weekStart, boolean current) {
        List<DailyVerdict> vs = verdictRepository.findRange(challengeId, weekStart, weekStart.plusDays(6));
        int kept = 0;
        for (DailyVerdict v : vs) {
            if (v.getResult() == DailyResult.NO_SPEND_DAY || v.getResult() == DailyResult.ON_PACE_DAY) kept++;
        }
        // NO_GRANT는 판정을 못 한 날(아직 안 지났거나 데이터가 없다)이라 분모에서 뺀다 —
        // 넣으면 주 초반에 방어율이 늘 낮게 보인다.
        int judged = (int) vs.stream().filter(v -> v.getResult() != DailyResult.NO_GRANT).count();
        return new WeekPoint(weekStart, weekLabel(weekStart), kept, judged,
                judged == 0 ? 0.0 : (double) kept / judged, current);
    }

    private static LabelSlice slice(String key, String label, int count, int total) {
        return new LabelSlice(key, label, count, total == 0 ? 0.0 : (double) count / total);
    }

    /** "7월 3주차" — 그 달의 몇 번째 주인지. 월 경계에 걸친 주는 시작일이 속한 달로 센다. */
    private static String weekLabel(LocalDate weekStart) {
        int nth = (weekStart.getDayOfMonth() - 1) / 7 + 1;
        return weekStart.getMonthValue() + "월 " + nth + "주차";
    }

    /** 추세를 한 문장으로. 숫자만 보여주면 잘한 건지 모른다. */
    private static String headline(List<WeekPoint> trend, Double delta) {
        List<WeekPoint> judged = trend.stream().filter(w -> w.judgedDays() > 0).toList();
        if (judged.size() >= 3) {
            boolean rising = true;
            for (int i = 1; i < judged.size(); i++) {
                if (judged.get(i).defenseRate() < judged.get(i - 1).defenseRate()) { rising = false; break; }
            }
            if (rising) return "방어율이 " + judged.size() + "주 연속 오르고 있어요. 이 흐름 그대로 가면 돼요.";
        }
        if (delta == null) return "이번 주 기록이 쌓이면 지난주와 견줘 볼게요.";
        if (delta > 0.01) return "지난주보다 나아졌어요. 무엇이 달랐는지 기억해 두면 좋아요.";
        if (delta < -0.01) return "지난주보다 조금 내려갔어요. 한 주는 흔들릴 수 있어요.";
        return "지난주와 비슷하게 지켰어요.";
    }

    private GuardianChallenge latest(Long userId) {
        List<GuardianChallenge> all = challengeRepository.findByUserIdOrderByIdDesc(userId);
        if (all.isEmpty()) throw new IllegalStateException("아직 챌린지가 없어요");
        return all.get(0);
    }
}
