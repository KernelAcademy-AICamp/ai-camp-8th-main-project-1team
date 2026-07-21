package com.finntech.service;

import com.finntech.config.AnalysisProperties;
import com.finntech.engine.AnalysisEngine;
import com.finntech.engine.AnalysisResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * FDS 임계치 캘리브레이션 (문서 §5 ①, D-11).
 *
 * <p><b>임계치는 "최적화"가 아니라 "제약 충족"으로 정한다</b> — "더미 100건 중 경고가 3~5건만 뜨도록" 역산한다.
 * 데모에서 경고가 도배되면 <i>"이거 그냥 다 잡는 거 아니냐"</i>는 질문을 받고 발표 주인공 기능이 그 자리에서 무너진다.
 *
 * <p>현재 계수(3.5)는 <b>잠정값</b>이다. 페르소나(D-09)가 확정되면 그 분포로 이 스윕을 다시 돌려
 * 목표 구간에 드는 임계치를 고른 뒤 {@code finntech.analysis.fds.modified-z-threshold}에 넣는다.
 * 그 교체가 값 하나로 끝나도록 임계치를 설정값으로 분리해 둔 것이다 (문서 §8 설계 제약 2).
 */
@Service
public class CalibrationService {

    private final AnalysisEngine engine;
    private final AlertService alertService;
    private final AnalysisProperties props;

    public CalibrationService(AnalysisEngine engine, AlertService alertService,
                              AnalysisProperties props) {
        this.engine = engine;
        this.alertService = alertService;
        this.props = props;
    }

    /**
     * 임계치를 스윕하며 경고율을 측정한다.
     *
     * <p><b>주의</b>: 전역 설정값을 잠시 바꿔가며 측정하므로 동시 요청과 경쟁한다.
     * 개발/관리자 전용 경로에서만 부르고, 운영 트래픽과 섞지 않는다.
     */
    public synchronized CalibrationReport sweep(Long userId, LocalDateTime referenceTime,
                                                double from, double to, double step,
                                                int targetPer100Min, int targetPer100Max) {
        double original = props.getFds().getModifiedZThreshold();
        List<Point> points = new ArrayList<>();
        try {
            for (double t = from; t <= to + 1e-9; t += step) {
                props.getFds().setModifiedZThreshold(round(t));
                AnalysisResult analysis = engine.analyze(userId, referenceTime);
                int evaluated = analysis.deviations().size();
                int alerts = alertService.detect(analysis).size();
                double per100 = evaluated == 0 ? 0.0 : alerts * 100.0 / evaluated;
                points.add(new Point(round(t), evaluated, alerts, round2(per100),
                        per100 >= targetPer100Min && per100 <= targetPer100Max));
            }
        } finally {
            // 반드시 복구한다. 예외로 빠져나가면 전역 임계치가 스윕 값에 고정돼 버린다.
            props.getFds().setModifiedZThreshold(original);
        }

        Point recommended = points.stream()
                .filter(Point::withinTarget)
                .findFirst()
                .orElse(null);

        String verdict = recommended != null
                ? "임계 " + recommended.threshold() + " 를 쓰면 100건당 "
                        + recommended.alertsPer100() + "건으로 목표 구간에 든다"
                : "목표 구간(" + targetPer100Min + "~" + targetPer100Max
                        + "건/100건)에 드는 임계치가 없다. 더미 데이터의 이상거래 주입 비율을 조정하거나 "
                        + "룰 AND 조건을 조여야 한다";

        return new CalibrationReport(userId, original, targetPer100Min, targetPer100Max,
                points, recommended, verdict);
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    public record Point(
            double threshold,
            int evaluatedCount,
            int alertCount,
            double alertsPer100,
            boolean withinTarget
    ) {}

    public record CalibrationReport(
            Long userId,
            double currentThreshold,
            int targetMinPer100,
            int targetMaxPer100,
            List<Point> sweep,
            Point recommended,
            String verdict
    ) {}
}
