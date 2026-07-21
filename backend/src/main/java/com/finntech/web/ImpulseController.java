package com.finntech.web;

import com.finntech.domain.AppUser;
import com.finntech.repository.AppUserRepository;
import com.finntech.service.ImpulseSaverService;
import org.springframework.http.HttpStatus;
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

    private ImpulseSaverService.Snapshot guard(Supplier<ImpulseSaverService.Snapshot> action) {
        try { return action.get(); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
    }

    @GetMapping
    public ImpulseSaverService.Snapshot snapshot(@RequestParam Long userId) {
        return service.snapshot(user(userId), now());
    }

    @PostMapping("/categories")
    public ImpulseSaverService.Snapshot setCategories(@RequestBody CategoriesRequest req) {
        return guard(() -> service.setImpulseCategories(user(req.userId()), req.categories(), now()));
    }

    @PostMapping("/spend")
    public ImpulseSaverService.Snapshot spend(@RequestBody SpendRequest req) {
        return guard(() -> service.recordImpulseSpend(user(req.userId()), req.categoryCode(), req.amount(), now()));
    }

    @PostMapping("/upload")
    public ImpulseSaverService.Snapshot upload(@RequestBody UploadRequest req) {
        return guard(() -> service.uploadCard(user(req.userId()), req.csv(), now()));
    }

    public record CategoriesRequest(Long userId, List<String> categories) {}
    public record SpendRequest(Long userId, String categoryCode, BigDecimal amount) {}
    public record UploadRequest(Long userId, String csv) {}
}
