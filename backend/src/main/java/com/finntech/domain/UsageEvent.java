package com.finntech.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 실사용자의 행태 기록 한 건 — 화면·클릭·참여시간 (V35).
 *
 * <p>근거는 개인정보 처리방침 정본 33조가 수집 항목에 적어 둔 <i>"이용자의 서비스 이용내역을
 * 비롯한 행태정보"</i> 다. 그 조항이 함께 정한 두 가지를 코드가 지킨다 — <b>동의한 실사용자만</b>
 * 기록하고, <b>탈퇴·철회 시 파기</b>한다.
 *
 * <h2>담지 않는 것이 절반이다</h2>
 *
 * <p>클릭을 자동으로 줍기 때문에 <b>무엇을 읽느냐가 곧 무엇이 새느냐</b>다. 이 앱의 버튼
 * 라벨에는 실제로 개인정보가 있다({@code aria-label={`${g.name} 삭제`}}). 그래서 수집기가
 * 텍스트를 읽지 않고, 이 표에는 <b>금액·가맹점명·사업자번호·결제 식별자를 담을 칸 자체가 없다.</b>
 * 넣을 자리가 없어야 안 들어간다.
 *
 * <h2>{@link #engagedMs} 는 서버가 만들 수 없는 값이다</h2>
 *
 * <p>"이벤트 사이 간격"으로 체류를 재면 앱을 켜 두고 자리를 비운 시간이 그대로 잡힌다.
 * GA4 는 Page Visibility API 로 <b>포그라운드 시간만</b> 세고, 이벤트마다 직전 이벤트 이후의
 * <b>델타</b>를 실어 보낸다. 서버는 그 기기가 백그라운드였는지 알 방법이 없으므로
 * 클라이언트가 준 값을 그대로 적는다.
 */
@Entity
@Table(name = "usage_event",
        uniqueConstraints = @UniqueConstraint(name = "uq_usage_event_session_seq",
                columnNames = {"session_id", "seq"}),
        indexes = {
                @Index(name = "idx_usage_event_user_time", columnList = "user_id, occurred_at"),
                @Index(name = "idx_usage_event_screen", columnList = "screen, occurred_at"),
                @Index(name = "idx_usage_event_occurred", columnList = "occurred_at")
        })
public class UsageEvent {

    /**
     * 무슨 일이 있었나.
     *
     * <p>GA4 의 {@code session_start}·{@code screen_view}·{@code click}·{@code user_engagement}
     * 에 대응한다. 종류를 늘리기 전에 <b>그것이 통계에서 무엇을 답하는지</b> 먼저 정한다 —
     * 답할 것이 없는 이벤트는 잡음이다.
     */
    public enum Kind {
        /** 세션이 열렸다. 30분 이상 조용하면 클라이언트가 새 세션을 연다. */
        SESSION_START,
        /** 화면에 들어왔다. 막 들어왔으므로 {@code engagedMs} 가 없다. */
        SCREEN_VIEW,
        /** 무언가를 눌렀다. {@code element} 가 있는 유일한 종류. */
        CLICK,
        /**
         * 참여 시간 보고.
         *
         * <p>화면을 옮기기 직전·앱을 숨길 때·주기적으로 보낸다. <b>세션의 마지막 화면도
         * 체류가 잡히는 이유</b>가 이것이다 — 다음 이벤트를 기다리지 않는다.
         */
        ENGAGEMENT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 클라이언트가 만든 세션 식별자. 서버가 나누지 않는 이유는 백그라운드 시간을 모르기 때문이다. */
    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    /** 세션 안 순번 — {@code (sessionId, seq)} 가 유일해 재전송을 두 번 세지 않는다. */
    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "screen", nullable = false, length = 40)
    private String screen;

    /** {@code data-track} 값 또는 DOM 구조 경로. <b>텍스트가 아니다.</b> */
    @Column(name = "element", length = 80)
    private String element;

    /** 직전 이벤트 이후 포그라운드 누적 ms. 진입 이벤트에는 없다. */
    @Column(name = "engaged_ms")
    private Integer engagedMs;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** 기기가 찍은 시각 — 집계에 쓰지 않고 <b>기기 시계가 틀렸는지</b> 보는 용도다. */
    @Column(name = "client_at", nullable = false)
    private LocalDateTime clientAt;

    /**
     * <b>창</b> 크기 {@code '390x844'}. 회전·리사이즈로 세션 도중에 바뀌므로 여기 둔다.
     *
     * <p>세션 내내 안 변하는 것(플랫폼·기기 화면 전체 크기·브라우저·OS·유입경로·언어·시간대)은
     * {@link UsageSession} 이 세션당 한 줄로 갖는다 — 이벤트 줄마다 되풀이하면 가장 빨리 자라는
     * 표가 더 빨리 자란다.
     */
    @Column(name = "viewport", length = 12)
    private String viewport;

    protected UsageEvent() {}

    public UsageEvent(Long userId, String sessionId, int seq, Kind kind, String screen,
                      String element, Integer engagedMs, LocalDateTime occurredAt,
                      LocalDateTime clientAt, String viewport) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.seq = seq;
        this.kind = kind.name();
        this.screen = screen;
        this.element = element;
        this.engagedMs = engagedMs;
        this.occurredAt = occurredAt;
        this.clientAt = clientAt;
        this.viewport = viewport;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getSessionId() { return sessionId; }
    public int getSeq() { return seq; }
    public String getKind() { return kind; }
    public String getScreen() { return screen; }
    public String getElement() { return element; }
    public Integer getEngagedMs() { return engagedMs; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public LocalDateTime getClientAt() { return clientAt; }
    public String getViewport() { return viewport; }
}
