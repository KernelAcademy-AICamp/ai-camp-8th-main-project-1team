package com.finntech.web;

import com.finntech.domain.Category;
import com.finntech.domain.Consumption;
import com.finntech.domain.UserPayment;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.CategoryRepository;
import com.finntech.repository.ConsumptionRepository;
import com.finntech.repository.UserPaymentRepository;
import com.finntech.service.MerchantCategoryService;
import com.finntech.service.MerchantClassifierService;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 미분류 결제를 사람이 정리하는 창구 — <b>AI 는 제안만, 확정은 사람이.</b>
 *
 * <pre>
 *   GET  /api/merchant-category/unclassified?userId=1   미분류 목록 + AI 추정
 *   POST /api/merchant-category/{paymentId}/confirm     사람이 확정 → 사전에 쌓인다
 * </pre>
 */
@RestController
@RequestMapping("/api/merchant-category")
public class MerchantCategoryController {

    /** 못 잡았을 때 다시 물을 기준 — 이만큼 반복되거나(정기결제) 이만큼 크면 그냥 넘기지 않는다. */
    private static final int RETRY_MIN_COUNT = 2;
    private static final long RETRY_MIN_AMOUNT = 50_000L;

    private final UserPaymentRepository payments;
    private final ConsumptionRepository consumptions;
    private final CategoryRepository categories;
    private final MerchantCategoryService dictionary;
    private final MerchantClassifierService classifier;
    private final IndustryCategoryMapper mapper;
    /** 사업자번호가 한 사업인가 여러 사업인가(V16). 확정이 그 판정을 흔들 수 있다. */
    private final com.finntech.service.BusinessNumberKindService kinds;
    private final java.time.Clock clock;

    public MerchantCategoryController(UserPaymentRepository payments,
                                      ConsumptionRepository consumptions,
                                      CategoryRepository categories,
                                      MerchantCategoryService dictionary,
                                      MerchantClassifierService classifier,
                                      IndustryCategoryMapper mapper,
                                      com.finntech.service.BusinessNumberKindService kinds,
                                      java.time.Clock clock) {
        this.payments = payments;
        this.consumptions = consumptions;
        this.categories = categories;
        this.dictionary = dictionary;
        this.classifier = classifier;
        this.mapper = mapper;
        this.kinds = kinds;
        this.clock = clock;
    }

    /**
     * 아직 분류되지 않은 결제와 <b>AI 추정</b>을 함께 준다.
     *
     * <p>추정은 {@code category2} 를 덮지 않는다 — 화면이 "AI 추정" 배지로 보여 주고 사람이
     * 확인해야 확정이 된다. 물어볼 대상은 {@link MerchantClassifierService#worthAsking} 이 고른다
     * (PG 상호는 물어봐도 소용없으므로 뺀다).
     */
    @GetMapping("/unclassified")
    @Transactional
    public Map<String, Object> unclassified(@RequestParam Long userId) {
        List<UserPayment> rows = payments.findTop100ByUserIdAndCategory2OrderByPaymentDateDesc(
                userId, IndustryCategoryMapper.UNCLASSIFIED);

        // 같은 가맹점이 여러 번 나온다 — 이름당 한 번만 묻는다.
        List<UserPayment> askable = rows.stream()
                .filter(p -> p.getCategory2Llm() == null)
                .filter(p -> classifier.worthAsking(p.getMerchantName(), p.getBusinessNumber()))
                .toList();
        // **이미 물어본 가맹점은 다시 묻지 않는다.** 추정은 결제 행(`category2_llm`)에만 남기면
        // 다음 달 같은 넷플릭스가 새 결제로 들어올 때 또 묻게 된다 — 행이 새것이라 비어 있기
        // 때문이다. 그래서 사전에도 `LLM_GUESS` 로 남기고, 여기서 그것부터 꺼내 쓴다.
        Map<String, String> remembered = new LinkedHashMap<>();
        for (UserPayment p : askable) {
            remembered.computeIfAbsent(p.getMerchantName(),
                    n -> dictionary.guess(p.getBusinessNumber(), n).orElse(null));
        }
        remembered.values().removeIf(java.util.Objects::isNull);

        List<String> ask = askable.stream().map(UserPayment::getMerchantName).distinct()
                .filter(n -> !remembered.containsKey(n)).toList();

        // **못 잡았을 때 다시 물을 값어치**를 여기서 정한다 — 건수·금액을 아는 것은 이쪽이다.
        // 모델은 알면서도 큰 묶음에서 흘리므로(2026-08-05 실측: 넷플릭스), 중요한 것은 작게
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

        Map<String, String> guessed = new LinkedHashMap<>(remembered);
        guessed.putAll(fresh);
        for (UserPayment p : rows) {
            String g = guessed.get(p.getMerchantName());
            if (g != null) p.suggestCategory2(g);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (UserPayment p : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("paymentId", p.getPaymentId());
            item.put("date", p.getPaymentDate());
            item.put("amount", p.getAmount());
            item.put("merchantName", p.getMerchantName());
            item.put("businessNumber", p.getBusinessNumber());
            item.put("suggested", p.getCategory2Llm());
            item.put("source", p.getCategory2Source());
            // 상호 자체가 결제대행사면 **원리적으로 알 수 없는 결제**다. 화면이 이것을 "정말
            // 모르는 것"과 나눠 보여줘야, 남은 미분류가 사용자가 손댈 수 있는 것만 남는다.
            item.put("paymentAgency", classifier.isPaymentAgencyMerchant(p.getMerchantName()));
            // 더미 사용자의 확정은 사전에 쌓이지 않는다. 화면이 미리 알 수 있게 함께 준다.
            item.put("canConfirm", p.isFromRealPerson());
            items.add(item);
        }
        return Map.of("categories", mapper.midCategories(),
                      "aiEnabled", classifier.aiEnabled(),
                      "items", items);
    }

    /**
     * 사람이 분류를 확정한다 — 그 결제가 바뀌고, <b>사전에도 쌓여</b> 다음부터 안 묻는다.
     *
     * <p>사전에 쌓이는 것은 <b>실제 사람의 결제일 때만</b>이다. 더미 사용자의 사업자번호는
     * 생성기가 만든 것이라 실재하지 않아, 쌓이면 사전이 거짓이 된다
     * ({@link MerchantCategoryService#confirmFrom}). 결제 자체의 분류는 그래도 바뀐다 —
     * 데모에서도 화면은 정상으로 보여야 하기 때문이다.
     */
    @PostMapping("/{paymentId}/confirm")
    @Transactional
    public Map<String, Object> confirm(@RequestParam Long userId,
                                       @PathVariable String paymentId,
                                       @RequestBody ConfirmRequest request) {
        if (!mapper.midCategories().contains(request.category2())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "모르는 중분류입니다: " + request.category2());
        }
        UserPayment payment = payments.findById(paymentId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다"));
        if (!payment.getUserId().equals(userId)) {
            // 남의 결제를 고쳐 사전에 밀어 넣을 수 없어야 한다.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인의 결제가 아닙니다");
        }

        payment.confirmCategory2(request.category2(), "USER");

        // 결제 원장만 고치면 **화면은 그대로다.** 리포트·분석이 읽는 것은 `Consumption` 이고
        // 그 카테고리는 적재할 때 박힌 값이라, 짝을 함께 고쳐야 확정이 실제로 반영된다.
        Category category = categories.findByCode(request.category2())
                .orElseGet(() -> categories.save(new Category(request.category2(), request.category2())));
        int moved = 0;
        for (Consumption c : consumptions.findBySourcePaymentId(paymentId)) {
            c.reclassify(category);
            moved++;
        }

        boolean storedInDictionary =
                dictionary.confirmFrom(payment, request.category2(), userId).isPresent();

        // **판정을 다시 본다**(V16). 사람이 다르게 확정했다는 것은 그 번호가 갈렸다는 증거일 수
        // 있다. 다만 굳은 판정은 한 번의 교정으로 뒤집지 않는다 — 그 문턱은 판정 서비스가 갖는다.
        // 여기서 부르는 이유는, 다음 연동까지 기다리면 그 사이 완화가 계속 오염시키기 때문이다.
        observeAfterConfirm(userId, payment.getBusinessNumber());

        // **같은 가맹점의 나머지 결제도 함께 고친다.** 사전에만 쌓으면 다음 연동부터 반영되고,
        // 지금 화면에는 고친 한 건만 바뀐다 — 사용자는 "고쳤는데 다른 건 그대로"를 본다
        // (2026-08-05 운영: 티머니 17건 중 1건만 바뀌었다). 사전이 새 답을 주는 결제를 찾아
        // 그 자리에서 맞춘다. 판정 근거는 여전히 사전 하나다(원칙 2).
        int alsoFixed = 0;
        if (storedInDictionary) {
            for (UserPayment other : payments.findByUserIdOrderByPaymentDateDesc(userId)) {
                if (other.getPaymentId().equals(paymentId)) continue;
                // 사람이 이미 확정한 결제는 건드리지 않는다 — 남의 판단을 덮으면 안 된다.
                if ("USER".equals(other.getCategory2Source())) continue;
                String now = dictionary.lookup(other.getBusinessNumber(), other.getMerchantName())
                        .orElse(null);
                if (now == null || now.equals(other.getCategory2())) continue;
                other.confirmCategory2(now, "DICT");
                Category c2 = categories.findByCode(now)
                        .orElseGet(() -> categories.save(new Category(now, now)));
                for (Consumption c : consumptions.findBySourcePaymentId(other.getPaymentId())) {
                    c.reclassify(c2);
                }
                alsoFixed++;
            }
        }

        return Map.of("paymentId", paymentId,
                      "category2", request.category2(),
                      "reclassifiedConsumptions", moved,
                      "alsoFixed", alsoFixed,
                      "storedInDictionary", storedInDictionary);
    }

    /**
     * 한 번호의 상호·분류를 다시 세어 판정을 갱신한다.
     *
     * <p>사용자의 교정 자체가 <b>"이 번호는 갈린다"는 신호</b>일 수 있다. 다만 그것만으로 뒤집지는
     * 않는다 — 택시처럼 상호가 수천 종인 번호는 한 번의 실수로 무너지면 안 되므로, 뒤집는 데
     * 필요한 반대 증거를 관측량에 비례해 요구한다({@code BusinessNumberKindService}).
     *
     * <p><b>교정 자체는 문턱과 무관하게 즉시 보인다.</b> 정확일치 행이 완화보다 먼저 걸린다.
     */
    private void observeAfterConfirm(Long userId, String businessNumber) {
        if (businessNumber == null || businessNumber.isBlank()) return;
        if (mapper.isPaymentAgency(businessNumber)) return;
        Map<String, String> observed = new LinkedHashMap<>();
        Map<String, String> confirmed = new LinkedHashMap<>();
        for (UserPayment p : payments.findByUserIdOrderByPaymentDateDesc(userId)) {
            if (!businessNumber.equals(p.getBusinessNumber())) continue;
            String name = p.getMerchantName();
            if (name == null || name.isBlank()) continue;
            String mid = IndustryCategoryMapper.UNCLASSIFIED.equals(p.getCategory2())
                    ? null : p.getCategory2();
            observed.put(name, mid);
            if ("USER".equals(p.getCategory2Source())) confirmed.put(name, mid);
        }
        kinds.observe(businessNumber, observed, confirmed, java.time.LocalDateTime.now(clock));
    }

    public record ConfirmRequest(@NotNull String category2) {}
}
