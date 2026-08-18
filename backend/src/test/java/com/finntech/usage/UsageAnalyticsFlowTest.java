package com.finntech.usage;

import com.finntech.domain.AppUser;
import com.finntech.domain.UsageEvent;
import com.finntech.domain.UsageSession;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.UsageEventRepository;
import com.finntech.repository.UsageSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 행태 수집의 관문·멱등·세션 속성, 그리고 집계가 실제로 그 값을 낸다는 것.
 *
 * <h2>여기서 못박는 것</h2>
 *
 * <ol>
 *   <li><b>관문</b> — 더미이거나 동의 안 한 사람은 한 줄도 안 남는다. 통계의 전제다
 *   <li><b>멱등</b> — {@code sendBeacon} 이 같은 묶음을 두 번 보내도 두 번 세지 않는다
 *   <li><b>세션 속성은 세션당 한 줄</b> — 두 번 보내도 덮어쓰지 않고, 다른 세션에 안 새어 든다
 *   <li><b>참여 세션의 정의</b> — 10초 이상 <u>또는</u> 화면 2개. 둘 다 아니면 이탈이다
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class UsageAnalyticsFlowTest {

    @Autowired UsageEventService service;
    @Autowired UsageStatsService stats;
    @Autowired UsageEventRepository events;
    @Autowired UsageSessionRepository sessions;
    @Autowired AppUserRepository users;
    @Autowired Clock clock;

    private Long real;
    private Long dummy;
    private Long noConsent;

    @BeforeEach
    void 사람_셋을_세운다() {
        events.deleteAll();
        sessions.deleteAll();
        real = save("실사용자", true, true);
        dummy = save("더미", false, true);
        noConsent = save("미동의", true, false);
    }

    /** {@code app_user.nickname} 은 유일 제약이라 시험마다 다른 이름을 쓴다. */
    private Long save(String nickname, boolean realPerson, boolean consent) {
        AppUser user = new AppUser(nickname + '-' + java.util.UUID.randomUUID().toString().substring(0, 8),
                java.math.BigDecimal.valueOf(3_000_000),
                java.math.BigDecimal.valueOf(10_000_000), 12);
        user.setRealPerson(realPerson);
        user.setConsentGiven(consent);
        return users.save(user).getId();
    }

    private UsageEventService.Incoming event(String session, int seq, UsageEvent.Kind kind,
                                             String screen, Integer engagedMs) {
        return new UsageEventService.Incoming(session, seq, kind, screen, null, engagedMs,
                LocalDateTime.now(clock), "390x844");
    }

    // ── 관문 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("더미와 미동의는 한 줄도 안 남는다 — 통계의 전제다")
    void 관문이_막는다() {
        assertThat(service.record(dummy, List.of(event("s1", 0, UsageEvent.Kind.SCREEN_VIEW, "home", null))).gated())
                .isTrue();
        assertThat(service.record(noConsent, List.of(event("s2", 0, UsageEvent.Kind.SCREEN_VIEW, "home", null))).gated())
                .isTrue();
        assertThat(events.count()).isZero();
        assertThat(sessions.count()).isZero();
    }

    @Test
    @DisplayName("관문에 걸려도 오류가 아니다 — 오류면 프론트가 영원히 재시도한다")
    void 막혀도_조용히_성공이다() {
        UsageEventService.Result result =
                service.record(dummy, List.of(event("s1", 0, UsageEvent.Kind.SCREEN_VIEW, "home", null)));
        assertThat(result.accepted()).isZero();
        assertThat(result.gated()).isTrue();
    }

    // ── 멱등 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 (세션, 순번) 을 두 번 보내도 한 번만 센다")
    void 재전송을_두_번_세지_않는다() {
        List<UsageEventService.Incoming> batch = List.of(
                event("s1", 0, UsageEvent.Kind.SESSION_START, "home", null),
                event("s1", 1, UsageEvent.Kind.SCREEN_VIEW, "home", null));

        assertThat(service.record(real, batch).accepted()).isEqualTo(2);
        UsageEventService.Result again = service.record(real, batch);
        assertThat(again.accepted()).isZero();
        assertThat(again.duplicated()).isEqualTo(2);
        assertThat(events.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("한 묶음 안의 중복도 막는다 — 클라이언트 큐가 꼬일 수 있다")
    void 묶음_안_중복도_막는다() {
        UsageEventService.Result result = service.record(real, List.of(
                event("s1", 0, UsageEvent.Kind.SCREEN_VIEW, "home", null),
                event("s1", 0, UsageEvent.Kind.SCREEN_VIEW, "home", null)));
        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.duplicated()).isEqualTo(1);
    }

    // ── 세션 속성 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("세션 속성은 한 줄만 생기고, UA 원문은 어디에도 안 남는다")
    void 세션은_한_줄이다() {
        var info = new UsageEventService.SessionInfo("s1",
                "https://search.naver.com/search.naver?query=비밀검색어", null, null, null,
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15"
                        + " (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1",
                "390x844", "ko-KR", "Asia/Seoul", "ios");

        service.record(real, List.of(event("s1", 0, UsageEvent.Kind.SESSION_START, "boot", null)), info);
        service.record(real, List.of(event("s1", 1, UsageEvent.Kind.SCREEN_VIEW, "home", null)), info);

        assertThat(sessions.count()).isEqualTo(1);
        UsageSession row = sessions.findById("s1").orElseThrow();
        assertThat(row.getChannel()).isEqualTo(UsageSession.Channel.ORGANIC.name());
        // 검색어가 실린 질의는 버려지고 호스트만 남는다.
        assertThat(row.getReferrerHost()).isEqualTo("search.naver.com");
        assertThat(row.getSource()).isEqualTo("search.naver.com");
        assertThat(row.getBrowser()).isEqualTo("Safari");
        assertThat(row.getOs()).isEqualTo("iOS");
        assertThat(row.getDeviceCategory()).isEqualTo("mobile");
        assertThat(row.getCountry()).isEqualTo("KR");
        assertThat(row.getTimeZone()).isEqualTo("Asia/Seoul");
    }

    @Test
    @DisplayName("남의 세션 속성이 새어 들지 않는다 — 한 묶음에 두 세션이 섞여 올 수 있다")
    void 속성은_자기_세션에만_붙는다() {
        var info = new UsageEventService.SessionInfo("s1", "https://www.instagram.com/", null, null,
                null, null, null, "ko-KR", "Asia/Seoul", "web");

        service.record(real, List.of(
                event("s1", 0, UsageEvent.Kind.SESSION_START, "home", null),
                event("s2", 0, UsageEvent.Kind.SESSION_START, "home", null)), info);

        assertThat(sessions.findById("s1").orElseThrow().getChannel())
                .isEqualTo(UsageSession.Channel.SOCIAL.name());
        // 속성 블록이 없는 세션은 최소한으로 만들어진다 — 사라지면 세션 수가 조용히 준다.
        UsageSession bare = sessions.findById("s2").orElseThrow();
        assertThat(bare.getChannel()).isEqualTo(UsageSession.Channel.DIRECT.name());
        assertThat(bare.getBrowser()).isNull();
    }

    @Test
    @DisplayName("속성 블록을 실은 요청이 유실돼도 세션은 남는다")
    void 블록이_없어도_세션은_생긴다() {
        service.record(real, List.of(event("s9", 5, UsageEvent.Kind.CLICK, "home", 1_000)), null);
        assertThat(sessions.findById("s9")).isPresent();
    }

    // ── 참여시간 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("진입 이벤트에는 참여시간이 없고, 말이 안 되는 값은 버린다")
    void 참여시간을_거른다() {
        service.record(real, List.of(
                event("s1", 0, UsageEvent.Kind.SCREEN_VIEW, "home", 5_000),      // 진입 → 버려짐
                event("s1", 1, UsageEvent.Kind.ENGAGEMENT, "home", 9_000),
                event("s1", 2, UsageEvent.Kind.ENGAGEMENT, "home", 99_999_999),  // 30분 초과 → 버려짐
                event("s1", 3, UsageEvent.Kind.ENGAGEMENT, "home", -1)));        // 음수 → 버려짐

        Map<String, Integer> byKind = new java.util.HashMap<>();
        events.findAll().forEach(e -> byKind.put(e.getKind() + ":" + e.getSeq(),
                e.getEngagedMs() == null ? -1 : e.getEngagedMs()));
        assertThat(byKind.get("SCREEN_VIEW:0")).isEqualTo(-1);
        assertThat(byKind.get("ENGAGEMENT:1")).isEqualTo(9_000);
        assertThat(byKind.get("ENGAGEMENT:2")).isEqualTo(-1);
        assertThat(byKind.get("ENGAGEMENT:3")).isEqualTo(-1);
    }

    // ── 집계 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("참여 세션은 10초 이상 또는 화면 2개 — 둘 다 아니면 이탈이다")
    @SuppressWarnings("unchecked")
    void 참여율과_이탈률은_서로의_여집합이다() {
        // s1: 화면 하나 + 3초 → 이탈
        service.record(real, List.of(
                event("s1", 0, UsageEvent.Kind.SCREEN_VIEW, "home", null),
                event("s1", 1, UsageEvent.Kind.ENGAGEMENT, "home", 3_000)));
        // s2: 화면 둘 → 참여 (시간은 짧아도)
        service.record(real, List.of(
                event("s2", 0, UsageEvent.Kind.SCREEN_VIEW, "home", null),
                event("s2", 1, UsageEvent.Kind.SCREEN_VIEW, "report", null)));
        // s3: 화면 하나 + 12초 → 참여
        service.record(real, List.of(
                event("s3", 0, UsageEvent.Kind.SCREEN_VIEW, "my", null),
                event("s3", 1, UsageEvent.Kind.ENGAGEMENT, "my", 12_000)));

        Map<String, Object> summary =
                (Map<String, Object>) stats.overview(30).get("summary");
        assertThat(summary.get("sessions")).isEqualTo(3L);
        assertThat(summary.get("engagedSessions")).isEqualTo(2L);
        assertThat((Double) summary.get("engagementRate")).isEqualTo(66.67);
        assertThat((Double) summary.get("bounceRate")).isEqualTo(33.33);
    }

    @Test
    @DisplayName("분모가 0이면 0%가 아니라 '없음'이다 — 0%는 거짓말이다")
    @SuppressWarnings("unchecked")
    void 없는_것을_0으로_적지_않는다() {
        Map<String, Object> summary = (Map<String, Object>) stats.overview(30).get("summary");
        assertThat(summary.get("sessions")).isEqualTo(0L);
        assertThat(summary.get("engagementRate")).isNull();
        assertThat(summary.get("bounceRate")).isNull();
        assertThat(summary.get("avgEngagedMsPerSession")).isNull();
    }

    @Test
    @DisplayName("진입·이탈 화면과 화면 이동이 세션 순서를 그대로 읽는다")
    @SuppressWarnings("unchecked")
    void 진입_이탈_이동() {
        service.record(real, List.of(
                event("s1", 0, UsageEvent.Kind.SESSION_START, "boot", null),
                event("s1", 1, UsageEvent.Kind.SCREEN_VIEW, "home", null),
                event("s1", 2, UsageEvent.Kind.SCREEN_VIEW, "report", null),
                event("s1", 3, UsageEvent.Kind.SCREEN_VIEW, "my", null)));

        Map<String, Object> out = stats.overview(30);
        List<Map<String, Object>> landing = (List<Map<String, Object>>) out.get("landingScreens");
        List<Map<String, Object>> exit = (List<Map<String, Object>>) out.get("exitScreens");
        List<Map<String, Object>> flow = (List<Map<String, Object>>) out.get("screenFlow");

        assertThat(landing).singleElement().extracting(m -> m.get("screen")).isEqualTo("home");
        assertThat(exit).singleElement().extracting(m -> m.get("screen")).isEqualTo("my");
        // 바로 이어진 쌍만 잇는다 — home→my 는 없어야 한다.
        assertThat(flow).extracting(m -> m.get("from") + "→" + m.get("to"))
                .containsExactlyInAnyOrder("home→report", "report→my");
    }

    @Test
    @DisplayName("전환은 설정이 정한다 — 분모는 세션이다")
    @SuppressWarnings("unchecked")
    void 전환을_센다() {
        service.record(real, List.of(
                event("s1", 0, UsageEvent.Kind.SCREEN_VIEW, "home", null),
                event("s1", 1, UsageEvent.Kind.SCREEN_VIEW, "done", null)));
        service.record(real, List.of(event("s2", 0, UsageEvent.Kind.SCREEN_VIEW, "home", null)));

        List<Map<String, Object>> keys =
                (List<Map<String, Object>>) stats.overview(30).get("keyEvents");
        Map<String, Object> confirm = keys.stream()
                .filter(k -> "SCREEN_VIEW".equals(k.get("kind")) && "done".equals(k.get("screen")))
                .findFirst().orElseThrow();
        assertThat(confirm.get("sessions")).isEqualTo(1L);
        assertThat(confirm.get("sessionRate")).isEqualTo(50.0);
    }

    @Test
    @DisplayName("획득·기기·인구통계가 세션 표에서 나온다 — 이벤트 수에 안 휘둘린다")
    @SuppressWarnings("unchecked")
    void 세션_축_집계는_이벤트_수에_안_휘둘린다() {
        var info = new UsageEventService.SessionInfo("s1", "https://www.google.com/", null, null,
                null, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                + " (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                "1920x1080", "ko-KR", "Asia/Seoul", "web");
        // 한 세션에 이벤트를 잔뜩 — 세션 축 집계는 그래도 1이라야 한다.
        service.record(real, List.of(
                event("s1", 0, UsageEvent.Kind.SESSION_START, "home", null),
                event("s1", 1, UsageEvent.Kind.CLICK, "home", 1_000),
                event("s1", 2, UsageEvent.Kind.CLICK, "home", 1_000),
                event("s1", 3, UsageEvent.Kind.CLICK, "home", 1_000)), info);

        Map<String, Object> out = stats.overview(30);
        Map<String, Object> acq = (Map<String, Object>) out.get("acquisition");
        List<Map<String, Object>> channels = (List<Map<String, Object>>) acq.get("byChannel");
        assertThat(channels).singleElement()
                .satisfies(m -> {
                    assertThat(m.get("channel")).isEqualTo("ORGANIC");
                    assertThat(m.get("sessions")).isEqualTo(1L);   // 4가 아니다
                });

        Map<String, Object> tech = (Map<String, Object>) out.get("tech");
        assertThat((List<Map<String, Object>>) tech.get("byBrowser"))
                .singleElement().extracting(m -> m.get("browser")).isEqualTo("Chrome");
        assertThat((List<Map<String, Object>>) tech.get("byDevice"))
                .singleElement().extracting(m -> m.get("device")).isEqualTo("desktop");
    }

    @Test
    @DisplayName("성별·연령은 사람에게 붙는다 — 인증 전이면 '(미상)' 이지 빈칸이 아니다")
    @SuppressWarnings("unchecked")
    void 인구통계는_사람에게_붙는다() {
        AppUser user = users.findById(real).orElseThrow();
        user.setGender("FEMALE");
        user.setBirthYear(LocalDateTime.now(clock).getYear() - 30);
        users.save(user);

        service.record(real, List.of(event("s1", 0, UsageEvent.Kind.SCREEN_VIEW, "home", null)));

        Map<String, Object> demo = (Map<String, Object>) stats.overview(30).get("demographics");
        assertThat((List<Map<String, Object>>) demo.get("byGender"))
                .singleElement().satisfies(m -> {
                    assertThat(m.get("gender")).isEqualTo("FEMALE");
                    assertThat(m.get("users")).isEqualTo(1L);
                });
        assertThat((List<Map<String, Object>>) demo.get("byAge"))
                .singleElement().extracting(m -> m.get("age")).isEqualTo("25-34");
    }

    // ── 파기 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("보관기간이 지나면 이벤트와 함께 빈 세션 줄도 사라진다")
    void 보관기간_정리가_세션까지_치운다() {
        service.record(real, List.of(event("s1", 0, UsageEvent.Kind.SCREEN_VIEW, "home", null)),
                new UsageEventService.SessionInfo("s1", null, null, null, null, null, null,
                        "ko-KR", "Asia/Seoul", "web"));

        // 보관기간보다 훨씬 오래된 것으로 밀어 놓는다.
        events.findAll().forEach(e -> {
            events.delete(e);
            events.save(new UsageEvent(real, e.getSessionId(), e.getSeq(),
                    UsageEvent.Kind.valueOf(e.getKind()), e.getScreen(), e.getElement(),
                    e.getEngagedMs(), LocalDateTime.now(clock).minusDays(400),
                    e.getClientAt(), e.getViewport()));
        });

        // 세션 줄도 함께 민다. 운영에서는 세션이 첫 이벤트와 같은 시각에 열리므로 둘이 같이
        // 늙지만, 시험에서 이벤트만 밀면 세션은 '아직 안 늙은' 것이라 안 지워지는 것이 맞다.
        sessions.save(new UsageSession("s1", real, LocalDateTime.now(clock).minusDays(400)));

        assertThat(service.purgeExpired()).isEqualTo(1);
        assertThat(events.count()).isZero();
        assertThat(sessions.count()).isZero();
    }
}
