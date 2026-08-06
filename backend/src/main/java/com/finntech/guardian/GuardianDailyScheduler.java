package com.finntech.guardian;

import com.finntech.guardian.domain.GuardianChallenge;
import com.finntech.guardian.repository.GuardianChallengeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 지킴이 새벽 배치.
 *
 * <p><b>왜 필요한가.</b> 설계서는 배치를 전제로 쓰여 있다 — "C5·C9·C10·C11은 거래 순간이 아니라
 * <b>새벽 배치</b>에서 평가한다", "상태 전이는 유예가 만료된 뒤 <b>새벽 배치</b>가 확정한다".
 * 그런데 {@link GuardianBatchService#runDaily}를 부르는 곳이 데모 패널의 버튼과 수동
 * {@code POST /cron/daily} 뿐이었다. 지킴이 패키지에 {@code @Scheduled}가 하나도 없었고
 * {@link GuardianChallengeRepository#findDue}는 호출부가 0건이었다.
 *
 * <p>그래서 <b>아무도 버튼을 누르지 않으면 일 판정도, 무지출 보상도, 종료 정산도 영영 돌지 않았다.</b>
 * 챌린지는 종료일이 한참 지나도 ACTIVE로 남았다. 시연이 되던 것은 사람이 눌러 줬기 때문이다.
 *
 * <p><b>따라잡기(catch-up)를 한다.</b> 서버가 꺼져 있었거나 배치가 밀렸으면 판정하지 않은 날이
 * 여러 개 쌓인다. 어제 하루만 돌리면 그 구멍이 영원히 메워지지 않고, 종료일이 지나 버린 챌린지는
 * 정산 자체가 불가능해진다({@code runDaily}가 종료일 이후 날짜를 거부하므로). 그래서 시작일부터
 * 종료일·어제 중 이른 쪽까지 순서대로 훑는다. 판정이 이미 있는 날은 {@code runDaily}가 멱등하게
 * 넘긴다.
 *
 * <p><b>가상 시계를 존중한다.</b> 시연용 시간여행은 사용자마다 오프셋이 다르므로, 여기서
 * 날짜를 정하지 않고 {@link GuardianClock}에 사용자별로 묻는다.
 *
 * <p><b>한 사용자의 실패가 다음 사용자를 막지 않는다.</b> {@link MyDataSyncScheduler}와 같은
 * 이유로 사용자 단위로 예외를 삼키고 로그만 남긴다.
 *
 * <p>{@code finntech.guardian.daily-batch.enabled=false}로 끈다(테스트·오프라인 개발).
 */
@Component
@ConditionalOnProperty(name = "finntech.guardian.daily-batch.enabled", havingValue = "true", matchIfMissing = true)
public class GuardianDailyScheduler {

    private static final Logger log = LoggerFactory.getLogger(GuardianDailyScheduler.class);

    /**
     * 한 번에 따라잡을 최대 일수. 챌린지 하나가 오래 방치돼도 배치 한 번이 수백 일을 돌아
     * 다른 사용자를 굶기지 않게 한다. 남은 날은 다음 주기에 이어서 메운다.
     */
    private static final int MAX_CATCH_UP_DAYS = 40;

    private final GuardianChallengeRepository challengeRepository;
    private final GuardianBatchService batchService;
    private final GuardianClock clock;

    public GuardianDailyScheduler(GuardianChallengeRepository challengeRepository,
                                  GuardianBatchService batchService,
                                  GuardianClock clock) {
        this.challengeRepository = challengeRepository;
        this.batchService = batchService;
        this.clock = clock;
    }

    /**
     * {@code fixedDelay}(이전 실행 <b>종료</b> 후 간격)를 쓴다 — {@code fixedRate}는 한 번이
     * 느려지면 다음 실행이 겹쳐 같은 챌린지를 동시에 판정하게 된다. 최초 지연은 기동 직후
     * DB가 자리잡기 전에 돌지 않게 하려는 것이다.
     */
    @Scheduled(
            fixedDelayString = "${finntech.guardian.daily-batch.interval-ms:600000}",
            initialDelayString = "${finntech.guardian.daily-batch.initial-delay-ms:90000}")
    public void runDueChallenges() {
        List<GuardianChallenge> running = challengeRepository.findAllRunning();
        if (running.isEmpty()) return;

        int judged = 0, settled = 0, nudged = 0, failed = 0;
        for (GuardianChallenge ch : running) {
            try {
                judged += catchUp(ch);
                // 정산은 runDaily ⑥이 종료일 판정과 함께 처리한다. 상태로 성사 여부를 센다.
                if (!ch.isRunning()) settled++;
                // C9는 "그 시간대 직전"이 조건이라 하루 배치에 넣으면 영영 참이 되지 않는다.
                // 여기서 매 주기 보는 이유다 — 실제 발송은 4주 반복과 주 1회 쿨다운이 거른다.
                nudged += batchService.runNudges(ch.getUserId(), clock.now(ch.getUserId())).size();
            } catch (Exception e) {
                failed++;
                log.warn("지킴이 일 판정 건너뜀 — challengeId={} userId={} : {}",
                        ch.getId(), ch.getUserId(), e.toString());
            }
        }
        if (judged > 0 || settled > 0 || nudged > 0 || failed > 0) {
            log.info("지킴이 새벽 배치 — 판정 {}일, 정산 {}건, 넛지 {}건, 실패 {}건",
                    judged, settled, nudged, failed);
        }
    }

    /** 판정하지 않은 날을 시작일부터 순서대로 메운다. 판정한 날 수를 돌려준다. */
    private int catchUp(GuardianChallenge ch) {
        LocalDate yesterday = clock.today(ch.getUserId()).minusDays(1);
        LocalDate last = ch.getEndDate().isBefore(yesterday) ? ch.getEndDate() : yesterday;
        if (last.isBefore(ch.getStartDate())) return 0;   // 아직 판정할 날이 없다

        int done = 0;
        for (LocalDate d = ch.getStartDate(); !d.isAfter(last) && done < MAX_CATCH_UP_DAYS; d = d.plusDays(1)) {
            batchService.runDaily(ch.getUserId(), d);
            done++;
        }
        return done;
    }
}
