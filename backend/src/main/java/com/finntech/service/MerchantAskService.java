package com.finntech.service;

import com.finntech.domain.Category;
import com.finntech.domain.Consumption;
import com.finntech.domain.UserPayment;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.CategoryRepository;
import com.finntech.repository.ConsumptionRepository;
import com.finntech.repository.UserPaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 분류 순위 <b>③</b> — 아직 답이 없는 가맹점을 <b>모아서</b> LLM 에 묻는다.
 *
 * <p><b>부르는 곳이 둘이고 임계값만 다르다.</b> 로직이 두 벌이 되면 한쪽만 고쳐져 조용히
 * 갈라지므로(이 저장소가 여러 번 밟은 실패다) 한 곳에 둔다.
 *
 * <pre>
 *   백그라운드 동기화   40곳 이상 쌓였을 때만   — 프롬프트 한 번에 꽉 채워 보낸다
 *   사용자가 화면 열 때  1곳이라도 있으면        — 체감 지연이 0 이라야 한다
 * </pre>
 *
 * <p><b>왜 임계값이 40인가.</b> 프롬프트의 76%가 업종 목록(385종·5,599자)이라 <b>1곳을 묻든
 * 40곳을 묻든 값이 거의 같다.</b> 1곳씩 40번 부르면 40배가 든다. 반대로 40곳을 채워 한 번에
 * 물으면 목록을 한 번만 보낸다 — {@code MerchantClassifierService.BATCH} 와 같은 수라야
 * 정확히 한 번의 호출로 떨어진다.
 *
 * <p><b>기다리는 동안 화면은 '카테고리없음'이고, 그것이 정확한 표현이다.</b> 임시로 그럴듯한
 * 값을 붙이는 방법(작은 로컬 모델·이름 규칙)을 재 봤지만 정확도가 각각 낮거나 79% 라
 * <b>다섯 곳 중 한 곳이 틀린 값을 보이다 나중에 바뀐다</b>(2026-08-07 실측). 모르는 것을
 * 모른다고 두는 편이 낫다 — 낭비 판정도 '카테고리없음'을 빼므로 리포트가 오염되지 않는다.
 *
 * <p>그리고 기다림이 실제로 눈에 띄는 순간에는 임계값이 1 이다. 사용자가 화면을 열면 남은 것을
 * 전부 몰아 묻는다. 두 번째 진입부터는 물어볼 것이 없어 호출이 나가지 않는다 —
 * 시도 이력이 그것을 보장한다.
 */
@Service
public class MerchantAskService {

    private static final Logger log = LoggerFactory.getLogger(MerchantAskService.class);

    /** 못 잡았을 때 다시 물을 기준 — 이만큼 반복되거나(정기결제) 이만큼 크면 그냥 넘기지 않는다. */
    private static final int RETRY_MIN_COUNT = 2;
    private static final long RETRY_MIN_AMOUNT = 50_000L;

    /**
     * <b>백그라운드에서 부를 임계값</b> — 프롬프트 한 번을 꽉 채우는 수.
     *
     * <p>{@link MerchantClassifierService#BATCH} 를 그대로 쓴다. 다른 수를 적으면 한 번에
     * 안 떨어져 목록(프롬프트의 76%)을 두 번 보내거나, 채우지도 않고 부르게 된다.
     */
    public static final int BACKGROUND_MIN = MerchantClassifierService.BATCH;

    /** 사용자가 화면을 열었을 때 — 한 곳이라도 있으면 묻는다. 체감 지연이 0 이라야 한다. */
    public static final int ON_DEMAND_MIN = 1;

    private final UserPaymentRepository payments;
    private final ConsumptionRepository consumptions;
    private final CategoryRepository categories;
    private final MerchantCategoryService dictionary;
    private final MerchantClassifierService classifier;
    private final Clock clock;

    public MerchantAskService(UserPaymentRepository payments, ConsumptionRepository consumptions,
                              CategoryRepository categories, MerchantCategoryService dictionary,
                              MerchantClassifierService classifier, Clock clock) {
        this.payments = payments;
        this.consumptions = consumptions;
        this.categories = categories;
        this.dictionary = dictionary;
        this.classifier = classifier;
        this.clock = clock;
    }

    /** 한 번 물어본 결과 — 화면이 이어서 쓸 수 있게 추정과 종결을 함께 준다. */
    public record Asked(List<UserPayment> rows, Map<String, String> guesses, Set<String> settled) {
        public int askedMerchants() { return guesses.size() + settled.size(); }
    }

    /**
     * 아직 답이 없는 가맹점을 묻는다 — <b>{@code minMerchants} 곳 미만이면 묻지 않는다.</b>
     *
     * <p>묻지 않아도 <b>이미 아는 추정은 돌려준다.</b> 화면은 그것으로 배지를 그린다.
     *
     * @param minMerchants 이만큼 쌓여야 부른다. 화면에서 부를 때는 1, 백그라운드는 40.
     */
    @Transactional
    public Asked ask(Long userId, int minMerchants) {
        List<UserPayment> rows = payments.findTop100ByUserIdAndCategory2OrderByPaymentDateDesc(
                userId, IndustryCategoryMapper.UNCLASSIFIED);

        // 같은 가맹점이 여러 번 나온다 — 이름당 한 번만 묻는다.
        List<UserPayment> askable = rows.stream()
                .filter(p -> p.getCategory2Llm() == null)
                .filter(p -> classifier.worthAsking(p.getMerchantName(), p.getBusinessNumber()))
                .toList();

        // **이미 물어본 가맹점은 다시 묻지 않는다.** 추정을 결제 행에만 남기면 다음 달 같은
        // 넷플릭스가 새 결제로 들어올 때 또 묻게 된다 — 행이 새것이라 비어 있기 때문이다.
        Map<String, String> remembered = new LinkedHashMap<>();
        for (UserPayment p : askable) {
            remembered.computeIfAbsent(p.getMerchantName(),
                    n -> dictionary.guess(p.getBusinessNumber(), n).orElse(null));
        }
        remembered.values().removeIf(java.util.Objects::isNull);

        List<String> ask = askable.stream().map(UserPayment::getMerchantName).distinct()
                .filter(n -> !remembered.containsKey(n)).toList();

        // **여기가 임계값이다.** 모자라면 부르지 않고 아는 것만 돌려준다.
        if (ask.size() < minMerchants) {
            return new Asked(rows, remembered, Set.of());
        }

        // **못 잡았을 때 다시 물을 값어치**를 여기서 정한다 — 건수·금액을 아는 것은 이쪽이다.
        // 모델은 알면서도 큰 묶음에서 흘리므로(2026-08-05 실측: 넷플릭스) 중요한 것은 작게
        // 나눠 한 번 더 묻는다. 다만 1건 200원짜리 카드 수수료까지 다시 물으면 호출만 쓴다 —
        // 실측으로 이 기준이 못 잡은 금액의 93%를 덮었다.
        Map<String, Integer> count = new LinkedHashMap<>();
        Map<String, Long> total = new LinkedHashMap<>();
        for (UserPayment p : askable) {
            count.merge(p.getMerchantName(), 1, Integer::sum);
            total.merge(p.getMerchantName(), (long) p.getAmount(), Long::sum);
        }
        Set<String> important = ask.stream()
                .filter(n -> count.getOrDefault(n, 0) >= RETRY_MIN_COUNT
                        || total.getOrDefault(n, 0L) >= RETRY_MIN_AMOUNT)
                .collect(java.util.stream.Collectors.toSet());

        Map<String, String> fresh = classifier.classify(ask, important);

        // 새로 알아낸 것만 사전에 남긴다 — 다음 연동·다음 달 결제에서 재사용된다.
        // (실제 사람의 결제일 때만 쌓인다. 더미의 사업자번호는 실재하지 않는다.)
        for (UserPayment p : askable) {
            String g = fresh.get(p.getMerchantName());
            if (g != null) dictionary.rememberGuess(p, g);
        }

        // **헛물켠 질문을 센다** — 물었는데 답이 안 온 가맹점. 세 번째면 '기타'로 종결하고
        // 다음부터는 조회도 질문도 하지 않는다. 이 기록이 없으면 화면을 열 때마다 같은 상호를
        // 다시 묻는다: 답이 없다는 사실이 어디에도 안 남기 때문이다.
        //
        // **가맹점당 한 번만 센다.** 결제 건마다 세면 넷플릭스 12건짜리 가맹점이 한 번의
        // 질문에 12회로 기록돼 첫 시도에 바로 종결된다.
        LocalDateTime askedAt = LocalDateTime.now(clock);
        Set<String> settled = new java.util.LinkedHashSet<>();
        Set<String> counted = new java.util.HashSet<>();
        for (UserPayment p : askable) {
            String name = p.getMerchantName();
            if (fresh.containsKey(name) || !counted.add(name)) continue;
            if (dictionary.noteLlmMiss(p, askedAt)) settled.add(name);
        }
        applySettled(rows, settled);

        Map<String, String> guesses = new LinkedHashMap<>(remembered);
        guesses.putAll(fresh);
        for (UserPayment p : rows) {
            String g = guesses.get(p.getMerchantName());
            if (g != null) p.suggestCategory2(g);
        }
        log.info("가맹점 분류 질의 — userId={} 물어본 곳 {}, 답 얻음 {}, 종결 {}",
                userId, ask.size(), fresh.size(), settled.size());
        return new Asked(rows, guesses, settled);
    }

    /**
     * 종결된 가맹점을 화면에서도 '기타'로 만든다 — 사전에만 적으면 사용자는 계속
     * '카테고리없음'을 보고, 다음에 또 이 가맹점이 물어볼 대상으로 세어진다.
     */
    private void applySettled(List<UserPayment> rows, Set<String> settled) {
        if (settled.isEmpty()) return;
        Category other = categories.findByCode(IndustryCategoryMapper.OTHER)
                .orElseGet(() -> categories.save(
                        new Category(IndustryCategoryMapper.OTHER, IndustryCategoryMapper.OTHER)));
        for (UserPayment p : rows) {
            if (!settled.contains(p.getMerchantName())) continue;
            p.confirmCategory2(IndustryCategoryMapper.OTHER, "GIVE_UP");
            for (Consumption c : consumptions.findBySourcePaymentId(p.getPaymentId())) {
                c.reclassify(other);
            }
        }
    }
}
