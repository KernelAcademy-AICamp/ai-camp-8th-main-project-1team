package com.finntech.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 마이데이터 <b>후속 단계</b>를 도는 일꾼.
 *
 * <p><b>왜 요청 스레드에서 빼는가.</b> {@code MyDataLinkService.runFollowUps} 는 바깥 서버를
 * <b>순차로</b> 부르고 사이에 쉰다 — 등록 업종 조회 최대 40곳, 주소 채우기 최대 40곳, 거기에
 * 모델 호출 둘이다. 한 곳이 최대 4초라 최악은 몇 분이다. 예전에는 트랜잭션만 벗어나고 요청
 * 스레드에는 남아 있어, 자산연결을 누른 실사용자가 그 시간을 그대로 기다렸다. 실측으로
 * <b>57초</b>가 걸렸고(2026-08-12 운영 userId=30, 08:43:52→08:44:49) 게이트웨이가 먼저 끊어
 * {@code 504 /api/mydata/link} 가 났다.
 *
 * <p><b>왜 그때까지 안 났는가.</b> {@code runFollowUps} 첫 줄이 더미 사용자를 곧바로 돌려보낸다.
 * 그동안 연동한 사람이 전부 더미라 이 길로 들어온 적이 없었다 — 첫 실사용자가 첫 피해자였다.
 *
 * <p><b>왜 한 스레드인가.</b> 이 통로가 지키던 성질이 "남의 서버를 연달아 두드리지 않는다"였다.
 * 일꾼을 늘리면 그 성질이 일꾼 수만큼 깨진다 — 순차성은 유지하고 자리만 옮긴다.
 *
 * <p><b>왜 빈으로 빼는가.</b> 시험이 이 서비스를 직접 조립하는데, 배경 스레드로 도망가면
 * "후속 단계가 실제로 돌았는가"를 검사할 수 없다. 주입 가능하게 두면 시험은 같은 스레드에서
 * 돌려 종전의 뜻을 그대로 지키고, <b>비동기라는 사실 자체</b>도 따로 검사할 수 있다.
 */
@Configuration
public class FollowUpExecutorConfig {

    /** 주입 지점이 이름으로 집는다 — 애플리케이션의 다른 {@code Executor} 와 섞이지 않게. */
    public static final String BEAN = "myDataFollowUpExecutor";

    /**
     * 큐가 차면 거절한다. 버려도 안전하다 — 5분 배치가 같은 일을 이어받고,
     * 부르는 쪽이 그때 자물쇠를 놓는다.
     */
    private static final int QUEUE = 100;

    @Bean(BEAN)
    public Executor myDataFollowUpExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE),
                runnable -> {
                    Thread thread = new Thread(runnable, "mydata-followups");
                    thread.setDaemon(true);     // 기동 중지를 막지 않는다
                    return thread;
                });
    }
}
