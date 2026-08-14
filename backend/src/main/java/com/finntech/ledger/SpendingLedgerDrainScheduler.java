package com.finntech.ledger;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 배수를 주기로 한 번씩 깨운다 — <b>나팔이 놓친 것을 잇는 안전선</b>.
 *
 * <p>보통은 표시가 커밋될 때 나팔이 울려 곧바로 돈다. 이 배치는 그 신호가 버려졌거나
 * (일꾼 큐가 하나다) 프로세스가 재기동돼 신호 자체가 사라진 경우를 위한 것이다. 표에 남은
 * 표시는 그때도 그대로이므로, 다음 회차가 집어 들면 아무것도 잃지 않는다.
 *
 * <p>끌 수 있게 둔 것은 시험 때문이다 — 배경 스레드가 도는 채로 단정을 걸면 경주가 된다.
 */
@Component
@ConditionalOnProperty(name = "finntech.ledger.drain.enabled", havingValue = "true", matchIfMissing = true)
public class SpendingLedgerDrainScheduler {

    private final SpendingLedgerDrainer drainer;

    public SpendingLedgerDrainScheduler(SpendingLedgerDrainer drainer) {
        this.drainer = drainer;
    }

    /**
     * {@code fixedDelay} 라 <b>이전 회차가 끝난 뒤에</b> 센다 — 오래 걸리는 회차가 다음 회차를
     * 부르지 않는다. 초기 지연은 기동 직후의 다른 준비 작업과 겹치지 않게 둔다.
     */
    @Scheduled(fixedDelayString = "${finntech.ledger.drain.interval-ms:15000}",
               initialDelayString = "${finntech.ledger.drain.initial-delay-ms:30000}")
    public void drain() {
        drainer.drainAll();
    }
}
