package com.finntech.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 소비 원장 재작성 전용 일꾼 — 하나뿐이고, 큐가 차면 <b>버린다</b>.
 *
 * <h2>왜 {@code mydata-followups} 를 같이 쓰지 않나</h2>
 *
 * <p>저쪽은 <b>바깥 서버를 순차로 두드리는</b> 통로다 — 등록 업종 조회 40곳 × 4초에 주소 조회와
 * 모델 호출까지 붙어 최악 몇 분이 걸린다. 재작성을 그 뒤에 세우면 "업종코드가 처음 판별되는
 * 순간 표에 반영된다"가 성립하지 않는다. 그리고 그 통로가 지키는 성질
 * (<i>남의 서버를 연달아 두드리지 않는다</i>)에 순수 DB 작업을 섞으면 그 성질의 근거가 흐려진다.
 *
 * <h2>넘치면 버려도 되는 이유</h2>
 *
 * <p>여기 실리는 것은 두 가지인데 <b>둘 다 잃어도 영영 잃지 않는다.</b>
 *
 * <ul>
 *   <li><b>배수 신호</b> — 할 일 목록은 {@code spending_ledger_dirty} 에 있다. 신호를 버려도
 *       이미 대기 중인 신호 하나가 그 표를 통째로 훑고, 그마저 놓치면 주기 배치가 집어 든다.
 *   <li><b>판정 기록</b>(고정지출·낭비) — 버리면 그 칸이 잠시 낡을 뿐이고, 다음에 그 판정이
 *       돌 때 같은 답이 다시 온다. 낡았다는 사실은 {@code *_recorded_at} 이 말해 준다.
 * </ul>
 *
 * <p>{@code mydata-followups} 와 갈리는 자리다. 저쪽은 큐가 차면 <b>할 일 자체</b>가 사라지고
 * (5분 배치가 같은 일을 다시 하기 때문에 안전한 것이다), 여기는 <b>사본</b>만 사라진다.
 *
 * <p>그래도 조용히 버리지는 않는다 — 자주 버려지면 일꾼이 못 따라간다는 뜻이고, 그것은
 * 로그에 남아야 한다.
 */
@Configuration
public class SpendingLedgerExecutorConfig {

    /** 주입받을 때 쓰는 이름. */
    public static final String BEAN = "spendingLedgerExecutor";

    /** 스레드 이름 — 로그에서 이 일이 어디서 도는지 바로 보이게. */
    static final String THREAD_NAME = "spending-ledger";

    /** 대기 상한. 신호는 하나면 족하지만 판정 기록은 사용자별로 쌓일 수 있어 여유를 둔다. */
    static final int QUEUE = 64;

    private static final Logger log = LoggerFactory.getLogger(SpendingLedgerExecutorConfig.class);

    @Bean(BEAN)
    public Executor spendingLedgerExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE),
                runnable -> {
                    Thread thread = new Thread(runnable, THREAD_NAME);
                    thread.setDaemon(true);
                    return thread;
                },
                (rejected, executor) -> log.warn(
                        "소비 원장 일감을 버렸다 — 대기 {}개가 꽉 찼다. 표가 잠시 낡을 뿐이고 다음 회차가 잇는다",
                        QUEUE));
    }
}
