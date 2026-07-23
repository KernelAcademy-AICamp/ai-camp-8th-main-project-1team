package com.finntech.service;

// Spring Boot 4는 Jackson 3을 쓴다 — 패키지가 com.fasterxml.jackson이 아니라 tools.jackson이다.
import tools.jackson.databind.ObjectMapper;
import com.finntech.domain.Enums;
import com.finntech.domain.Report;
import com.finntech.engine.AnalysisResult;
import com.finntech.ml.WasteScoringService;
import com.finntech.repository.ReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 절약 리포트 (문서 §5, RFP D22).
 *
 * <p>RFP 필수 수행 규칙 D22: "소비 내역을 <b>긍정적/부정적 요인으로 객관적으로 분류하는
 * 명확한 기준</b>을 수립함". 그 기준이 곧 {@code overspendingCategories}이며,
 * 이 서비스는 기준을 다시 만들지 않고 엔진 결과를 그대로 분류에 쓴다 (원칙 2).
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final ReportRepository reportRepository;
    private final ObjectMapper objectMapper;
    private final WasteScoringService wasteScoringService;

    public ReportService(ReportRepository reportRepository, ObjectMapper objectMapper,
                         WasteScoringService wasteScoringService) {
        this.reportRepository = reportRepository;
        this.objectMapper = objectMapper;
        this.wasteScoringService = wasteScoringService;
    }

    /**
     * 저장된 리포트를 우선 반환하고, 없으면 계산 후 저장한다 (문서 §5 "재조회 시 재계산 방지").
     *
     * <p><b>ESTIMATED 모드는 캐시하지 않는다.</b> 데이터가 더 쌓이면 결과가 바뀌어야 하는데
     * 캐시해 버리면 "N건 더 기록하면 정확해집니다"라고 안내해 놓고 정작 갱신되지 않는다.
     */
    @Transactional
    public ReportBody buildCached(Long userId, String period, AnalysisResult analysis,
                                  LocalDateTime at) {
        // 부정 카테고리 판정 소스: 마이데이터 연동 시 ML 낭비(과반) 카테고리, 아니면 규칙 overspending(W8 다운스트림).
        java.util.Set<String> mlWaste = wasteScoringService.summarize(userId)
                .map(WasteScoringService.MlSummary::wasteCategories).orElse(null);
        if (!analysis.isConfirmed()) {
            return build(analysis, mlWaste);
        }
        Optional<Report> cached = reportRepository.findByUserIdAndPeriod(userId, period);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get().getBodyJson(), ReportBody.class);
            } catch (Exception e) {
                // 스키마가 바뀌면 역직렬화가 깨진다. 캐시를 버리고 다시 계산하는 편이 안전하다.
                log.warn("리포트 캐시 역직렬화 실패 — 재계산한다: {}", e.toString());
                reportRepository.delete(cached.get());
            }
        }
        ReportBody body = build(analysis, mlWaste);
        try {
            reportRepository.save(new Report(userId, period, objectMapper.writeValueAsString(body), at));
        } catch (Exception e) {
            log.warn("리포트 캐시 저장 실패 — 응답에는 영향 없음: {}", e.toString());
        }
        return body;
    }

    /** 캐시 무효화 — 특정 기간. */
    @Transactional
    public void invalidate(Long userId, String period) {
        reportRepository.findByUserIdAndPeriod(userId, period).ifPresent(reportRepository::delete);
    }

    /**
     * 사용자의 리포트 캐시를 전부 버린다.
     *
     * <p>캐시 본문은 특정 달이 아니라 <b>전체 이력</b>을 집계한 것인데 저장 키는 조회 시점의 달이다.
     * 그래서 입력 건의 달로만 무효화하면 키가 어긋나 새 입력이 반영되지 않는다.
     */
    @Transactional
    public void invalidateAll(Long userId) {
        reportRepository.deleteByUserId(userId);
    }

    public ReportBody build(AnalysisResult analysis) {
        return build(analysis, null);
    }

    /**
     * @param mlWasteCategories ML이 낭비로 판정한 category1 집합(W8). null이면 규칙 overspending으로 폴백.
     *                          마이데이터 연동 사용자는 ML 판정이, 그 외에는 규칙 기준이 '줄이면 좋은 소비'를 정한다.
     */
    public ReportBody build(AnalysisResult analysis, java.util.Set<String> mlWasteCategories) {
        List<Line> good = new ArrayList<>();
        List<Line> bad = new ArrayList<>();

        for (Map.Entry<String, AnalysisResult.CategoryStat> e : analysis.categoryStats().entrySet()) {
            AnalysisResult.CategoryStat s = e.getValue();
            Line line = new Line(s.categoryCode(), s.displayName(), s.totalAmount(),
                    Math.round(s.spendRatio() * 1000.0) / 10.0, s.count());
            boolean isBad = mlWasteCategories != null
                    ? mlWasteCategories.contains(s.categoryCode())
                    : analysis.overspendingCategories().contains(s.categoryCode());
            if (isBad) {
                bad.add(line);
            } else {
                good.add(line);
            }
        }
        // 비중 내림차순 고정 정렬 — 재현성
        good.sort((a, b) -> Double.compare(b.spendPercent(), a.spendPercent()));
        bad.sort((a, b) -> Double.compare(b.spendPercent(), a.spendPercent()));

        return new ReportBody(analysis.totalSpend(), good, bad,
                analysis.monthlySpend(), analysis.dataSourceMode(), analysis.estimationReason());
    }

    public record Line(
            String categoryCode,
            String displayName,
            BigDecimal amount,
            double spendPercent,
            long count
    ) {}

    public record ReportBody(
            BigDecimal totalSpend,
            /** 잘한 소비 — 쏠림 기준 미만 */
            List<Line> positive,
            /** 과한 소비 — 쏠림 기준 초과 */
            List<Line> negative,
            Map<String, BigDecimal> monthlySpend,
            Enums.DataSourceMode dataSourceMode,
            String estimationReason
    ) {}
}
