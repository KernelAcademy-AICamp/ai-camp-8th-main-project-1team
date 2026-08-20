package com.finntech.web;

import com.finntech.auth.AdminAuthService;
import com.finntech.auth.AuthFilter;
import com.finntech.domain.AppUser;
import com.finntech.repository.AppUserRepository;
import com.finntech.service.MyDataClient;
import com.finntech.service.PrivacyService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * <b>관리자가 한 사람의 것을 전부 지운다</b> — CI 완전일치로만.
 *
 * <h2>왜 CI 하나만 받나</h2>
 *
 * <p>이름·전화번호로 찾게 하면 <b>지우는 일 때문에 관리자가 개인식별정보를 보게 된다.</b>
 * 앞뒤가 바뀐 것이다. CI 는 {@code SHA-256(이름+주민앞7+전화)} 이라 되돌릴 수 없고, 그 자체로는
 * 누구인지 말해 주지 않는다. 64자를 <b>통째로</b> 받고 부분일치·검색·목록을 두지 않으므로
 * <b>이미 그 값을 아는 사람만</b> 지울 수 있다 — 훑어보다 잘못 누를 여지가 없다.
 *
 * <p>{@code AdminIntakeController} 가 이름을 {@code 홍○동} 으로 마스킹해 주는 것과 같은 태도다:
 * 관리자에게 필요한 것은 "누구인지"가 아니라 "무엇을 하는가"다.
 *
 * <h2>두 곳을 함께 지운다</h2>
 *
 * <p>사람의 자취는 본체(소비·리포트·지킴이·행태)와 제공자(신원·카드·결제) <b>양쪽에</b> 있다.
 * 한쪽만 지우면 파기가 아니다 — 실제로 오늘 손으로 SQL 을 두드려야 했던 것이 제공자 쪽에
 * 통로가 없어서였다(2026-08-20).
 *
 * <p><b>한쪽에만 있어도 지운다.</b> 신청은 승인됐는데 앱은 안 쓴 사람은 제공자에만 있고,
 * 연동 없이 둘러본 사람은 본체에만 있다. 어느 쪽이 없다고 나머지를 안 지우면 안 된다.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminUserPurgeController {

    private static final Logger log = LoggerFactory.getLogger(AdminUserPurgeController.class);

    /** CI 는 SHA-256 hex 라 정확히 64자다. 형식이 아니면 조회조차 하지 않는다. */
    private static final Pattern CI = Pattern.compile("[0-9a-f]{64}");

    private final AppUserRepository users;
    private final PrivacyService privacy;
    private final MyDataClient myDataClient;
    private final AdminAuthService adminAuth;
    private final Clock clock;

    public AdminUserPurgeController(AppUserRepository users, PrivacyService privacy,
                                    MyDataClient myDataClient, AdminAuthService adminAuth,
                                    Clock clock) {
        this.users = users;
        this.privacy = privacy;
        this.myDataClient = myDataClient;
        this.adminAuth = adminAuth;
        this.clock = clock;
    }

    /**
     * @param ci      지울 사람의 CI 64자
     * @param confirm 화면이 받아 온 확인 문구. {@link #CONFIRM} 과 같아야 한다.
     */
    public record PurgeBody(String ci, String confirm) {}

    /**
     * 손이 미끄러지는 것을 막는 한 겹. 되돌릴 수 없는 일이라 <b>버튼 하나로는 안 되게</b> 한다.
     *
     * <p>문구를 한글로 둔 이유: 붙여넣기로 지나가는 것을 조금이라도 늦추려면 눈으로 읽고
     * 손으로 쳐야 한다.
     */
    public static final String CONFIRM = "파기합니다";

    @PostMapping("/users/purge")
    public Map<String, Object> purge(@RequestBody PurgeBody body, HttpServletRequest request) {
        String admin = requireReady(request);
        String ci = body.ci() == null ? "" : body.ci().trim().toLowerCase();
        if (!CI.matcher(ci).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "CI 64자를 그대로 넣어 주세요(영문 소문자와 숫자).");
        }
        if (!CONFIRM.equals(body.confirm())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "확인란에 '" + CONFIRM + "' 라고 적어야 진행합니다.");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ci", ci.substring(0, 12) + "…");   // 로그·화면에 통째로 남기지 않는다

        // ── 본체 ──────────────────────────────────────────────────────────
        // **ci 로 계정을 먼저 찾는다.** eraseUserData 가 ci 를 비우므로 순서를 뒤집으면 못 찾는다.
        AppUser target = users.findByCi(ci).orElse(null);
        if (target == null) {
            result.put("appUser", "없음");
            result.put("erasedConsumptions", 0);
        } else {
            int erased = privacy.eraseUserData(target.getId(), now);
            result.put("appUser", target.getId());
            result.put("erasedConsumptions", erased);
        }

        // ── 제공자 ────────────────────────────────────────────────────────
        // 본체가 없어도 부른다 — 신청은 승인됐는데 앱은 안 쓴 사람이 여기만 있다.
        MyDataClient.ProviderPurge provider = myDataClient.purgeUser(ci);
        result.put("provider", provider == null ? "응답 없음" : Map.of(
                "found", provider.found(),
                "payments", provider.payments(),
                "accountTxns", provider.accountTxns(),
                "cards", provider.cards(),
                "accounts", provider.accounts()));

        boolean anything = target != null || (provider != null && provider.found());
        result.put("found", anything);
        result.put("decidedBy", admin);
        log.warn("관리자 강제 파기 — admin={} ci={}… 본체={} 제공자={}",
                admin, ci.substring(0, 12), result.get("appUser"), result.get("provider"));
        return result;
    }

    /**
     * 로그인만으로는 부족하다 — <b>비밀번호를 바꾸고 TOTP 를 등록한 계정만</b> 지울 수 있다.
     *
     * <p>{@code AdminIntakeController.requireReady} 와 같은 규율이다. 승인보다 되돌리기 어려운
     * 일이라 더 느슨할 이유가 없다.
     */
    private String requireReady(HttpServletRequest request) {
        Object subject = request.getAttribute(AuthFilter.ATTR_SUBJECT_ID);
        if (subject == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var account = adminAuth.find((Long) subject)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (account.isMustChangePassword() || !account.isTotpConfirmed()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "비밀번호 변경과 2단계 인증 등록을 마쳐야 파기할 수 있습니다.");
        }
        return account.getUsername();
    }
}
