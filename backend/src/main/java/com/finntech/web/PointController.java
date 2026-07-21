package com.finntech.web;

import com.finntech.domain.AppUser;
import com.finntech.repository.AppUserRepository;
import com.finntech.service.PointService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * 게임화 저축 루프 API (문서 §5-5, Qapital 벤치마크 + 치팅데이 쿠폰). 판단은 {@link PointService}가, 컨트롤러는 배선만.
 *
 * <p>포인트·목표·쿠폰 전부 가상/더미, 이체·강제차감은 가상 이동이다 — 실제 송금·결제가 아니다.
 */
@RestController
@RequestMapping("/api/points")
public class PointController {

    private final PointService pointService;
    private final AppUserRepository userRepository;
    private final Clock clock;

    public PointController(PointService pointService, AppUserRepository userRepository, Clock clock) {
        this.pointService = pointService;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    private AppUser user(Long userId) {
        return userRepository.findById(userId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user " + userId + " not found"));
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }

    private PointService.PointSnapshot guard(Supplier<PointService.PointSnapshot> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping
    public PointService.PointSnapshot snapshot(@RequestParam Long userId) {
        return pointService.snapshot(user(userId), now());
    }

    // ---- 저축·소비 액션 ----------------------------------------------------

    /** "살 뻔했다" — 참은 즉시 랜덤 목표에 자동 입금. */
    @PostMapping("/avoided")
    public PointService.PointSnapshot avoided(@RequestBody AvoidRequest req) {
        return guard(() -> pointService.avoid(user(req.userId()), req.categoryCode(), req.amount(), now()));
    }

    @PostMapping("/spend")
    public PointService.PointSnapshot spend(@RequestBody SpendRequest req) {
        return guard(() -> pointService.spend(user(req.userId()), req.categoryCode(),
                req.amount(), req.necessary(), now()));
    }

    // ---- 목표 버킷 CRUD ----------------------------------------------------

    @PostMapping("/goals")
    public PointService.PointSnapshot createGoal(@RequestBody GoalRequest req) {
        return guard(() -> pointService.createGoal(user(req.userId()), req.name(), req.emoji(),
                req.targetAmount(), now()));
    }

    @PutMapping("/goals/{goalId}")
    public PointService.PointSnapshot updateGoal(@PathVariable Long goalId, @RequestBody GoalRequest req) {
        return guard(() -> pointService.updateGoal(user(req.userId()), goalId, req.name(), req.emoji(),
                req.targetAmount(), req.priority(), now()));
    }

    @DeleteMapping("/goals/{goalId}")
    public PointService.PointSnapshot deleteGoal(@PathVariable Long goalId, @RequestParam Long userId) {
        return guard(() -> pointService.deleteGoal(user(userId), goalId, now()));
    }

    // ---- 목표 마일스톤 -----------------------------------------------------

    @PostMapping("/goals/{goalId}/milestones")
    public PointService.PointSnapshot addMilestone(@PathVariable Long goalId, @RequestBody MilestoneRequest req) {
        return guard(() -> pointService.addMilestone(user(req.userId()), goalId,
                req.name(), req.emoji(), req.cost(), now()));
    }

    @DeleteMapping("/milestones/{milestoneId}")
    public PointService.PointSnapshot deleteMilestone(@PathVariable Long milestoneId, @RequestParam Long userId) {
        return guard(() -> pointService.deleteMilestone(user(userId), milestoneId, now()));
    }

    // ---- 저축 계획 · 목표별 통장 추천 --------------------------------------

    /** 이 목표를 위해 '줄일 습관 소비' 카테고리를 저장한다 → 월 절약액·달성 개월수가 파생 계산된다. */
    @PostMapping("/goals/{goalId}/plan")
    public PointService.PointSnapshot setGoalPlan(@PathVariable Long goalId, @RequestBody PlanRequest req) {
        return guard(() -> pointService.setGoalPlan(user(req.userId()), goalId, req.cutCategories(), now()));
    }

    /** 목표 1·2·3에 계획 기간에 맞는 실 적금을 중복 없이 추천(정보성). */
    @GetMapping("/recommendations")
    public java.util.List<PointService.GoalRecommendationView> recommendations(@RequestParam Long userId) {
        return pointService.recommendForGoals(user(userId), now());
    }

    // ---- 치팅데이 쿠폰 -----------------------------------------------------

    @PostMapping("/coupon/{couponId}/use")
    public PointService.PointSnapshot useCoupon(@PathVariable Long couponId, @RequestParam Long userId) {
        return pointService.useCoupon(user(userId), couponId, now());
    }

    @PostMapping("/coupon/{couponId}/decline")
    public PointService.PointSnapshot declineCoupon(@PathVariable Long couponId, @RequestParam Long userId) {
        return pointService.declineCoupon(user(userId), couponId, now());
    }

    // ---- 요청 바디 ---------------------------------------------------------

    public record AvoidRequest(Long userId, String categoryCode, BigDecimal amount) {}
    public record SpendRequest(Long userId, String categoryCode, BigDecimal amount, boolean necessary) {}
    public record GoalRequest(Long userId, String name, String emoji, BigDecimal targetAmount, Boolean priority) {}
    public record MilestoneRequest(Long userId, String name, String emoji, BigDecimal cost) {}
    public record PlanRequest(Long userId, java.util.List<String> cutCategories) {}
}
