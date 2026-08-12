package com.finntech.auth;

import com.finntech.domain.UserToken;
import com.finntech.repository.AdminAccountRepository;
import com.finntech.repository.AdminRecoveryCodeRepository;
import com.finntech.repository.UserTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>잠긴 admin 이 실제로 빠져나오는가.</b>
 *
 * <p>이 앱의 admin 은 비밀번호를 잊거나 폰을 잃으면 화면에서 빠져나올 방법이 없다 —
 * 비밀번호 재설정 메일도 본인확인도 없다(만들면 그것이 새 공격면이 된다). 그래서
 * <b>서버에 닿을 수 있는 사람</b>만 쓰는 되돌리기가 유일한 길이고, 그 길이 막히면
 * 계정이 영영 죽는다. 설계서에 적어만 두고 만들지 않아 실제로 그 상태가 났다(2026-08-12).
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminLockoutRecoveryTest {

    @Autowired AdminAuthService adminAuth;
    @Autowired AdminAccountRepository accounts;
    @Autowired AdminRecoveryCodeRepository recoveryCodes;
    @Autowired UserTokenRepository tokens;
    @Autowired Clock clock;

    private static final String NAME = "recovery-test";

    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    /**
     * `@Modifying` 삭제 질의는 트랜잭션 안에서만 돈다.
     *
     * <p>`@BeforeEach` 에 `@Transactional` 을 붙여도 안 걸린다 — 스프링 프록시는 시험 메서드를
     * 감싸지 생명주기 콜백을 감싸지 않는다. 그래서 트랜잭션을 직접 연다.
     */
    @BeforeEach
    void clean() {
        new org.springframework.transaction.support.TransactionTemplate(txManager)
                .executeWithoutResult(status -> accounts.findByUsername(NAME).ifPresent(account -> {
                    recoveryCodes.deleteByAdminId(account.getId());
                    tokens.deleteAllOf(UserToken.Role.ADMIN, account.getId());
                    accounts.delete(account);
                }));
    }

    /** 로그인까지 마쳐 2단계 인증이 등록된, "정상적으로 쓰이던" 계정을 만든다. */
    private Long enrolledAccount(String temporary) {
        var account = accounts.findByUsername(NAME).orElseThrow();
        adminAuth.login(NAME, temporary, "", new MockHttpServletRequest());
        adminAuth.changePassword(account.getId(), temporary, "LongEnoughPassword2026!");
        var enroll = adminAuth.beginTotpEnrollment(account.getId(), "MOA");
        String code = Totp.codeAt(enroll.get("secret"), Totp.stepOf(clock.instant().getEpochSecond()));
        adminAuth.confirmTotpEnrollment(account.getId(), code);
        return account.getId();
    }

    @Test
    @DisplayName("폰을 잃고 복구 코드도 없으면 — 되돌리기 전에는 못 들어온다")
    void lockedOutWithoutReset() {
        String temporary = adminAuth.createAccount(NAME);
        enrolledAccount(temporary);

        // 비밀번호는 알지만 인증 앱이 없다. 2단계가 등록돼 있으므로 빈 코드로는 못 들어간다.
        assertThat(adminAuth.login(NAME, "LongEnoughPassword2026!", "", new MockHttpServletRequest()))
                .as("2단계가 등록된 계정은 코드 없이 못 들어간다")
                .isEmpty();
    }

    @Test
    @DisplayName("되돌리면 임시 비밀번호만으로 다시 들어온다 — 2단계 인증이 함께 지워진다")
    void resetOpensTheDoorAgain() {
        String temporary = adminAuth.createAccount(NAME);
        enrolledAccount(temporary);

        String fresh = adminAuth.resetAccount(NAME);

        // **여기가 요점이다.** TOTP 를 같이 지우지 않으면 폰을 잃은 사람은
        // 비밀번호를 새로 받아도 여전히 못 들어온다 — 되돌리는 의미가 없다.
        var result = adminAuth.login(NAME, fresh, "", new MockHttpServletRequest());
        assertThat(result).as("되돌린 뒤에는 임시 비밀번호와 빈 코드로 들어온다").isPresent();
        assertThat(result.get().mustChangePassword()).isTrue();
        assertThat(result.get().totpEnrolled()).isFalse();
    }

    @Test
    @DisplayName("되돌리면 옛 비밀번호·복구 코드·세션이 전부 죽는다")
    void resetInvalidatesEverythingOld() {
        String temporary = adminAuth.createAccount(NAME);
        Long adminId = enrolledAccount(temporary);
        // 확인용 세션 하나를 더 만들어 둔다
        adminAuth.login(NAME, "LongEnoughPassword2026!",
                Totp.codeAt(crackSecret(adminId), Totp.stepOf(clock.instant().getEpochSecond() + 60)),
                new MockHttpServletRequest());

        adminAuth.resetAccount(NAME);

        assertThat(adminAuth.login(NAME, "LongEnoughPassword2026!", "", new MockHttpServletRequest()))
                .as("옛 비밀번호는 죽는다").isEmpty();
        assertThat(tokens.findAll().stream()
                .filter(t -> t.getRole() == UserToken.Role.ADMIN && t.getSubjectId().equals(adminId)))
                .as("옛 세션은 전부 폐기된다").isEmpty();
        assertThat(recoveryCodes.findAll().stream().filter(c -> c.getAdminId().equals(adminId)))
                .as("옛 복구 코드는 전부 폐기된다").isEmpty();
    }

    @Test
    @DisplayName("없는 계정을 되돌리려 하면 조용히 넘어가지 않는다")
    void resetUnknownAccountFails() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> adminAuth.resetAccount("no-such-admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("되돌린 뒤 2단계 인증을 다시 등록할 수 있다 — 한 번 등록하면 끝이 아니다")
    void canEnrollAgainAfterReset() {
        String temporary = adminAuth.createAccount(NAME);
        Long adminId = enrolledAccount(temporary);
        adminAuth.resetAccount(NAME);

        // 되돌리기 전에는 `이미 등록됐습니다` 로 막히던 자리다.
        var enroll = adminAuth.beginTotpEnrollment(adminId, "MOA");
        assertThat(enroll.get("secret")).isNotBlank();
    }

    /** 시험 안에서만 쓰는 우회 — 등록된 비밀을 복호화해 코드를 만든다. */
    private String crackSecret(Long adminId) {
        var account = accounts.findById(adminId).orElseThrow();
        return new String(account.getTotpSecretEnc(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
