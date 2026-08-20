package com.finntech.guardian.domain;

import com.finntech.guardian.domain.GuardianEnums.DeliveryKind;
import com.finntech.guardian.domain.GuardianEnums.Feedback;
import com.finntech.guardian.domain.GuardianEnums.FeedbackReason;
import com.finntech.guardian.domain.GuardianEnums.PhrasingMode;
import com.finntech.guardian.domain.GuardianEnums.SuppressedReason;
import com.finntech.guardian.domain.GuardianEnums.Tone;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 알림 로그 — <b>침묵도 기록한다</b> (설계서 §4.1).
 *
 * <p>"보내지 않기로 했다"도 하나의 결정이다. 침묵 전환율을 지표로 올리려면 그 사실 자체가
 * 남아 있어야 하므로, {@code delivery=SILENT} + {@code suppressedReason}으로 적재한다.
 * 목록 API는 SILENT를 빼고 내려준다 — 계산용이지 사용자에게 보일 것이 아니다.
 */
@Entity
@Table(name = "guardian_notification", indexes = {
        @Index(name = "idx_gnoti_user_sent", columnList = "user_id, sent_at"),
        @Index(name = "idx_gnoti_case", columnList = "challenge_id, case_id, sent_at"),
        @Index(name = "idx_gnoti_budget", columnList = "user_id, delivery, sent_at")
})
public class GuardianNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "challenge_id")
    private Long challengeId;

    @Column(name = "transaction_id")
    private Long transactionId;

    /** C1..C14, W1, M1 */
    @Column(name = "case_id", nullable = false, length = 10)
    private String caseId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Tone tone;

    @Enumerated(EnumType.STRING)
    @Column(name = "phrasing_mode", length = 12)
    private PhrasingMode phrasingMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DeliveryKind delivery;

    @Enumerated(EnumType.STRING)
    @Column(name = "suppressed_reason", length = 20)
    private SuppressedReason suppressedReason;

    @Column(length = 40)
    private String title;

    @Column(length = 200)
    private String body;

    /** 고정구를 걸러낸 특징 표현(CSV) — 반복 감지용. */
    @Column(name = "key_phrases", length = 400)
    private String keyPhrases;

    /** 문장이 LLM이 아니라 고정 템플릿에서 나왔는가. 폴백 사용률(목표 5% 이하) 계측용. */
    @Column(name = "is_fallback", nullable = false)
    private boolean fallback;

    /** 프롬프트 개선 전후 비교용. */
    @Column(name = "prompt_version", length = 20)
    private String promptVersion;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 12)
    private Feedback feedback;

    /** 별점보다 이 태그가 중요하다 — 프롬프트를 어느 방향으로 고칠지는 사유가 정한다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "feedback_reason", length = 20)
    private FeedbackReason feedbackReason;

    @Column(name = "feedback_at")
    private LocalDateTime feedbackAt;

    protected GuardianNotification() {}

    /** 침묵 기록 — 사용자에게 보이지 않지만 지표에는 잡힌다. */
    public static GuardianNotification silent(Long userId, Long challengeId, Long transactionId,
                                              String caseId, SuppressedReason reason, LocalDateTime sentAt) {
        GuardianNotification n = new GuardianNotification();
        n.userId = userId;
        n.challengeId = challengeId;
        n.transactionId = transactionId;
        n.caseId = caseId;
        n.delivery = DeliveryKind.SILENT;
        n.suppressedReason = reason;
        n.sentAt = sentAt;
        return n;
    }

    public static GuardianNotification spoken(Long userId, Long challengeId, Long transactionId, String caseId,
                                              Tone tone, PhrasingMode phrasingMode, DeliveryKind delivery,
                                              String title, String body, List<String> keyPhrases,
                                              boolean fallback, String promptVersion, LocalDateTime sentAt) {
        GuardianNotification n = new GuardianNotification();
        n.userId = userId;
        n.challengeId = challengeId;
        n.transactionId = transactionId;
        n.caseId = caseId;
        n.tone = tone;
        n.phrasingMode = phrasingMode;
        n.delivery = delivery;
        n.title = title;
        n.body = body;
        n.keyPhrases = keyPhrases == null || keyPhrases.isEmpty() ? null : String.join(",", keyPhrases);
        n.fallback = fallback;
        n.promptVersion = promptVersion;
        n.sentAt = sentAt;
        return n;
    }

    /**
     * <b>템플릿으로 나간 알림에 모델 문장을 나중에 얹는다.</b>
     *
     * <p>알림은 규칙이 정한 값으로 <b>먼저 저장</b>되고 화면에 즉시 뜬다. 모델 문장은 그 뒤에
     * 배경에서 받아 이 메서드로 갈아 끼운다 — 화면이 LLM 을 기다리지 않게 하려는 것이다
     * (`GuardianService.deliver` 주석). 이미 모델 문장이 들어간 알림은 건드리지 않는다.
     *
     * @return 실제로 갈아 끼웠으면 true
     */
    public boolean upgradeSentence(String newTitle, String newBody, List<String> newKeyPhrases) {
        if (!fallback) return false;                       // 이미 모델 문장이다
        if (newTitle == null || newBody == null) return false;
        this.title = newTitle;
        this.body = newBody;
        this.keyPhrases = newKeyPhrases == null || newKeyPhrases.isEmpty()
                ? null : String.join(",", newKeyPhrases);
        this.fallback = false;
        return true;
    }

    public void recordFeedback(Feedback feedback, FeedbackReason reason, LocalDateTime at) {
        this.feedback = feedback;
        this.feedbackReason = reason;
        this.feedbackAt = at;
    }

    @Transient
    public List<String> getKeyPhraseList() {
        if (keyPhrases == null || keyPhrases.isBlank()) return List.of();
        return Arrays.stream(keyPhrases.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getChallengeId() { return challengeId; }
    public Long getTransactionId() { return transactionId; }
    public String getCaseId() { return caseId; }
    public Tone getTone() { return tone; }
    public PhrasingMode getPhrasingMode() { return phrasingMode; }
    public DeliveryKind getDelivery() { return delivery; }
    public SuppressedReason getSuppressedReason() { return suppressedReason; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public boolean isFallback() { return fallback; }
    public String getPromptVersion() { return promptVersion; }
    public LocalDateTime getSentAt() { return sentAt; }
    public LocalDateTime getReadAt() { return readAt; }
    public void setReadAt(LocalDateTime v) { this.readAt = v; }
    public Feedback getFeedback() { return feedback; }
    public FeedbackReason getFeedbackReason() { return feedbackReason; }
    public LocalDateTime getFeedbackAt() { return feedbackAt; }
}
