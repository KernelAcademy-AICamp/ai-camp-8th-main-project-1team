package com.finntech.web;

import com.finntech.domain.AppUser;
import com.finntech.seed.SeedGenerator;
import com.finntech.service.CalibrationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 개발/관리자 전용 시드 삽입 (문서 §5-2 ④, §6).
 *
 * <p><b>일반 API로는 {@code DUMMY_SEED}가 생성되지 않아야 한다.</b> 이 컨트롤러는
 * {@code finntech.dev.seed-enabled=true}일 때만 빈으로 등록되며, 운영 프로파일에서는 꺼진다.
 */
@RestController
@RequestMapping("/api/dev")
@ConditionalOnProperty(name = "finntech.dev.seed-enabled", havingValue = "true")
public class DevSeedController {

    private final SeedGenerator seedGenerator;
    private final CalibrationService calibrationService;
    private final Clock clock;

    public DevSeedController(SeedGenerator seedGenerator,
                             CalibrationService calibrationService, Clock clock) {
        this.seedGenerator = seedGenerator;
        this.calibrationService = calibrationService;
        this.clock = clock;
    }

    /**
     * FDS 임계치 스윕 (D-11). 페르소나 확정 후 이걸 돌려 나온 값을
     * {@code finntech.analysis.fds.modified-z-threshold}에 넣는다.
     *
     * <pre>curl 'localhost:8080/api/dev/calibrate?userId=1&amp;from=2.0&amp;to=6.0&amp;step=0.25'</pre>
     */
    @GetMapping("/calibrate")
    public CalibrationService.CalibrationReport calibrate(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "2.0") double from,
            @RequestParam(defaultValue = "6.0") double to,
            @RequestParam(defaultValue = "0.25") double step,
            @RequestParam(defaultValue = "3") int targetMin,
            @RequestParam(defaultValue = "5") int targetMax) {
        return calibrationService.sweep(userId, LocalDateTime.now(clock),
                from, to, step, targetMin, targetMax);
    }

    public record SeedRequest(
            String nickname,
            BigDecimal monthlyIncome,
            BigDecimal goalAmount,
            Integer goalMonths,
            Integer months,
            Integer txPerMonth,
            Map<String, Double> categoryMix,
            Double plannedRatio,
            Double volatility,
            Integer anomalyCount,
            /** 이상거래 강도(평소 대비 배수). 경계 근처(1.5~3.0)로 두면 임계치 캘리브레이션이 의미를 갖는다. */
            Double anomalyMagnitudeMin,
            Double anomalyMagnitudeMax,
            Long seed
    ) {}

    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestBody SeedRequest req) {
        LocalDateTime ref = LocalDateTime.now(clock);
        SeedGenerator.SeedSpec spec = new SeedGenerator.SeedSpec(
                req.nickname(),
                req.monthlyIncome(),
                req.goalAmount(),
                req.goalMonths() == null ? 6 : req.goalMonths(),
                req.months() == null ? 6 : req.months(),
                req.txPerMonth() == null ? 30 : req.txPerMonth(),
                req.categoryMix(),
                req.plannedRatio() == null ? 0.6 : req.plannedRatio(),
                req.volatility() == null ? 0.2 : req.volatility(),
                req.anomalyCount() == null ? 3 : req.anomalyCount(),
                req.anomalyMagnitudeMin() == null ? 6.0 : req.anomalyMagnitudeMin(),
                req.anomalyMagnitudeMax() == null ? 10.0 : req.anomalyMagnitudeMax(),
                req.seed() == null ? 42L : req.seed());

        AppUser user = seedGenerator.generate(spec, ref);
        int products = seedGenerator.seedProducts(List.copyOf(spec.categoryMix().keySet()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", user.getId());
        body.put("nickname", user.getNickname());
        body.put("productsCreated", products);
        body.put("referenceTime", ref);
        return body;
    }
}
