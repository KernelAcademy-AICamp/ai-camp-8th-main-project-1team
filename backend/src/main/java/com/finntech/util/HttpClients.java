package com.finntech.util;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 외부 API를 부를 때 쓰는 요청 팩토리 — <b>타임아웃을 반드시 건다.</b>
 *
 * <p><b>왜 이 클래스가 있는가.</b> {@code RestClient.builder()}를 그냥 쓰면 스프링 부트가
 * 자동 구성해 둔 설정이 붙지 않고, 그 밑의 JDK {@link HttpClient}는 읽기 타임아웃이
 * <b>기본값 없음(무한)</b>이다. 상대가 응답을 끊지 않고 물고만 있으면 요청 스레드가 영영
 * 돌아오지 않는다. 톰캣 스레드는 유한하므로, 외부 하나가 느려지면 앱 전체가 무응답이 된다.
 *
 * <p>실제로 이 저장소는 원인 미상의 서버 무응답을 한 번 겪었고, 그때 스레드 덤프상 JVM은
 * 유휴였다. 외부 호출에 타임아웃이 없으면 그 모양이 되기 쉽다.
 *
 * <p>부트의 {@code RestClient.Builder}를 주입받는 방법도 있지만, 그러면 타임아웃이
 * {@code application.yml}의 전역값에 묶여 <b>호출처마다 다른 인내심</b>을 줄 수 없다.
 * 금감원 공시(느려도 기다릴 만하다)와 LLM(사용자를 기다리게 하면 안 된다)은 기준이 다르다.
 */
public final class HttpClients {
    private HttpClients() {}

    /**
     * @param connect 연결까지 기다리는 시간 — 상대가 죽어 있으면 여기서 끝난다
     * @param read    응답 본문까지 기다리는 시간 — 연결은 됐는데 응답이 안 오는 경우를 끊는다
     */
    public static ClientHttpRequestFactory factory(Duration connect, Duration read) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connect).build());
        factory.setReadTimeout(read);
        return factory;
    }
}
