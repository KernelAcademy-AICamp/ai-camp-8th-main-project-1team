package com.finntech.guardian;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 지킴이 알림 문장을 <b>뒤에서</b> 받아 오는 일꾼.
 *
 * <p><b>왜 요청 스레드에서 빼는가.</b> 문장 한 건에 최대 11초가 걸리고
 * ({@code GuardianNarrative} 의 연결 3초 + 읽기 8초), {@code syncFromMyData} 는 새 결제마다
 * 그것을 반복한다. 다섯 건이면 55초 — 홈에 들어가는 데 1분이 걸린다는 뜻이다.
 * 게다가 그 호출이 {@code @Transactional} 안에 있어 DB 커넥션까지 그동안 묶였다.
 *
 * <p><b>왜 한 스레드인가.</b> {@code FollowUpExecutorConfig} 와 같은 이유다 — 이 통로가
 * 지키던 성질이 "남의 서버를 연달아 두드리지 않는다"이고, 일꾼을 늘리면 그 성질이 일꾼 수만큼
 * 깨진다. 순차성은 유지하고 자리만 옮긴다.
 *
 * <p><b>왜 빈으로 빼는가.</b> 시험이 같은 스레드에서 돌리는 실행기를 넣어 "문장이 실제로
 * 갱신됐는가"를 검사할 수 있어야 한다. 배경 스레드로 도망가면 그 검사를 못 쓴다.
 */
@Configuration
public class GuardianSentenceExecutorConfig {

    /** 주입 지점이 이름으로 집는다 — 애플리케이션의 다른 {@code Executor} 와 섞이지 않게. */
    public static final String BEAN = "guardianSentenceExecutor";

    /**
     * 큐가 차면 거절한다. 버려도 안전하다 — <b>알림은 이미 템플릿 문장으로 저장돼 있고</b>,
     * 못 받은 것은 "덜 예쁜 문장"이지 "없는 알림"이 아니다.
     */
    private static final int QUEUE = 200;

    @Bean(BEAN)
    public Executor guardianSentenceExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE),
                runnable -> {
                    Thread thread = new Thread(runnable, "guardian-sentence");
                    thread.setDaemon(true);     // 기동 중지를 막지 않는다
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
}
