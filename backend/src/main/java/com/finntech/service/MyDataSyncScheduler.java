package com.finntech.service;

import com.finntech.guardian.GuardianService;
import com.finntech.repository.UserCardCompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 마이데이터 자동 동기화 배치.
 *
 * <p><b>왜 필요한가.</b> 증분 동기화({@link MyDataLinkService#renew})는 있었지만 부르는 곳이
 * 화면의 '동기화' 버튼뿐이었다. 마이데이터 커트오프가 실시간으로 전진해도 <b>사용자가 버튼을 눌러야만</b>
 * 새 결제가 들어와, 앱을 켜두지 않으면 지킴이의 차감·일 판정·알림이 영영 발화하지 않았다.
 * 실제 마이데이터도 정기 전송(주 1회 + 승인내역 알림)이 표준이라, 서버가 주기적으로 당겨오는 편이
 * 도메인적으로도 맞다.
 *
 * <p><b>두 단을 한 번에 돈다.</b> ① 마이데이터 서버 → 본체 원장(UserPayment·Consumption)
 * ② 본체 원장 → 지킴이 챌린지 원장. ②까지 해야 앱을 안 켠 사이에 생긴 결제도 판정·알림으로 이어진다.
 * ②는 화면 진입 시에도 호출되지만 멱등이라 중복 적재되지 않는다.
 *
 * <p><b>한 사용자의 실패가 다음 사용자를 막지 않는다.</b> 마이데이터 서버가 잠깐 죽어도 배치 전체가
 * 멈추면 안 되므로 사용자 단위로 예외를 삼킨다. 조용히 넘어가되 로그는 남긴다.
 *
 * <p>{@code finntech.mydata.auto-sync.enabled=false}로 끈다(테스트·오프라인 개발).
 */
@Component
@ConditionalOnProperty(name = "finntech.mydata.auto-sync.enabled", havingValue = "true", matchIfMissing = true)
public class MyDataSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(MyDataSyncScheduler.class);

    private final UserCardCompanyRepository linkRepository;
    private final MyDataLinkService linkService;
    private final GuardianService guardianService;

    public MyDataSyncScheduler(UserCardCompanyRepository linkRepository,
                               MyDataLinkService linkService,
                               GuardianService guardianService) {
        this.linkRepository = linkRepository;
        this.linkService = linkService;
        this.guardianService = guardianService;
    }

    /**
     * 연결된 사용자 전원의 새 결제를 당겨와 지킴이 원장까지 반영한다.
     *
     * <p>{@code fixedDelay}(이전 실행 <b>종료</b> 후 간격)를 쓴다. {@code fixedRate}는 한 번이 느려지면
     * 다음 실행이 겹쳐 같은 사용자를 동시에 동기화하게 된다 — 멱등이라 데이터가 깨지진 않지만
     * 마이데이터 서버를 두 배로 두드린다. 최초 지연을 두는 이유는 기동 직후 DB·외부 연결이
     * 자리잡기 전에 돌지 않게 하려는 것이다.
     */
    @Scheduled(
            fixedDelayString = "${finntech.mydata.auto-sync.interval-ms:300000}",
            initialDelayString = "${finntech.mydata.auto-sync.initial-delay-ms:60000}")
    public void syncLinkedUsers() {
        List<Long> userIds = linkRepository.findDistinctUserIds();
        if (userIds.isEmpty()) return;

        int users = 0, payments = 0, ledger = 0;
        for (Long userId : userIds) {
            try {
                int added = linkService.renew(userId).newPayments();
                payments += added;
                // 새 결제가 없어도 ②는 돌린다 — 이전 배치에서 원장 반영만 실패했을 수 있다.
                ledger += guardianService.syncFromMyData(userId);
                users++;
            } catch (Exception e) {
                // 마이데이터 서버 장애·동의 철회 등. 이 사용자만 건너뛰고 계속한다.
                log.warn("자동 동기화 건너뜀 — userId={} : {}", userId, e.toString());
            }
        }
        // 새로 들어온 게 있을 때만 INFO로 남긴다 — 5분마다 "0건"을 찍으면 로그가 그것만으로 찬다.
        // 배치가 돌기는 하는지 확인해야 할 때는 DEBUG를 켠다.
        if (payments > 0 || ledger > 0) {
            log.info("마이데이터 자동 동기화 — 사용자 {}명, 새 결제 {}건, 지킴이 원장 {}건", users, payments, ledger);
        } else {
            log.debug("마이데이터 자동 동기화 — 사용자 {}명 확인, 변경 없음", users);
        }
    }
}
