package com.finntech.usage;

import com.finntech.domain.AppUser;
import com.finntech.domain.UsageEvent;
import com.finntech.domain.UsageSession;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.UsageEventRepository;
import com.finntech.repository.UsageSessionRepository;
import com.finntech.repository.UserPaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 행태 기록을 받아 적는다 — <b>관문·멱등·정리</b>가 여기 모인다.
 *
 * <h2>관문 둘</h2>
 *
 * <ol>
 *   <li><b>실사용자만.</b> 더미 계정의 발자취는 통계를 오염시킨다 — 데모로 둘러본 사람의 클릭이
 *       실제 이용 패턴인 척 섞인다. 소비 원장이 같은 이유로 같은 관문을 둔다.
 *   <li><b>동의한 사람만.</b> 방침 1조가 <i>"동의가 없을 시 더미 데이터 기반 데모 모드"</i> 라고
 *       한다. 동의 없이 행태를 적으면 방침이 약속하지 않은 수집이 된다.
 * </ol>
 *
 * <p>관문에 걸린 것은 <b>조용히 버린다.</b> 오류로 돌려주면 프론트가 재시도하고, 재시도해도
 * 영원히 같은 답이라 요청만 쌓인다. 게다가 응답으로 "당신은 실사용자가 아니다"를 알려 줄
 * 이유도 없다.
 *
 * <h2>멱등</h2>
 *
 * <p>화면을 벗어날 때 {@code sendBeacon} 으로 밀어낸 묶음은 <b>다음 기동에서 한 번 더 갈 수
 * 있다.</b> {@code (sessionId, seq)} 유일 제약이 그것을 막고, 여기서는 넣기 전에 한 번 걸러
 * 제약 위반 예외로 트랜잭션이 통째로 깨지는 것을 피한다.
 */
@Service
public class UsageEventService {

    private static final Logger log = LoggerFactory.getLogger(UsageEventService.class);

    /** 한 번에 받는 최대 건수. 넘치면 자른다 — 큐가 밀린 기기가 서버를 밀지 않게. */
    static final int MAX_BATCH = 100;

    /** 화면·요소 식별자 길이 상한. 칸 폭과 같아야 잘림으로 실패하지 않는다. */
    private static final int SCREEN_MAX = 40;
    private static final int ELEMENT_MAX = 80;

    /** 한 번에 지우는 양 — 보관기간 정리가 한 트랜잭션에 수만 행을 싣지 않게. */
    private static final int PURGE_CHUNK = 5_000;

    private final UsageEventRepository events;
    private final UsageSessionRepository sessions;
    private final AppUserRepository users;
    private final UserPaymentRepository payments;
    private final Clock clock;
    private final int retentionDays;

    public UsageEventService(UsageEventRepository events, UsageSessionRepository sessions,
                             AppUserRepository users,
                             UserPaymentRepository payments, Clock clock,
                             @Value("${finntech.usage.retention-days:90}") int retentionDays) {
        this.events = events;
        this.sessions = sessions;
        this.users = users;
        this.payments = payments;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    /** 클라이언트가 보내는 한 건. */
    public record Incoming(String sessionId, int seq, UsageEvent.Kind kind, String screen,
                           String element, Integer engagedMs, LocalDateTime clientAt,
                           String viewport) {}

    /**
     * 세션이 열릴 때 <b>한 번만</b> 오는 블록 — 그 세션 내내 안 변하는 것들.
     *
     * <p>유입 경로·브라우저·OS·기기·해상도·언어·시간대는 세션이 시작될 때 정해지고 끝날 때까지
     * 그대로다. 이벤트마다 실어 보내면 <b>같은 값을 수백 번 보내고 수백 번 저장한다.</b>
     * 그래서 클라이언트는 세션을 열 때만 이 블록을 붙이고, 서버는 세션 표에 한 줄만 남긴다.
     *
     * <p>{@code userAgent} 는 <b>저장하지 않는다.</b> {@link UserAgentParser} 가 브라우저·OS·
     * 기기 종류로 줄인 값만 적히고 원문은 버려진다 — UA 원문은 그 자체로 지문이다.
     */
    public record SessionInfo(String sessionId, String referrer, String source, String medium,
                              String campaign, String userAgent, String screenSize,
                              String language, String timeZone, String platform) {}

    /** 받은 결과 — 프론트는 안 읽지만 시험과 로그가 읽는다. */
    public record Result(int accepted, int duplicated, boolean gated) {

        static Result refused() { return new Result(0, 0, true); }
    }

    /** 칸 폭을 넘는 값은 잘라 넣는다 — 길이 하나로 묶음 전체가 실패하면 안 된다. */
    private static String clip(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /**
     * 한 묶음을 받아 적는다.
     *
     * @param userId {@code AuthFilter} 가 확인한 토큰의 주인 — <b>요청 본문에서 받지 않는다.</b>
     *               지금 있는 {@code /api/analytics/track} 은 public 이라 아무나 남의 번호로
     *               쏠 수 있는데, 통계가 목적이면 그 값은 못 쓴다.
     */
    @Transactional
    public Result record(Long userId, List<Incoming> batch) {
        return record(userId, batch, null);
    }

    /**
     * @param info 세션이 열릴 때만 실려 오는 고정 속성. 없으면 세션 줄은 <b>최소한으로</b>
     *             만들어진다 — 그 블록을 실은 요청이 유실될 수 있고, 그때 세션 자체가 통계에서
     *             사라지면 세션 수가 조용히 줄어든다
     */
    @Transactional
    public Result record(Long userId, List<Incoming> batch, SessionInfo info) {
        if (batch == null || batch.isEmpty()) return new Result(0, 0, false);
        if (!eligible(userId)) return Result.refused();

        List<Incoming> capped = batch.size() > MAX_BATCH ? batch.subList(0, MAX_BATCH) : batch;
        if (batch.size() > MAX_BATCH) {
            log.warn("행태 기록 묶음이 {}건이라 {}건만 받는다 — userId={}", batch.size(), MAX_BATCH, userId);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        // 같은 묶음 안의 중복도 막는다. 클라이언트 큐가 꼬이면 한 요청에 같은 순번이 둘 올 수 있다.
        Set<String> seenInBatch = new HashSet<>();
        List<UsageEvent> fresh = new ArrayList<>(capped.size());
        int duplicated = 0;

        for (Incoming in : capped) {
            if (in == null || in.sessionId() == null || in.sessionId().isBlank()
                    || in.kind() == null || in.screen() == null || in.screen().isBlank()) {
                continue;                                   // 형태가 안 맞으면 버린다
            }
            String key = in.sessionId() + '' + in.seq();
            if (!seenInBatch.add(key) || events.existsBySessionIdAndSeq(in.sessionId(), in.seq())) {
                duplicated++;
                continue;
            }
            fresh.add(new UsageEvent(
                    userId,
                    clip(in.sessionId(), 36),
                    in.seq(),
                    in.kind(),
                    clip(in.screen(), SCREEN_MAX),
                    // 요소 식별자는 CLICK 에만 둔다. 다른 종류에 붙어 오면 버린다 —
                    // 칸의 뜻이 흐려지면 집계가 뜻을 잃는다.
                    in.kind() == UsageEvent.Kind.CLICK ? clip(in.element(), ELEMENT_MAX) : null,
                    // 진입 이벤트에는 참여시간이 없다(막 들어왔다). 음수·비정상은 버린다.
                    engagedOf(in),
                    now,
                    in.clientAt() == null ? now : in.clientAt(),
                    clip(in.viewport(), 12)));
        }
        if (!fresh.isEmpty()) events.saveAll(fresh);
        // 세션 줄은 이벤트를 넣은 뒤에 챙긴다 — 관문에 걸린 요청은 여기까지 오지 않는다.
        openSessions(userId, fresh, info, now);
        return new Result(fresh.size(), duplicated, false);
    }

    /**
     * 이번 묶음에 처음 보는 세션이 있으면 세션 줄을 연다.
     *
     * <p>{@code SESSION_START} 가 왔는지로 판단하지 <b>않는다.</b> 그 이벤트를 실은 요청만
     * 유실돼도 세션이 통째로 통계에서 사라진다. 대신 "표에 없는 세션 식별자"를 기준으로 삼아,
     * 어느 이벤트가 먼저 닿든 한 줄이 생기게 한다.
     */
    private void openSessions(Long userId, List<UsageEvent> fresh, SessionInfo info,
                              LocalDateTime now) {
        Set<String> ids = new HashSet<>();
        for (UsageEvent e : fresh) ids.add(e.getSessionId());
        for (String id : ids) {
            if (sessions.existsById(id)) continue;
            UsageSession session = new UsageSession(id, userId, now);
            // 블록은 자기 세션에만 적용한다. 한 묶음에 두 세션이 섞여 올 수 있는데(세션이
            // 바뀌는 순간의 큐), 그때 남의 속성을 붙이면 유입 경로가 뒤바뀐다.
            if (info != null && id.equals(info.sessionId())) apply(session, info);
            sessions.save(session);
        }
    }

    private static void apply(UsageSession session, SessionInfo info) {
        String host = hostOf(info.referrer());
        String source = clip(info.source(), 60);
        String medium = clip(info.medium(), 40);
        if (source == null && host != null) source = host;   // utm 이 없으면 참조 호스트가 출처다
        session.acquisition(channelOf(source, medium, host), source, medium,
                clip(info.campaign(), 60), host);

        UserAgentParser.Agent agent = UserAgentParser.parse(info.userAgent(), info.platform());
        session.device(agent.deviceCategory(), agent.browser(), clip(agent.browserVersion(), 10),
                agent.os(), clip(agent.osVersion(), 10),
                clip(info.screenSize(), 12), clip(info.platform(), 20));

        String language = clip(info.language(), 20);
        session.locale(language, clip(info.timeZone(), 40), countryOf(language));
    }

    /** 검색으로 들어온 것 — GA4 의 Organic Search 에 해당한다. */
    private static final Set<String> SEARCH = Set.of(
            "google", "naver", "daum", "bing", "yahoo", "duckduckgo", "yandex", "baidu");
    /** 소셜 — GA4 의 Organic Social. */
    private static final Set<String> SOCIAL = Set.of(
            "facebook", "instagram", "twitter", "x", "threads", "youtube", "linkedin",
            "kakao", "band", "tiktok", "reddit", "t");

    /**
     * 유입 경로의 갈래를 정한다 — <b>규칙을 한 곳에만 둔다.</b>
     *
     * <p>{@code utm_medium} 이 있으면 그 말을 먼저 믿는다(링크를 만든 쪽이 스스로 밝힌 것이다).
     * 없으면 참조 호스트의 <b>등록 이름</b>으로 가른다 — {@code www.google.co.kr} 이든
     * {@code m.search.naver.com} 이든 가운데 한 조각이 서비스 이름이다.
     */
    static UsageSession.Channel channelOf(String source, String medium, String referrerHost) {
        if (medium != null) {
            String m = medium.toLowerCase(Locale.ROOT);
            if (m.contains("organic") || m.contains("search")) return UsageSession.Channel.ORGANIC;
            if (m.contains("social")) return UsageSession.Channel.SOCIAL;
            if (m.contains("referral")) return UsageSession.Channel.REFERRAL;
            if (m.contains("none") || m.contains("direct")) return UsageSession.Channel.DIRECT;
            return UsageSession.Channel.REFERRAL;
        }
        if (referrerHost == null) {
            // utm 만 있고 참조가 없는 경우 — 링크에 표시를 달아 뿌린 것이므로 참조로 본다.
            return source == null ? UsageSession.Channel.DIRECT : UsageSession.Channel.REFERRAL;
        }
        for (String part : referrerHost.toLowerCase(Locale.ROOT).split("\\.")) {
            if (SEARCH.contains(part)) return UsageSession.Channel.ORGANIC;
            if (SOCIAL.contains(part)) return UsageSession.Channel.SOCIAL;
        }
        return UsageSession.Channel.REFERRAL;
    }

    /**
     * 참조 주소에서 <b>호스트만</b> 꺼낸다. 경로·질의는 버린다.
     *
     * <p>남의 사이트 주소에 무엇이 붙어 있을지 모른다 — 검색어가 질의에 그대로 실려 오는 곳이
     * 흔하다. 호스트까지만 남기면 그 위험이 없다.
     */
    static String hostOf(String referrer) {
        if (referrer == null || referrer.isBlank()) return null;
        try {
            String host = java.net.URI.create(referrer.trim()).getHost();
            return host == null || host.isBlank() ? null : clip(host, 100);
        } catch (IllegalArgumentException e) {
            return null;                                   // 주소 꼴이 아니면 버린다
        }
    }

    /**
     * 국가 — <b>브라우저 언어 설정의 지역 부분</b>에서 얻는다({@code ko-KR} → {@code KR}).
     *
     * <p>GA4 는 IP 로 위치를 알아내지만 우리는 IP 를 저장하지 않는다. 그래서 굵기가 국가까지고
     * 도시는 못 낸다. 그리고 이것은 <b>있는 곳이 아니라 쓰는 말</b>이라, 해외에서 한국어로 쓰면
     * KR 로 잡힌다. 그 한계를 통계 화면에도 적는다.
     */
    static String countryOf(String languageTag) {
        if (languageTag == null || languageTag.isBlank()) return null;
        String country = Locale.forLanguageTag(languageTag.trim()).getCountry();
        return country == null || country.length() != 2 ? null : country;
    }

    /**
     * 참여시간을 받아들일지 — <b>말이 안 되는 값은 버린다.</b>
     *
     * <p>기기 시계가 튀거나 탭이 오래 잠들었다 깨면 몇 시간짜리 값이 올 수 있다. 그것을 그대로
     * 합치면 "이 화면 평균 체류 3시간" 같은 통계가 나온다. 한 번에 30분을 넘으면 버린다 —
     * 그 사이 {@code ENGAGEMENT} 가 여러 번 왔어야 정상이다.
     */
    private static Integer engagedOf(Incoming in) {
        if (in.kind() == UsageEvent.Kind.SESSION_START || in.kind() == UsageEvent.Kind.SCREEN_VIEW) {
            return null;
        }
        Integer ms = in.engagedMs();
        if (ms == null || ms < 0 || ms > 30 * 60 * 1000) return null;
        return ms;
    }

    /**
     * 기록해도 되는 사람인가 — 실사용자이고 동의했는가.
     *
     * <p>{@code app_user.real_person} 이 거짓으로 굳는 경우가 있어(적재를 다시 안 돌리면
     * 갱신되지 않는다) 결제로 한 번 되짚는다. 소비 원장의 관문과 같은 이유·같은 방법이다.
     */
    private boolean eligible(Long userId) {
        if (userId == null) return false;
        AppUser user = users.findById(userId).orElse(null);
        if (user == null || !user.isConsentGiven()) return false;
        return user.isRealPerson() || payments.existsRealPersonPaymentByUserId(userId);
    }

    // ── 보관기간 ──────────────────────────────────────────────────────────────

    /**
     * 보관기간이 지난 기록을 지운다.
     *
     * <p>방침 33조의 보유기간은 "탈퇴·철회까지"라 <b>기간 자체가 상한을 주지는 않는다.</b>
     * 그래도 자르는 이유는 둘이다 — 필요 이상 오래 들고 있을 이유가 없고(개인정보 최소 보유),
     * 이 표는 사용자 한 명이 하루에 수백 행을 만들어 <b>가장 빨리 자라는 표</b>가 된다.
     * 2026-08-18 에 디스크가 95% 까지 찬 뒤라 더 그렇다.
     *
     * @return 지운 행 수
     */
    @Transactional
    public int purgeExpired() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(retentionDays);
        List<Long> ids = events.findIdsOlderThan(cutoff, PageRequest.of(0, PURGE_CHUNK));
        if (ids.isEmpty()) return 0;
        events.deleteAllByIdInBatch(ids);
        // 이벤트가 한 건도 안 남은 세션 줄은 남겨 둘 이유가 없다. 이벤트를 지운 **뒤에** 본다.
        int orphans = sessions.deleteOrphansBefore(cutoff);
        log.info("행태 기록 {}건 파기 (cutoff={}, 남은 만료분 {}건, 빈 세션 {}건)",
                ids.size(), cutoff, events.countByOccurredAtBefore(cutoff), orphans);
        return ids.size();
    }

    public int retentionDays() { return retentionDays; }
}
