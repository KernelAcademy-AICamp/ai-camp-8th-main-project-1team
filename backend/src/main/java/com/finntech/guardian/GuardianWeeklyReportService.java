package com.finntech.guardian;

import com.finntech.guardian.domain.DailyVerdict;
import com.finntech.guardian.domain.GuardianChallenge;
import com.finntech.guardian.domain.GuardianEnums.DailyResult;
import com.finntech.guardian.domain.GuardianEnums.TxState;
import com.finntech.guardian.domain.GuardianTransaction;
import com.finntech.guardian.repository.DailyVerdictRepository;
import com.finntech.guardian.repository.GuardianChallengeRepository;
import com.finntech.guardian.repository.GuardianTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
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

    private final GuardianChallengeRepository challengeRepository;
    private final DailyVerdictRepository verdictRepository;
    private final GuardianTransactionRepository txRepository;
    private final GuardianClock clock;

    public GuardianWeeklyReportService(GuardianChallengeRepository challengeRepository,
                                       DailyVerdictRepository verdictRepository,
                                       GuardianTransactionRepository txRepository,
                                       GuardianClock clock) {
        this.challengeRepository = challengeRepository;
        this.verdictRepository = verdictRepository;
        this.txRepository = txRepository;
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

    public record WeeklyReport(LocalDate weekStart, LocalDate weekEnd, String weekLabel,
                               double defenseRate, Double deltaFromLastWeek,
                               List<WeekPoint> trend, List<LabelSlice> labels,
                               int labeledCount, long exemptedAmount, String headline) {}

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

        return new WeeklyReport(start, end, weekLabel(start), cur.defenseRate(), delta,
                trend, labels, labeled, exemptedAmount, headline(trend, delta));
    }

    // ======================================================================
    //  내부
    // ======================================================================

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
