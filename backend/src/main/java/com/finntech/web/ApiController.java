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
    private final CardRecommendService cardRecommendService;
    private final ReportService reportService;
    private final com.finntech.service.PeriodSpendService periodSpend;
    private final AlertService alertService;
    private final ScoreService scoreService;
    private final NarrativeService narrativeService;
    private final AuditService auditService;
    private final AppUserRepository userRepository;
    /** 저장된 문장을 주고, 낡았으면 큐에 올린다 — 화면은 모델을 기다리지 않는다. */
    private final com.finntech.service.NarrativeCacheService narratives;
    private final CategoryRepository categoryRepository;
    private final ConsumptionRepository consumptionRepository;
    private final com.finntech.service.PeerCompareService peerCompare;
    private final AlertRepository alertRepository;
    private final Clock clock;

    public ApiController(AnalysisEngine engine, ReportService reportService, AlertService alertService,
                         ScoreService scoreService, NarrativeService narrativeService,
                         AuditService auditService, AppUserRepository userRepository,
                         com.finntech.service.NarrativeCacheService narratives,
                         CategoryRepository categoryRepository,
                         ConsumptionRepository consumptionRepository,
                         AlertRepository alertRepository, CardRecommendService cardRecommendService,
                         Clock clock,
                         com.finntech.service.PeerCompareService peerCompare,
                         com.finntech.service.PeriodSpendService periodSpend) {
        this.peerCompare = peerCompare;
        this.periodSpend = periodSpend;
        this.engine = engine;
        this.cardRecommendService = cardRecommendService;
        this.reportService = reportService;
        this.alertService = alertService;
        this.scoreService = scoreService;
        this.narrativeService = narrativeService;
        this.auditService = auditService;
        this.userRepository = userRepository;
        this.narratives = narratives;
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

    /**
     * 카드 추천 (개편안 {@code s-compare}).
     *
     * <p>여기 카드는 <b>실제 상품</b>이다(마스터 §4 원칙 5 재개정 2026-08-10). 예적금은 별도
     * 일반 비교 화면에서만 다룬다. 응답에 소비 요약을 함께 싣는 이유는 순위의 근거를 화면에서
     * 바로 대조할 수 있게 하기 위함이다 — 근거 없는 순위는 광고다.
     *
     * <p><b>여기는 혜택 비교까지다.</b> 신청 링크·CTA 를 응답에 싣지 않는다. 카드 정보는 수집
     * 시점 스냅샷이라 {@code asOf}(공시 기준일)를 함께 보내고, 화면은 그것을 반드시 병기한다.
     */
    @GetMapping("/products/recommend-cards")
    public CardRecommendService.Result recommendCards(@RequestParam Long userId) {
        user(userId);   // 없는 사용자면 404
        return cardRecommendService.recommend(engine.analyze(userId, now()), now());
    }

    // ---- 리포트 -----------------------------------------------------------

    /**
     * 소비 리포트.
     *
     * <p><b>특정 달을 고를 수 없다.</b> 예전에는 {@code month} 파라미터를 받았지만 엔진에
     * 전달되지 않고 캐시 키로만 쓰였다. {@code month=2026-03}으로 불러도 숫자는 최신
     * 전 기간 누계가 나오면서 응답에는 {@code "month":"2026-03"}이 함께 실려, 클라이언트가
     * 3월 한 달 지출로 오독할 수 있었다({@code monthlySpend} 맵에는 다른 달 키가 다 들어 있어
     * 스스로 모순이었다). 프론트는 이 파라미터를 보낸 적이 없으므로 그냥 없앤다.
     *
     * <p>본문은 <b>전 기간 집계</b>다. 달별 값이 필요하면 {@code monthlySpend}를 쓴다.
     * 캐시 키는 조회 시점의 달이라 본문 기간과 다르며, 그래서 무효화는 사용자 단위로 한다
     * ({@link ReportService#invalidateAll}).
     */
    /**
     * 또래 비교 — <b>같은 나이대의 중앙값</b>과 견준다(프로토타입_0818 리포트 `.peer`).
     *
     * <p>견줄 수 없으면 {@code 204} 다. 출생연도를 모르거나(본인인증 전) 또래 표본이 얇을
     * 때인데, 그때 억지로 숫자를 만들면 <b>없는 비교를 사실처럼</b> 보여주게 된다.
     * 화면은 204 를 받으면 그 절을 통째로 감춘다.
     */
    @GetMapping("/report/peer")
    public org.springframework.http.ResponseEntity<com.finntech.service.PeerCompareService.PeerCompare>
            peer(@RequestParam Long userId,
                 @RequestParam(defaultValue = "30") int days) {
        user(userId);
        var body = peerCompare.compare(userId, Math.max(1, Math.min(365, days)));
        return body == null ? org.springframework.http.ResponseEntity.noContent().build()
                : org.springframework.http.ResponseEntity.ok(body);
    }

    /**
     * <b>그 기간에 얼마를 썼는가</b> — 챌린지가 없어도 답한다.
     *
     * <p>리포트의 일별 계열은 지금까지 지킴이 주간 리포트에서만 나왔는데, 그것은 챌린지에
     * 딸린 것이라 없으면 404 이고 있어도 시작일 전은 안 센다. 그래서 소비 내역에는 결제가
     * 쌓여 있는데 리포트만 비는 일이 있었다(사용자 보고 2026-08-20). 이 진입로는 소비만
     * 보므로 <b>챌린지 유무와 무관하게</b> 같은 답을 준다.
     */
    @GetMapping("/report/period")
    public com.finntech.service.PeriodSpendService.PeriodSpend periodSpend(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "week") String period,
            @RequestParam(defaultValue = "0") int offset) {
        user(userId);
        // 과거로 너무 멀리 가면 빈 구간만 훑는다 — 2년으로 끊는다(주 104 · 달 24).
        int capped = Math.max(0, Math.min("month".equalsIgnoreCase(period) ? 24 : 104, offset));
        return periodSpend.of(userId, period, capped);
    }

    @GetMapping("/report/monthly")
    public Map<String, Object> report(@RequestParam Long userId) {
        user(userId);
        AnalysisResult analysis = engine.analyze(userId, now());
        String period = now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        // CONFIRMED일 때만 캐시된다 — ESTIMATED를 캐시하면 "더 기록하면 정확해집니다"가 거짓말이 된다
        ReportService.ReportBody rb = reportService.buildCached(userId, period, analysis, now());
        var narrativeReq = narrativeService.reportRequest(userId, rb, analysis);
        var shownNarrative = narratives.show(narrativeReq);
        NarrativeService.Narrative narrative =
                new NarrativeService.Narrative(shownNarrative.body(), shownNarrative.source());
        narratives.enqueueIfNeeded(narrativeReq);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
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
