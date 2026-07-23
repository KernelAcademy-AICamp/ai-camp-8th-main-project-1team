package com.finntech.ml;

import com.finntech.ml.WasteScoringService.WasteJudgment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 낭비/필수 ML 판정 노출(W8). 규칙 FDS(/api/alert)는 baseline으로 병존. */
@RestController
@RequestMapping("/api/ml")
public class MlController {

    private final WasteScoringService wasteScoringService;

    public MlController(WasteScoringService wasteScoringService) {
        this.wasteScoringService = wasteScoringService;
    }

    /** 모델 로드 여부(미배치면 규칙 baseline 사용). */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("modelReady", wasteScoringService.modelReady());
    }

    /** 사용자 거래별 낭비/필수 판정 + 설명. */
    @GetMapping("/waste/{userId}")
    public List<WasteJudgment> waste(@PathVariable Long userId) {
        return wasteScoringService.scoreUser(userId);
    }

    /** 개인화 재분류(W8-5): category2를 본인 기준 낭비/필수로 지정. "본인 취미/필수면 보호". */
    public record OverrideRequest(Long userId, String category2, boolean waste) {}

    @PostMapping("/override")
    public Map<String, Object> override(@RequestBody OverrideRequest req) {
        wasteScoringService.setOverride(req.userId(), req.category2(), req.waste());
        return Map.of("ok", true, "category2", req.category2(), "waste", req.waste());
    }
}
