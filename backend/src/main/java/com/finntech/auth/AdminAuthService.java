package com.finntech.auth;

import com.finntech.audit.AuditService;
import com.finntech.crypto.FieldCrypto;
import com.finntech.domain.AdminAccount;
import com.finntech.domain.AdminRecoveryCode;
import com.finntech.domain.UserToken;
import com.finntech.repository.AdminAccountRepository;
import com.finntech.repository.AdminRecoveryCodeRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * admin 로그인 — 실 개인정보 적재를 승인하는 열쇠라 다층으로 막는다.
 *
 * <pre>
 *   비밀번호(Argon2id)  +  TOTP 6자리  +  IP 단위 지연  +  HttpOnly 쿠키  +  감사 기록
 * </pre>
 *
 * <p>IP 허용목록은 쓰지 않기로 했으므로 <b>TOTP 가 유일한 2차 방어</b>다.
 *
 * <h2>실패를 구분하지 않는다</h2>
 *
 * <p>"그런 계정 없음" / "비밀번호 틀림" / "TOTP 틀림" 을 나누면 <b>계정 열거</b>가 되고,
 * 단계를 나누면 "비밀번호는 맞았다"를 알려주는 셈이다. 전부 같은 문구로 답한다.
 *
 * <h2>타이밍도 맞춘다</h2>
 *
 * <p>계정이 없을 때 Argon2 계산을 건너뛰면 <b>빨리 응답해서 계정 존재가 드러난다.</b>
 * 그래서 없어도 더미 해시를 검증해 시간을 맞춘다.
 */
@Service
public class AdminAuthService {

    /** 무엇이 틀렸든 이 문구 하나다. */
    public static final String GENERIC_FAILURE = "로그인할 수 없습니다.";

    /**
     * 계정이 없을 때 시간을 맞추기 위해 검증하는 더미 해시.
     *
     * <p>실제 비밀번호가 아니다 — 아무도 맞힐 수 없는 값이면 되고, 목적은 오직
     * <b>Argon2 계산에 같은 시간을 쓰는 것</b>이다.
     */
    private static final String TIMING_DUMMY =
            "$argon2id$v=19$m=19456,t=2,p=1$c29tZS10aW1pbmctc2FsdA$Y2xhaW1lZC10aW1pbmctZHVtbXktdmFsdWU";

    private static final int RECOVERY_CODE_COUNT = 8;

    private final AdminAccountRepository accounts;
    private final AdminRecoveryCodeRepository recoveryCodes;
    private final AuthTokenService tokens;
    private final LoginThrottle throttle;
    private final FieldCrypto crypto;
    private final AuditService audit;
    private final Clock clock;
    /** OWASP 권장 파라미터. 메모리를 강제로 써 GPU 병렬화를 막는 것이 핵심이다. */
    private final PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);

    public AdminAuthService(AdminAccountRepository accounts,
                            AdminRecoveryCodeRepository recoveryCodes,
                            AuthTokenService tokens, LoginThrottle throttle,
                            FieldCrypto crypto, AuditService audit, Clock clock) {
        this.accounts = accounts;
        this.recoveryCodes = recoveryCodes;
        this.tokens = tokens;
        this.throttle = throttle;
        this.crypto = crypto;
        this.audit = audit;
        this.clock = clock;
    }

    /** 로그인 성공 결과. {@code rawToken} 은 쿠키로만 나가고 본문에는 싣지 않는다. */
    public record LoginResult(String rawToken, String username, boolean mustChangePassword,
                              boolean totpEnrolled) {}

    /**
     * 로그인.
     *
     * @param totpOrRecovery 6자리 TOTP, 또는 폰을 잃었을 때의 복구 코드
     * @return 실패하면 비어 있다 — <b>사유를 돌려주지 않는다</b>
     */
    @Transactional
    public Optional<LoginResult> login(String username, String password, String totpOrRecovery,
                                       HttpServletRequest request) {
        String ip = AuthTokenService.clientIp(request);
        Optional<AdminAccount> found = accounts.findByUsername(username == null ? "" : username.trim());

        // 계정이 없어도 같은 시간을 쓴다 — 응답 속도가 계정 존재를 흘리지 않게.
        if (found.isEmpty() || !found.get().isEnabled()) {
            encoder.matches(password == null ? "" : password, TIMING_DUMMY);
            fail(ip, username, "UNKNOWN_OR_DISABLED");
            return Optional.empty();
        }
        AdminAccount account = found.get();

        if (password == null || !encoder.matches(password, account.getPasswordHash())) {
            fail(ip, username, "BAD_PASSWORD");
            return Optional.empty();
        }

        // TOTP 등록 전이면 2차 인증을 건너뛴다 — 첫 로그인에서 비밀번호를 바꾸고 등록해야 한다.
        // 그 상태로는 승인 API 에 닿을 수 없게 컨트롤러가 막는다.
        boolean enrolled = account.isTotpConfirmed() && account.getTotpSecretEnc() != null;
        if (enrolled && !checkSecondFactor(account, totpOrRecovery)) {
            fail(ip, username, "BAD_SECOND_FACTOR");
            return Optional.empty();
        }

        throttle.recordSuccess(ip);
        account.markLogin(LocalDateTime.now(clock));
        accounts.save(account);

        AuthTokenService.Issued issued =
                tokens.issue(UserToken.Role.ADMIN, account.getId(), request);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", account.getUsername());
        payload.put("ip", ip);
        payload.put("agent", request == null ? null : request.getHeader("User-Agent"));
        audit.append("ADMIN_LOGIN", payload, LocalDateTime.now(clock));

        return Optional.of(new LoginResult(issued.raw(), account.getUsername(),
                account.isMustChangePassword(), enrolled));
    }

    /**
     * TOTP 또는 복구 코드를 확인한다.
     *
     * <p>성공한 TOTP 구간을 저장해 <b>같은 코드의 재사용을 막는다</b>. 복구 코드는 쓰는 즉시
     * 지운다 — 일회용이라야 종이가 새어도 피해가 한 번으로 끝난다.
     */
    private boolean checkSecondFactor(AdminAccount account, String input) {
        if (input == null || input.isBlank()) return false;

        String secret = crypto.decrypt(account.getTotpSecretEnc());
        Long step = Totp.verify(secret, input, clock.instant().getEpochSecond(),
                account.getTotpLastStep());
        if (step != null) {
            account.markTotpStep(step);
            return true;
        }
        // TOTP 가 아니면 복구 코드일 수 있다.
        String normalized = Tokens.normalizeRecoveryCode(input);
        if (normalized.length() < 8) return false;
        return recoveryCodes.findByAdminIdAndCodeHash(account.getId(), Tokens.hash(normalized))
                .map(code -> { recoveryCodes.delete(code); return true; })
                .orElse(false);
    }

    private void fail(String ip, String username, String reason) {
        throttle.recordFailure(ip);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("ip", ip);
        payload.put("reason", reason);      // 감사에는 남기되 응답으로는 나가지 않는다
        audit.append("ADMIN_LOGIN_FAILED", payload, LocalDateTime.now(clock));
    }

    /** 로그인 전에 이 IP 가 기다려야 하는 시간. */
    public java.time.Duration delayFor(HttpServletRequest request) {
        return throttle.delayFor(AuthTokenService.clientIp(request));
    }

    // ── 계정 관리 ────────────────────────────────────────────────────────────

    /**
     * 계정을 만든다. <b>사람별로</b> 만든다 — 공용 계정은 두지 않는다.
     *
     * @return 발급된 임시 비밀번호. 화면에 한 번만 보여주고 저장하지 않는다
     */
    @Transactional
    public String createAccount(String username) {
        if (accounts.existsByUsername(username)) {
            throw new IllegalArgumentException("이미 있는 계정: " + username);
        }
        String temporary = Tokens.newRecoveryCode();       // 사람이 옮겨 적을 수 있는 형식
        accounts.save(new AdminAccount(username, encoder.encode(temporary), LocalDateTime.now(clock)));
        return temporary;
    }

    /**
     * 계정을 <b>처음 상태로 되돌린다</b> — 잠겼을 때 빠져나오는 유일한 길.
     *
     * <h2>왜 필요한가</h2>
     *
     * <p>지금 구조에서 admin 이 잠기는 경우가 둘 있다. <b>비밀번호를 잊었을 때</b>와
     * <b>폰을 잃고 복구 코드도 없을 때</b>다. 둘 다 화면에서는 빠져나올 방법이 없다 —
     * 비밀번호 재설정 메일도, 본인확인도 없기 때문이다(만들면 그것이 새 공격면이 된다).
     *
     * <p>그래서 <b>서버에 닿을 수 있는 사람</b>만 쓸 수 있는 길을 둔다. 그 권한은 AWS IAM 이
     * 관리하고(SSM · CloudTrail), 우리가 만든 비밀번호보다 강하다. 설계서가 "SSM 우회 복구
     * 경로"라고 적어 둔 것이 이것인데, 정작 만들지 않아 <b>고칠 수 없는 상태가 실재했다</b>
     * (2026-08-12).
     *
     * <h2>무엇을 되돌리나</h2>
     *
     * <p>비밀번호를 새 임시값으로 · 다음 로그인에서 변경 강제 · <b>TOTP 등록 해제</b> ·
     * 복구 코드 폐기 · <b>기존 세션 전부 폐기</b>. 즉 계정을 방금 만든 상태로 되돌린다.
     * TOTP 를 지우지 않으면 폰을 잃은 사람은 여전히 못 들어온다 — 그것이 이 함수의 요점이다.
     *
     * @return 새 임시 비밀번호. 호출부가 한 번 보여주고 버린다
     */
    @Transactional
    public String resetAccount(String username) {
        AdminAccount account = accounts.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("없는 계정: " + username));
        String temporary = Tokens.newRecoveryCode();
        account.reset(encoder.encode(temporary));
        accounts.save(account);
        recoveryCodes.deleteByAdminId(account.getId());
        tokens.revokeAllOf(UserToken.Role.ADMIN, account.getId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("by", "server-side-reset");
        audit.append("ADMIN_RESET", payload, LocalDateTime.now(clock));
        return temporary;
    }

    /**
     * 비밀번호를 바꾼다. <b>기존 세션을 전부 끊는다</b> — 바꾼 이유가 유출이라면
     * 옛 세션이 살아 있는 것이 곧 구멍이다.
     */
    @Transactional
    public void changePassword(Long adminId, String currentPassword, String newPassword) {
        AdminAccount account = accounts.findById(adminId).orElseThrow();
        if (!encoder.matches(currentPassword, account.getPasswordHash())) {
            throw new IllegalArgumentException(GENERIC_FAILURE);
        }
        if (newPassword == null || newPassword.length() < 12) {
            throw new IllegalArgumentException("비밀번호는 12자 이상이어야 합니다.");
        }
        account.changePassword(encoder.encode(newPassword));
        accounts.save(account);
        tokens.revokeAllOf(UserToken.Role.ADMIN, adminId);
    }

    /** TOTP 등록 시작 — 비밀을 만들어 암호화해 두고 등록 주소를 돌려준다. */
    @Transactional
    public Map<String, String> beginTotpEnrollment(Long adminId, String issuer) {
        AdminAccount account = accounts.findById(adminId).orElseThrow();
        if (account.isTotpConfirmed()) {
            throw new IllegalStateException("이미 등록됐습니다. 다시 등록하려면 초기화가 필요합니다.");
        }
        String secret = Totp.newSecret();
        account.prepareTotp(crypto.encrypt(secret));
        accounts.save(account);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("secret", secret);       // 카메라가 안 될 때 손으로 넣을 값
        result.put("uri", Totp.provisioningUri(issuer, account.getUsername(), secret));
        return result;
    }

    /**
     * 등록 확정 — <b>지금 뜬 코드가 맞는지 확인한 뒤에만</b> 확정한다.
     *
     * <p>확인 없이 확정하면 QR 을 잘못 스캔한 사람이 다음 로그인에서 영영 못 들어온다.
     *
     * @return 복구 코드 8개. <b>이 순간에만 볼 수 있다</b>
     */
    @Transactional
    public List<String> confirmTotpEnrollment(Long adminId, String code) {
        AdminAccount account = accounts.findById(adminId).orElseThrow();
        String secret = crypto.decrypt(account.getTotpSecretEnc());
        Long step = Totp.verify(secret, code, clock.instant().getEpochSecond(), null);
        if (step == null) {
            throw new IllegalArgumentException("인증번호가 맞지 않습니다.");
        }
        account.markTotpStep(step);
        account.confirmTotp();
        accounts.save(account);

        recoveryCodes.deleteByAdminId(adminId);
        List<String> plain = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String code9 = Tokens.newRecoveryCode();
            plain.add(code9);
            recoveryCodes.save(new AdminRecoveryCode(adminId,
                    Tokens.hash(Tokens.normalizeRecoveryCode(code9)), LocalDateTime.now(clock)));
        }
        return plain;
    }

    public Optional<AdminAccount> find(Long adminId) {
        return accounts.findById(adminId);
    }
}
