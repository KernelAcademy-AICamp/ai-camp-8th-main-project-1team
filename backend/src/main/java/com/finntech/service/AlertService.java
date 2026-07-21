package com.finntech.service;

import com.finntech.audit.AuditService;
import com.finntech.config.AnalysisProperties;
import com.finntech.domain.Alert;
import com.finntech.domain.Enums;
import com.finntech.engine.AnalysisResult;
import com.finntech.repository.AlertRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FDS — 발표 주인공 (D-07).
 *
 * <p><b>최종 경고 = z-score 플래그 AND 룰 1개 이상 일치</b> (문서 §5 ①).
 * 단일 조건으로 판정하지 않는 이유는 금감원 공개 시나리오가 4개 조건을 AND로 묶는 구조이고,
 * 단일 조건이면 오탐이 도배되기 때문이다.
 */
@Service
public class AlertService {

    private final AnalysisProperties props;
    private final AlertRepository alertRepository;
    private final AuditService auditService;

    public AlertService(AnalysisProperties props, AlertRepository alertRepository,
                        AuditService auditService) {
        this.props = props;
        this.alertRepository = alertRepository;
        this.auditService = auditService;
    }

    /** 판정만 하고 저장하지 않는다 — 재현성 테스트가 이 메서드를 직접 부른다. */
    public List<Detection> detect(AnalysisResult analysis) {
        List<Detection> out = new ArrayList<>();
        for (AnalysisResult.Deviation d : analysis.deviations()) {
            if (!d.exceedsThreshold()) continue;
            List<Enums.FdsRule> matched = matchRules(d);
            if (matched.isEmpty()) continue;
            out.add(new Detection(d, matched, explain(d, matched)));
        }
        return out;
    }

    private List<Enums.FdsRule> matchRules(AnalysisResult.Deviation d) {
        AnalysisProperties.Fds cfg = props.getFds();
        List<Enums.FdsRule> matched = new ArrayList<>();

        int hour = d.occurredAt().getHour();
        boolean night = (cfg.getNightStartHour() <= cfg.getNightEndHour())
                ? (hour >= cfg.getNightStartHour() && hour < cfg.getNightEndHour())
                // 자정을 넘기는 구간(예: 22시~6시) 지원. 24시간은 원형 변수라 단순 비교가 안 된다.
                : (hour >= cfg.getNightStartHour() || hour < cfg.getNightEndHour());
        if (night && d.baselineMedianAmount() > 0
                && d.amount().doubleValue() >= d.baselineMedianAmount() * cfg.getNightAmountMultiplier()) {
            matched.add(Enums.FdsRule.NIGHT_HIGH_AMOUNT);
        }

        if (d.baselineCount() <= cfg.getNewCategoryMaxBaselineCount()) {
            matched.add(Enums.FdsRule.NEW_CATEGORY_SPIKE);
        }

        if (d.baselineMonthlyAvgCount() > 0
                && d.recentCount() > d.baselineMonthlyAvgCount() * cfg.getFrequencyMultiplier()) {
            matched.add(Enums.FdsRule.FREQUENCY_DEVIATION);
        }
        return matched;
    }

    private String explain(AnalysisResult.Deviation d, List<Enums.FdsRule> rules) {
        String basis = d.baselineSource() == AnalysisResult.BaselineSource.CATEGORY
                ? "같은 카테고리의 평소 지출" : "회원님 전체 평소 지출";
        StringBuilder sb = new StringBuilder();
        sb.append(basis).append(" 대비 이례적인 금액입니다 (편차점수 ")
          .append(String.format("%.2f", d.modifiedZ())).append("). ");
        for (Enums.FdsRule r : rules) {
            sb.append(switch (r) {
                case NIGHT_HIGH_AMOUNT -> "심야 시간대 고액 결제. ";
                case NEW_CATEGORY_SPIKE -> "평소 쓰지 않던 카테고리에서 발생. ";
                case FREQUENCY_DEVIATION -> "해당 카테고리 결제 빈도가 평소보다 급증. ";
            });
        }
        return sb.toString().trim();
    }

    /** 판정 후 저장 + 감사로그 기록. 저장 자체가 감사 대상이다. */
    @Transactional
    public List<Alert> detectAndRecord(AnalysisResult analysis, LocalDateTime at) {
        alertRepository.deleteByUserId(analysis.userId());
        List<Alert> saved = new ArrayList<>();
        for (Detection det : detect(analysis)) {
            AnalysisResult.Deviation d = det.deviation();
            String rules = det.matchedRules().stream().map(Enum::name).reduce((a, b) -> a + "," + b).orElse("");
            Alert alert = alertRepository.save(new Alert(
                    analysis.userId(), d.consumptionId(), d.categoryCode(), d.amount(),
                    d.occurredAt(), d.modifiedZ(), rules, at));
            saved.add(alert);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", analysis.userId());
            payload.put("consumptionId", d.consumptionId());
            payload.put("categoryCode", d.categoryCode());
            payload.put("amount", d.amount().toPlainString());
            payload.put("deviationScore", String.format("%.6f", d.modifiedZ()));
            payload.put("matchedRules", rules);
            auditService.append("FDS_ALERT_CREATED", payload, at);
        }
        auditService.sealBatch(at);
        return saved;
    }

    public record Detection(
            AnalysisResult.Deviation deviation,
            List<Enums.FdsRule> matchedRules,
            String explanation
    ) {}
}
