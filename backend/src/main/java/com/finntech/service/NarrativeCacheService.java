package com.finntech.service;

import com.finntech.domain.NarrativeCache;
import com.finntech.freechannel.FreeChannelQueue;
import com.finntech.freechannel.Lane;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.NarrativeCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 화면에 보일 문장을 <b>저장된 것으로 즉시</b> 주고, 낡았으면 뒤에서 갱신한다.
 *
 * <pre>
 *   화면이 부른다  →  저장된 문장을 그대로 준다 (없으면 템플릿)
 *                  →  낡았으면 큐에 넣는다 — 여기서 모델을 부르지 않는다
 *   큐가 돌면      →  받은 문장을 저장한다. 못 받으면 시도 기록만 남긴다
 *   다음에 열면    →  새 문장이 보인다
 * </pre>
 *
 * <p><b>여기가 "다시 넣을지"를 정하는 자리다.</b> 큐의 중복 접기는 <i>지금 대기·진행 중</i>인
 * 것만 막는다. 끝난 뒤에 또 들어오는 것을 막는 일은 이쪽 몫이고, 판단이 <b>성공과 실패를 모두</b>
 * 반영해야 한다 —
 *
 * <ul>
 *   <li><b>성공</b>은 저절로 닫힌다. 새 문장이 적히므로 다음 스캔이 "안 낡았다"고 본다.</li>
 *   <li><b>실패</b>는 안 닫힌다. 아무것도 안 변하므로 스캔이 또 넣는다 — 그래서
 *       {@code attempted_at}·{@code failures} 를 보고 <b>쉬었다 간다.</b> 없으면 통로 장애 하나가
 *       사용자가 페이지를 넘길 때마다 예산을 먹는다.</li>
 * </ul>
 */
@Service
public class NarrativeCacheService {

    private static final Logger log = LoggerFactory.getLogger(NarrativeCacheService.class);

    private final NarrativeCacheRepository cache;
    private final AppUserRepository users;
    private final TempClassifierService free;
    private final FreeChannelQueue queue;
    private final Clock clock;
    /** 저장은 트랜잭션 안, 모델 질의는 밖 — 그 경계를 프록시로 잡는다(§13-13). */
    private final ObjectProvider<NarrativeCacheService> selfProvider;

    public NarrativeCacheService(NarrativeCacheRepository cache, AppUserRepository users,
                                 TempClassifierService free, FreeChannelQueue queue, Clock clock,
                                 ObjectProvider<NarrativeCacheService> selfProvider) {
        this.cache = cache;
        this.users = users;
        this.free = free;
        this.queue = queue;
        this.clock = clock;
        this.selfProvider = selfProvider;
    }

    /**
     * 한 화면의 문장을 만들 재료 — 부르는 쪽이 <b>집계만</b> 담아 준다(§4 원칙 1).
     *
     * <p>근거 지문은 따로 받지 않고 <b>프롬프트에서 뽑는다.</b> 프롬프트에 들어가는 것이 곧
     * 그 문장이 근거한 숫자이므로, 프롬프트가 같으면 숫자가 같고 다르면 다르다. 지문을 따로
     * 받으면 그 둘이 갈라질 자리가 생긴다 — 숫자는 바뀌었는데 지문은 안 바뀌는 식으로.
     */
    public record Request(Long userId, NarrativeCache.Kind kind, String subject,
                          String prompt, String template) {}

    /**
     * 보여줄 문장을 준다 — <b>기다리지 않는다.</b>
     *
     * @return 저장된 문장, 없으면 넘겨준 템플릿
     */
    @Transactional(readOnly = true)
    public Shown show(Request req) {
        return find(req).map(r -> new Shown(r.getBody(), r.getSource()))
                .orElseGet(() -> new Shown(req.template(), "TEMPLATE"));
    }

    /**
     * 보여줄 문장과 <b>그 출처</b>.
     *
     * <p>출처를 함께 주는 이유는 화면이 그것을 배지로 찍기 때문이다. 예전에는 부르는 쪽이
     * 무조건 {@code "CACHED"} 를 달았는데, 그러면 <b>저장된 출처를 아무도 못 본다</b> —
     * 모델이 쓴 문장인지 고정 템플릿인지 화면에서 구별이 안 된다(2026-08-08 감사).
     */
    public record Shown(String body, String source) {}

    private Optional<NarrativeCache> find(Request req) {
        return cache.findByUserIdAndKindAndSubject(req.userId(), req.kind(), subjectOf(req));
    }

    private static String subjectOf(Request req) {
        return req.subject() == null ? "" : req.subject();
    }

    /**
     * 필요하면 큐에 넣는다 — <b>모델을 여기서 부르지 않는다.</b>
     *
     * @return 넣었으면 true
     */
    public boolean enqueueIfNeeded(Request req) {
        LocalDateTime now = LocalDateTime.now(clock);
        String basis = fingerprint(req.prompt());
        Optional<NarrativeCache> row = find(req);

        // 이미 최신이면 할 일이 없다 — 성공이 저절로 닫는 자리가 여기다.
        // (`needsWork` 는 "아직 모델 문장을 못 받았다"도 낡음으로 본다 — 첫 시도가 실패하면
        //  행은 방금 만들어진 템플릿이라 '안 낡음'으로 읽혀 24시간 잠기기 때문이다.)
        if (row.isPresent() && !row.get().needsWork(basis, now)) return false;
        // 방금 실패했으면 쉰다 — 실패가 안 닫는 자리를 여기서 닫는다.
        if (row.isPresent() && !row.get().mayRetry(now)) return false;
        if (!free.usable()) return false;

        boolean real = users.existsByIdAndRealPersonTrue(req.userId());
        // **없음과 낡음은 사용자가 보는 것이 다르다.** 없으면 기계 같은 템플릿이고,
        // 낡으면 어제의 멀쩡한 문장이다. 그래서 앞의 것만 앞 차선에 둔다.
        Lane lane = !real ? Lane.DUMMY
                : row.isEmpty() ? Lane.USER_NOW
                : Lane.USER_REFRESH;

        return queue.submit(lane, "narrative:" + req.userId() + ":" + req.kind() + ":" + subjectOf(req),
                () -> generate(req, basis, real));
    }

    /** 큐가 부르는 자리 — 트랜잭션 밖이다. 받은 것만 트랜잭션을 열어 적는다. */
    private void generate(Request req, String basis, boolean real) {
        Optional<String> got = free.sentence(req.prompt());
        NarrativeCacheService self = selfProvider.getObject();
        if (got.isPresent()) self.store(req, basis, got.get(), real);
        else self.storeFailure(req, basis, real);
    }

    /** 받은 문장을 적는다. */
    @Transactional
    public void store(Request req, String basis, String body, boolean real) {
        LocalDateTime now = LocalDateTime.now(clock);
        find(req).ifPresentOrElse(
                row -> row.renew(body, basis, now),
                () -> cache.save(new NarrativeCache(req.userId(), req.kind(), subjectOf(req),
                        body, "AI", basis, now, real)));
        log.debug("문장을 새로 받았다 — userId={} kind={}", req.userId(), req.kind());
    }

    /**
     * 못 받았다 — <b>본문은 그대로 두고 시도만 적는다.</b>
     *
     * <p>행이 아예 없으면 템플릿으로 만들어 둔다. 그래야 화면에 보여줄 것이 생기고, 다음 스캔이
     * 유예를 볼 근거가 남는다. 여기에 "만들 수 없는 문장"이라고 적지는 않는다 — 답을 못 받은
     * 것과 답이 없는 것은 다르고, 앞의 것을 사실로 적으면 통로 장애가 데이터로 굳는다.
     */
    @Transactional
    public void storeFailure(Request req, String basis, boolean real) {
        LocalDateTime now = LocalDateTime.now(clock);
        find(req).ifPresentOrElse(
                row -> row.noteFailure(now),
                () -> cache.save(new NarrativeCache(req.userId(), req.kind(), subjectOf(req),
                        req.template(), "TEMPLATE", basis, now, real)));
    }

    /** 큐에서 같은 일을 가리키는 이름 — 사용자 한 명의 한 화면에 문장은 하나다. */
    private static String key(Long userId, NarrativeCache.Kind kind) {
        return "narrative:" + userId + ":" + kind;
    }

    /** 근거 숫자를 짧은 지문으로 접는다 — 길이가 칸을 넘지 않게. */
    private static String fingerprint(String raw) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(String.valueOf(raw).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 이 없는 JVM 은 없다
        }
    }
}
