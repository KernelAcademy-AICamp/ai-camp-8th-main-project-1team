package com.finntech.intake;

import com.finntech.auth.AuthTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료된 것을 치운다 — 신청 대기열과 인증 토큰.
 *
 * <p><b>대기열은 창고가 아니라 통로다.</b> 아무도 손대지 않은 신청에는 실 개인정보가 들어
 * 있으므로 방치하면 그 자체가 위험이다. 토큰도 같은 이유로(만료된 행이 무한히 쌓인다) 치운다.
 *
 * <p>새벽에 한 번만 돈다. 자주 돌 이유가 없고, 잦은 삭제는 운영 중 잠금 경합만 만든다.
 */
@Component
@ConditionalOnProperty(name = "finntech.intake.enabled", havingValue = "true")
public class IntakeMaintenance {

    private static final Logger log = LoggerFactory.getLogger(IntakeMaintenance.class);

    private final IntakeService intake;
    private final AuthTokenService tokens;

    public IntakeMaintenance(IntakeService intake, AuthTokenService tokens) {
        this.intake = intake;
        this.tokens = tokens;
    }

    @Scheduled(cron = "0 20 4 * * *", zone = "Asia/Seoul")
    public void sweep() {
        int expiredIntakes = intake.purgeExpired();
        int expiredTokens = tokens.purgeExpired();
        if (expiredIntakes > 0 || expiredTokens > 0) {
            log.info("정리 — 만료 신청 {}건 · 만료 토큰 {}건", expiredIntakes, expiredTokens);
        }
    }
}
