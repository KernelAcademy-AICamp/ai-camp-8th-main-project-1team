package com.finntech.web;

import com.finntech.auth.AuthFilter;
import com.finntech.domain.UsageEvent;
import com.finntech.usage.UsageEventService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 행태 기록을 받는 문 — <b>사용자 토큰이 있어야 한다.</b>
 *
 * <h2>왜 기존 {@code /api/analytics/track} 을 안 쓰나</h2>
 *
 * <p>그 문은 {@code AuthFilter} 의 공개 목록에 있고 {@code userId} 를 <b>요청 본문에서</b>
 * 받는다. 즉 누구든 남의 번호로 아무 이벤트나 쏠 수 있다. 익명 사용성 수집(RFP 제출물)에는
 * 그것으로 충분했지만, <b>사람별 통계</b>가 목적이면 위조 가능한 값 위에 세울 수 없다.
 *
 * <p>그래서 이 문은 공개 목록에 넣지 않는다. 사용자 번호는 <b>토큰에서만</b> 온다 —
 * {@code AuthFilter} 가 검증하고 요청 속성에 실어 준 값이다. 본문에 {@code userId} 를 받는
 * 칸 자체를 두지 않아, 보내도 무시되는 것이 아니라 <b>보낼 수가 없다.</b>
 *
 * <h2>응답은 언제나 성공이다</h2>
 *
 * <p>관문(실사용자·동의)에 걸려도 200 을 준다. 오류로 돌려주면 프론트가 재시도하고, 재시도해도
 * 영원히 같은 답이라 요청만 쌓인다. <b>계측이 화면을 방해하면 안 된다</b>는 것이 더 중요하다.
 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private static final Logger log = LoggerFactory.getLogger(UsageController.class);

    private final UsageEventService usage;

    public UsageController(UsageEventService usage) {
        this.usage = usage;
    }

    /** 프론트가 보내는 한 건. {@code userId} 가 없는 것이 요점이다. */
    public record EventPayload(String sessionId, Integer seq, String kind, String screen,
                               String element, Integer engagedMs, Long clientAtEpochMs,
                               String viewport) {}

    /**
     * 세션이 열릴 때 <b>한 번만</b> 실려 오는 고정 속성.
     *
     * <p>유입 경로·브라우저·OS·해상도·언어·시간대는 세션 내내 안 변한다. 이벤트마다 보내면
     * 같은 값을 수백 번 실어 나르게 된다 — 그래서 클라이언트가 세션을 열 때만 붙인다.
     * {@code userAgent} 는 서버가 굵게 줄여 읽고 <b>원문을 저장하지 않는다.</b>
     */
    public record SessionPayload(String sessionId, String referrer, String source, String medium,
                                 String campaign, String userAgent, String screenSize,
                                 String language, String timeZone, String platform) {}

    public record TrackRequest(@Valid List<EventPayload> events, SessionPayload session) {}

    /**
     * 한 묶음을 받는다. 프론트는 5초/20건마다, 그리고 화면을 벗어날 때 {@code sendBeacon} 으로 보낸다.
     *
     * <p>{@code sendBeacon} 은 {@code Content-Type} 을 마음대로 못 정하는 경우가 있어
     * {@code text/plain} 으로 올 수 있다. 그래서 두 형식을 다 받는다.
     */
    @PostMapping(path = "/track", consumes = {"application/json", "text/plain"})
    public ResponseEntity<Void> track(@RequestBody TrackRequest body, HttpServletRequest request) {
        Object subject = request.getAttribute(AuthFilter.ATTR_SUBJECT_ID);
        if (!(subject instanceof Long userId)) {
            // 필터를 껐거나(로컬) 토큰이 없는 경우. 조용히 버린다 — 계측은 화면을 막지 않는다.
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }
        if (body == null || body.events() == null || body.events().isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        List<UsageEventService.Incoming> batch = body.events().stream()
                .map(UsageController::toIncoming)
                .filter(java.util.Objects::nonNull)
                .toList();

        UsageEventService.SessionInfo info = toSessionInfo(body.session());
        UsageEventService.Result result = usage.record(userId, batch, info);
        if (result.duplicated() > 0) {
            log.debug("행태 기록 중복 {}건 — userId={} (재전송으로 본다)", result.duplicated(), userId);
        }
        return ResponseEntity.noContent().build();
    }

    /** 모양이 안 맞으면 {@code null} — 한 건이 이상하다고 묶음 전체를 버리지 않는다. */
    private static UsageEventService.Incoming toIncoming(EventPayload p) {
        if (p == null || p.sessionId() == null || p.seq() == null
            || p.kind() == null || p.screen() == null) {
            return null;
        }
        UsageEvent.Kind kind;
        try {
            kind = UsageEvent.Kind.valueOf(p.kind());
        } catch (IllegalArgumentException e) {
            return null;                       // 모르는 종류는 버린다 — 칸의 뜻이 흐려지면 집계가 죽는다
        }
        LocalDateTime clientAt = p.clientAtEpochMs() == null ? null
                : LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(p.clientAtEpochMs()),
                        java.time.ZoneId.systemDefault());
        return new UsageEventService.Incoming(p.sessionId(), p.seq(), kind, p.screen(),
                p.element(), p.engagedMs(), clientAt, p.viewport());
    }

    private static UsageEventService.SessionInfo toSessionInfo(SessionPayload p) {
        if (p == null || p.sessionId() == null || p.sessionId().isBlank()) return null;
        return new UsageEventService.SessionInfo(p.sessionId(), p.referrer(), p.source(),
                p.medium(), p.campaign(), p.userAgent(), p.screenSize(),
                p.language(), p.timeZone(), p.platform());
    }
}
