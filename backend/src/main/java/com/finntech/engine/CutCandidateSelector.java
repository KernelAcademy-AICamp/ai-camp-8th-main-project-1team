package com.finntech.engine;

import com.finntech.config.AnalysisProperties;
import com.finntech.domain.UserPayment;
import com.finntech.repository.UserPaymentRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * 절약 후보 선정(⑤) — 최근 창의 결제를 category2로 모아 3등급 규칙으로 후보를 뽑고 절감액 순으로 정렬한다.
 *
 * <p>보호 카테고리(공과금·통신비 등)는 원천 제외한다. 제거가능은 전액을, 최적화가능은 중앙값 초과분을 절감액으로
 * 삼는다. 미분류 category2(3등급 어디에도 없음)는 보수적으로 후보에서 뺀다. 판정 근거는 코드가 만들고(원칙 1),
 * {@code wasteRatioThreshold}는 후속 EBM 낭비확률 게이트로 예약(현 단계는 3등급 규칙만).
 *
 * <p>재현성(§3): {@code referenceTime} 주입, 그룹핑 {@link TreeMap} 고정, 순수 코어 {@link #selectFrom} 단위테스트.
 */
@Component
public class CutCandidateSelector {

    private final UserPaymentRepository payments;
    private final AnalysisProperties props;

    public CutCandidateSelector(UserPaymentRepository payments, AnalysisProperties props) {
        this.payments = payments;
        this.props = props;
    }

    /** 최근 {@code windowDays}일 절약 후보(⑤). 절감액 큰 순. */
    public List<CutCandidate> select(Long userId, LocalDateTime referenceTime, int windowDays) {
        LocalDateTime from = referenceTime.minusDays(windowDays);
        List<UserPayment> window = payments.findByUserIdOrderByPaymentDateDesc(userId).stream()
                .filter(p -> !p.getPaymentDate().isBefore(from) && !p.getPaymentDate().isAfter(referenceTime))
                .toList();
        return selectFrom(window, props.getCutCandidate());
    }

    /** 순수 선정 — 테스트 진입점. */
    static List<CutCandidate> selectFrom(List<UserPayment> window, AnalysisProperties.CutCandidate cfg) {
        Set<String> removable = Set.copyOf(cfg.getRemovable());
        Set<String> optimizable = Set.copyOf(cfg.getOptimizable());
        Set<String> protectedCats = Set.copyOf(cfg.getProtectedCategories());

        TreeMap<String, List<Integer>> byCat2 = new TreeMap<>();
        for (UserPayment p : window) {
            String cat2 = p.getCategory2();
            if (cat2 == null || protectedCats.contains(cat2)) continue;
            byCat2.computeIfAbsent(cat2, k -> new ArrayList<>()).add(p.getAmount());
        }

        List<CutCandidate> out = new ArrayList<>();
        for (var e : byCat2.entrySet()) {
            String cat2 = e.getKey();
            List<Integer> amounts = e.getValue();
            long spend = amounts.stream().mapToLong(Integer::longValue).sum();

            if (removable.contains(cat2)) {
                out.add(new CutCandidate(cat2, CutCandidate.Type.REMOVABLE, spend, spend,
                        cat2 + " 지출 " + won(spend) + "은 전액 절약 대상(제거가능)"));
            } else if (optimizable.contains(cat2)) {
                long median = Math.round(Stats.median(amounts.stream().mapToDouble(Integer::doubleValue).toArray()));
                long excess = amounts.stream().mapToLong(a -> Math.max(0, a - median)).sum();
                if (excess > 0) {
                    out.add(new CutCandidate(cat2, CutCandidate.Type.OPTIMIZABLE, spend, excess,
                            cat2 + " 중앙값(" + won(median) + ") 초과분 " + won(excess) + " 절감 가능(최적화가능)"));
                }
            }
            // 미분류 category2 → 후보 아님(보수적)
        }
        out.sort(Comparator.comparingLong(CutCandidate::estimatedSaving).reversed()
                .thenComparing(CutCandidate::category2)); // 동점은 사전순 → 결정론
        return out;
    }

    private static String won(long v) {
        return String.format("%,d원", v);
    }
}
