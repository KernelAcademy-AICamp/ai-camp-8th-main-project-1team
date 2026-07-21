package com.finntech.seed;

import com.finntech.domain.*;
import com.finntech.repository.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 파라미터 기반 더미 시드 생성기 (문서 §8 설계 제약 4).
 *
 * <p><b>왜 스크립트여야 하는가</b>: 손으로 만든 CSV를 커밋하면 페르소나 확정 시점(D-09)에
 * 더미를 통째로 다시 만들어야 하고, 그 재작업이 2단계 일정(여유 0)을 잡아먹는다.
 * 분포 파라미터를 받아 생성하면 세그먼트 교체가 <b>파라미터 교체</b>로 끝난다.
 *
 * <p><b>결정론</b>: {@code seed}가 같으면 항상 같은 데이터가 나온다. 재현성 검증(원칙 3)의 전제다.
 * 카테고리 이름은 호출자가 파라미터로 넘기며, 이 클래스에 하드코딩하지 않는다(설계 제약 1).
 */
@Component
public class SeedGenerator {

    private final AppUserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final ConsumptionRepository consumptionRepository;
    private final FinancialProductRepository productRepository;

    public SeedGenerator(AppUserRepository userRepository,
                         CategoryRepository categoryRepository,
                         ConsumptionRepository consumptionRepository,
                         FinancialProductRepository productRepository) {
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.consumptionRepository = consumptionRepository;
        this.productRepository = productRepository;
    }

    /**
     * @param nickname      익명 계정 닉네임
     * @param monthlyIncome 월 소득
     * @param goalAmount    목표 금액
     * @param goalMonths    목표 기간(개월)
     * @param months        생성할 개월 수 (문서 §4: 6개월로 통일)
     * @param txPerMonth    월 거래 건수
     * @param categoryMix   카테고리코드 → 지출 비중(합이 1이 아니어도 정규화됨)
     * @param plannedRatio  계획소비 비율 0~1
     * @param volatility    월별 지출 변동폭 0~1 (0이면 매월 동일)
     * @param anomalyCount  주입할 이상거래 건수 (최근 1개월에 심야 고액으로 삽입)
     * @param anomalyMagnitudeMin 이상거래 배수 하한 (평소 금액 대비)
     * @param anomalyMagnitudeMax 이상거래 배수 상한
     * @param seed          난수 시드 — 같으면 항상 같은 결과
     */
    public record SeedSpec(
            String nickname,
            BigDecimal monthlyIncome,
            BigDecimal goalAmount,
            int goalMonths,
            int months,
            int txPerMonth,
            Map<String, Double> categoryMix,
            double plannedRatio,
            double volatility,
            int anomalyCount,
            double anomalyMagnitudeMin,
            double anomalyMagnitudeMax,
            long seed
    ) {
        /**
         * 이상거래 강도 기본값 6~10배.
         *
         * <p>⚠️ <b>이 강도에서는 임계치 캘리브레이션이 무의미하다.</b> 분포에서 너무 멀리 떨어져 있어
         * z 임계를 2.0~6.0 어디에 두든 같은 결과가 나온다 — 즉 임계치가 구속조건이 아니게 된다.
         * 임계치를 실제로 검증하려면 {@code 1.5~3.0}처럼 <b>경계에 가까운 강도</b>로 시드를 만들어야 한다.
         */
        public SeedSpec(String nickname, BigDecimal monthlyIncome, BigDecimal goalAmount,
                        int goalMonths, int months, int txPerMonth, Map<String, Double> categoryMix,
                        double plannedRatio, double volatility, int anomalyCount, long seed) {
            this(nickname, monthlyIncome, goalAmount, goalMonths, months, txPerMonth,
                    categoryMix, plannedRatio, volatility, anomalyCount, 6.0, 10.0, seed);
        }
    }

    @Transactional
    public AppUser generate(SeedSpec spec, LocalDateTime referenceTime) {
        Random rnd = new Random(spec.seed());

        AppUser user = userRepository.findByNickname(spec.nickname())
                .orElseGet(() -> userRepository.save(new AppUser(
                        spec.nickname(), spec.monthlyIncome(), spec.goalAmount(), spec.goalMonths())));

        // 카테고리는 DB 데이터. 없으면 코드값 그대로 만든다(이름 하드코딩 아님).
        Map<String, Category> categories = new TreeMap<>();
        for (String code : new TreeSet<>(spec.categoryMix().keySet())) {
            categories.put(code, categoryRepository.findByCode(code)
                    .orElseGet(() -> categoryRepository.save(new Category(code, code))));
        }

        double mixTotal = spec.categoryMix().values().stream().mapToDouble(Double::doubleValue).sum();
        if (mixTotal <= 0) throw new IllegalArgumentException("categoryMix 합이 0입니다");

        // 월별 기본 지출액 = 소득의 60%를 기준으로 변동폭 적용
        BigDecimal baseMonthlySpend = spec.monthlyIncome()
                .multiply(BigDecimal.valueOf(0.60)).setScale(0, RoundingMode.HALF_UP);

        List<Consumption> batch = new ArrayList<>();

        for (int m = spec.months() - 1; m >= 0; m--) {
            LocalDateTime monthStart = referenceTime.minusMonths(m)
                    .withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
            double factor = 1.0 + (rnd.nextDouble() * 2 - 1) * spec.volatility();
            BigDecimal monthSpend = baseMonthlySpend.multiply(BigDecimal.valueOf(factor))
                    .setScale(0, RoundingMode.HALF_UP);

            for (int i = 0; i < spec.txPerMonth(); i++) {
                String code = pickCategory(spec.categoryMix(), mixTotal, rnd);
                double share = spec.categoryMix().get(code) / mixTotal;
                // 건당 금액 = 월지출 × 카테고리비중 / 해당 카테고리 예상 건수, ±30% 흔들기
                double expectedCount = Math.max(1.0, spec.txPerMonth() * share);
                double amt = monthSpend.doubleValue() * share / expectedCount
                        * (0.7 + rnd.nextDouble() * 0.6);

                int day = 1 + rnd.nextInt(28);
                int hour = 8 + rnd.nextInt(13);   // 평상시는 08~20시
                LocalDateTime at = monthStart.withDayOfMonth(day).withHour(hour)
                        .withMinute(rnd.nextInt(60));

                batch.add(new Consumption(user.getId(), categories.get(code),
                        BigDecimal.valueOf(Math.max(1000, Math.round(amt))),
                        at, rnd.nextDouble() < spec.plannedRatio(), Enums.DataSource.DUMMY_SEED));
            }
        }

        // 이상거래 주입: 최근 1개월, 심야 시간대, 평소의 N배 금액.
        // 임계치 캘리브레이션("100건 중 3~5건")을 확인하려면 정답이 있는 데이터가 필요하다.
        List<String> codes = new ArrayList<>(categories.keySet());
        double magMin = spec.anomalyMagnitudeMin();
        double magSpan = Math.max(0.0, spec.anomalyMagnitudeMax() - magMin);
        for (int i = 0; i < spec.anomalyCount(); i++) {
            String code = codes.get(rnd.nextInt(codes.size()));
            double share = spec.categoryMix().get(code) / mixTotal;
            double normal = baseMonthlySpend.doubleValue() * share
                    / Math.max(1.0, spec.txPerMonth() * share);
            double magnitude = magMin + rnd.nextDouble() * magSpan;
            LocalDateTime at = referenceTime.minusDays(1 + rnd.nextInt(25))
                    .withHour(rnd.nextInt(5)).withMinute(rnd.nextInt(60));
            batch.add(new Consumption(user.getId(), categories.get(code),
                    BigDecimal.valueOf(Math.max(1000, Math.round(normal * magnitude))),
                    at, false, Enums.DataSource.DUMMY_SEED));
        }

        consumptionRepository.saveAll(batch);
        return user;
    }

    private String pickCategory(Map<String, Double> mix, double total, Random rnd) {
        double r = rnd.nextDouble() * total;
        double acc = 0;
        // TreeMap 순회로 순서를 고정해야 같은 시드가 같은 결과를 낸다.
        for (Map.Entry<String, Double> e : new TreeMap<>(mix).entrySet()) {
            acc += e.getValue();
            if (r <= acc) return e.getKey();
        }
        return new TreeMap<>(mix).lastKey();
    }

    /** 더미 금융상품 최소 5개 (RFP D18). 전부 실재하지 않는 상품이다 (D-04). */
    @Transactional
    public int seedProducts(List<String> categoryCodes) {
        if (productRepository.count() > 0) return 0;
        String c0 = categoryCodes.isEmpty() ? null : categoryCodes.get(0);
        String c1 = categoryCodes.size() > 1 ? categoryCodes.get(1) : c0;

        List<FinancialProduct> products = List.of(
                new FinancialProduct("[더미] 든든 정기예금", Enums.ProductType.DEPOSIT,
                        new BigDecimal("1000000"), 12, new BigDecimal("3.20"),
                        Enums.RiskGrade.STABLE, null),
                new FinancialProduct("[더미] 차곡차곡 자유적금", Enums.ProductType.SAVINGS,
                        new BigDecimal("100000"), 6, new BigDecimal("3.80"),
                        Enums.RiskGrade.STABLE, null),
                new FinancialProduct("[더미] 목표달성 적금", Enums.ProductType.SAVINGS,
                        new BigDecimal("300000"), 12, new BigDecimal("4.10"),
                        Enums.RiskGrade.NEUTRAL, null),
                new FinancialProduct("[더미] 성장형 인덱스 펀드", Enums.ProductType.FUND,
                        new BigDecimal("500000"), 24, new BigDecimal("6.50"),
                        Enums.RiskGrade.AGGRESSIVE, null),
                new FinancialProduct("[더미] 생활밀착 캐시백카드", Enums.ProductType.CASHBACK_CARD,
                        new BigDecimal("0"), 1, new BigDecimal("5.00"),
                        Enums.RiskGrade.STABLE, c0),
                new FinancialProduct("[더미] 라이프스타일 캐시백카드", Enums.ProductType.CASHBACK_CARD,
                        new BigDecimal("0"), 3, new BigDecimal("3.00"),
                        Enums.RiskGrade.NEUTRAL, c1)
        );
        productRepository.saveAll(products);
        return products.size();
    }
}
