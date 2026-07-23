package com.finntech.web;

import com.finntech.domain.AppUser;
import com.finntech.repository.AppUserRepository;
import com.finntech.service.ImpulseSaverService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

/**
 * 충동예산 절약통 API (마스터 §5-5). 판단은 {@link ImpulseSaverService}가, 컨트롤러는 배선만.
 * 전부 가상 — 실 송금·결제 아님.
 */
@RestController
@RequestMapping("/api/impulse")
public class ImpulseController {

    private final ImpulseSaverService service;
    private final AppUserRepository userRepository;
    private final Clock clock;

    public ImpulseController(ImpulseSaverService service, AppUserRepository userRepository, Clock clock) {
        this.service = service;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    private AppUser user(Long userId) {
        return userRepository.findById(userId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user " + userId + " not found"));
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }

    /**
     * 서비스 호출 래퍼 — (1) 검증 실패는 400으로 번역, (2) 신규 사용자 <b>첫 접근 동시 요청</b> 시
     * {@code impulse_saver_state} 생성 경쟁(unique {@code idx_iss_user} 중복)은 <b>새 트랜잭션으로 1회 재시도</b>한다.
     * 컨트롤러(비트랜잭션 경계)에서 재시도해야 fresh 스냅샷이 먼저 커밋된 행을 보고 통과한다(MySQL REPEATABLE READ).
     */
    private ImpulseSaverService.Snapshot run(Supplier<ImpulseSaverService.Snapshot> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (DataIntegrityViolationException | UnexpectedRollbackException firstAccessRace) {
            return action.get();   // 상태행이 방금 생겼으니 재시도는 조회로 통과
        }
    }

    @GetMapping
    public ImpulseSaverService.Snapshot snapshot(@RequestParam Long userId) {
        return run(() -> service.snapshot(user(userId), now()));
    }

    @PostMapping("/categories")
    public ImpulseSaverService.Snapshot setCategories(@RequestBody CategoriesRequest req) {
        return run(() -> service.setImpulseCategories(user(req.userId()), req.categories(), now()));
    }

    @PostMapping("/spend")
    public ImpulseSaverService.Snapshot spend(@RequestBody SpendRequest req) {
        return run(() -> service.recordImpulseSpend(user(req.userId()), req.categoryCode(), req.amount(), now()));
    }

    @PostMapping("/upload")
    public ImpulseSaverService.Snapshot upload(@RequestBody UploadRequest req) {
        return run(() -> service.uploadCard(user(req.userId()), req.csv(), now()));
    }

    public record CategoriesRequest(Long userId, List<String> categories) {}
    public record SpendRequest(Long userId, String categoryCode, BigDecimal amount) {}
    public record UploadRequest(Long userId, String csv) {}
}
