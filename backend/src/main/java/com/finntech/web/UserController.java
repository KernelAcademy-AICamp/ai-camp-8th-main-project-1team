package com.finntech.web;

import com.finntech.domain.AppUser;
import com.finntech.repository.AppUserRepository;
import com.finntech.service.PrivacyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 익명 계정 · 동의 · 정보주체 권리 (문서 §5-3).
 *
 * <p>가입에 <b>실명·이메일·연락처를 받지 않는다.</b> 닉네임과 목표 정보만으로 계정이 성립하며,
 * 이것이 RFP D26("실제 인증이나 개인정보를 요구하지 않고")을 만족시키는 구조다.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AppUserRepository userRepository;
    private final PrivacyService privacyService;
    private final Clock clock;

    public UserController(AppUserRepository userRepository, PrivacyService privacyService, Clock clock) {
        this.userRepository = userRepository;
        this.privacyService = privacyService;
        this.clock = clock;
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }

    public record CreateUserRequest(
            @NotBlank @Size(max = 40) String nickname,
            @NotNull @DecimalMin("0") BigDecimal monthlyIncome,
            @NotNull @DecimalMin("0") BigDecimal goalAmount,
            @NotNull @Min(1) @Max(120) Integer goalMonths
    ) {}

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateUserRequest req) {
        userRepository.findByNickname(req.nickname()).ifPresent(u -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다");
        });
        AppUser saved = userRepository.save(new AppUser(
                req.nickname(), req.monthlyIncome(), req.goalAmount(), req.goalMonths()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(saved));
    }

    @GetMapping("/{userId}")
    public Map<String, Object> get(@PathVariable Long userId) {
        return view(find(userId));
    }

    public record ConsentRequest(@NotNull Boolean consent) {}

    /** 동의/철회. 철회하면 이미 수집된 USER_INPUT을 즉시 파기한다. */
    @PostMapping("/{userId}/consent")
    public Map<String, Object> consent(@PathVariable Long userId,
                                       @Valid @RequestBody ConsentRequest req) {
        AppUser user = privacyService.setConsent(userId, req.consent(), now());
        return view(user);
    }

    /** 정보주체의 열람 요청 (처리방침 6번). */
    @GetMapping("/{userId}/data")
    public Map<String, Object> export(@PathVariable Long userId) {
        find(userId);
        List<Map<String, Object>> records = privacyService.exportUserData(userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("recordCount", records.size());
        body.put("records", records);
        body.put("notice", "본인이 직접 입력한 기록만 포함됩니다. 예시(더미) 데이터는 개인정보가 아니므로 제외됩니다.");
        return body;
    }

    /** 정보주체의 삭제 요청 (처리방침 6번). 삭제 사실은 감사로그에 남는다. */
    @DeleteMapping("/{userId}/data")
    public Map<String, Object> erase(@PathVariable Long userId) {
        find(userId);
        int deleted = privacyService.eraseUserData(userId, now());
        return Map.of("userId", userId, "deletedCount", deleted,
                "notice", "삭제 사실이 감사로그에 기록되었습니다.");
    }

    private AppUser find(Long userId) {
        return userRepository.findById(userId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user " + userId + " not found"));
    }

    private Map<String, Object> view(AppUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", u.getId());
        m.put("nickname", u.getNickname());
        m.put("monthlyIncome", u.getMonthlyIncome());
        m.put("goalAmount", u.getGoalAmount());
        m.put("goalMonths", u.getGoalMonths());
        m.put("consentGiven", u.isConsentGiven());
        return m;
    }
}
