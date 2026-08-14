package com.finntech.auth;

import com.finntech.repository.AdminAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * admin 계정을 <b>만들거나 되돌린다</b> — 기동 1회용 도구.
 *
 * <pre>
 *   FINNTECH_ADMIN_BOOTSTRAP=admin1   없으면 만든다. 이미 있으면 아무것도 안 한다
 *   FINNTECH_ADMIN_RESET=admin1       처음 상태로 되돌린다 (비밀번호 · TOTP · 세션)
 * </pre>
 *
 * <p>임시 비밀번호를 <b>로그에 한 번 찍고 끝난다.</b> 받아 적은 뒤 그 환경변수를 지운다.
 *
 * <h2>왜 되돌리는 길이 필요한가</h2>
 *
 * <p>admin 이 잠기는 경우가 둘 있다 — <b>비밀번호를 잊었을 때</b>와 <b>폰을 잃고 복구 코드도
 * 없을 때</b>. 화면에는 빠져나올 방법이 없다. 비밀번호 재설정 메일이나 본인확인을 만들면
 * 그것 자체가 새 공격면이 되기 때문이다.
 *
 * <p>그래서 <b>서버에 닿을 수 있는 사람</b>만 쓸 수 있는 길을 둔다. 그 권한은 AWS IAM 이
 * 관리하고(SSM · CloudTrail 감사), 우리가 만든 비밀번호보다 강하다.
 *
 * <p>설계서가 "SSM 우회 복구 경로" 라고 적어 둔 것이 이것인데 정작 만들지 않아,
 * 실제로 <b>고칠 수 없는 상태</b>가 생겼다(2026-08-12). 그래서 여기 둔다.
 *
 * <h2>왜 SQL 이 아니라 여기인가</h2>
 *
 * <p>Argon2 해시를 손으로 만들면 파라미터(m·t·p)가 어긋나기 쉽고, 어긋나면 <b>로그인이 안 되는
 * 이유를 찾기 어렵다</b> — 비밀번호가 틀린 것과 구분되지 않기 때문이다. 검증하는 쪽과 같은
 * 인코더로 만드는 것이 유일하게 확실한 방법이다.
 *
 * <h2>재기동이 계정 탈취가 되지 않게</h2>
 *
 * <p>{@code BOOTSTRAP} 은 <b>이미 있으면 아무것도 안 한다.</b> 환경변수를 지우는 것을 잊어도
 * 재기동 때마다 비밀번호가 바뀌지 않는다. 반대로 {@code RESET} 은 부를 때마다 실제로 되돌리므로,
 * <b>쓰고 나면 반드시 지운다</b> — 기동할 때마다 admin 이 초기화되면 그것도 잠김이다.
 */
@Component
public class AdminBootstrapRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final String bootstrapUsername;
    private final String resetUsername;
    private final AdminAccountRepository accounts;
    private final AdminAuthService adminAuth;

    public AdminBootstrapRunner(@Value("${finntech.admin.bootstrap:}") String bootstrapUsername,
                                @Value("${finntech.admin.reset:}") String resetUsername,
                                AdminAccountRepository accounts, AdminAuthService adminAuth) {
        this.bootstrapUsername = bootstrapUsername == null ? "" : bootstrapUsername.trim();
        this.resetUsername = resetUsername == null ? "" : resetUsername.trim();
        this.accounts = accounts;
        this.adminAuth = adminAuth;
    }

    @Override
    public void run(String... args) {
        if (!resetUsername.isEmpty()) reset();
        if (!bootstrapUsername.isEmpty()) bootstrap();
    }

    private void bootstrap() {
        if (accounts.existsByUsername(bootstrapUsername)) {
            log.info("admin '{}' 은 이미 있다 — 아무것도 하지 않는다 (되돌리려면 FINNTECH_ADMIN_RESET)",
                    bootstrapUsername);
            return;
        }
        announce("계정을 만들었다", bootstrapUsername, adminAuth.createAccount(bootstrapUsername));
    }

    private void reset() {
        try {
            announce("계정을 처음 상태로 되돌렸다 (비밀번호 · 2단계 인증 · 세션)",
                    resetUsername, adminAuth.resetAccount(resetUsername));
        } catch (IllegalArgumentException exception) {
            log.error("admin 되돌리기 실패 — {}", exception.getMessage());
        }
    }

    /**
     * 임시 비밀번호를 로그에 <b>한 번만</b> 남긴다.
     *
     * <p>첫 로그인에서 반드시 바꾸게 되어 있으므로({@code must_change_password}),
     * 이 값은 그때까지만 유효하다.
     */
    private void announce(String what, String username, String temporary) {
        log.warn("""

                ┌──────────────────────────────────────────────────────────────┐
                │ admin {}
                │   계정         : {}
                │   임시 비밀번호 : {}
                │                                                              │
                │ 첫 로그인에서 비밀번호를 바꾸고 2단계 인증을 등록해야 승인할 수 있다. │
                │ 그 뒤 FINNTECH_ADMIN_BOOTSTRAP / FINNTECH_ADMIN_RESET 을 지워라.  │
                └──────────────────────────────────────────────────────────────┘""",
                what, username, temporary);
    }
}
