package com.finntech.service;

import com.finntech.domain.Category;
import com.finntech.domain.Consumption;
import com.finntech.domain.UserPayment;
import com.finntech.freechannel.Lane;
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
     * <p><b>왜 40인가.</b> 예전에는 40곳을 한 프롬프트에 묶어 물었고 이 값이 그 묶음 크기였다.
     * 지금은 한 곳씩 묻지만({@code IndustryPrompt}) 값은 그대로 둔다 — 뜻이 바뀌었을 뿐이다.
     * 이제는 <b>배경에서 통로를 두드릴 만큼 쌓였는가</b>의 문턱이다. 한두 곳 때문에 배경 작업을
     * 깨우면 통로 예산만 쓰고 사용자는 아무것도 못 느낀다. 화면을 연 사람은
     * {@link #ON_DEMAND_MIN} 로 곧바로 처리되므로 기다리게 되는 일도 없다.
     */
    public static final int BACKGROUND_MIN = 40;

    /** 사용자가 화면을 열었을 때 — 한 곳이라도 있으면 묻는다. 체감 지연이 0 이라야 한다. */
    public static final int ON_DEMAND_MIN = 1;

    /**
     * 화면을 연 사람의 것 중 <b>앞 차선으로 보낼 개수</b>.
     *
     * <p>앞 두 차선은 토큰이 있는 만큼 다 나가므로(뒤 차선은 회차당 2건) 많이 넣으면
     * 우선순위가 사라지고 같은 차선의 문장 작업이 굶는다. 한 곳이 최대 3회를 부르니
     * 셋이면 9회 — 분당 예산 40 의 4분의 1 남짓이다. 나머지는 뒤 차선으로 보내도
     * 사용자는 스크롤해야 볼 자리라 체감이 없다.
     */
    private static final int URGENT_HEAD = 3;

    private final UserPaymentRepository payments;
    private final ConsumptionRepository consumptions;
    private final CategoryRepository categories;
    private final MerchantCategoryService dictionary;
    private final MerchantClassifierService classifier;
    /** ②-c 임시 분류 — 무료 통로. 답은 메모리에만 살고 DB 에 안 들어간다. */
    private final TempClassifierService temporary;
    private final Clock clock;
    /** 업종 이름에서 국세청 코드를 되찾는 대조표 — 카드 혜택 축이 그 코드로 정해진다. */
    private final com.finntech.engine.IndustryCategoryMapper industries;
    /**
     * <b>자기 자신의 프록시.</b> 추리기·입히기만 트랜잭션 안이고 모델 질의는 밖이라야 하는데,
     * {@code @Transactional} 은 프록시가 걸어 주는 것이라 같은 객체 안에서 그냥 부르면 안 걸린다.
     */
    private final org.springframework.beans.factory.ObjectProvider<MerchantAskService> selfProvider;
    /**
     * <b>지금 묻고 있는 사용자.</b> 진입로가 셋이라 겹칠 수 있다 — 5분 배치, 화면의
     * {@code POST /api/mydata/sync}, 그리고 미분류 화면을 여는 순간({@code ON_DEMAND_MIN}).
     *
     * <p>겹치면 같은 가맹점을 두 모델에 두 번 묻는다. 유료 통로에서는 그게 곧 돈이다.
     *
     * <p><b>물러나되 빈손으로 돌아가지 않는다.</b> 화면이 부른 것일 수 있으므로 모델만 건너뛰고
     * 이미 아는 추정은 그대로 그려 준다 — 잠깐 뒤 새로고침이면 나머지도 보인다.
     */
    private final java.util.Set<Long> asking = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public MerchantAskService(UserPaymentRepository payments, ConsumptionRepository consumptions,
                              CategoryRepository categories, MerchantCategoryService dictionary,
                              MerchantClassifierService classifier,
                              TempClassifierService temporary, Clock clock,
                              com.finntech.engine.IndustryCategoryMapper industries,
                              org.springframework.beans.factory.ObjectProvider<MerchantAskService> selfProvider,
            @org.springframework.beans.factory.annotation.Value(
                    "${finntech.temp-classifier.batch-size:8}") int tempBatchSize) {
        this.payments = payments;
        this.consumptions = consumptions;
        this.categories = categories;
        this.dictionary = dictionary;
        this.classifier = classifier;
        this.temporary = temporary;
        this.clock = clock;
        this.industries = industries;
        this.selfProvider = selfProvider;
        this.tempBatchSize = Math.max(1, tempBatchSize);
    }

    /**
     * 임시 분류를 <b>한 회차에 몇 곳까지</b> 물을지.
     *
     * <p>무료 통로라 돈은 안 들지만 가맹점당 6~10초다 — 상한이 없으면 미분류 50곳에서
     * 한 번에 8분이 되고, 그 시간은 사용자가 로딩 화면으로 겪는다(프론트 상한 60초에
     * 먼저 잘린다). 남은 것은 다음 회차가 잇는다.
     */
    private final int tempBatchSize;

    /** 한 번 물어본 결과 — 화면이 이어서 쓸 수 있게 추정과 종결을 함께 준다. */
    public record Asked(List<UserPayment> rows, Map<String, String> guesses, Set<String> settled) {
        public int askedMerchants() { return guesses.size() + settled.size(); }
    }

    /**
     * <b>트랜잭션이 열려 있지 않다.</b> 세 단계로 갈라져 있고 가운데(모델 질의)만 밖에서 돈다.
     *
     * <pre>
     *   ① 무엇을 물을지 추린다   짧은 읽기 트랜잭션
     *   ② 모델에 묻는다          트랜잭션 없음 — 무료 통로는 가맹점당 6~10초 × N, 유료는 한 번에 30초+
     *   ③ 얻은 답을 입힌다       짧은 트랜잭션
     * </pre>
     *
     * <p>한 트랜잭션으로 묶으면 그 시간 내내 DB 커넥션을 붙잡는다. 무료 통로를 켜고 미분류가
     * 50곳이면 <b>한 번에 8분</b>이다(2026-08-07 감사).
     *
     * <p>묻지 않아도 <b>이미 아는 추정은 돌려준다.</b> 화면은 그것으로 배지를 그린다.
     *
     * @param minMerchants 이만큼 쌓여야 유료 통로를 부른다. 화면에서 부를 때는 1, 백그라운드는 40.
     */
    public Asked ask(Long userId, int minMerchants) {
        MerchantAskService self = selfProvider.getObject();
        if (!asking.add(userId)) {
            log.debug("이미 묻고 있어 모델 호출을 건너뜀 — userId={}", userId);
            return self.applyGuesses(userId, self.plan(userId).remembered(),
                    Map.of(), Map.of(), Set.of());
        }
        try {
            return askOnce(self, userId, minMerchants);
        } finally {
            asking.remove(userId);
        }
    }

    private Asked askOnce(MerchantAskService self, Long userId, int minMerchants) {
        Plan plan = self.plan(userId);

        /* ②-c **임시 분류 — 무료라 임계값 없이 지금 묻는다.**
         *
         * 유료 통로는 40곳이 쌓여야 부르는데, 그동안 새 결제는 '카테고리없음'으로 남는다.
         * 무료 통로는 값이 0 이라 결제가 들어오는 대로 물어도 손해가 없다. 답은 **DB 에
         * 남기지 않고** 화면 표시에만 쓴다 — 사전에는 유료 모델과 사람의 확정만 들어간다.
         *
         * **다만 한 번에 묻는 수에는 상한이 있다.** 무료라도 <b>시간은 공짜가 아니다</b> —
         * 가맹점당 6~10초라 미분류가 50곳이면 한 번에 8분이고, 그동안 화면은 로딩만 돈다
         * (프론트 상한 60초에 먼저 잘린다). 남은 것은 다음 동기화가 잇는다 — 이 메서드는
         * 화면을 열 때마다 다시 불리므로 <b>몇 번 나눠 부르면 결국 다 채워진다.</b> */
        List<String> tempBatch = plan.ask().size() <= tempBatchSize
                ? plan.ask() : plan.ask().subList(0, tempBatchSize);
        if (tempBatch.size() < plan.ask().size()) {
            log.info("임시 분류를 {}곳만 묻는다 — 남은 {}곳은 다음 회차가 잇는다",
                    tempBatch.size(), plan.ask().size() - tempBatch.size());
        }
        /* **차선을 가른다.** 앞 두 차선(USER_NOW·USER_REFRESH)은 토큰이 있는 만큼 다 나가고,
         * 그 아래는 한 번에 두 건이다({@code LOW_LANE_PER_TICK}). 그래서 앞 차선에 많이 넣으면
         * 우선순위가 무의미해지고 같은 차선의 문장 작업이 굶는다.
         *
         * 화면을 연 사람은 지금 '카테고리없음'을 보고 있으므로 앞 차선이 맞다 — 다만
         * <b>맨 앞 몇 곳만</b>이다({@link #URGENT_HEAD}). 그 사람이 지금 보는 것은 목록의
         * 첫 화면이고, 나머지는 스크롤해야 나온다. 배경 적재는 보는 사람이 없으니 전부 뒤 차선. */
        // 돌려받는 것은 **이미 아는 것**뿐이다 — 새로 올린 것은 다음 회차가 캐시에서 집어 간다.
        Map<String, TempClassifierService.Guess> temp = new LinkedHashMap<>();
        boolean onDemand = minMerchants <= ON_DEMAND_MIN;
        if (onDemand && !tempBatch.isEmpty()) {
            int head = Math.min(URGENT_HEAD, tempBatch.size());
            temp.putAll(temporary.classify(tempBatch.subList(0, head), Lane.USER_NOW));
            if (head < tempBatch.size()) {
                temp.putAll(temporary.classify(
                        tempBatch.subList(head, tempBatch.size()), Lane.USER_BACKGROUND));
            }
        } else {
            temp.putAll(temporary.classify(tempBatch, Lane.USER_BACKGROUND));
        }

        if (!plan.ask().isEmpty() || !temp.isEmpty()) {
            log.info("미분류 최신화 — userId={} 남은 가맹점 {}, 임시 분류 {}, 임계값 {}",
                    userId, plan.ask().size(), temp.size(), minMerchants);
        }

        /* **무료와 유료를 가르지 않는다**(2026-08-21 사용자 결정).
         *
         * 예전에는 둘을 다르게 다뤘다 — 유료 답만 사전에 올리고(`LLM`), 무료 답은 화면 표시에만
         * 썼다(`TEMP`, DB 미저장). 두 통로의 품질이 다르다는 전제였는데, 실측으로 그 전제가
         * 깨졌다: 무료 1위 모델이 <b>72.3%</b> 로 유료 Gemini(<b>70.5%</b>)보다 높았고 실패도 0
         * 이었다(148건 · 20초 간격 순차 채점).
         *
         * 그래서 <b>무료 답을 유료와 같은 자리에 놓는다</b> — 사전에도 올라가고 출처도 `LLM`
         * 이다. 다르게 다룰 근거가 없어졌고, 두 갈래를 유지하면 같은 일을 두 벌로 관리하게 된다.
         *
         * **유료는 비상용으로 남는다.** 무료 사슬 5종이 다 죽었을 때만 부른다 — 큐 앞자리로
         * 넣어도 시간이 안 되는 경우가 아니면 유료를 쓸 이유가 없다. */
        Map<String, String> industries = new java.util.TreeMap<>();
        Map<String, String> fresh = new LinkedHashMap<>();
        Set<String> answered = new java.util.HashSet<>();
        temp.forEach((name, g) -> {
            fresh.put(name, g.category2());
            industries.put(name, g.industryName());
            answered.add(name);
        });

        // 무료가 못 잡았고 임계값을 넘겼을 때만 유료를 부른다 — 마지막 보루다.
        List<String> stillUnknown = plan.ask().stream().filter(n -> !fresh.containsKey(n)).toList();
        if (stillUnknown.size() >= minMerchants && classifier.aiEnabled()) {
            Map<String, String> paid = classifier.classify(stillUnknown, plan.important(),
                    industries, answered);
            fresh.putAll(paid);
        }

        Asked out = self.applyGuesses(userId, plan.remembered(), fresh, Map.of(), answered, industries);
        log.info("가맹점 분류 — userId={} 물어본 곳 {}, 무료가 답한 곳 {}, 합계 {}, 종결 {}",
                userId, plan.ask().size(), temp.size(), fresh.size(), out.settled().size());
        return out;
    }

    /**
     * 이번에 <b>무엇을 물을지</b> — 이름과 집계만 담는다(엔티티가 아니다).
     *
     * @param ask        물어야 할 가맹점명
     * @param remembered 이미 사전이 아는 추정 — 묻지 않는다
     * @param important  못 잡았을 때 다시 물을 값어치가 있는 곳
     */
    public record Plan(List<String> ask, Map<String, String> remembered, Set<String> important) {}

    /** ① 물을 곳을 추린다 — 읽기만 한다. */
    @Transactional(readOnly = true)
    public Plan plan(Long userId) {
        List<UserPayment> askable = askable(userId);

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
        return new Plan(ask, remembered, important);
    }

    /**
     * 물어도 되는 결제 — <b>추리는 자리와 입히는 자리가 같은 규칙을 써야 한다.</b>
     *
     * <p>두 단계가 서로 다른 트랜잭션이라 각자 읽는다. 규칙이 갈라지면 "물어놓고 안 입히는"
     * 조용한 실패가 난다.
     *
     * <p><b>미분류 전부를 본다.</b> 예전에는 최신 100건만 봤는데, 미분류가 그보다 많으면 오래된
     * 것은 영원히 차례가 안 왔다(2026-08-07 운영: 150건 중 100건만 보여 카드사 수수료가
     * 사각지대에 있었다).
     *
     * <p><b>더미 결제는 어느 모델에도 보내지 않는다.</b> 생성기가 만든 상호라 물어볼 값이 없고
     * (카탈로그가 이미 안다), 답이 와도 사전에 못 들어간다 — 사전에 실사용자 게이트가 있기
     * 때문이다. 즉 더미를 물으면 <b>호출만 쓰고 버려진다.</b> 유료 통로에서는 그게 곧 돈이고,
     * 무료 통로에서도 남의 서버를 헛되이 두드리는 일이다.
     */
    private List<UserPayment> askable(Long userId) {
        return payments.findByUserIdAndCategory2OrderByPaymentDateDesc(
                        userId, IndustryCategoryMapper.UNCLASSIFIED).stream()
                .filter(UserPayment::isFromRealPerson)
                .filter(p -> p.getCategory2Llm() == null)
                .filter(p -> classifier.worthAsking(p.getMerchantName(), p.getBusinessNumber()))
                .toList();
    }

    /**
     * ③ 얻은 답을 결제와 사전에 입힌다 — <b>여기서 다시 읽는다.</b>
     *
     * <p>모델 질의는 트랜잭션 밖에서 돌므로 그때 손에 든 엔티티는 영속 상태가 아니다.
     * 고치는 순간에 트랜잭션을 열고 그 안에서 읽은 것을 고쳐야 반영된다.
     *
     * @param answered <b>모델에 물어보고 답을 받은</b> 가맹점명. '헛물'은 <b>이 안에서만</b> 센다.
     *                 비어 있으면 유료 통로를 안 부른 것이라 아무것도 세지 않는다.
     */
    @Transactional
    public Asked applyGuesses(Long userId, Map<String, String> remembered,
                              Map<String, String> fresh,
                              Map<String, TempClassifierService.Guess> temp,
                              Set<String> answered) {
        return applyGuesses(userId, remembered, fresh, temp, answered, Map.of());
    }

    /**
     * @param industries 모델이 답한 <b>업종 이름</b>(가맹점명 → 업종명). <b>무료·유료 양쪽</b>이
     *                   담긴다 — 둘을 가르지 않기로 한 뒤로 이 지도는 한 벌이다(2026-08-21).
     *                   업종코드를 되찾아 결제에 적고, 사전에 남겨 소분류의 근거로 쓴다.
     */
    @Transactional
    public Asked applyGuesses(Long userId, Map<String, String> remembered,
                              Map<String, String> fresh,
                              Map<String, TempClassifierService.Guess> temp,
                              Set<String> answered,
                              Map<String, String> industries) {
        List<UserPayment> rows = payments.findByUserIdAndCategory2OrderByPaymentDateDesc(
                userId, IndustryCategoryMapper.UNCLASSIFIED);
        Set<String> settled = new java.util.LinkedHashSet<>();
        if (!answered.isEmpty() || !fresh.isEmpty()) {
            List<UserPayment> askable = askable(userId);
            // 새로 알아낸 것만 사전에 남긴다 — 다음 연동·다음 달 결제에서 재사용된다.
            // (실제 사람의 결제일 때만 쌓인다. 더미의 사업자번호는 실재하지 않는다.)
            for (UserPayment p : askable) {
                String g = fresh.get(p.getMerchantName());
                // **업종 이름도 같이 넘긴다**(V43). 중분류만 넘기면 사전의 업종 이름 칸이 비어
                // 소분류를 이름에서 찾는 길이 끊긴다 — 브랜드가 안 붙는 개인 상호가 통째로
                // 소분류도 추정 업종코드도 못 얻는다.
                if (g != null) dictionary.rememberGuess(p, g, industries.get(p.getMerchantName()));
            }
            // **헛물켠 질문을 센다** — 물었는데 답이 안 온 가맹점. 세 번째면 '기타'로 종결하고
            // 다음부터는 조회도 질문도 하지 않는다. 이 기록이 없으면 화면을 열 때마다 같은
            // 상호를 다시 묻는다: 답이 없다는 사실이 어디에도 안 남기 때문이다.
            //
            // **가맹점당 한 번만 센다.** 결제 건마다 세면 넷플릭스 12건짜리 가맹점이 한 번의
            // 질문에 12회로 기록돼 첫 시도에 바로 종결된다.
            //
            // **세는 자격은 `answered` 하나로 정한다.** "물어보고 답을 받았는데 그 가맹점이
            // 답에 없었다" 만이 침묵이다. 그 밖은 전부 <b>우리가 못 물은 것</b>이고, 못 물은 것을
            // 세면 세 번 만에 멀쩡한 가맹점이 '기타'로 종결된다 — 종결은 결제와 소비 원장까지
            // 고치므로 리포트에서 그 지출이 통째로 사라진다. 다음 넷이 한 규칙으로 막힌다
            // (2026-08-07 감사·재감사):
            //
            //   · 사전이 이미 추정을 아는 곳   ①에서 질문 목록에서 빠진다(remembered)
            //   · 통로가 꺼져 있다             키가 없으면 HTTP 를 한 번도 안 낸다
            //   · 호출이 실패했다              429·타임아웃이면 그 묶음이 통째로 빈손이다
            //   · 상한을 넘겼다                5회 × 40곳을 넘은 뒤쪽은 프롬프트에 안 담긴다
            //   · ①과 ③ 사이에 새로 들어왔다   질문 당시에는 존재하지도 않았다
            LocalDateTime askedAt = LocalDateTime.now(clock);
            Set<String> counted = new java.util.HashSet<>();
            for (UserPayment p : askable) {
                String name = p.getMerchantName();
                if (!answered.contains(name)) continue;
                if (fresh.containsKey(name) || !counted.add(name)) continue;
                if (dictionary.noteLlmMiss(p, askedAt)) settled.add(name);
            }
            applySettled(rows, settled);
        }

        Map<String, String> guesses = new LinkedHashMap<>(remembered);
        guesses.putAll(fresh);
        paint(rows, guesses, industries);
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
    /**
     * <b>최초 연동 한 번 — 유료 통로로 크게 훑는다.</b>
     *
     * <p>평소에는 무료 통로가 한 곳씩 물어 채운다. 그런데 최초 연동은 <b>사람이 로딩 화면
     * 앞에서 기다린다</b>. 110종을 한 곳씩 물으면 호출 예산(분당 40)에 걸려 3분이고, 40곳씩
     * 묶으면 세 번이라 30초다({@code MerchantClassifierService#classifyInBulk}).
     *
     * <p><b>이 자리에서만 묶는다.</b> 여기서 못 맞힌 것은 무료 통로가 한 곳씩 이어받는다 —
     * 먼저 크게 훑는 것이지 유일한 통로가 아니다.
     *
     * <p>많이 남았을 때만 부른다({@link #BULK_MIN}). 몇 곳 때문에 유료를 깨우면 값만 쓰고
     * 사용자는 차이를 못 느낀다.
     *
     * @return 새로 붙인 결제 수
     */
    @Transactional
    public int askInBulk(Long userId) {
        if (!classifier.aiEnabled()) return 0;
        MerchantAskService self = selfProvider.getObject();
        Plan plan = self.plan(userId);
        if (plan.ask().size() < BULK_MIN) return 0;

        Map<String, String> industries = new java.util.TreeMap<>();
        Map<String, String> got = classifier.classifyInBulk(plan.ask(), industries);
        if (got.isEmpty()) return 0;

        List<UserPayment> rows = payments.findByUserIdAndCategory2OrderByPaymentDateDesc(
                userId, IndustryCategoryMapper.UNCLASSIFIED);
        paint(rows, got, industries);
        // 사전에도 남긴다 — 다음 사람의 같은 가맹점은 다시 안 묻는다.
        for (UserPayment p : rows) {
            String guess = got.get(p.getMerchantName());
            if (guess != null) dictionary.rememberGuess(p, guess, industries.get(p.getMerchantName()));
        }
        log.info("최초 연동 일괄 분류 — userId={} 물어본 곳 {}, 붙인 가맹점 {}",
                userId, plan.ask().size(), got.size());
        return got.size();
    }

    /**
     * 일괄을 깨울 최소 가맹점 수.
     *
     * <p>이보다 적으면 무료 통로가 한두 회차에 끝낸다 — 유료를 쓸 이유가 없다. 실측에서
     * 새 실사용자의 미분류 가맹점이 110종·47종이었으니, 최초 연동은 대개 이 문턱을 넘는다.
     */
    private static final int BULK_MIN = 20;

    private void paint(List<UserPayment> rows, Map<String, String> guesses,
                       Map<String, String> industries) {
        for (UserPayment p : rows) {
            String guess = guesses.get(p.getMerchantName());
            if (guess == null) continue;
            // **출처는 하나뿐이다.** 예전에는 유료를 `LLM`, 무료를 `TEMP` 로 갈랐는데 무료
            // 1위가 유료보다 정확했다(72.3% 대 70.5%, 148건 실측). 다르게 적을 근거가 없다.
            p.suggestCategory2(guess, "LLM");
            learnCode(p, industries.get(p.getMerchantName()));
        }
    }

    /**
     * <b>알아낸 업종의 코드를 결제에 적는다</b> — 카드추천의 혜택 축이 그 코드로 정해진다.
     *
     * <p>실 명세서에는 업종코드가 없어 적재기가 자리채움값을 넣고, 그 코드는 대조표에 아예
     * 없다. 그래서 실사용자 결제 <b>1,579건 전부</b>가 카드추천에서 축 없음으로 빠지고 있었다
     * (2026-08-21 실측). 모델이 업종 이름을 알아냈으면 표에서 코드를 되찾아 그 자리를 메운다.
     *
     * <p>코드가 여럿이면 첫 것을 쓴다 — 그때는 그 코드들의 중분류가 만장일치라 어느 것을
     * 골라도 축이 같다(V29 의 만장일치 규칙).
     */
    private void learnCode(UserPayment payment, String industryName) {
        if (industryName == null || industryName.isBlank()) return;
        var codes = industries.codesOfFineName(industryName);
        if (!codes.isEmpty()) payment.learnIndustryCode(codes.get(0));
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
