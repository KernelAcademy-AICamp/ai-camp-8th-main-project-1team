package com.finntech.web;

import com.finntech.domain.CutCandidateSelection;
import com.finntech.engine.CutCandidate;
import com.finntech.engine.PatternAnalyzer;
import com.finntech.engine.ProfileBuilder;
import com.finntech.engine.RecurringPayment;
import com.finntech.engine.RecurringPaymentDetector;
import com.finntech.engine.SpendingPattern;
import com.finntech.engine.UserProfile;
import com.finntech.service.CutCandidateService;
import com.finntech.service.NarrativeService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 소비 분석 API(②③④⑤). "판단은 코드가"(엔진)의 집계 산출물은 결정론 데이터 엔드포인트로 내리고,
 * "표현은 AI가"(LLM 문장)는 별도 온디맨드 엔드포인트로 분리한다(마스터 §4). 시간은 {@link Clock} 주입으로 고정(§3).
 */
@RestController
@RequestMapping("/api/analysis")
public class ConsumptionAnalysisController {

    private final RecurringPaymentDetector recurringDetector;
    private final PatternAnalyzer patternAnalyzer;
    private final ProfileBuilder profileBuilder;
    private final CutCandidateService cutCandidateService;
    private final NarrativeService narrativeService;
    /** 저장된 문장을 주고, 낡았으면 큐에 올린다 — 화면은 모델을 기다리지 않는다. */
    private final com.finntech.service.NarrativeCacheService narratives;
    private final Clock clock;

    public ConsumptionAnalysisController(RecurringPaymentDetector recurringDetector, PatternAnalyzer patternAnalyzer,
                                         ProfileBuilder profileBuilder, CutCandidateService cutCandidateService,
                                         NarrativeService narrativeService,
                                         com.finntech.service.NarrativeCacheService narratives, Clock clock) {
        this.recurringDetector = recurringDetector;
        this.patternAnalyzer = patternAnalyzer;
        this.profileBuilder = profileBuilder;
        this.cutCandidateService = cutCandidateService;
        this.narrativeService = narrativeService;
        this.narratives = narratives;
        this.clock = clock;
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }

    /** ②③④⑤ 집계 데이터 — 문장 없이 결정론적으로. */
    @GetMapping
    public AnalysisSummary summary(@RequestParam Long userId, @RequestParam(defaultValue = "90") int days) {
        LocalDateTime ref = now();
        return new AnalysisSummary(
                profileBuilder.build(userId, ref, days),
                recurringDetector.detect(userId, ref),
                patternAnalyzer.analyze(userId, ref, days),
                cutCandidateService.candidates(userId, ref, days));
    }

    /** ④ 프로필 요약 문장(LLM, 온디맨드). 실패 시 템플릿 폴백. */
    @GetMapping("/profile/narrative")
    public NarrativeService.Narrative profileNarrative(@RequestParam Long userId,
                                                       @RequestParam(defaultValue = "90") int days) {
        // 저장된 문장을 곧바로 준다 — 낡았으면 큐에 올리고, 새 문장은 다음에 열 때 보인다.
        var req = narrativeService.profileRequest(userId, profileBuilder.build(userId, now(), days));
        var shown = narratives.show(req);
        narratives.enqueueIfNeeded(req);
        return new NarrativeService.Narrative(shown.body(), shown.source());
    }

    /** ⑤ 절약 후보 권유 문장(LLM, 온디맨드). */
    @GetMapping("/cut/explain")
    public NarrativeService.Narrative explainCut(@RequestParam Long userId, @RequestParam String category2,
                                                 @RequestParam(defaultValue = "90") int days) {
        CutCandidate c = cutCandidateService.candidates(userId, now(), days).stream()
                .filter(x -> x.category2().equals(category2))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("절약 후보가 아닙니다: " + category2));
        var req = narrativeService.cutCandidateRequest(userId, c);
        var shown = narratives.show(req);
        narratives.enqueueIfNeeded(req);
        return new NarrativeService.Narrative(shown.body(), shown.source());
    }

    /** ⑤ 후보 하나를 "줄이겠다"고 선택 → 추적 시작. */
    @PostMapping("/cut/choose")
    public CutCandidateSelection choose(@RequestParam Long userId, @RequestParam String category2,
                                        @RequestParam(defaultValue = "90") int days) {
        return cutCandidateService.choose(userId, category2, now(), days);
    }

    /** ⑤ 월말 재검증(1회) — ACTIVE 선택들의 개선 여부 확정. */
    @PostMapping("/cut/verify")
    public List<CutCandidateSelection> verify(@RequestParam Long userId, @RequestParam(defaultValue = "90") int days) {
        return cutCandidateService.verifyActive(userId, now(), days);
    }

    /** ⑤ 선택 이력(최신순). */
    @GetMapping("/cut/history")
    public List<CutCandidateSelection> history(@RequestParam Long userId) {
        return cutCandidateService.history(userId);
    }

    public record AnalysisSummary(UserProfile profile, List<RecurringPayment> recurring,
                                  SpendingPattern pattern, List<CutCandidate> cutCandidates) {}

    /** 후보 아님 등 잘못된 요청 → 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }

    /** 이미 추적 중 등 상태 충돌 → 409. */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(IllegalStateException e) {
        return Map.of("error", e.getMessage());
    }
}
