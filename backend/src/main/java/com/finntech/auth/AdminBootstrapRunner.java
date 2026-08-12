package com.finntech.auth;

import com.finntech.repository.AdminAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * admin 계정을 하나 만든다 — <b>기동 1회용 도구</b>.
 *
 * <pre>
 *   FINNTECH_ADMIN_BOOTSTRAP=admin1 java -jar backend.jar ...
 * </pre>
 *
 * <p>임시 비밀번호를 <b>로그에 한 번 찍고 끝난다.</b> 받아 적은 뒤 그 환경변수를 지운다.
 *
 * <h2>왜 SQL 이 아니라 여기인가</h2>
 *
 * <p>Argon2 해시를 손으로 만들면 파라미터(m·t·p)가 어긋나기 쉽고, 어긋나면 <b>로그인이 안 되는
 * 이유를 찾기 어렵다</b> — 비밀번호가 틀린 것과 구분되지 않기 때문이다. 검증하는 쪽과 같은
 * 인코더로 만드는 것이 유일하게 확실한 방법이다.
 *
 * <h2>이미 있으면 아무것도 안 한다</h2>
 *
 * <p>재기동 때마다 비밀번호가 바뀌면 <b>기동이 곧 계정 탈취</b>가 된다. 환경변수를 지우는 것을
 * 잊어도 안전해야 한다.
 */
@Component
public class AdminBootstrapRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final String username;
    private final AdminAccountRepository accounts;
    private final AdminAuthService adminAuth;

    public AdminBootstrapRunner(@Value("${finntech.admin.bootstrap:}") String username,
                                AdminAccountRepository accounts, AdminAuthService adminAuth) {
        this.username = username == null ? "" : username.trim();
        this.accounts = accounts;
        this.adminAuth = adminAuth;
    }

    @Override
    public void run(String... args) {
        if (username.isEmpty()) return;
        if (accounts.existsByUsername(username)) {
            log.info("admin '{}' 은 이미 있다 — 아무것도 하지 않는다 (비밀번호는 그대로다)", username);
            return;
        }
        String temporary = adminAuth.createAccount(username);
        // 로그에 한 번만 남는다. 첫 로그인에서 반드시 바꾸게 되어 있다(must_change_password).
        log.warn("""

                ┌─────────────────────────────────────────────────────────┐
                │ admin 계정을 만들었다 — 이 값은 다시 볼 수 없다          │
                │   계정      : {}
                │   임시 비밀번호: {}
                │                                                         │
                │ 첫 로그인에서 비밀번호를 바꾸고 2단계 인증을 등록해야    │
                │ 승인을 할 수 있다. 그 뒤 FINNTECH_ADMIN_BOOTSTRAP 을 지워라. │
                └─────────────────────────────────────────────────────────┘""",
                username, temporary);
    }
}
