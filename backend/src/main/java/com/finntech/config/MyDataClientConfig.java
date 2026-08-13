package com.finntech.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 마이데이터 서버 호출용 {@link RestClient} 빈 (§13-3). WebFlux 없이 동기 호출.
 * base-url은 {@code finntech.mydata.base-url}(기본 http://localhost:8082).
 *
 * <p><b>서버간 인증(W7-2)</b>: 제공자(마이데이터)-사업자(본체) 간 공유 시크릿을 모든 요청에
 * {@code X-MyData-Token} 헤더로 실어 보낸다. 8082 격리(단층 방어)가 뚫려도 토큰 없는 직접 호출은
 * 마이데이터가 401로 막는다. 시크릿은 {@code finntech.mydata.shared-secret}(env MYDATA_SHARED_SECRET).
 * 미설정(빈 값)이면 헤더를 붙이지 않는다 — dev(마이데이터 h2)는 강제하지 않으므로 로컬 개발이 통과한다.
 *
 * <p><b>타임아웃을 반드시 준다.</b> {@code RestClient.builder()} 는 정적 팩터리라 부트가
 * 자동구성한 빌더 빈이 아니고, 그래서 {@code spring.http.client.*} 도 어떤 기본값도 안 붙는다.
 * 이 저장소에는 HttpComponents·Jetty·Reactor Netty 의존이 없어 JDK 팩터리로 떨어지는데,
 * 그 기본값은 <b>연결·읽기 둘 다 무한</b>이다({@link com.finntech.util.HttpClients} 자바독이
 * 같은 함정을 적어 두었고, 분류·조회·자격 클라이언트는 전부 그것을 거치는데 여기만 빠져 있었다).
 *
 * <p>무한이면 어떻게 되는가 — 제공자가 TCP 는 물고 응답을 안 주는 상태(과부하, half-open 소켓,
 * 컨테이너 정지)에서 {@code pullNewPayments} 의 왕복이 영영 안 돌아온다. 그 호출은 쓰기
 * 트랜잭션 안이라 커넥션이 묶이고, 스케줄러 스레드는 기본 한 개라 이후 모든 배치가 서고,
 * {@code syncing} 자물쇠는 {@code finally} 에 닿지 못해 그 사용자의 동기화가 프로세스가 죽을
 * 때까지 막힌다. <b>예외 로그 한 줄 없이</b> 그렇게 된다 — 배치는 "도는 중"으로 보인다.
 * 타임아웃 하나면 셋 다 예외로 바뀌어 롤백·{@code finally}·{@code catch} 가 제 일을 한다
 * (2026-08-07 재감사).
 */
@Configuration
public class MyDataClientConfig {

    @Bean
    public RestClient myDataRestClient(
            @Value("${finntech.mydata.base-url:http://localhost:8082}") String baseUrl,
            @Value("${finntech.mydata.shared-secret:}") String sharedSecret,
            @Value("${finntech.mydata.connect-timeout-ms:3000}") long connectMs,
            @Value("${finntech.mydata.read-timeout-ms:30000}") long readMs,
            @Value("${finntech.mydata.truststore:}") String truststorePath,
            @Value("${finntech.mydata.truststore-password:}") String truststorePassword) {
        // 읽기 30초는 증분 질의가 큰 표를 훑는 경우까지 견디도록 넉넉히 잡은 값이다.
        // 넉넉한 것과 무한한 것은 다르다 — 유한하기만 하면 위의 연쇄가 끊긴다.
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(com.finntech.util.HttpClients.factory(
                        java.time.Duration.ofMillis(connectMs), java.time.Duration.ofMillis(readMs),
                        trustOnly(truststorePath, truststorePassword)));
        if (sharedSecret != null && !sharedSecret.isBlank()) {
            builder.defaultHeader("X-MyData-Token", sharedSecret);
        }
        return builder.build();
    }

    /**
     * 지정한 신뢰저장소 <b>하나만</b> 믿는 SSL 문맥. 경로가 비면 null(=JDK 기본).
     *
     * <p>제공자는 자체 서명 인증서를 쓴다 — 도커 내부망의 `backend-mydata` 라는 이름에는
     * 공인 CA 가 인증서를 발급하지 않는다. 여기서 필요한 것은 "공인된 신원"이 아니라
     * <b>"아무나 믿지 않는 것"</b>이고, 그래서 저장소에 그 인증서 하나만 넣는다.
     *
     * <p><b>검증을 끄는 선택지는 두지 않았다.</b> `-k` 에 해당하는 스위치를 만들어 두면
     * 언젠가 그것이 켜진 채로 배포된다. 못 믿으면 <b>기동이 실패하는 편</b>이 낫다.
     */
    private static javax.net.ssl.SSLContext trustOnly(String path, String password) {
        if (path == null || path.isBlank()) return null;
        try (java.io.InputStream in = new java.io.FileInputStream(path)) {
            java.security.KeyStore store = java.security.KeyStore.getInstance("PKCS12");
            store.load(in, password == null ? new char[0] : password.toCharArray());
            javax.net.ssl.TrustManagerFactory trust = javax.net.ssl.TrustManagerFactory
                    .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            javax.net.ssl.SSLContext context = javax.net.ssl.SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            return context;
        } catch (Exception exception) {
            // **여기서 멈춘다.** 못 읽은 채로 넘어가면 JDK 기본 저장소로 붙으려다 매 호출이
            // 실패하고, 증상은 "마이데이터가 안 된다"로만 보인다. 원인을 기동에서 밝힌다.
            throw new IllegalStateException(
                    "마이데이터 신뢰저장소를 못 읽었다: " + path, exception);
        }
    }
}
