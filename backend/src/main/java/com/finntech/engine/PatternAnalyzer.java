package com.finntech.engine;

import com.finntech.config.AnalysisProperties;
import com.finntech.domain.UserPayment;
import com.finntech.repository.UserPaymentRepository;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 소비 패턴 분석(③) — 최근 창의 결제를 요일×시간대로 집계한다. 마이데이터 결제({@link UserPayment})만 대상.
 *
 * <p>재현성(§3): {@code referenceTime} 주입, 집계는 {@link TreeMap}로 키 정렬 고정. 본체 {@link #analyzeFrom}은
 * 순수 함수. 시간대 버킷은 {@link AnalysisProperties.Daypart}(②와 공유) 단일 정의를 쓴다.
 */
@Component
public class PatternAnalyzer {

    private final UserPaymentRepository payments;
    private final AnalysisProperties props;

    public PatternAnalyzer(UserPaymentRepository payments, AnalysisProperties props) {
        this.payments = payments;
        this.props = props;
    }

    /** 최근 {@code windowDays}일 소비 패턴. */
    public SpendingPattern analyze(Long userId, LocalDateTime referenceTime, int windowDays) {
        return analyzeFrom(payments.findByUserIdOrderByPaymentDateDesc(userId),
                referenceTime.minusDays(windowDays), referenceTime, props.getDaypart());
    }

    /** 순수 집계 — 테스트 진입점. {@code (from, to]} 창의 결제만 센다. */
    static SpendingPattern analyzeFrom(List<UserPayment> txns, LocalDateTime from, LocalDateTime to,
                                       AnalysisProperties.Daypart daypart) {
        Map<DayOfWeek, Long> amountByDayOfWeek = new TreeMap<>();
        Map<String, Long> amountByDaypart = new TreeMap<>();
        Map<String, Long> countByCell = new TreeMap<>();
        Map<String, Long> amountByCell = new TreeMap<>();

        for (UserPayment p : txns) {
            LocalDateTime at = p.getPaymentDate();
            if (at.isBefore(from) || at.isAfter(to)) continue;
            DayOfWeek dow = at.getDayOfWeek();
            String bucket = daypart.bucketOf(at.getHour());
            long amt = p.getAmount();
            amountByDayOfWeek.merge(dow, amt, Long::sum);
            amountByDaypart.merge(bucket, amt, Long::sum);
            String cell = dow.name() + "|" + bucket;
            countByCell.merge(cell, 1L, Long::sum);
            amountByCell.merge(cell, amt, Long::sum);
        }

        SpendingPattern.PeakCell peak = null; // 지출 최대 칸 — TreeMap 순회라 동점 시 사전순 앞칸 고정
        for (Map.Entry<String, Long> e : amountByCell.entrySet()) {
            if (peak == null || e.getValue() > peak.amount()) {
                int bar = e.getKey().indexOf('|');
                peak = new SpendingPattern.PeakCell(
                        DayOfWeek.valueOf(e.getKey().substring(0, bar)), e.getKey().substring(bar + 1), e.getValue());
            }
        }
        return new SpendingPattern(amountByDayOfWeek, amountByDaypart, countByCell, peak);
    }
}
