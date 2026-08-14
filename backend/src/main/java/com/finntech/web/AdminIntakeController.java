package com.finntech.web;

import com.finntech.auth.AdminAuthService;
import com.finntech.auth.AuthFilter;
import com.finntech.domain.RealUserIntake;
import com.finntech.intake.IntakeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * admin 승인 화면의 API (설계서 Phase 3).
 *
 * <h2>요약만 준다</h2>
 *
 * <p>건별 목록을 주지 않고 <b>원문 열람 경로를 아예 만들지 않는다.</b> 승인이 판정하는 것은
 * <i>"이 배치가 정상적인 명세서인가"</i> 지 <i>"이 사람이 무엇을 샀는가"</i> 가 아니다.
 * 남의 소비내역 전체를 admin 이 열람하는 것은 그 자체가 개인정보 처리다.
 *
 * <p>이름도 마스킹해서 준다({@code 홍○동}) — 승인에 필요한 것은 "누구인지"가 아니라
 * "어떤 배치인지"다.
 */
@RestController
@RequestMapping("/api/admin")
@ConditionalOnProperty(name = "finntech.intake.enabled", havingValue = "true")
public class AdminIntakeController {

    private final IntakeService intake;
    private final AdminAuthService adminAuth;

    public AdminIntakeController(IntakeService intake, AdminAuthService adminAuth) {
        this.intake = intake;
        this.adminAuth = adminAuth;
    }

    /** 대기 중인 신청 — 요약만. */
    @GetMapping("/intake")
    public List<Map<String, Object>> pending(HttpServletRequest request) {
        requireReady(request);
        return intake.pending().stream().map(AdminIntakeController::summarize).toList();
    }

    /** 승인 → 제공자 적재. 성공하면 카드별 결과가 돌아온다. */
    @PostMapping("/intake/{id}/approve")
    public Map<String, Object> approve(@PathVariable Long id, HttpServletRequest request) {
        String username = requireReady(request);
        Map<String, Object> result = intake.approve(id, username);
        Map<String, Object> payload = new LinkedHashMap<>(result);
        payload.put("decidedBy", username);
        payload.put("notice", "사용자가 앱에서 본인인증 후 카드사 연결을 해야 화면에 보입니다.");
        return payload;
    }

    public record RejectBody(String reason) {}

    /**
     * 반려.
     *
     * <p>사유는 <b>코드로만</b> 고른다. 자유 입력을 두면 내용에 관한 사유를 쓰게 되고,
     * 그것은 내용을 봤다는 뜻이 된다.
     */
    @PostMapping("/intake/{id}/reject")
    public Map<String, Object> reject(@PathVariable Long id, @RequestBody RejectBody body,
                                      HttpServletRequest request) {
        String username = requireReady(request);
        RealUserIntake.RejectReason reason;
        try {
            reason = RealUserIntake.RejectReason.valueOf(body.reason());
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사유를 목록에서 골라 주세요.");
        }
        intake.reject(id, username, reason);
        return Map.of("ok", true, "decidedBy", username);
    }

    /** 반려 사유 목록 — 화면이 드롭다운을 그린다. */
    @GetMapping("/intake/reject-reasons")
    public List<Map<String, String>> rejectReasons() {
        return List.of(
                Map.of("code", "TOO_MANY_BAD_ROWS", "label", "못 읽은 줄이 너무 많음"),
                Map.of("code", "PERIOD_OUT_OF_RANGE", "label", "기간이 범위를 벗어남"),
                Map.of("code", "TOO_FEW_ROWS", "label", "결제 건수가 너무 적음"),
                Map.of("code", "BUSINESS_NUMBER_MISSING", "label", "사업자번호가 거의 없음"),
                Map.of("code", "DUPLICATE_REQUEST", "label", "중복 신청"),
                Map.of("code", "OTHER", "label", "그 밖에"));
    }

    private static Map<String, Object> summarize(RealUserIntake found) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", found.getId());
        row.put("ticket", found.getTicket());
        row.put("maskedName", found.getMaskedName());
        row.put("submittedAt", found.getSubmittedAt());
        row.put("submittedIp", found.getSubmittedIp());
        row.put("expiresAt", found.getExpiresAt());
        row.put("cardCount", found.getCardCount());
        row.put("rowCount", found.getRowCount());
        row.put("rejectedRowCount", found.getRejectedRowCount());
        row.put("totalAmount", found.getTotalAmount());
        row.put("refundCount", found.getRefundCount());
        row.put("refundAmount", found.getRefundAmount());
        row.put("withBusinessNumber", found.getWithBusinessNumber());
        row.put("distinctMerchants", found.getDistinctMerchants());
        row.put("periodFrom", found.getPeriodFrom());
        row.put("periodTo", found.getPeriodTo());
        return row;
    }

    /**
     * 로그인만으로는 부족하다 — <b>비밀번호를 바꾸고 TOTP 를 등록한 계정만</b> 승인할 수 있다.
     *
     * <p>최초 발급 비밀번호로 들어온 계정이 실 개인정보를 승인하면, 그 비밀번호를 아는 사람이
     * 곧 승인자가 된다. 2FA 없이 승인하는 것도 마찬가지다.
     */
    private String requireReady(HttpServletRequest request) {
        Object subject = request.getAttribute(AuthFilter.ATTR_SUBJECT_ID);
        if (subject == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var account = adminAuth.find((Long) subject)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (account.isMustChangePassword() || !account.isTotpConfirmed()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "비밀번호 변경과 2단계 인증 등록을 마쳐야 승인할 수 있습니다.");
        }
        return account.getUsername();
    }
}
