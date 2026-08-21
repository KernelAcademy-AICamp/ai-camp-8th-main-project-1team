package com.finntech.service;

import com.finntech.freechannel.FreeChannelQueue;
import com.finntech.freechannel.Lane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * <b>모델에게 묻는 하나의 문</b> — 무료가 먼저, 유료는 비상용.
 *
 * <h2>왜 만드는가</h2>
 *
 * <p>다섯 서비스가 각자 Gemini {@code RestClient} 를 들고 있었다({@code NarrativeService} ·
 * {@code GuardianNarrative} · {@code ProductLookupService} · {@code EligibilityLabelService} ·
 * {@code PreferentialLabelService}). 전부 <b>유료</b>다. 그런데 실측에서 무료 1위 모델이
 * 유료보다 정확했고(72.3% 대 70.5%, 148건), 무료는 값이 0 이다.
 *
 * <p>사용자 결정(2026-08-21): <b>지금 즉시 답해야 하는 경우가 아니면 전부 무료로.</b>
 * 빨리 처리해야 하면 큐의 앞 차선을 쓰면 되고, 그럴 시간조차 없는 것만 유료다.
 *
 * <h2>큐를 거친다</h2>
 *
 * <p>무료 통로는 분당 예산이 유한하고 그 순서를 {@link FreeChannelQueue} 가 정한다
 * ({@code OneDoorTest} 가 그 규약을 지킨다). 이 관문도 예외가 아니다 — 부르는 쪽이 차선을
 * 정하고, 문은 하나다.
 *
 * <p><b>기다릴 수 있게도 해 둔다.</b> 부르는 쪽 중에는 화면 요청 안에서 답이 필요한 것이
 * 있다(자격·우대 라벨). 그런 자리는 {@link #askNow} 로 짧게 기다리고, 못 받으면 자기
 * 폴백으로 간다 — 규칙 파서든 빈 결과든 이미 갖고 있다. 기다리는 동안에도 <b>차선은
 * 지켜진다</b>: 큐가 순서를 정하고 이 관문은 그 결과를 받을 뿐이다.
 *
 * <h2>유료는 언제 쓰나</h2>
 *
 * <p>무료가 <b>꺼져 있거나 답을 못 준</b> 뒤에만 부른다. 무료 사슬은 이미 모델 다섯을
 * 순서대로 시도하므로({@code ModelChain}) 여기까지 왔다는 것은 다섯이 다 죽었다는 뜻이다.
 */
@Component
public class ModelGateway {

    private static final Logger log = LoggerFactory.getLogger(ModelGateway.class);

    /**
     * 화면 요청 안에서 기다려 줄 최대 시간.
     *
     * <p>짧다. 이 자리의 부르는 쪽은 전부 폴백을 갖고 있어 <b>못 받아도 화면이 산다</b>.
     * 길게 잡으면 큐가 밀렸을 때 그 시간만큼 화면이 멈춘다 — 오늘 로딩 40초가 그 모양이었다.
     */
    private static final long WAIT_MS = 3_000;

    private final TempClassifierService free;
    private final FreeChannelQueue queue;

    public ModelGateway(TempClassifierService free, FreeChannelQueue queue) {
        this.free = free;
        this.queue = queue;
    }

    /** 무료 통로를 지금 쓸 수 있는가 — 꺼져 있거나 쉬는 중이면 부르는 쪽이 유료로 간다. */
    public boolean freeUsable() {
        return free.usable();
    }

    /**
     * <b>뒤에서 답을 받아 둔다</b> — 화면이 안 기다리는 자리용.
     *
     * @param lane 급한 정도. 화면에 걸린 것이 아니면 {@link Lane#USER_BACKGROUND} 아래로.
     * @param key  같은 일을 가리키는 이름. 같으면 큐가 접는다.
     * @return 큐에 올렸으면 {@code true}. 이미 대기 중이거나 무료가 꺼져 있으면 {@code false}.
     */
    public boolean submit(Lane lane, String key, String prompt,
                          java.util.function.Consumer<String> whenAnswered) {
        if (!free.usable()) return false;
        return queue.submit(lane, key, () ->
                free.sentence(prompt).ifPresent(whenAnswered));
    }

    /**
     * <b>짧게 기다려 받는다</b> — 화면 요청 안에서 답이 필요한 자리용.
     *
     * <p>큐를 거치므로 차선 규율은 그대로다. {@link #WAIT_MS} 안에 못 받으면 비어서 돌아가고,
     * 부르는 쪽은 자기 폴백으로 간다. <b>큐에 올린 일은 취소하지 않는다</b> — 뒤에서 끝나
     * 캐시에 남으면 다음 요청이 그것을 쓴다.
     */
    public Optional<String> askNow(Lane lane, String key, String prompt) {
        if (!free.usable()) return Optional.empty();
        CompletableFuture<String> slot = new CompletableFuture<>();
        boolean queued = queue.submit(lane, key, () -> {
            String out = free.sentence(prompt).orElse(null);
            slot.complete(out);
        });
        if (!queued) return Optional.empty();     // 이미 같은 일이 대기 중 — 다음 요청이 받는다
        try {
            String out = slot.get(WAIT_MS, TimeUnit.MILLISECONDS);
            return Optional.ofNullable(out).filter(s -> !s.isBlank());
        } catch (java.util.concurrent.TimeoutException e) {
            log.debug("무료 통로가 {}ms 안에 안 왔다 — 폴백으로 간다: {}", WAIT_MS, key);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
