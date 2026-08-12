package com.finntech.web;

import com.finntech.auth.AdminAuthService;
import com.finntech.auth.AuthFilter;
import com.finntech.auth.AuthTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * admin 로그인·계정 관리 (설계서 Phase 1).
 *
 * <p><b>접근은 URL 직접 입력만이다.</b> 사용자 앱 어디에도 이 화면으로 가는 링크·버튼이 없고,
 * 관리 화면은 별도 번들({@code admin.html})이라 사용자에게 배포되는 JS 에 들어가지 않는다.
 * 다만 <b>경로 숨김은 방어가 아니라 소음 감소</b>다 — API 는 어차피 열려 있고, 방어는
 * Argon2id·TOTP·지연·HttpOnly 쿠키·감사가 진다.
 *
 * <p>토큰은 <b>본문이 아니라 쿠키</b>로 나간다. {@code HttpOnly} 라 JS 가 읽지 못하므로
 * XSS 가 있어도 훔칠 수 없고, {@code Path=/api/admin} 이라 사용자 API 요청에는 실리지 않는다.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

    private final AdminAuthService adminAuth;
    private final AuthTokenService tokens;
    private final boolean secureCookie;
    private final String issuer;

    public AdminAuthController(AdminAuthService adminAuth, AuthTokenService tokens,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${finntech.auth.secure-cookie:true}") boolean secureCookie,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${finntech.auth.totp-issuer:MOA}") String issuer) {
        this.adminAuth = adminAuth;
        this.tokens = tokens;
        this.secureCookie = secureCookie;
        this.issuer = issuer;
    }

    public record LoginRequest(String username, String password, String code) {}

    /**
     * 로그인 — 비밀번호와 2차 인증을 <b>한 번에</b> 받는다.
     *
     * <p>단계를 나누면 "비밀번호는 맞았고 인증번호만 틀렸다"를 알려주는 셈이고, 그것은
     * 비밀번호를 확인해 주는 것이다. 실패 문구도 무엇이 틀렸든 하나다.
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest body,
                                                     HttpServletRequest request) {
        // 이 IP 가 최근에 여러 번 틀렸으면 응답을 늦춘다. **거부하지 않는다** —
        // 정당한 사용자가 세 번 틀렸다고 못 들어오면 안 된다(계정을 잠그지 않는 것과 같은 이유).
        sleep(adminAuth.delayFor(request));

        return adminAuth.login(body.username(), body.password(), body.code(), request)
                .map(result -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("username", result.username());
                    payload.put("mustChangePassword", result.mustChangePassword());
                    payload.put("totpEnrolled", result.totpEnrolled());
                    return ResponseEntity.ok()
                            .header(HttpHeaders.SET_COOKIE, sessionCookie(result.rawToken()).toString())
                            .body(payload);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", AdminAuthService.GENERIC_FAILURE)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @CookieValue(name = AuthFilter.ADMIN_COOKIE, required = false) String session) {
        tokens.revoke(session);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredCookie().toString())
                .body(Map.of("ok", true));
    }

    /** 지금 로그인한 admin 이 누구이고 무엇을 더 해야 하는가. */
    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        var account = adminAuth.find(subjectId(request)).orElseThrow();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", account.getUsername());
        payload.put("mustChangePassword", account.isMustChangePassword());
        payload.put("totpEnrolled", account.isTotpConfirmed());
        payload.put("ready", !account.isMustChangePassword() && account.isTotpConfirmed());
        return payload;
    }

    public record PasswordChangeRequest(String currentPassword, String newPassword) {}

    /** 비밀번호 변경 — 성공하면 <b>모든 세션이 끊긴다</b>. 다시 로그인해야 한다. */
    @PostMapping("/password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @RequestBody PasswordChangeRequest body, HttpServletRequest request) {
        try {
            adminAuth.changePassword(subjectId(request), body.currentPassword(), body.newPassword());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredCookie().toString())
                .body(Map.of("ok", true, "message", "비밀번호를 바꿨습니다. 다시 로그인해 주세요."));
    }

    /**
     * TOTP 등록 시작 — QR 주소와 함께 <b>비밀 문자열도 준다</b>.
     * 카메라를 못 쓰는 환경에서 손으로 넣을 길이 없으면 등록 자체가 막힌다.
     */
    @PostMapping("/totp/begin")
    public Map<String, String> beginTotp(HttpServletRequest request) {
        return adminAuth.beginTotpEnrollment(subjectId(request), issuer);
    }

    public record TotpConfirmRequest(String code) {}

    /**
     * 등록 확정 — 지금 뜬 코드를 확인한 뒤에만 확정한다.
     *
     * @return 복구 코드 8개. <b>이 응답에서만 볼 수 있다</b> — 저장은 해시로만 된다
     */
    @PostMapping("/totp/confirm")
    public Map<String, Object> confirmTotp(@RequestBody TotpConfirmRequest body,
                                           HttpServletRequest request) {
        try {
            List<String> codes = adminAuth.confirmTotpEnrollment(subjectId(request), body.code());
            return Map.of("ok", true, "recoveryCodes", codes,
                    "notice", "이 코드는 다시 볼 수 없습니다. 종이에 적어 보관하세요.");
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    private static Long subjectId(HttpServletRequest request) {
        Object value = request.getAttribute(AuthFilter.ATTR_SUBJECT_ID);
        if (value == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return (Long) value;
    }

    /**
     * 세션 쿠키.
     *
     * <p>{@code HttpOnly} — JS 가 못 읽는다(XSS 방어).
     * {@code SameSite=Strict} — 다른 사이트에서 온 요청에 안 실린다(CSRF 방어).
     * {@code Path=/api/admin} — 사용자 API 요청에는 아예 실리지 않는다.
     */
    private ResponseCookie sessionCookie(String raw) {
        return ResponseCookie.from(AuthFilter.ADMIN_COOKIE, raw)
                .httpOnly(true).secure(secureCookie).sameSite("Strict")
                .path("/api/admin").maxAge(Duration.ofHours(12)).build();
    }

    private ResponseCookie expiredCookie() {
        return ResponseCookie.from(AuthFilter.ADMIN_COOKIE, "")
                .httpOnly(true).secure(secureCookie).sameSite("Strict")
                .path("/api/admin").maxAge(0).build();
    }

    private static void sleep(Duration delay) {
        if (delay == null || delay.isZero() || delay.isNegative()) return;
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
