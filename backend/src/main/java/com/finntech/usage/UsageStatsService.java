package com.finntech.usage;

import com.finntech.domain.UsageEvent;
import com.finntech.repository.UsageEventRepository;
import com.finntech.repository.UsageSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 행태 기록을 admin 이 읽을 모양으로 집계한다 — <b>GA4 표준 보고서에 맞춘다.</b>
 *
 * <h2>GA4 의 용어를 그대로 쓴다</h2>
 *
 * <p>새 이름을 지으면 보는 사람이 뜻을 또 배워야 한다. 활성 사용자·세션·참여 세션·참여율·
 * 이탈률·평균 참여시간은 이미 뜻이 정해진 말이라 정의까지 그대로 가져온다.
 *
 * <ul>
 *   <li><b>참여 세션</b> — 10초 이상 <u>또는</u> 화면 2개 이상. (GA4 는 "전환 1건"도 세는데
 *       우리에게는 전환이 없다.)
 *   <li><b>참여율</b> = 참여 세션 ÷ 세션. <b>이탈률</b> = 1 − 참여율. 둘은 서로의 여집합이다.
 *   <li><b>신규 사용자</b> — 그 기간에 <u>처음</u> 온 사람. 판정은 <b>전 기간</b>의 첫 방문일로
 *       한다 — 조회 창 안에서만 보면 오래된 사용자가 매번 신규로 보인다.
 *   <li><b>리텐션 D1/D7/D30</b> — 첫 방문일 코호트가 N일 뒤에 다시 왔는가.
 * </ul>
 *
 * <h2>GA4 의 보고서를 하나씩 대응시킨다</h2>
 *
 * <ul>
 *   <li><b>실시간</b> · <b>참여도</b>(이벤트·화면·랜딩) · <b>리텐션</b> — 이벤트 표에서
 *   <li><b>획득</b>(채널·출처/매체·캠페인·참조) — 세션 표에서. 세션이 열릴 때 한 번 적는다
 *   <li><b>Tech</b>(기기·브라우저·OS·해상도) — 세션 표에서. UA 원문은 저장하지 않는다
 *   <li><b>인구통계</b>(연령·성별·국가·언어) — {@code app_user} 조인 + 세션 표
 *   <li><b>전환</b>(핵심 이벤트) — 무엇이 전환인지는 {@link UsageProperties} 가 설정에서 읽는다
 * </ul>
 *
 * <h2>남는 한계는 감추지 않고 응답에 적는다</h2>
 *
 * <ul>
 *   <li><b>도시 단위 위치</b> — IP 를 저장하지 않아 국가까지가 한계다. 게다가 그 국가는
 *       <b>있는 곳이 아니라 브라우저 언어 설정</b>에서 나온다
 *   <li><b>수익화·LTV</b> — 실화폐 결제가 없어 사건 자체가 일어나지 않는다
 * </ul>
 *
 * <h2>세션 집계만 애플리케이션에서 센다</h2>
 *
 * <p>참여 세션의 판정이 "10초 이상 <u>또는</u> 화면 2개 이상"이라 한 질의로 쓰면 방언을 탄다.
 * 세션 수는 이벤트 수보다 두 자리 작으므로 끌어와도 안전하다 — 다만 상한을 둔다.
 */
@Service
public class UsageStatsService {

    /** 참여 세션 기준 — GA4 기본값과 같다(10초). */
    private static final long ENGAGED_MS = 10_000;
    private static final int ENGAGED_SCREENS = 2;

    /** 실시간 창 — GA4 와 같이 최근 30분. */
    private static final int REALTIME_MINUTES = 30;

    /** 순위 목록을 자르는 길이. 전부 주면 화면이 못 읽는다. */
    private static final int TOP_N = 30;

    /** 세션 집계를 애플리케이션으로 끌어올 상한. 넘으면 잘라 세고 그 사실을 적는다. */
    private static final int SESSION_CAP = 50_000;

    private static final String[] WEEKDAY = {"", "일", "월", "화", "수", "목", "금", "토"};

    private final UsageEventRepository events;
    private final UsageSessionRepository sessions;
    private final UsageProperties properties;
    private final Clock clock;

    public UsageStatsService(UsageEventRepository events, UsageSessionRepository sessions,
                             UsageProperties properties, Clock clock) {
        this.events = events;
        this.sessions = sessions;
        this.properties = properties;
        this.clock = clock;
    }

    /** 세션 하나를 굴린 값 — 참여·길이 판정의 재료. */
    private record SessionRow(String sessionId, long userId, LocalDateTime start, LocalDateTime end,
                              long engagedMs, int screenViews, long eventCount) {

        boolean engaged() { return engagedMs >= ENGAGED_MS || screenViews >= ENGAGED_SCREENS; }

        long durationMs() { return java.time.Duration.between(start, end).toMillis(); }
    }

    // ── 실시간 ────────────────────────────────────────────────────────────────

    /** GA4 의 실시간 — 최근 30분에 누가 어디 있나. */
    @Transactional(readOnly = true)
    public Map<String, Object> realtime() {
        LocalDateTime since = LocalDateTime.now(clock).minusMinutes(REALTIME_MINUTES);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowMinutes", REALTIME_MINUTES);
        out.put("since", since);

        List<Object[]> totals = events.realtimeTotals(since);
        Object[] row = totals.isEmpty() ? new Object[]{0L, 0L, 0L} : totals.get(0);
        out.put("users", num(row[0]));
        out.put("sessions", num(row[1]));
        out.put("events", num(row[2]));

        List<Map<String, Object>> screens = new ArrayList<>();
        for (Object[] s : events.realtimeScreens(since)) {
            screens.add(ordered("screen", s[0], "users", num(s[1]), "events", num(s[2])));
        }
        out.put("byScreen", screens);
        return out;
    }

    // ── 전체 보고서 ───────────────────────────────────────────────────────────

    /**
     * GA4 표준 보고서에 대응하는 집계 전부.
     *
     * @param days 며칠치를 볼 것인가
     */
    @Transactional(readOnly = true)
    public Map<String, Object> overview(int days) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime since = now.minusDays(Math.max(1, days));
        LocalDate sinceDate = since.toLocalDate();

        List<SessionRow> sessionRows = sessions(since);
        Map<Long, LocalDate> firstSeen = firstSeen();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", now);
        out.put("since", since);
        out.put("days", days);
        out.put("realtime", realtime());
        out.put("summary", summary(sessionRows, firstSeen, sinceDate));
        out.put("byDay", byDay(since, firstSeen));
        out.put("byScreen", byScreen(since));
        out.put("landingScreens", pairs(events.landingScreens(since), "screen", "sessions"));
        out.put("exitScreens", pairs(events.exitScreens(since), "screen", "sessions"));
        out.put("screenFlow", screenFlow(since));
        out.put("byEvent", triples(events.kindTotals(since), "event", "count", "users"));
        out.put("byClick", byClick(since));
        out.put("byHour", triples(events.hourlyTotals(since), "hour", "events", "users"));
        out.put("byWeekday", byWeekday(since));
        out.put("keyEvents", keyEvents(since, sessionRows));
        out.put("acquisition", acquisition(since));
        out.put("tech", tech(since));
        out.put("demographics", demographics(since, now.getYear()));
        out.put("retention", retention());
        out.put("sessionDuration", sessionDuration(sessionRows));
        out.put("byUser", byUser(since, firstSeen));
        return out;
    }

    // ── 요약(GA4 Engagement Overview) ─────────────────────────────────────────

    private Map<String, Object> summary(List<SessionRow> sessions, Map<Long, LocalDate> firstSeen,
                                        LocalDate sinceDate) {
        Set<Long> users = new HashSet<>();
        long engagedSessions = 0;
        long engagedMs = 0;
        long screenViews = 0;
        long eventCount = 0;
        long durationMs = 0;
        for (SessionRow s : sessions) {
            users.add(s.userId());
            if (s.engaged()) engagedSessions++;
            engagedMs += s.engagedMs();
            screenViews += s.screenViews();
            eventCount += s.eventCount();
            durationMs += s.durationMs();
        }
        long sessionCount = sessions.size();

        long newUsers = users.stream()
                .filter(u -> { LocalDate f = firstSeen.get(u); return f != null && !f.isBefore(sinceDate); })
                .count();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activeUsers", users.size());
        m.put("newUsers", newUsers);
        m.put("returningUsers", users.size() - newUsers);
        m.put("sessions", sessionCount);
        m.put("engagedSessions", engagedSessions);
        // 참여율과 이탈률은 서로의 여집합이다 — 둘 다 내보내야 화면이 계산을 안 한다.
        m.put("engagementRate", ratio(engagedSessions, sessionCount));
        m.put("bounceRate", sessionCount == 0 ? null
                : Math.round((1 - engagedSessions / (double) sessionCount) * 10000) / 100.0);
        m.put("eventCount", eventCount);
        m.put("screenViews", screenViews);
        m.put("engagedMs", engagedMs);
        m.put("avgEngagedMsPerSession", div(engagedMs, sessionCount));
        m.put("avgEngagedMsPerUser", div(engagedMs, users.size()));
        m.put("avgSessionDurationMs", div(durationMs, sessionCount));
        m.put("sessionsPerUser", rate(sessionCount, users.size()));
        m.put("viewsPerSession", rate(screenViews, sessionCount));
        m.put("viewsPerUser", rate(screenViews, users.size()));
        m.put("eventsPerSession", rate(eventCount, sessionCount));
        return m;
    }

    // ── 각 보고서 ─────────────────────────────────────────────────────────────

    private List<Map<String, Object>> byDay(LocalDateTime since, Map<Long, LocalDate> firstSeen) {
        // 날짜별 신규 사용자 수 — 첫 방문일이 그날인 사람.
        Map<String, Long> newByDay = new TreeMap<>();
        firstSeen.values().forEach(d -> newByDay.merge(d.toString(), 1L, Long::sum));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : events.dailyTotals(since)) {
            String date = String.valueOf(row[0]);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("date", date);
            long users = num(row[1]);
            long newUsers = newByDay.getOrDefault(date, 0L);
            d.put("users", users);
            d.put("newUsers", newUsers);
            d.put("returningUsers", Math.max(0, users - newUsers));
            d.put("sessions", num(row[2]));
            d.put("engagedMs", num(row[3]));
            out.add(d);
        }
        return out;
    }

    private List<Map<String, Object>> byScreen(LocalDateTime since) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : events.screenTotals(since)) {
            long views = num(row[1]);
            long engaged = num(row[2]);
            long users = num(row[3]);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("screen", row[0]);
            s.put("views", views);
            s.put("users", users);
            s.put("engagedMs", engaged);
            // 진입이 0인데 참여시간이 있는 경우가 있다(창 경계에 걸친 세션). 그때는 평균을 내지
            // 않는다 — 0으로 나눈 값을 0으로 적으면 거짓이 된다.
            s.put("avgEngagedMsPerView", div(engaged, views));
            s.put("viewsPerUser", rate(views, users));
            out.add(s);
        }
        return out;
    }

    private List<Map<String, Object>> byClick(LocalDateTime since) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : events.clickTotals(since)) {
            out.add(ordered("screen", row[0], "element", row[1],
                    "clicks", num(row[2]), "users", num(row[3])));
            if (out.size() >= TOP_N) break;
        }
        return out;
    }

    private List<Map<String, Object>> screenFlow(LocalDateTime since) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : events.screenFlow(since)) {
            out.add(ordered("from", row[0], "to", row[1], "moves", num(row[2])));
            if (out.size() >= TOP_N) break;
        }
        return out;
    }

    private List<Map<String, Object>> byWeekday(LocalDateTime since) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : events.weekdayTotals(since)) {
            int index = (int) num(row[0]);
            out.add(ordered("weekday", index >= 1 && index <= 7 ? WEEKDAY[index] : String.valueOf(index),
                    "events", num(row[1]), "users", num(row[2])));
        }
        return out;
    }

    // ── 획득 (GA4 의 사용자/트래픽 획득) ─────────────────────────────────────

    /**
     * 어디서 들어왔는가.
     *
     * <p>세션 표를 센다 — 이벤트 표를 세면 클릭이 많은 세션의 유입 경로가 그만큼 무겁게 잡힌다.
     */
    private Map<String, Object> acquisition(LocalDateTime since) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byChannel", triples(sessions.channelTotals(since), "channel", "sessions", "users"));
        List<Map<String, Object>> sourceMedium = new ArrayList<>();
        for (Object[] row : sessions.sourceMediumTotals(since)) {
            sourceMedium.add(ordered("source", row[0], "medium", row[1],
                    "sessions", num(row[2]), "users", num(row[3])));
            if (sourceMedium.size() >= TOP_N) break;
        }
        out.put("bySourceMedium", sourceMedium);
        out.put("byCampaign", triples(sessions.campaignTotals(since), "campaign", "sessions", "users"));
        out.put("byReferrer", triples(sessions.referrerTotals(since), "referrer", "sessions", "users"));
        return out;
    }

    // ── Tech ─────────────────────────────────────────────────────────────────

    /** GA4 의 Tech. UA 원문은 저장하지 않고 브라우저·OS·기기 종류로 줄여 적은 값을 센다. */
    private Map<String, Object> tech(LocalDateTime since) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byPlatform", triples(sessions.platformTotals(since), "platform", "sessions", "users"));
        out.put("byDevice", triples(sessions.deviceTotals(since), "device", "sessions", "users"));
        out.put("byBrowser", versioned(sessions.browserTotals(since), "browser"));
        out.put("byOs", versioned(sessions.osTotals(since), "os"));
        out.put("byScreenSize", triples(sessions.screenSizeTotals(since), "screenSize", "sessions", "users"));

        List<Map<String, Object>> viewports = new ArrayList<>();
        for (Object[] row : events.viewportTotals(since)) {
            viewports.add(ordered("viewport", row[0], "users", num(row[1]), "events", num(row[2])));
            if (viewports.size() >= TOP_N) break;
        }
        // 창 크기는 회전·리사이즈로 바뀌므로 세션이 아니라 이벤트를 센다.
        out.put("byViewport", viewports);
        return out;
    }

    /** 이름과 주버전을 나란히 — {@code (Chrome, 124)}. */
    private static List<Map<String, Object>> versioned(List<Object[]> rows, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            String version = String.valueOf(r[1] == null ? "" : r[1]);
            out.add(ordered(key, r[0], "version", version.isBlank() ? null : version,
                    "sessions", num(r[2]), "users", num(r[3])));
            if (out.size() >= TOP_N) break;
        }
        return out;
    }

    // ── 인구통계 ─────────────────────────────────────────────────────────────

    /**
     * GA4 의 인구통계.
     *
     * <p>연령·성별은 <b>본인인증에서 온 값</b>이라 {@code app_user} 에 있고 세션마다 복사하지
     * 않는다. 국가는 IP 가 아니라 <b>브라우저 언어 설정</b>에서 나온다 — 도시는 못 내고,
     * 해외에서 한국어로 쓰면 KR 로 잡힌다.
     *
     * @param thisYear 나이를 세는 기준 해. {@code Clock} 에서 온다 — 질의가 {@code now()} 를
     *                 직접 읽으면 재현성이 깨진다(마스터 §4 원칙 3)
     */
    private Map<String, Object> demographics(LocalDateTime since, int thisYear) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byGender", triples(sessions.genderTotals(since), "gender", "users", "sessions"));

        // 연령대는 GA4 와 같은 칸으로 나눈다 — 18-24 · 25-34 · … · 65+.
        int[] edges = {25, 35, 45, 55, 65};
        String[] labels = {"18-24", "25-34", "35-44", "45-54", "55-64", "65+"};
        long[] users = new long[labels.length];
        long[] sessionCounts = new long[labels.length];
        long unknownUsers = 0;
        long unknownSessions = 0;
        for (Object[] row : sessions.birthYearTotals(since)) {
            if (row[0] == null) {
                unknownUsers += num(row[1]);
                unknownSessions += num(row[2]);
                continue;
            }
            int age = thisYear - (int) num(row[0]);
            int bucket = edges.length;
            for (int i = 0; i < edges.length; i++) {
                if (age < edges[i]) { bucket = i; break; }
            }
            users[bucket] += num(row[1]);
            sessionCounts[bucket] += num(row[2]);
        }
        List<Map<String, Object>> byAge = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            if (users[i] == 0) continue;                 // 빈 칸은 안 그린다
            byAge.add(ordered("age", labels[i], "users", users[i], "sessions", sessionCounts[i]));
        }
        if (unknownUsers > 0) {
            byAge.add(ordered("age", "(미상)", "users", unknownUsers, "sessions", unknownSessions));
        }
        out.put("byAge", byAge);

        out.put("byCountry", triples(sessions.countryTotals(since), "country", "sessions", "users"));
        out.put("byLanguage", triples(sessions.languageTotals(since), "language", "sessions", "users"));
        out.put("byTimeZone", triples(sessions.timeZoneTotals(since), "timeZone", "sessions", "users"));
        return out;
    }

    // ── 전환 ─────────────────────────────────────────────────────────────────

    /**
     * 핵심 이벤트 — GA4 의 전환.
     *
     * <p>무엇이 전환인지는 설정이 정한다({@link UsageProperties}). 전환율의 분모는
     * <b>세션</b>이다 — GA4 의 '세션 전환율' 과 같다.
     */
    private List<Map<String, Object>> keyEvents(LocalDateTime since, List<SessionRow> all) {
        long totalSessions = all.size();
        List<Map<String, Object>> out = new ArrayList<>();
        for (UsageProperties.KeyEvent spec : properties.parsedKeyEvents()) {
            List<Object[]> rows = spec.element() == null
                    ? events.keyEventTotals(since, spec.kind(), spec.screen())
                    : events.keyEventTotals(since, spec.kind(), spec.screen(), spec.element());
            Object[] row = rows.isEmpty() ? new Object[]{0L, 0L, 0L} : rows.get(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", spec.label());
            m.put("kind", spec.kind());
            m.put("screen", spec.screen());
            m.put("element", spec.element());
            m.put("count", num(row[0]));
            m.put("users", num(row[1]));
            m.put("sessions", num(row[2]));
            m.put("sessionRate", ratio(num(row[2]), totalSessions));
            out.add(m);
        }
        return out;
    }

    /**
     * 리텐션 — 첫 방문일 코호트가 N일 뒤에 다시 왔는가.
     *
     * <p><b>전 기간</b>을 본다. 조회 창으로 자르면 "7일 뒤"를 볼 수 없는 코호트가 생겨
     * 분모가 조용히 작아진다.
     */
    private Map<String, Object> retention() {
        Map<Long, LocalDate> firstSeen = firstSeen();
        Map<Long, Set<LocalDate>> activeDays = new HashMap<>();
        for (Object[] row : events.activeDaysPerUser()) {
            activeDays.computeIfAbsent(num(row[0]), k -> new HashSet<>()).add(toDate(row[1]));
        }
        LocalDate today = LocalDate.now(clock);

        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> byOffset = new ArrayList<>();
        for (int offset : new int[]{1, 3, 7, 14, 30}) {
            long eligible = 0;
            long returned = 0;
            for (var entry : firstSeen.entrySet()) {
                LocalDate first = entry.getValue();
                // 아직 그날이 안 온 사람은 분모에서 뺀다 — 넣으면 리텐션이 시간이 갈수록
                // 저절로 올라가는 것처럼 보인다.
                if (first.plusDays(offset).isAfter(today)) continue;
                eligible++;
                Set<LocalDate> days = activeDays.getOrDefault(entry.getKey(), Set.of());
                if (days.contains(first.plusDays(offset))) returned++;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("day", offset);
            r.put("cohort", eligible);
            r.put("returned", returned);
            r.put("rate", ratio(returned, eligible));
            byOffset.add(r);
        }
        out.put("byOffset", byOffset);

        List<Map<String, Object>> cohorts = new ArrayList<>();
        Map<String, Long> sizeByFirstDay = new TreeMap<>();
        firstSeen.values().forEach(d -> sizeByFirstDay.merge(d.toString(), 1L, Long::sum));
        sizeByFirstDay.forEach((date, size) ->
                cohorts.add(ordered("firstSeen", date, "users", size)));
        out.put("cohorts", cohorts);
        return out;
    }

    /** 세션 길이 분포 — 평균 하나로는 "짧은 게 많은지 긴 게 몇 개인지"를 못 본다. */
    private List<Map<String, Object>> sessionDuration(List<SessionRow> sessions) {
        long[] edges = {10_000, 30_000, 60_000, 180_000, 600_000, 1_800_000};
        String[] labels = {"10초 미만", "10~30초", "30초~1분", "1~3분", "3~10분", "10~30분", "30분 이상"};
        long[] counts = new long[labels.length];
        for (SessionRow s : sessions) {
            long d = s.durationMs();
            int bucket = edges.length;
            for (int i = 0; i < edges.length; i++) {
                if (d < edges[i]) { bucket = i; break; }
            }
            counts[bucket]++;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            out.add(ordered("bucket", labels[i], "sessions", counts[i]));
        }
        return out;
    }

    private List<Map<String, Object>> byUser(LocalDateTime since, Map<Long, LocalDate> firstSeen) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : events.perUserTotals(since)) {
            long userId = num(row[0]);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("userId", userId);
            p.put("firstEverSeen", String.valueOf(firstSeen.get(userId)));
            p.put("sessions", num(row[1]));
            p.put("engagedMs", num(row[2]));
            p.put("events", num(row[3]));
            p.put("firstSeen", row[4]);
            p.put("lastSeen", row[5]);
            out.add(p);
        }
        return out;
    }

    // ── 원본 ──────────────────────────────────────────────────────────────────

    /**
     * 한 사람의 최근 발자취 — 통계가 이상할 때 원본을 본다.
     *
     * <p>200건으로 자른다. 전부 주기 시작하면 이 문이 <b>개인정보를 통째로 내보내는 문</b>이 된다.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> trail(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UsageEvent e : events.findTop200ByUserIdOrderByOccurredAtDesc(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("at", e.getOccurredAt());
            row.put("session", e.getSessionId());
            row.put("seq", e.getSeq());
            row.put("kind", e.getKind());
            row.put("screen", e.getScreen());
            row.put("element", e.getElement());
            row.put("engagedMs", e.getEngagedMs());
            row.put("viewport", e.getViewport());
            out.add(row);
        }
        return out;
    }

    // ── 재료 ──────────────────────────────────────────────────────────────────

    private List<SessionRow> sessions(LocalDateTime since) {
        List<Object[]> rows = events.sessionRollup(since);
        List<SessionRow> out = new ArrayList<>(Math.min(rows.size(), SESSION_CAP));
        for (Object[] r : rows) {
            if (out.size() >= SESSION_CAP) break;
            out.add(new SessionRow(String.valueOf(r[0]), num(r[1]),
                    (LocalDateTime) r[2], (LocalDateTime) r[3],
                    num(r[4]), (int) num(r[5]), num(r[6])));
        }
        return out;
    }

    private Map<Long, LocalDate> firstSeen() {
        Map<Long, LocalDate> out = new HashMap<>();
        for (Object[] row : events.firstSeenPerUser()) out.put(num(row[0]), toDate(row[1]));
        return out;
    }

    // ── 잔손질 ────────────────────────────────────────────────────────────────

    private static List<Map<String, Object>> pairs(List<Object[]> rows, String a, String b) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) out.add(ordered(a, r[0], b, num(r[1])));
        return out;
    }

    private static List<Map<String, Object>> triples(List<Object[]> rows, String a, String b, String c) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) out.add(ordered(a, r[0], b, num(r[1]), c, num(r[2])));
        return out;
    }

    /** 키 순서를 유지하는 맵 — 화면이 받은 순서대로 그린다. */
    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static LocalDate toDate(Object value) {
        if (value instanceof LocalDate d) return d;
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof LocalDateTime t) return t.toLocalDate();
        return LocalDate.parse(String.valueOf(value).substring(0, 10));
    }

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    /** 백분율(소수 둘째 자리). 분모가 0이면 <b>0이 아니라 없음</b>이다. */
    private static Double ratio(long part, long total) {
        return total == 0 ? null : Math.round(part * 10000.0 / total) / 100.0;
    }

    /** 평균. 분모가 0이면 없음. */
    private static Long div(long total, long count) {
        return count == 0 ? null : total / count;
    }

    private static Double rate(long total, long count) {
        return count == 0 ? null : Math.round(total * 100.0 / count) / 100.0;
    }
}
