package com.finntech.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 시험에서는 소비 원장 일감이 <b>부른 그 자리에서</b> 돌아야 한다.
 *
 * <h2>왜 이걸 시험으로 못박나</h2>
 *
 * <p>{@code finntech.ledger.async} 를 한 글자만 잘못 적으면 {@code matchIfMissing=true} 때문에
 * <b>조용히 배경 일꾼이 돌아온다.</b> 그러면 앞 시험이 낸 판정 기록이 뒤 시험의
 * {@code deleteAll()} 뒤에 도착해 없는 줄을 고치려 들고, 그 실패는 기록기가 잡아 로그로만
 * 남으므로 <b>초록불 뒤에 숨는다</b>(CI 실측 2026-08-14).
 *
 * <p>설정이 실제로 걸렸는지는 이렇게 물어보는 수밖에 없다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SpendingLedgerExecutorConfigTest {

    @Autowired
    @Qualifier(SpendingLedgerExecutorConfig.BEAN)
    Executor executor;

    @Test
    @DisplayName("시험 프로파일에서는 같은 스레드 일꾼이 붙는다")
    void 시험에서는_같은_스레드다() {
        assertFalse(executor instanceof ThreadPoolExecutor,
                "배경 일꾼이 붙었다 — finntech.ledger.async 설정이 안 걸렸다");

        Thread caller = Thread.currentThread();
        Thread[] ran = new Thread[1];
        executor.execute(() -> ran[0] = Thread.currentThread());
        assertTrue(caller == ran[0], "일감이 부른 스레드에서 돌아야 시험 사이로 안 샌다");
    }
}
