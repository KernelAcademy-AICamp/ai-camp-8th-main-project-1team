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
    /** 분류 순위 ③ — 묻는 일은 여기 하나에 있다(백그라운드와 공유). */
    private final com.finntech.service.MerchantAskService ask;
    private final IndustryCategoryMapper mapper;
    /** 사업자번호가 한 사업인가 여러 사업인가(V16). 확정이 그 판정을 흔들 수 있다. */
    private final com.finntech.service.BusinessNumberKindService kinds;
    /** 소비 원장을 고치면 리포트 캐시가 낡는다 — 그 자리에서 깨기 위해 든다. */
    private final com.finntech.repository.ReportRepository reports;
    private final java.time.Clock clock;

    public MerchantCategoryController(UserPaymentRepository payments,
                                      ConsumptionRepository consumptions,
                                      CategoryRepository categories,
                                      MerchantCategoryService dictionary,
                                      MerchantClassifierService classifier,
                                      com.finntech.service.MerchantAskService ask,
                                      IndustryCategoryMapper mapper,
                                      com.finntech.service.BusinessNumberKindService kinds,
                                      com.finntech.repository.ReportRepository reports,
                                      java.time.Clock clock) {
        this.payments = payments;
        this.consumptions = consumptions;
        this.categories = categories;
        this.dictionary = dictionary;
        this.classifier = classifier;
        this.ask = ask;
        this.mapper = mapper;
        this.kinds = kinds;
        this.reports = reports;
        this.clock = clock;
    }

    /**
     * 아직 분류되지 않은 결제와 <b>AI 추정</b>을 함께 준다.
     *
     * <p>추정은 {@code category2} 를 덮지 않는다 — 화면이 "AI 추정" 배지로 보여 주고 사람이
     * 확인해야 확정이 된다. 물어볼 대상은 {@link MerchantClassifierService#worthAsking} 이 고른다
     * (PG 상호는 물어봐도 소용없으므로 뺀다).
     *
     * <p><b>트랜잭션을 열지 않는다.</b> {@code ask} 는 안에서 추리기·질의·입히기로 갈라져 있고
     * 가운데(모델 질의)만 트랜잭션 밖에서 돌게 되어 있는데, 여기에 {@code @Transactional} 이
     * 붙으면 그 셋이 <b>전부 이 트랜잭션에 합류해</b> 분리가 통째로 무의미해진다. 그리고 이
     * 진입로가 가장 나쁘다 — 임계값이 1({@code ON_DEMAND_MIN})이라 남은 것을 전부 몰아 묻기
     * 때문이다(무료 6~10초 × N + 유료 30초+). 2026-08-07 감사에서 여기만 남아 있었다.
     *
     * <p>돌려받는 결제 행은 영속 상태가 아니지만 상관없다 — 값만 읽어 그대로 내보낸다
     * ({@code UserPayment} 에는 지연 로딩할 연관이 없다).
     */
    @GetMapping("/unclassified")
    public Map<String, Object> unclassified(@RequestParam Long userId) {
        // **묻는 일은 서비스가 한다.** 백그라운드 동기화도 같은 것을 부르는데 임계값만 다르다
        // (거긴 40곳, 여긴 1곳). 두 벌로 적으면 한쪽만 고쳐져 조용히 갈라진다.
        var asked = ask.ask(userId, com.finntech.service.MerchantAskService.ON_DEMAND_MIN);
        List<UserPayment> rows = asked.rows();

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

        // **결제대행사는 사전에 안 쌓는다.** 사전은 전역 자산이라 여기 들어가면 <b>모든
        // 사용자</b>의 `토스페이먼츠` 결제가 한 카테고리가 된다 — 무엇을 샀는지 모르는 돈이
        // 통째로 한 칸에 쌓인다. 그 사람의 결제 한 건만 고치고 끝낸다.
        boolean agency = classifier.isPaymentAgencyMerchant(payment.getMerchantName());
        boolean storedInDictionary = !agency
                && dictionary.confirmFrom(payment, request.category2(), userId).isPresent();

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
                // **간편결제는 휩쓸리지 않는다.** 상호가 결제대행사 자신이라 무엇을 샀는지
                // 원리적으로 모르는 결제다 — 한 건을 고쳤다고 나머지 수십 건이 같은
                // 카테고리가 되면, 모르는 돈이 통째로 한 칸에 쌓인다.
                if (IndustryCategoryMapper.SIMPLE_PAY.equals(other.getCategory2())) continue;
                // **본인의 표가 사전보다 먼저다**(V30). 사전의 값은 이제 전역 다수결이라,
                // 내 표가 졌을 때 그대로 밀어 넣으면 **남의 다수결이 내 결제를 덮는다** —
                // "본인에게만 적용"이라는 약속이 여기서 깨진다. 전역은 내가 아직 표를 안 던진
                // 가맹점에만 붙는 기본값이다.
                String mine = dictionary
                        .voteOf(other.getBusinessNumber(), other.getMerchantName(), userId)
                        .orElse(null);
                String now = mine != null ? mine
                        : dictionary.lookup(other.getBusinessNumber(), other.getMerchantName())
                                .orElse(null);
                if (now == null || now.equals(other.getCategory2())) continue;
                // 내 표로 고친 것은 내 판단이므로 그렇게 적는다 — 다음에 사전이 덮지 않는다.
                other.confirmCategory2(now, mine != null ? "USER" : "DICT");
                Category c2 = categories.findByCode(now)
                        .orElseGet(() -> categories.save(new Category(now, now)));
                for (Consumption c : consumptions.findBySourcePaymentId(other.getPaymentId())) {
                    c.reclassify(c2);
                }
                alsoFixed++;
            }
        }

        // **소비 원장을 고쳤으면 리포트 캐시를 깬다.** 리포트는 (사용자, 연월) 로 캐시되는데
        // 그 값은 `Consumption` 의 카테고리를 집계한 것이다. 여기서 고치고 캐시를 안 깨면
        // 사용자는 "고쳤다"는 응답을 받고도 리포트에서는 옛 숫자를 계속 본다(2026-08-07 재감사).
        if (moved > 0 || alsoFixed > 0) reports.deleteByUserId(userId);

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
            // **더미의 "맞아요"는 전역 판정 표에 안 들어간다.** 사전 쪽은 `confirmFrom` 이 막는데
            // 이쪽 문이 열려 있었다 — 데모로 둘러보다 누른 것이 실사용자의 완화 판정을 정하게
            // 된다(2026-08-07 감사). 결제 자체의 분류는 그대로 바뀐다. 화면은 정상이어야 한다.
            if (!p.isFromRealPerson()) continue;
            if (!businessNumber.equals(p.getBusinessNumber())) continue;
            String name = p.getMerchantName();
            if (name == null || name.isBlank()) continue;
            // **'기타'는 분류가 아니다** — 관측하는 자리는 여기와 `observeBusinessNumbers` 둘이고
            // 둘이 같은 규칙을 써야 한다. '기타'를 중분류로 세면 종결 하나가 그 번호를 영구
            // MULTI 로 만들어 완화가 죽는다(2026-08-07 재감사).
            String mid = IndustryCategoryMapper.isUnknown(p.getCategory2())
                    ? null : p.getCategory2();
            observed.put(name, mid);
            if ("USER".equals(p.getCategory2Source())) confirmed.put(name, mid);
        }
        kinds.observe(businessNumber, observed, confirmed, java.time.LocalDateTime.now(clock));
    }

    public record ConfirmRequest(@NotNull String category2) {}
}
