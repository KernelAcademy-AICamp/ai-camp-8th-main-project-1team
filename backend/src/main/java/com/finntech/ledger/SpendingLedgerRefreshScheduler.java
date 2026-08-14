package com.finntech.ledger;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 밤에 한 번, 판정이 낡은 사용자의 <b>고정지출·낭비 층</b>을 채운다.
 *
 * <h2>왜 밤 한 번인가</h2>
 *
 * <p>이 배치는 표를 채우려고 판정을 부른다 — <i>"표는 계산을 일으키지 않는다"</i> 는 원칙에
 * 어긋나는 유일한 상시 경로다({@link SpendingLedgerJudgmentRefresher} 머리말에 사정을 적었다).
 * 그 양보를 <b>가장 좁게</b> 두려고 하루 한 번이고, 낡은 사람만 고르고, 예산이 있다.
 *
 * <p>상시로 돌리면 원칙은 이름만 남는다. 반대로 아예 안 돌리면 화면을 안 연 사용자가
 * 뒤에 붙을 알고리즘 프로그램에서 통째로 빠진다. 하루 한 번이 그 사이다.
 *
 * <h2>시각</h2>
 *
 * <p>04:50 — 앞에 선 것들과 겹치지 않게 뒤에 둔다. 04:00 보유기간 파기
 * ({@code PrivacyController}), 04:20 신청 만료({@code IntakeMaintenance}) 다음이다.
 * 파기가 먼저 돌아야 <b>지워질 사용자를 갱신하느라 헛돌지 않는다.</b>
 */
@Component
@ConditionalOnProperty(name = "finntech.ledger.refresh.enabled", havingValue = "true",
        matchIfMissing = true)
public class SpendingLedgerRefreshScheduler {

    private final SpendingLedgerJudgmentRefresher refresher;

    public SpendingLedgerRefreshScheduler(SpendingLedgerJudgmentRefresher refresher) {
        this.refresher = refresher;
    }

    @Scheduled(cron = "${finntech.ledger.refresh.cron:0 50 4 * * *}", zone = "Asia/Seoul")
    public void refresh() {
        refresher.refreshStale();
    }
}
