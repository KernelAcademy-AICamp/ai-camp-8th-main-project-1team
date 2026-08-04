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

    private final UserPaymentRepository payments;
    private final ConsumptionRepository consumptions;
    private final CategoryRepository categories;
    private final MerchantCategoryService dictionary;
    private final MerchantClassifierService classifier;
    private final IndustryCategoryMapper mapper;

    public MerchantCategoryController(UserPaymentRepository payments,
                                      ConsumptionRepository consumptions,
                                      CategoryRepository categories,
                                      MerchantCategoryService dictionary,
                                      MerchantClassifierService classifier,
                                      IndustryCategoryMapper mapper) {
        this.payments = payments;
        this.consumptions = consumptions;
        this.categories = categories;
        this.dictionary = dictionary;
        this.classifier = classifier;
        this.mapper = mapper;
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
        List<String> ask = rows.stream()
                .filter(p -> p.getCategory2Llm() == null)
                .filter(p -> classifier.worthAsking(p.getMerchantName(), p.getBusinessNumber()))
                .map(UserPayment::getMerchantName)
                .distinct()
                .toList();
        Map<String, String> guessed = classifier.classify(ask);
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

        return Map.of("paymentId", paymentId,
                      "category2", request.category2(),
                      "reclassifiedConsumptions", moved,
                      "storedInDictionary", storedInDictionary);
    }

    public record ConfirmRequest(@NotNull String category2) {}
}
