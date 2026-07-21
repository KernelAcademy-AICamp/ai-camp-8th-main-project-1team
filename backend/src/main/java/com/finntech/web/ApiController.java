package com.finntech.web;

import com.finntech.audit.AuditService;
import com.finntech.domain.Alert;
import com.finntech.domain.AppUser;
import com.finntech.domain.Category;
import com.finntech.domain.Consumption;
import com.finntech.domain.Enums;
import com.finntech.engine.AnalysisEngine;
import com.finntech.engine.AnalysisResult;
import com.finntech.repository.*;
import com.finntech.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/**
 * REST API (문서 §6).
 *
 * <p><b>공통 규약</b>: 모든 조회 응답에 {@code dataSourceMode}를 포함한다.
 * 모든 추천 응답에 "왜 이 순위인지" 근거 필드를 포함한다 (RFP D19의 설명 요구).
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final AnalysisEngine engine;
    private final RecommendService recommendService;
    private final ReportService reportService;
    private final AlertService alertService;
    private final ScoreService scoreService;
    private final NarrativeService narrativeService;
    private final AuditService auditService;
    private final AppUserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final ConsumptionRepository consumptionRepository;
    private final AlertRepository alertRepository;
    private final Clock clock;

    public ApiController(AnalysisEngine engine, RecommendService recommendService,
                         ReportService reportService, AlertService alertService,
                         ScoreService scoreService, NarrativeService narrativeService,
                         AuditService auditService, AppUserRepository userRepository,
                         CategoryRepository categoryRepository,
                         ConsumptionRepository consumptionRepository,
                         AlertRepository alertRepository, Clock clock) {
        this.engine = engine;
        this.recommendService = recommendService;
        this.reportService = reportService;
        this.alertService = alertService;
        this.scoreService = scoreService;
        this.narrativeService = narrativeService;
        this.auditService = auditService;
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.consumptionRepository = consumptionRepository;
        this.alertRepository = alertRepository;
        this.clock = clock;
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }

    private AppUser user(Long userId) {
        return userRepository.findById(userId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user " + userId + " not found"));
    }

    // ---- 추천 -------------------------------------------------------------

    @GetMapping("/products/recommend")
    public Map<String, Object> recommend(@RequestParam Long userId) {
        AppUser u = user(userId);
        AnalysisResult analysis = engine.analyze(userId, now());
        RecommendService.Recommendations rec = recommendService.recommend(u, analysis);

        List<Map<String, Object>> items = new ArrayList<>();
        int rank = 1;
        for (RecommendService.Scored s : rec.items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", rank++);
            m.put("productId", s.product().getId());
            m.put("name", s.product().getName());
            m.put("productType", s.product().getProductType());
            m.put("riskGrade", s.product().getRiskGrade());
            m.put("expectedRate", s.product().getExpectedRate());
            m.put("minJoinAmount", s.product().getMinJoinAmount());
            m.put("minPeriodMonths", s.product().getMinPeriodMonths());
            m.put("targetCategoryCode", s.product().getTargetCategoryCode());
            // 근거 필드 — 설명가능성
            m.put("matchScore", s.totalScore());
            m.put("scoreBreakdown", Map.of(
                    "periodFit", s.periodFit(),
                    "riskFit", s.riskFit(),
                    "categoryFit", s.categoryFit()));
            m.put("gateReason", s.gateReason());
            items.add(m);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("items", items);
        body.put("availableFunds", rec.availableFunds());
        body.put("gatingRelaxed", rec.gatingRelaxed());
        body.put("overspendingCategories", analysis.overspendingCategories());
        body.put("longTermVolatilityIndex", round(analysis.longTermVolatilityIndex()));
        body.put("dataSourceMode", rec.dataSourceMode());
        body.put("estimationReason", rec.estimationReason());
        return body;
    }

    // ---- 리포트 -----------------------------------------------------------

    @GetMapping("/report/monthly")
    public Map<String, Object> report(@RequestParam Long userId,
                                      @RequestParam(required = false) String month) {
        user(userId);
        AnalysisResult analysis = engine.analyze(userId, now());
        String period = (month == null || month.isBlank())
                ? now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"))
                : month;
        // CONFIRMED일 때만 캐시된다 — ESTIMATED를 캐시하면 "더 기록하면 정확해집니다"가 거짓말이 된다
        ReportService.ReportBody rb = reportService.buildCached(userId, period, analysis, now());
        NarrativeService.Narrative narrative = narrativeService.summarizeReport(rb, analysis);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("month", month);
        body.put("totalSpend", rb.totalSpend());
        body.put("positive", rb.positive());
        body.put("negative", rb.negative());
        body.put("monthlySpend", rb.monthlySpend());
        body.put("narrative", narrative.text());
        body.put("narrativeSource", narrative.source());
        body.put("dataSourceMode", rb.dataSourceMode());
        body.put("estimationReason", rb.estimationReason());
        return body;
    }

    // ---- FDS --------------------------------------------------------------

    @GetMapping("/alert/list")
    public Map<String, Object> alerts(@RequestParam Long userId) {
        user(userId);
        AnalysisResult analysis = engine.analyze(userId, now());
        List<Alert> stored = alertRepository.findByUserIdOrderByOccurredAtDescIdDesc(userId);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Alert a : stored) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("alertId", a.getId());
            m.put("consumptionId", a.getConsumptionId());
            m.put("categoryCode", a.getCategoryCode());
            m.put("amount", a.getAmount());
            m.put("occurredAt", a.getOccurredAt());
            m.put("deviationScore", round(a.getDeviationScore()));
            m.put("matchedRules", a.getMatchedRules().isBlank()
                    ? List.of() : List.of(a.getMatchedRules().split(",")));
            items.add(m);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("items", items);
        body.put("evaluatedCount", analysis.deviations().size());
        body.put("dataSourceMode", analysis.dataSourceMode());
        body.put("estimationReason", analysis.estimationReason());
        return body;
    }

    /** 분석을 다시 돌려 경고를 재생성한다. 시연에서 버튼 하나로 부른다. */
    @PostMapping("/alert/rescan")
    public Map<String, Object> rescan(@RequestParam Long userId) {
        user(userId);
        AnalysisResult analysis = engine.analyze(userId, now());
        List<Alert> created = alertService.detectAndRecord(analysis, now());
        return Map.of("userId", userId, "created", created.size(),
                "evaluatedCount", analysis.deviations().size(),
                "dataSourceMode", analysis.dataSourceMode());
    }

    // ---- 소비건전성지수 ----------------------------------------------------

    @GetMapping("/score/{userId}")
    public Map<String, Object> score(@PathVariable Long userId) {
        AppUser u = user(userId);
        AnalysisResult analysis = engine.analyze(userId, now());
        ScoreService.ScoreResult r = scoreService.score(u, analysis);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("score", r.score());
        body.put("grade", r.grade());
        // Map.of는 null 값을 허용하지 않는다. stability는 '측정 불가'일 때 null이다.
        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("savingsProgress", r.savingsProgress());
        breakdown.put("stability", r.stability());
        breakdown.put("plannedRatio", r.plannedRatio());
        body.put("breakdown", breakdown);
        body.put("volatilityMeasured", r.volatilityMeasured());
        body.put("dataSourceMode", r.dataSourceMode());
        body.put("estimationReason", r.estimationReason());
        return body;
    }

    // ---- 소비내역 입력 (실사용자 전용, source=USER_INPUT 고정) ----------------

    public record ConsumptionRequest(
            @NotNull Long userId,
            @NotBlank String categoryCode,
            @NotNull @DecimalMin("1") BigDecimal amount,
            @NotNull LocalDateTime occurredAt,
            boolean planned
    ) {}

    @PostMapping("/consumption")
    public ResponseEntity<Map<String, Object>> addConsumption(@Valid @RequestBody ConsumptionRequest req) {
        AppUser u = user(req.userId());
        if (!u.isConsentGiven()) {
            // 미동의 시 수집하지 않는다 (문서 §5-3). 더미 데모 모드로 안내한다.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "개인정보 수집에 동의하지 않은 계정입니다. 예시 데이터 기반 데모 모드로 이용해 주세요.");
        }
        Category category = categoryRepository.findByCode(req.categoryCode()).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "unknown category: " + req.categoryCode()));

        Consumption saved = consumptionRepository.save(new Consumption(
                req.userId(), category, req.amount(), req.occurredAt(),
                req.planned(), Enums.DataSource.USER_INPUT));   // source 고정

        // 캐시된 리포트는 특정 달이 아니라 '전체 이력'을 집계한 것이고 저장 키는 조회 시점의 달이다.
        // 그래서 입력 건의 달로만 무효화하면 지난달 소비를 넣었을 때 이번달 키의 캐시가 살아남아
        // 새 입력이 반영되지 않는다. 해당 사용자의 캐시를 전부 버린다.
        reportService.invalidateAll(req.userId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", req.userId());
        payload.put("consumptionId", saved.getId());
        payload.put("categoryCode", req.categoryCode());
        payload.put("source", Enums.DataSource.USER_INPUT.name());
        auditService.append("CONSUMPTION_CREATED", payload, now());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("id", saved.getId(), "source", saved.getSource()));
    }

    // ---- 감사로그 검증 ------------------------------------------------------

    @GetMapping("/audit/verify")
    public AuditService.VerificationResult verify() {
        return auditService.verify();
    }

    /**
     * 계층 3 — PENDING 배치를 외부 TSA에 앵커링한다.
     * 요청 간 15초 지연이 있으므로 배치가 여러 개면 시간이 걸린다.
     */
    @PostMapping("/audit/anchor")
    public AuditService.AnchorReport anchor() {
        return auditService.anchorPendingBatches();
    }

    @GetMapping("/categories")
    public List<Category> categories() {
        return categoryRepository.findAllByOrderByCodeAsc();
    }

    private static double round(double v) { return Math.round(v * 10000.0) / 10000.0; }
}
