package com.finntech.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 로그인 무차별 대입 방어 — <b>잠금이 아니라 지연</b>.
 *
 * <h2>왜 계정을 잠그지 않는가</h2>
 *
 * <p><b>잠금은 그 자체가 공격 수단이다.</b> 공격자가 admin 계정에 일부러 틀린 비밀번호를
 * 반복하면 계정이 잠기고 <b>승인이 멈춘다</b>. 막으려던 것보다 큰 피해를 스스로 만든다.
 *
 * <p>그래서 대상도 <b>계정이 아니라 IP</b> 다. 계정 단위로 세면 아무 IP 에서나 남의 계정을
 * 잠글 수 있다. IP 단위면 공격자의 IP 만 느려지고 진짜 admin 은 영향을 받지 않는다.
 *
 * <h2>지연만으로 충분한 이유</h2>
 *
 * <p>Argon2id 가 한 번 계산에 이미 50~100ms 를 쓴다. 여기에 지연을 얹으면 초당 몇 번밖에
 * 시도하지 못하고, 그러면 비밀번호 하나를 맞히는 데 현실적으로 불가능한 시간이 걸린다.
 * IP 를 바꿔가며 시도해도 각 IP 에서 몇 번씩밖에 못 한다.
 *
 * <p>단일 인스턴스라 메모리에 둔다. 여러 대로 늘어나면 공용 저장소로 옮겨야 하지만,
 * 그때도 <b>잠금이 아니라 지연</b>이라는 성질은 바꾸지 않는다.
 */
@Component
public class LoginThrottle {

    /** 이 횟수까지는 벌을 주지 않는다 — 사람은 몇 번 틀린다. */
    private static final int FREE_ATTEMPTS = 3;
    /** 지연 상한. 무한히 늘리면 사실상 잠금이 되어 버린다. */
    private static final Duration MAX_DELAY = Duration.ofSeconds(30);
    /** 이 시간 동안 조용하면 카운터를 잊는다. */
    private static final Duration FORGET_AFTER = Duration.ofMinutes(30);

    private record Attempt(int count, Instant lastAt) {}

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /**
     * 이 IP 가 지금 기다려야 하는 시간. 0 이면 바로 시도해도 된다.
     *
     * <p>호출부는 이 값만큼 <b>응답을 늦춘다</b>. 거부하지 않는다 — 정당한 사용자가
     * 세 번 틀렸다고 못 들어오면 안 되기 때문이다.
     */
    public Duration delayFor(String ip) {
        Attempt attempt = attempts.get(key(ip));
        if (attempt == null) return Duration.ZERO;
        if (Duration.between(attempt.lastAt(), clock.instant()).compareTo(FORGET_AFTER) > 0) {
            attempts.remove(key(ip));
            return Duration.ZERO;
        }
        int over = attempt.count() - FREE_ATTEMPTS;
        if (over <= 0) return Duration.ZERO;
        // 지수 증가 — 4회째 1초, 5회째 2초, 6회째 4초 … 상한까지
        long seconds = 1L << Math.min(over - 1, 10);
        Duration delay = Duration.ofSeconds(seconds);
        return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
    }

    public void recordFailure(String ip) {
        attempts.compute(key(ip), (unused, previous) ->
                new Attempt(previous == null ? 1 : previous.count() + 1, clock.instant()));
    }

    /** 성공하면 잊는다 — 벌은 실패에만 붙는다. */
    public void recordSuccess(String ip) {
        attempts.remove(key(ip));
    }

    private static String key(String ip) {
        return ip == null ? "unknown" : ip;
    }
}
