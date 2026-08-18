package com.finntech.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보관기간이 지난 행태 기록을 지운다.
 *
 * <h2>왜 자르나 — 방침이 상한을 안 주기 때문이다</h2>
 *
 * <p>방침 33조의 보유기간은 "회원 탈퇴, 동의 철회까지"라 <b>기간 자체로는 끝이 없다.</b>
 * 그래도 자르는 이유가 둘이다.
 *
 * <ul>
 *   <li>필요 이상 오래 들고 있을 이유가 없다 — 석 달 전 어느 버튼을 눌렀는지로 답할 물음이 없다
 *   <li>이 표는 <b>가장 빨리 자라는 표</b>다. 사용자 한 명이 하루에 수백 행을 만든다.
 *       2026-08-18 에 디스크가 95% 까지 찬 뒤라 더 그렇다
 * </ul>
 *
 * <h2>시각 — 04:30 UTC</h2>
 *
 * <p>호스트가 UTC 라 04:00 UTC 의 보유기간 파기({@code PrivacyController}) 다음이다.
 * 파기가 먼저 돌아야 <b>지워질 사용자의 기록을 정리하느라 헛돌지 않는다.</b>
 * (같은 "04시"가 서로 다른 시각을 뜻하는 사정은 {@code deploy/finntech-backup.timer} 참조.)
 *
 * <p>한 회차에 {@code PURGE_CHUNK} 만큼만 지운다. 남으면 다음 회차가 잇는다 — 처음 켜는 날
 * 밀린 것이 많아도 한 트랜잭션에 수만 행을 싣지 않는다.
 */
@Component
@ConditionalOnProperty(name = "finntech.usage.purge.enabled", havingValue = "true",
        matchIfMissing = true)
public class UsagePurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(UsagePurgeScheduler.class);

    private final UsageEventService usage;

    public UsagePurgeScheduler(UsageEventService usage) {
        this.usage = usage;
    }

    @Scheduled(cron = "${finntech.usage.purge.cron:0 30 4 * * *}")
    public void purge() {
        int removed = usage.purgeExpired();
        if (removed > 0) {
            log.info("행태 기록 정리 — {}건 지웠다(보관 {}일)", removed, usage.retentionDays());
        }
    }
}
