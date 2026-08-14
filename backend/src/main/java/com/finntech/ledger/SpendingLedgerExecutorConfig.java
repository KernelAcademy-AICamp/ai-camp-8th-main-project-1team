package com.finntech.ledger;

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
 * <h2>큐가 1이고 넘치면 버리는 이유</h2>
 *
 * <p>여기 넣는 것은 <b>할 일이 아니라 "할 일이 생겼다"는 신호</b>다. 할 일 목록은
 * {@code spending_ledger_dirty} 에 있으므로 신호를 버려도 잃는 것이 없다 — 이미 대기 중인
 * 신호 하나가 그 표를 통째로 훑고, 그사이 들어온 표시까지 함께 처리한다. 그마저 놓쳐도
 * 배수 배치가 {@code drain.interval-ms} 안에 집어 든다.
 *
 * <p>{@code mydata-followups} 와 갈리는 자리다. 저쪽은 큐가 차면 <b>할 일 자체</b>가 사라지고
 * (5분 배치가 같은 일을 다시 하기 때문에 안전한 것이다), 여기는 신호만 사라진다.
 */
@Configuration
public class SpendingLedgerExecutorConfig {

    /** 주입받을 때 쓰는 이름. */
    public static final String BEAN = "spendingLedgerExecutor";

    /** 스레드 이름 — 로그에서 이 일이 어디서 도는지 바로 보이게. */
    static final String THREAD_NAME = "spending-ledger";

    @Bean(BEAN)
    public Executor spendingLedgerExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                runnable -> {
                    Thread thread = new Thread(runnable, THREAD_NAME);
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardPolicy());
    }
}
