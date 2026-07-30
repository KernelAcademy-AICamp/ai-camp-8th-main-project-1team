package com.finntech.mydata.generation;

import com.finntech.mydata.domain.MyDataPayment;
import com.finntech.mydata.repository.MyDataPaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 생성된 <b>지출 구조</b>가 페르소나의 의도에서 크게 벗어나지 않는지 본다.
 *
 * <p><b>왜 건수가 아니라 금액인가.</b> 페르소나의 {@code categoryMix}는 "쇼핑에 30%"처럼
 * <b>지출 비중</b>으로 말한다. 방문가중이 {@code 지출비중 ÷ 평균단가}라 건수 분포는 단가가 싼
 * 카페 쪽으로 쏠리는 것이 정상이다 — 그래서 건수로 검사하면 늘 실패하거나, 반대로 진짜 왜곡을
 * 놓친다. 검사해야 할 것은 금액 비중이다.
 *
 * <p><b>이 테스트가 잡아낸 것.</b> '프로파일 밖' 지출(결제의 8%)이 전 업종에서 <b>균등</b>
 * 추출되고 있었다. 여행(20만원)과 카페(4천원)가 같은 확률로 뽑히니 건수는 같아도 금액은 50배라,
 * 8%가 지출 구조를 통째로 흔들었다 — 1,024만 건 실측에서 교통 4.07배·여행 3.39배로 부풀고
 * 식비는 0.54배로 눌렸다. 사람이 평소 안 가던 곳에 가더라도 비행기표보다 커피를 더 자주 산다.
 *
 * <p>완전히 일치할 수는 없다. 고시요금(지하철·통신비)은 금액 스케일에서 빠지고, 취미 주입도 섞인다.
 * 그래서 <b>배수</b>로 느슨하게 본다.
 *
 * <p><b>알려진 잔여 오차 — 대형마트 2.0배 · 편의점/잡화 1.7배.</b> 한 업종코드 안에 단가가 20배
 * 차이 나는 맥락이 섞여 있기 때문이다({@code 4711} = 대형마트 12,796원 + 백화점 261,875원,
 * {@code 4712} = 편의점 3,363원 + 화장품 31,833원). 계획단가는 그 평균 하나뿐인데 실제 결제는
 * 어느 맥락이 뽑혔느냐로 갈리고, 수량(1~3)까지 맥락별로 다르다. 업종을 더 쪼개지 않는 한 남는
 * 오차라 임계를 그만큼 열어 둔다 — 다만 <b>더 벌어지면</b> 새 결함이므로 잡힌다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mix_fidelity;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "mydata.seed.enabled=false",
        "mydata.generation.enabled=true",
        "mydata.generation.target-count=400000",
})
class SpendMixFidelityTest {

    /** 이 배수를 넘게 벗어나면 방문가중이나 스케일 어딘가가 어긋난 것이다. */
    private static final double MAX_OVER = 2.6, MIN_UNDER = 0.40;

    /** 비중이 이 미만인 중분류는 표본이 얇아 배수가 흔들린다 — 방향만 보고 단언에서는 뺀다. */
    private static final double TOO_SMALL_TO_JUDGE = 0.01;

    @Autowired MyDataPaymentRepository payments;
    @Autowired KsicCategoryMap ksicToMid;
    @Autowired CatalogLoader catalog;

    @Test
    @DisplayName("금액 비중이 페르소나 의도에서 크게 벗어나지 않는다")
    void realizedSpendMixTracksPersonaIntent() {
        // 의도 = 인구비로 가중한 categoryMix
        Map<String, Double> intended = new TreeMap<>();
        double popSum = 0;
        for (var p : catalog.personas()) popSum += p.populationShare();
        for (var p : catalog.personas()) {
            double mixSum = p.categoryMix().values().stream().mapToDouble(Double::doubleValue).sum();
            if (mixSum <= 0) continue;
            for (var e : p.categoryMix().entrySet()) {
                intended.merge(e.getKey(), p.populationShare() / popSum * e.getValue() / mixSum, Double::sum);
            }
        }

        // 실현 = 중분류별 금액 비중
        List<MyDataPayment> all = payments.findAll();
        assertThat(all).isNotEmpty();
        Map<String, Long> amountByMid = new TreeMap<>();
        long total = 0;
        for (MyDataPayment p : all) {
            amountByMid.merge(ksicToMid.midOf(p.getKsicCode()), (long) p.getAmount(), Long::sum);
            total += p.getAmount();
        }

        System.out.printf("  결제 %,d건 · 총액 %,d원%n", all.size(), total);
        System.out.printf("  %-14s%10s%10s%8s%n", "중분류", "의도%", "실현%", "배수");
        var offenders = new java.util.ArrayList<String>();
        for (var e : intended.entrySet()) {
            double want = e.getValue();
            double got = amountByMid.getOrDefault(e.getKey(), 0L) / (double) total;
            double mult = want > 0 ? got / want : Double.NaN;
            boolean judged = want >= TOO_SMALL_TO_JUDGE;
            System.out.printf("  %-14s%9.2f%%%9.2f%%%8.2f%s%n",
                    e.getKey(), want * 100, got * 100, mult, judged ? "" : "  (표본 얇음)");
            if (judged && (mult > MAX_OVER || mult < MIN_UNDER)) {
                offenders.add(String.format("%s 의도 %.2f%% → 실현 %.2f%% (%.2f배)",
                        e.getKey(), want * 100, got * 100, mult));
            }
        }
        assertThat(offenders).as("의도에서 크게 벗어난 중분류").isEmpty();
    }
}
