package com.finntech.web;

import com.finntech.service.PendingWorkScanner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>사용자의 아무 상호작용</b>이 밀린 일을 다시 큐에 올린다.
 *
 * <p><b>왜 필요한가.</b> 큐는 메모리에 있어 재기동하면 대기 목록이 날아간다. 그 자체는 문제가
 * 아니다 — 큐는 할 일의 사본이지 원본이 아니고, 원본(문장이 낡았다·브랜드가 없다)은 DB 에
 * 남는다. 다만 <b>누군가는 다시 올려 줘야 한다.</b> 그 자리를 사용자의 상호작용으로 둔다.
 * 앱을 다시 열든, 페이지를 넘기든, 무엇이든 요청이 오면 그때 훑는다.
 *
 * <p><b>왜 인터셉터인가.</b> 컨트롤러마다 붙이면 새 화면이 늘 때마다 빠뜨린다 — 2026-08-07
 * 감사에서 트랜잭션 경계가 정확히 그렇게 네 자리에서 새어 있었다. 문을 하나로 두면 새
 * 컨트롤러가 생겨도 그냥 걸린다.
 *
 * <p><b>세 가지를 지킨다.</b>
 * <ul>
 *   <li><b>그 사용자 것만</b> 훑는다. 전부 훑을 이유가 없다 — 남의 것은 그 사람이 들어올 때 걸린다.</li>
 *   <li><b>쿨다운</b>을 둔다. 페이지를 세 번 넘기면 스캔도 세 번인데, 큐의 중복 접기가 결과는
 *       막아도 스캔 비용은 세 번 든다.</li>
 *   <li><b>큐에 넣는 데까지만</b> 한다. 요청 안에서 모델을 부르는 일은 없다 — 스캔은 읽기 한 번,
 *       큐 넣기는 메모리라 요청이 안 느려진다.</li>
 * </ul>
 */
@Configuration
public class PendingWorkTrigger implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(PendingWorkTrigger.class);

    /** 같은 사용자를 이 간격 안에 두 번 훑지 않는다. */
    private static final long COOLDOWN_MILLIS = 30_000L;

    private final PendingWorkScanner scanner;
    private final Clock clock;
    private final Map<Long, Long> lastScan = new ConcurrentHashMap<>();

    public PendingWorkTrigger(PendingWorkScanner scanner, Clock clock) {
        this.scanner = scanner;
        this.clock = clock;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                        Object handler, Exception ex) {
                // **응답을 보낸 뒤에 한다.** 요청 처리에 한 톨도 얹지 않기 위해서다.
                trigger(request);
            }
        }).addPathPatterns("/api/**");
    }

    private void trigger(HttpServletRequest request) {
        Long userId = userIdOf(request);
        if (userId == null) return;

        long now = clock.millis();
        Long last = lastScan.get(userId);
        if (last != null && now - last < COOLDOWN_MILLIS) return;
        lastScan.put(userId, now);

        try {
            int added = scanner.scan(userId);
            if (added > 0) log.debug("상호작용으로 밀린 일 {}건을 큐에 올렸다 — userId={}", added, userId);
        } catch (RuntimeException e) {
            // 스캔이 실패해도 사용자의 요청은 이미 끝났다. 다음 상호작용이 다시 훑는다.
            log.debug("밀린 일 스캔 실패 — userId={} : {}", userId, e.toString());
        }
    }

    /** 이 저장소의 API 는 사용자를 질의문자열로 받는다. 없으면 훑을 대상이 없다. */
    private static Long userIdOf(HttpServletRequest request) {
        String raw = request.getParameter("userId");
        if (raw == null || raw.isBlank()) return null;
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
