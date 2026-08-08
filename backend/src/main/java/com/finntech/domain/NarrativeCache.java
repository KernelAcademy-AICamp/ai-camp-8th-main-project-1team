package com.finntech.domain;

import jakarta.persistence.*;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 사용자에게 보이는 <b>문장 한 줄</b>을 저장해 둔 것 (V25).
 *
 * <p><b>왜 저장하나.</b> 문장을 만들려면 모델을 불러야 하고 그건 6~10초다. 화면이 그걸 기다리면
 * 안 되고, 그렇다고 매번 부르면 같은 화면을 두 번 열 때 다른 말이 나온다. 저장해 두면
 * <b>즉시 그리고, 갱신은 뒤에서</b> 할 수 있다.
 *
 * <p><b>실패가 아무것도 안 망친다.</b> 새로 못 받으면 있던 문장이 그대로 남는다 — 사용자는
 * 어제 문장을 본다. 처음부터 없었으면 고정 템플릿을 본다.
 */
@Entity
@Table(name = "narrative_cache",
        uniqueConstraints = @UniqueConstraint(name = "uk_narrative_user_kind",
                columnNames = {"user_id", "kind", "subject"}))
public class NarrativeCache {

    /** 어느 화면의 문장인가. */
    public enum Kind { REPORT, PROFILE, CUT_CANDIDATE }

    /** 하루 지나면 새로 만든다 — <b>날짜가 아니라 마지막 생성으로부터</b>다. */
    public static final Duration MAX_AGE = Duration.ofHours(24);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private Kind kind;

    /**
     * <b>같은 화면 안에서 다시 갈리는 축.</b> 절약 후보는 카테고리마다 다른 문장이다.
     *
     * <p>이 칸이 없으면 (사용자, 화면) 한 행뿐이라 <b>카페 문장이 쇼핑 화면에 뜨고 서로
     * 덮어쓴다</b>(2026-08-08 PR 직전 감사). 갈릴 것이 없는 화면은 빈 문자열이다.
     */
    @Column(nullable = false, length = 60)
    private String subject = "";

    /**
     * <b>{@code @Lob} 을 안 쓴다.</b> 그러면 기본 길이 255 로 잡혀 Hibernate 가 {@code tinytext} 를
     * 기대하는데 V25 는 {@code TEXT} 로 만든다 — 운영은 {@code ddl-auto: validate} 라 그 차이
     * 하나로 <b>기동이 실패한다.</b> 마이그레이션 예행에서 잡았다(2026-08-08). 잡지 못했으면
     * V25 가 적용된 뒤라 규칙 3 때문에 파일도 못 고치는 상태가 됐다.
     *
     * <p>길이를 명시하면 방언이 {@code TEXT} 로 맞춰 본다. 문장은 두세 줄이라 넘칠 일이 없다.
     */
    @Column(nullable = false, length = 65535)
    private String body;

    /** {@code AI} 또는 {@code TEMPLATE}. 통로가 도는지 보는 단서다. */
    @Column(nullable = false, length = 20)
    private String source;

    /**
     * 이 문장이 <b>근거한 숫자</b>의 지문.
     *
     * <p>이것이 없으면 "지난주 숫자로 쓴 문장이 오늘 숫자 옆에" 붙는다. 문장은 그때의 집계를
     * 말로 옮긴 것이라 숫자가 바뀌면 같이 낡는다.
     */
    @Column(nullable = false, length = 64)
    private String basis;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    /**
     * <b>마지막으로 시도한 시각</b> — 성공·실패 무관.
     *
     * <p>이것이 실패의 무한 반복을 막는다. 큐에 넣는 트리거가 사용자의 아무 상호작용이라,
     * 실패하고 아무것도 안 적히면 <b>페이지를 넘길 때마다</b> 같은 것을 다시 넣는다.
     */
    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;

    /** 연속 실패 횟수. 성공하면 0 으로 돌아간다. */
    @Column(nullable = false)
    private int failures;

    @Column(name = "real_person", nullable = false)
    private boolean realPerson;

    protected NarrativeCache() {}

    public NarrativeCache(Long userId, Kind kind, String subject, String body, String source,
                          String basis, LocalDateTime at, boolean realPerson) {
        this.userId = userId;
        this.kind = kind;
        this.subject = subject == null ? "" : subject;
        this.body = body;
        this.source = source;
        this.basis = basis;
        this.generatedAt = at;
        this.attemptedAt = at;
        this.realPerson = realPerson;
    }

    /** 새 문장을 받았다 — 실패 기록도 함께 지운다. */
    public void renew(String body, String basis, LocalDateTime at) {
        this.body = body;
        this.source = "AI";
        this.basis = basis;
        this.generatedAt = at;
        this.attemptedAt = at;
        this.failures = 0;
    }

    /**
     * 시도했는데 못 받았다 — <b>본문은 건드리지 않는다.</b>
     *
     * <p>여기서 "만들 수 없는 문장"이라고 적으면 안 된다. 답을 못 받은 것과 답이 없는 것은
     * 다르고, 앞의 것을 사실로 적으면 통로 장애가 데이터로 굳는다(§13-13 의 '헛물'과 같은 이유).
     * 적는 것은 <b>우리가 시도했다</b>는 사실뿐이다.
     */
    public void noteFailure(LocalDateTime at) {
        this.attemptedAt = at;
        this.failures++;
    }

    /**
     * <b>아직 할 일이 남았는가</b> — 낡았거나, 아직 한 번도 모델 문장을 못 받았거나.
     *
     * <p>뒤엣것이 요점이다. 첫 시도가 실패하면 이 행은 <b>템플릿을 담은 채 방금 만들어진</b>
     * 상태가 된다 — {@code generatedAt} 이 지금이고 {@code basis} 도 지금 것이라 "안 낡았다"로
     * 읽힌다. 그러면 <b>24시간 동안 재시도가 아예 막히고</b>, 그 유예를 조절하려고 둔
     * {@link #mayRetry} 는 호출조차 안 된다 — 죽은 코드가 된다(2026-08-08 PR 직전 감사).
     *
     * <p>그래서 "모델 문장을 받은 적이 있는가"를 함께 본다. 재시도 간격은 {@link #mayRetry} 가 잡는다.
     */
    public boolean needsWork(String currentBasis, LocalDateTime now) {
        if (!"AI".equals(source)) return true;      // 아직 한 번도 못 받았다
        if (!basis.equals(currentBasis)) return true;
        return generatedAt.plus(MAX_AGE).isBefore(now);
    }

    /**
     * 지금 다시 시도해도 되는가 — <b>실패가 쌓였으면 쉬었다 간다.</b>
     *
     * <p>유예가 없으면 통로 장애 하나가 사용자의 상호작용마다 예산을 먹는다.
     */
    public boolean mayRetry(LocalDateTime now) {
        if (failures == 0) return true;
        // 2^n 분, 6시간에서 멈춘다. 예전에는 지수를 6으로 잘라 **상한 360이 도달 불가**였다
        // (2^6 = 64분이 최대였다) — 주석과 코드가 갈라져 있었다.
        long minutes = Math.min(360L, 1L << Math.min(9, failures));
        return attemptedAt.plusMinutes(minutes).isBefore(now);
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Kind getKind() { return kind; }
    public String getBody() { return body; }
    public String getSubject() { return subject; }
    public String getSource() { return source; }
    public String getBasis() { return basis; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public int getFailures() { return failures; }
    public boolean isRealPerson() { return realPerson; }
}
