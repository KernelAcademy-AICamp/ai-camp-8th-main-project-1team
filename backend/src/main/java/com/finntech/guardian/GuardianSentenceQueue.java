package com.finntech.guardian;

import com.finntech.guardian.domain.GuardianEnums.PhrasingMode;
import com.finntech.guardian.domain.GuardianEnums.Tone;
import com.finntech.guardian.domain.GuardianNotification;
import com.finntech.guardian.repository.GuardianNotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * <b>알림 문장을 나중에 채운다</b> — 화면이 LLM 을 기다리지 않게.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>예전에는 {@code GuardianService.deliver} 가 알림을 저장하기 <b>전에</b> Gemini 를 불렀다.
 * 그 호출은 건당 최대 11초이고({@code GuardianNarrative} 의 연결 3초 + 읽기 8초),
 * {@code syncFromMyData} 는 새 결제마다 그것을 반복한다. 새 결제 다섯 건이 전부 발화 대상이면
 * <b>홈에 들어가는 데 55초</b>다. 게다가 그 호출이 {@code @Transactional} 안에 있어
 * DB 커넥션을 그동안 붙잡고 있었다.
 *
 * <h2>같은 저장소에 이미 답이 있었다</h2>
 *
 * <p>{@code NarrativeCacheService} 는 화면이 문장을 기다리지 않는다 — 있으면 저장된 문장,
 * 없으면 템플릿을 즉시 주고 새 문장은 뒤에서 만든다. 지킴이만 그 통로를 안 쓰고 있었다.
 * 여기서 같은 규율을 지킴이에 옮긴다:
 *
 * <pre>
 *   규칙이 정한다 → **템플릿으로 저장하고 화면에 띄운다** → 여기 올린다
 *   일꾼이 돈다  → 모델 문장을 받아 그 알림을 갈아 끼운다. 못 받으면 템플릿 그대로 남는다
 * </pre>
 *
 * <h2>무료 통로 큐를 쓰지 않는 이유</h2>
 *
 * <p>{@code FreeChannelQueue} 는 이름 그대로 <b>무료 통로(NVIDIA)</b>의 예산과 순서를 정하는
 * 문이다({@code OneDoorTest} 가 그 규약을 지킨다). 지킴이 문장은 <b>Gemini(유료)</b>라
 * 거기 얹으면 무료 예산을 유료 호출이 갉아먹고, 큐가 무엇을 재는 것인지가 흐려진다.
 * 그래서 일꾼을 따로 둔다 — 다만 <b>한 스레드</b>다. 남의 서버를 연달아 두드리지 않는
 * 성질은 {@code FollowUpExecutorConfig} 가 지키던 것과 같다.
 *
 * <h2>잃어도 되는 일만 올린다</h2>
 *
 * <p>재기동하면 대기 목록이 날아간다. 그래도 잃는 것이 없다 — <b>알림은 이미 저장돼 있고</b>
 * 템플릿 문장이 들어 있다. 못 받은 것은 "덜 예쁜 문장"이지 "없는 알림"이 아니다.
 * 폴백이 먼저인 구조({@code GuardianNarrative} 설계서 §5.4)가 이것을 떠받친다.
 */
@Component
public class GuardianSentenceQueue {

    private static final Logger log = LoggerFactory.getLogger(GuardianSentenceQueue.class);

    private final GuardianNarrative narrative;
    private final GuardianNotificationRepository notifications;
    private final Executor worker;

    public GuardianSentenceQueue(GuardianNarrative narrative,
                                 GuardianNotificationRepository notifications,
                                 @Qualifier(GuardianSentenceExecutorConfig.BEAN) Executor worker) {
        this.narrative = narrative;
        this.notifications = notifications;
        this.worker = worker;
    }

    /** 문장 하나를 만드는 데 필요한 것 전부. 값은 이미 계산이 끝나 있다(원칙 1). */
    public record Job(long notificationId, String caseId, Tone tone, PhrasingMode phrasingMode,
                      Map<String, Object> numbers, List<String> recentKeyPhrases, boolean allowAi) {}

    /**
     * 뒤에서 문장을 받아 갈아 끼우도록 올린다.
     *
     * <p><b>부르는 쪽의 트랜잭션이 끝난 뒤에 돌아야 한다.</b> 안 그러면 일꾼이 아직 커밋되지
     * 않은 알림을 찾으러 가서 못 찾는다. 부르는 쪽이 커밋 뒤에 부르도록 되어 있다
     * ({@code GuardianService.deliver} 의 {@code afterCommit}).
     */
    public void submit(Job job) {
        if (!job.allowAi() || !narrative.aiEnabled()) return;   // 더미·키 없음 — 템플릿 그대로
        try {
            worker.execute(() -> run(job));
        } catch (RejectedExecutionException full) {
            // 큐가 찼다. 버려도 안전하다 — 알림은 이미 템플릿 문장으로 저장돼 있다.
            log.debug("문장 큐가 가득 차 건너뛴다 — notificationId={}", job.notificationId());
        }
    }

    /**
     * <b>부르는 쪽의 트랜잭션이 커밋된 뒤에</b> 올린다.
     *
     * <p>지금 올리면 일꾼이 아직 커밋 안 된 알림을 찾으러 가서 못 찾는다 — 문장이 조용히
     * 안 붙는다. 트랜잭션 밖에서 불렸으면(시험 등) 곧바로 올린다.
     */
    public void submitAfterCommit(Job job) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            submit(job);
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { submit(job); }
                });
    }

    /** 일꾼이 도는 자리 — <b>트랜잭션 밖</b>이다. 받은 것만 트랜잭션을 열어 적는다. */
    private void run(Job job) {
        try {
            GuardianNarrative.Message msg = narrative.compose(
                    job.caseId(), job.tone(), job.phrasingMode(),
                    job.numbers(), job.recentKeyPhrases(), false, true);
            if (msg.fallback()) return;                          // 못 받았다 — 템플릿 그대로 둔다
            store(job.notificationId(), msg);
        } catch (RuntimeException e) {
            // 문장을 못 받은 것으로 알림이 사라지면 안 된다.
            log.warn("지킴이 문장 갱신 실패 — notificationId={} {}", job.notificationId(), e.toString());
        }
    }

    /** 받은 문장을 그 알림에 얹는다. 새 트랜잭션이다 — 일꾼에게는 부르는 쪽의 것이 없다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void store(long notificationId, GuardianNarrative.Message msg) {
        notifications.findById(notificationId).ifPresent(n -> {
            if (n.upgradeSentence(msg.title(), msg.body(),
                    GuardianRules.stripFixedPhrases(msg.keyPhrases()))) {
                notifications.save(n);
                log.debug("지킴이 문장을 갈아 끼웠다 — notificationId={}", notificationId);
            }
        });
    }
}
