package com.finntech.ledger;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 판정이 낡은 사용자의 <b>고정지출·낭비 층</b>을 채운다 — 짧은 주기 한 번, 밤에 한 번.
 *
 * <h2>왜 짧은 주기가 필요한가</h2>
 *
 * <p>밤 한 번만으로는 <b>낡음이 하루 내내 쌓인다.</b> 분류는 5분마다 바뀌는데(후속 회차가
 * 가맹점을 물어 답이 오면 {@code factsUpdatedAt} 이 앞으로 간다) 재판정은 하루 한 번이라,
 * 그 사이 새로 분류된 결제는 전부 {@code UNJUDGED} 로 남는다.
 *
 * <p>운영 실측(2026-08-21): 새 실사용자 둘이 로그인한 뒤 분류가 계속 붙었는데, 판정은
 * 로그인 순간의 것뿐이라 <b>라진우 237건 중 135건 · 홍상호 307건 중 219건</b>이 미판정으로
 * 남았다. 리포트·점수는 원장을 읽으므로 화면에 보이는 것과 집계가 갈렸다.
 *
 * <h2>그래도 좁게 둔다</h2>
 *
 * <p>이 배치는 표를 채우려고 판정을 부른다 — <i>"표는 계산을 일으키지 않는다"</i> 는 원칙에
 * 어긋나는 유일한 상시 경로다({@link SpendingLedgerJudgmentRefresher} 머리말에 사정을 적었다).
 * 그래서 양보를 <b>가장 좁게</b> 둔다:
 *
 * <ul>
 *   <li><b>낡은 사람만 고른다</b> — {@code findUsersWithStaleJudgments} 가 걸러, 평소에는
 *       질의 하나로 끝난다. 아무도 안 낡았으면 아무 일도 안 일어난다.</li>
 *   <li><b>겹쳐 돌지 않는다</b> — {@code refreshStale} 이 {@code running} 으로 스스로 막는다.</li>
 *   <li><b>예산이 있다</b> — {@code refresh.max-millis} 를 채우면 멈추고 다음 회차가 잇는다.</li>
 * </ul>
 *
 * <p>밤 회차는 남겨 둔다 — 짧은 주기가 예산에 걸려 밀린 것을 한가한 시각에 몰아 끝낸다.
 *
 * <h2>시각 — 04:50 KST</h2>
 *
 * <p>04:20 신청 만료({@code IntakeMaintenance}) 다음이다. 둘 다 {@code zone="Asia/Seoul"} 이라
 * 같은 축에 선다.
 *
 * <p><b>보유기간 파기보다 뒤는 아니다</b>(2026-08-18 확인). {@code PrivacyController} 의
 * 파기는 {@code @Scheduled(cron = "0 0 4 * * *")} 인데 <b>zone 을 안 주고</b> 컨테이너 TZ 도
 * 없어 JVM 이 UTC 로 돈다 — 즉 실제로는 <b>13:00 KST</b> 다. 이 갱신은 그보다 여덟 시간 앞이다.
 * 지워질 사용자를 한 번 더 갱신하는 헛걸음이 생길 수 있으나, {@code eraseUserData} 가 소비
 * 원장을 함께 지우므로 <b>틀린 값이 남지는 않는다.</b> 같은 "04시"가 서로 다른 시각을 뜻하는
 * 것은 별도로 정리할 일이다.
 */
@Component
@ConditionalOnProperty(name = "finntech.ledger.refresh.enabled", havingValue = "true",
        matchIfMissing = true)
public class SpendingLedgerRefreshScheduler {

    private final SpendingLedgerJudgmentRefresher refresher;

    public SpendingLedgerRefreshScheduler(SpendingLedgerJudgmentRefresher refresher) {
        this.refresher = refresher;
    }

    /**
     * 짧은 주기 — 분류가 바뀐 사람을 곧 따라잡는다.
     *
     * <p>{@code fixedDelay} 라 앞 회차가 끝난 뒤부터 센다. 낡은 사람이 없으면 질의 하나로
     * 끝나므로 평소 비용은 사실상 0 이다.
     */
    @Scheduled(fixedDelayString = "${finntech.ledger.refresh.follow-delay-ms:600000}",
            initialDelayString = "${finntech.ledger.refresh.follow-initial-ms:120000}")
    public void followUp() {
        refresher.refreshStale();
    }

    /** 밤 회차 — 짧은 주기가 예산에 걸려 밀린 것을 한가한 시각에 몰아 끝낸다. */
    @Scheduled(cron = "${finntech.ledger.refresh.cron:0 50 4 * * *}", zone = "Asia/Seoul")
    public void refresh() {
        refresher.refreshStale();
    }
}
