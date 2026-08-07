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
    /** ②-c 임시 분류 — 무료 통로. 답은 메모리에만 살고 DB 에 안 들어간다. */
    private final TempClassifierService temporary;
    private final Clock clock;

    public MerchantAskService(UserPaymentRepository payments, ConsumptionRepository consumptions,
                              CategoryRepository categories, MerchantCategoryService dictionary,
                              MerchantClassifierService classifier,
                              TempClassifierService temporary, Clock clock) {
        this.payments = payments;
        this.consumptions = consumptions;
        this.categories = categories;
        this.dictionary = dictionary;
        this.classifier = classifier;
        this.temporary = temporary;
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
        // **미분류 전부를 본다.** 예전에는 최신 100건만 봤는데, 미분류가 그보다 많으면 오래된
        // 것은 영원히 차례가 안 왔다(2026-08-07 운영: 150건 중 100건만 보여 카드사 수수료가
        // 사각지대에 있었다). 무료 통로가 전부를 훑게 되면서 그 제한이 곧 구멍이 됐다.
        List<UserPayment> rows = payments.findByUserIdAndCategory2OrderByPaymentDateDesc(
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

        // ②-c **임시 분류 — 무료라 임계값 없이 지금 묻는다.**
        // 유료 통로는 40곳이 쌓여야 부르는데, 그동안 새 결제는 '카테고리없음'으로 남는다.
        // 무료 통로는 값이 0 이라 결제가 들어오는 대로 물어도 손해가 없다. 답은 **DB 에
        // 남기지 않고** 화면 표시에만 쓴다 — 사전에는 유료 모델과 사람의 확정만 들어간다.
        Map<String, TempClassifierService.Guess> temp = temporary.classify(ask);

        // **브랜드도 같이 뽑는다.** 가맹점명 하나씩 물어 브랜드를 알아 두면 그 브랜드의 새 지점은
        // 다시 안 물어도 되고, 한 지점이 분류되면 나머지에 물려줄 수 있다. 무료 통로라 하나씩
        // 물어도 손해가 없고, 이미 아는 가맹점은 건너뛰므로 쌓일수록 호출이 준다.
        if (!ask.isEmpty() || !temp.isEmpty()) {
            log.info("미분류 최신화 — userId={} 남은 가맹점 {}, 임시 분류 {}, 임계값 {}",
                    userId, ask.size(), temp.size(), minMerchants);
        }

        // **여기가 임계값이다.** 모자라면 유료 통로를 부르지 않고, 임시 답만 얹어 돌려준다.
        if (ask.size() < minMerchants) {
            paint(rows, remembered, temp);
            Map<String, String> shown = new LinkedHashMap<>(remembered);
            temp.forEach((n, g) -> shown.putIfAbsent(n, g.category2()));
            return new Asked(rows, shown, Set.of());
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

        // 업종 이름을 함께 받는다 — 재질의 후보를 중분류가 아니라 업종으로 주기 위해서다.
        Map<String, String> paidIndustries = new java.util.TreeMap<>();
        Map<String, String> fresh = classifier.classify(ask, important, paidIndustries);
        fresh = reconcile(fresh, paidIndustries, temp);

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

        Map<String, String> paid = new LinkedHashMap<>(remembered);
        paid.putAll(fresh);
        paint(rows, paid, temp);
        Map<String, String> guesses = new LinkedHashMap<>(paid);
        temp.forEach((n, g) -> guesses.putIfAbsent(n, g.category2()));
        log.info("가맹점 분류 질의 — userId={} 물어본 곳 {}, 답 얻음 {}, 종결 {}",
                userId, ask.size(), fresh.size(), settled.size());
        return new Asked(rows, guesses, settled);
    }

    /**
     * <b>두 모델이 갈렸을 때 유료 모델에게 둘 중 하나를 고르게 한다.</b>
     *
     * <p>무료 통로와 유료 통로가 같은 가맹점에 다른 업종을 답하는 일이 있다. 어느 쪽을 믿을지
     * 우리가 정할 근거가 없고, 그렇다고 유료 쪽을 무조건 쓰면 무료 쪽이 맞았을 때를 버린다.
     * 그래서 <b>다시 묻되 질문을 좁힌다</b> — 385개 중 고르라는 것과 둘 중 고르라는 것은
     * 난이도가 다르고, 업종 목록이 통째로 빠져 값도 싸다.
     *
     * <p><b>갈린 것만 대상이다.</b> 둘이 같거나 한쪽만 답한 가맹점은 그대로 둔다.
     * 재질의가 실패하면 유료 통로의 답을 쓴다 — 되돌아갈 자리가 늘 있다.
     *
     * @param paid 유료 통로의 답 (가맹점명 → 중분류)
     * @param temp 무료 통로의 답 (가맹점명 → 업종 이름 + 중분류)
     * @implNote 후보는 <b>업종 이름</b>이다. 중분류를 주면 모델에게 우리 축을 직접 고르게 하는
     *           셈이라 마스터 §4 원칙 1 과 어긋난다. 업종으로 물어야 모델은 사실만 말하고
     *           축 배정은 표가 한다 — 첫 질의와 같은 규칙이다.
     * @return 재질의를 반영한 최종 답
     */
    private Map<String, String> reconcile(Map<String, String> paid,
                                          Map<String, String> paidIndustries,
                                          Map<String, TempClassifierService.Guess> temp) {
        if (paid == null || paid.isEmpty() || temp.isEmpty()) return paid;

        Map<String, String[]> split = new LinkedHashMap<>();
        for (var e : paid.entrySet()) {
            TempClassifierService.Guess t = temp.get(e.getKey());
            // **중분류가 같으면 묻지 않는다.** 업종 이름이 달라도(제과점업 / 빵류 소매업) 우리 축이
            // 같은 곳으로 떨어졌다면 결론이 같다 — 결론이 같은 것을 다시 묻는 것은 호출 낭비다.
            if (t == null || t.category2().equals(e.getValue())) continue;
            String paidIndustry = paidIndustries.get(e.getKey());
            if (paidIndustry == null) continue;      // 유료 쪽 업종 이름을 모르면 후보를 못 만든다
            split.put(e.getKey(), new String[]{paidIndustry, t.industryName()});
        }
        if (split.isEmpty()) return paid;

        Map<String, String> picked = classifier.tieBreak(split);
        Map<String, String> out = new LinkedHashMap<>(paid);
        int applied = 0;
        for (var e : picked.entrySet()) {
            String mid = classifier.toMid(e.getValue());
            if (mid == null || IndustryCategoryMapper.isUnknown(mid)) continue;
            out.put(e.getKey(), mid);
            applied++;
        }
        log.info("두 통로가 갈린 가맹점 {}곳 — 재질의로 {}곳 확정", split.size(), applied);
        return out;
    }

    /**
     * 추정을 결제 행에 얹는다 — 확정({@code category2})은 건드리지 않는다.
     *
     * <p><b>출처를 갈라 적는다.</b> 유료 통로의 답({@code LLM})은 사전에 쌓여 다음에도 같은 값이
     * 나오고, 무료 통로의 답({@code TEMP})은 임시라 유료 답이 오면 덮인다. 화면에는 둘 다
     * "AI 추정"으로 똑같이 보이지만, 기록까지 같게 두면 나중에 어느 쪽 값인지 가려낼 수 없다.
     */
    private static void paint(List<UserPayment> rows, Map<String, String> paid,
                              Map<String, TempClassifierService.Guess> temp) {
        for (UserPayment p : rows) {
            String name = p.getMerchantName();
            String confirmed = paid.get(name);
            if (confirmed != null) {
                p.suggestCategory2(confirmed, "LLM");
                continue;
            }
            TempClassifierService.Guess t = temp.get(name);
            if (t != null) p.suggestCategory2(t.category2(), "TEMP");
        }
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
