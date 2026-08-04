package com.finntech.service;

import com.finntech.domain.MerchantCategory;
import com.finntech.domain.UserPayment;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.MerchantCategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 결제 한 건의 중분류를 정한다 — <b>순위가 한 곳에만 있어야 한다.</b>
 *
 * <pre>
 *   ① merchant_category (확정)  →  ② 업종코드 매핑  →  ③ LLM 추정(표시만)  →  ④ 카테고리없음
 * </pre>
 *
 * <p>①로 붙은 분류는 <b>처음부터 확정</b>이라 판정·알림에 바로 참여한다(§F 의 격리 대상이 아니다).
 * 사람이 준 것이거나 사람이 확인한 것만 ①에 들어오기 때문이다.
 *
 * <p>브랜드 일반화({@code GS25 역삼점} 을 처음 봐도 편의점인 것)는 사전이 아니라 <b>③ LLM</b> 이
 * 맡는다. 사전의 임무는 "같은 점포를 두 번 묻지 않는 것"이다 — 그 분업이 각자 잘하는 일에 맞다.
 */
@Service
public class MerchantCategoryService {

    private final MerchantCategoryRepository repository;
    private final IndustryCategoryMapper mapper;

    public MerchantCategoryService(MerchantCategoryRepository repository,
                                   IndustryCategoryMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /**
     * 사전에서 찾는다. 없으면 {@link Optional#empty()} — 부르는 쪽이 ②로 내려간다.
     *
     * <p><b>완화는 PG 가 아닐 때만</b> 적용한다. PG(전자지급결제대행)는 한 번호에 업종이 제각각인
     * 가맹점이 붙으므로, 같은 번호의 다른 행을 가져다 쓰면 그 PG 를 거친 모든 결제가 한 분류로
     * 오염된다. PG 가 아닌 번호는 한 사업자의 것이라 업종이 하나여서 안전하다.
     */
    @Transactional(readOnly = true)
    public Optional<String> lookup(String businessNumber, String merchantName) {
        String biz = MerchantCategory.normalize(businessNumber);
        if (merchantName == null || merchantName.isBlank()) {
            return Optional.empty();
        }

        // ① 정확 일치 — (사업자번호, 풀네임). 번호가 없으면 빈 문자열이 키다.
        Optional<MerchantCategory> exact =
                repository.findByBusinessNumberAndMerchantName(biz, merchantName);
        if (exact.isPresent()) {
            return exact.map(MerchantCategory::getCategory2);
        }

        // ② 번호가 없는 해외 가맹점은 풀네임만으로 한 번 더 본다.
        if (biz.isEmpty()) {
            return first(repository.findByNameOnly(merchantName));
        }

        // ③ 같은 번호의 다른 행 — 택시처럼 표시명이 결제마다 달라지는 가맹점 때문이다.
        //    PG 면 여기서 멈춘다. 이 완화가 PG 에 적용되는 순간 사전이 거짓말을 시작한다.
        if (mapper.isPaymentAgency(biz)) {
            return Optional.empty();
        }
        return first(repository.findByBusinessNumberOrdered(biz));
    }

    /**
     * 사전을 거쳐 최종 중분류를 정한다 — ①이 없으면 ②(업종코드)로 내려간다.
     *
     * @param industryCode 국세청 업종코드 6자리. 실제 명세서에는 없어 대개 null 이다.
     */
    @Transactional(readOnly = true)
    public String resolve(String industryCode, String businessNumber, String merchantName) {
        return lookup(businessNumber, merchantName)
                .orElseGet(() -> mapper.midOf(industryCode, businessNumber));
    }

    /**
     * 결제 한 건을 근거로 확정 분류를 쌓는다 — <b>실제 사람의 결제일 때만.</b>
     *
     * <p>더미 사용자의 사업자번호는 생성기가 만든 것이라 실재하지 않는다. 데모로 둘러보다
     * "맞아요"를 누른 것이 쌓이면 사전이 <i>"실제 사업자번호와 중분류"</i> 라는 약속을 어긴다.
     * 그래서 여기서 막는다 — 읽기는 막지 않는다(사전에 실물만 있으면 더미가 읽어도 안 더러워진다).
     *
     * @return 쌓았으면 그 행, 더미 결제라 거절했으면 {@link Optional#empty()}
     */
    @Transactional
    public Optional<MerchantCategory> confirmFrom(UserPayment payment, String category2,
                                                  Long confirmedBy) {
        if (payment == null || !payment.isFromRealPerson()) {
            return Optional.empty();
        }
        return Optional.of(confirm(payment.getBusinessNumber(), payment.getMerchantName(),
                category2, MerchantCategory.Source.USER_CONFIRMED, confirmedBy));
    }

    /**
     * 확정 분류를 쌓는다. 이미 있으면 덮어써서 <b>오입력을 되돌릴 수 있게</b> 한다.
     *
     * <p>{@code USER_CONFIRMED} 로 들어오는 것은 사람이 화면에서 "맞아요"를 누른 것이다.
     * LLM 추정은 여기로 오지 못한다 — {@code user_payment.category2_llm} 에 머문다.
     *
     * <p><b>사람이 부르는 경로는 {@link #confirmFrom} 이다.</b> 이쪽은 CSV 일괄 적재처럼
     * 출처가 이미 실물임이 확실한 경우에만 직접 쓴다.
     */
    @Transactional
    public MerchantCategory confirm(String businessNumber, String merchantName,
                                    String category2, MerchantCategory.Source source,
                                    Long confirmedBy) {
        String biz = MerchantCategory.normalize(businessNumber);
        return repository.findByBusinessNumberAndMerchantName(biz, merchantName)
                .map(existing -> {
                    existing.reclassify(category2, source, confirmedBy);
                    return existing;
                })
                .orElseGet(() -> repository.save(
                        new MerchantCategory(biz, merchantName, category2, source, confirmedBy)));
    }

    private static Optional<String> first(List<MerchantCategory> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0).getCategory2());
    }

    /**
     * 사전 전체를 한 번에 읽어 메모리에서 맞춘다 — <b>적재 루프용</b>이다.
     *
     * <p>연동 한 번에 결제가 수천 건 들어온다. 건마다 {@link #lookup} 을 부르면 질의가 그만큼
     * 나가 연동이 눈에 띄게 느려진다. 사전은 확정분만 쌓여 작으므로(가맹점 단위) 통째로 읽는 편이
     * 싸다. 조회 규칙은 {@link #lookup} 과 <b>같은 것을 쓴다</b> — 두 곳에 적으면 갈라진다.
     */
    @Transactional(readOnly = true)
    public Snapshot snapshot() {
        return new Snapshot(repository.findAll(), mapper);
    }

    /** 한 시점의 사전 사본. 적재하는 동안만 산다. */
    public static final class Snapshot {
        private final Map<String, String> exact = new HashMap<>();       // key(번호, 풀네임) → 중분류
        private final Map<String, String> byBusiness = new HashMap<>();  // 번호 → 중분류(PG 아닌 것만)
        private final Map<String, String> byNameOnly = new HashMap<>();  // 번호 없는 해외 가맹점
        private final IndustryCategoryMapper mapper;

        Snapshot(List<MerchantCategory> rows, IndustryCategoryMapper mapper) {
            this.mapper = mapper;
            // id 오름차순으로 넣어 먼저 들어온 행이 이긴다 — lookup 의 ORDER BY id ASC 와 같다.
            rows.stream().sorted(java.util.Comparator.comparing(MerchantCategory::getId,
                            java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                    .forEach(m -> {
                        String biz = m.getBusinessNumber();
                        exact.putIfAbsent(key(biz, m.getMerchantName()), m.getCategory2());
                        if (biz.isEmpty()) {
                            byNameOnly.putIfAbsent(m.getMerchantName(), m.getCategory2());
                        } else if (!mapper.isPaymentAgency(biz)) {
                            // PG 번호는 여기 담지 않는다. 담는 순간 그 PG 를 거친 모든 결제가
                            // 한 분류로 오염된다 — lookup 이 PG 를 걸러 내는 것과 같은 이유다.
                            byBusiness.putIfAbsent(biz, m.getCategory2());
                        }
                    });
        }

        /** {@link MerchantCategoryService#lookup} 과 같은 순서로 찾는다. */
        public Optional<String> lookup(String businessNumber, String merchantName) {
            if (merchantName == null || merchantName.isBlank()) return Optional.empty();
            String biz = MerchantCategory.normalize(businessNumber);
            String hit = exact.get(key(biz, merchantName));
            if (hit != null) return Optional.of(hit);
            if (biz.isEmpty()) return Optional.ofNullable(byNameOnly.get(merchantName));
            return Optional.ofNullable(byBusiness.get(biz));
        }

        /** 사전에 있으면 그 분류를, 없으면 업종코드가 정한 분류를 준다. */
        public String resolve(String industryCode, String businessNumber, String merchantName) {
            return lookup(businessNumber, merchantName)
                    .orElseGet(() -> mapper.midOf(industryCode, businessNumber));
        }

        /** 이름에 들어갈 수 없는 문자를 구분자로 쓴다 — 'A' + 'BC' 와 'AB' + 'C' 가 같아지지 않게. */
        private static String key(String businessNumber, String merchantName) {
            return businessNumber + '\u0001' + merchantName;
        }

        public boolean isEmpty() { return exact.isEmpty(); }
        public int size() { return exact.size(); }
    }
}
