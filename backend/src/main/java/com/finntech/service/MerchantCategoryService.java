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
    private final BusinessNumberKindService kinds;

    public MerchantCategoryService(MerchantCategoryRepository repository,
                                   IndustryCategoryMapper mapper,
                                   BusinessNumberKindService kinds) {
        this.repository = repository;
        this.mapper = mapper;
        this.kinds = kinds;
    }

    /**
     * 사전에서 찾는다. 없으면 {@link Optional#empty()} — 부르는 쪽이 ②로 내려간다.
     *
     * <p><b>PG 번호는 조회에서 버린다.</b> PG(전자지급결제대행)는 한 번호에 업종이 제각각인
     * 가맹점이 붙는다 — KG이니시스 하나에 넷플릭스·야놀자·스타벅스가 달려 있다. 같은 번호의
     * 다른 행을 가져다 쓰면 그 PG 를 거친 모든 결제가 한 분류로 오염되고, 그렇다고 멈춰 버리면
     * PG 결제는 사전이 영영 못 붙인다. 번호를 지우고 <b>이름</b>으로 보는 것이 답이다 — PG 를
     * 거친 결제에서 가맹점명이 유일한 정보이기 때문이다.
     */
    @Transactional(readOnly = true)
    public Optional<String> lookup(String businessNumber, String merchantName) {
        return find(businessNumber, merchantName, MerchantCategory::isConfirmed)
                .map(MerchantCategory::getCategory2);
    }

    /**
     * <b>이 가맹점을 이미 LLM 에 물어봤는가</b> — 물어봤다면 그때의 추정을 준다.
     *
     * <p>{@link #lookup} 과 분리된 이유는 이것이 <b>판정이 아니기 때문</b>이다. 추정은
     * 화면에 "AI 추정" 배지로만 나가고 사람이 확인해야 확정이 된다(마스터 §4 원칙 1).
     * 그래도 <b>다시 묻지 않기 위해</b> 사전에 남긴다 — 추정을 결제 행에만 적어 두면
     * 다음 달 같은 넷플릭스가 새 결제로 들어올 때 똑같은 질문을 또 하게 된다.
     * 찾는 규칙은 {@link #lookup} 과 같다(PG 는 이름으로, 나머지는 번호 완화까지).
     */
    @Transactional(readOnly = true)
    public Optional<String> guess(String businessNumber, String merchantName) {
        return find(businessNumber, merchantName, m -> !m.isConfirmed())
                .map(MerchantCategory::getCategory2);
    }

    /**
     * 조회 순서는 <b>여기 한 곳에만</b> 있다 — 확정을 찾든 추정을 찾든 같은 길이라야 한다.
     * 두 벌로 적으면 한쪽만 고쳐져 "쌓는 자리와 찾는 자리가 어긋나는" 조용한 실패가 난다.
     */
    private Optional<MerchantCategory> find(String businessNumber, String merchantName,
                                            java.util.function.Predicate<MerchantCategory> accept) {
        if (merchantName == null || merchantName.isBlank()) {
            return Optional.empty();
        }
        String biz = MerchantCategory.normalize(businessNumber);

        // ① 정확 일치 — (사업자번호, 풀네임). 번호가 없으면 빈 문자열이 키다.
        Optional<MerchantCategory> exact =
                repository.findByBusinessNumberAndMerchantName(biz, merchantName).filter(accept);
        if (exact.isPresent()) {
            return exact;
        }

        // ② 번호가 없는 해외 가맹점, 그리고 ③ PG 를 거친 결제는 <b>이름</b>으로 본다.
        //    PG 번호는 결제를 대행한 회사의 것이라 무엇을 샀는지 아무 말도 하지 않는다 —
        //    정보가 아니라 잡음이다. 이름 한 행이면 어느 PG 를 거치든 붙는다. 실제로 이
        //    명세서의 넷플릭스는 KG이니시스 8건과 NHNKCP 4건으로 갈라져 있어, PG 별
        //    복합키로는 둘 다 넣어야 겨우 따라잡는다(2026-08-05 실측).
        if (biz.isEmpty() || mapper.isPaymentAgency(biz)) {
            return repository.findByNameOnly(merchantName).stream().filter(accept).findFirst();
        }

        // ④ **복합 사업자는 완화하지 않는다.** 번호는 그 사업자의 것이 맞지만 성격이 다른
        //    가게가 여럿 붙어 있어(백화점 입점, 배 안의 편의점), 번호로 분류하면 서로 다른
        //    가게가 한 분류로 오염된다. 정확일치만 인정하고 없으면 업종코드·미분류로 내려보낸다.
        if (mapper.isMultiBusiness(biz)) {
            return Optional.empty();
        }

        // ⑤ **관측 판정을 본다.** 상호가 둘 이상 보인 번호는 "전부 같은 것을 판다"가 확인될
        //    때까지 완화를 보류한다(V16). 상호가 하나뿐이면 판정 대상이 아니라 그대로 통과한다 —
        //    오염될 대상이 없기 때문이다.
        if (!kinds.relaxationAllowed(biz)) {
            return Optional.empty();
        }

        // ⑥ 같은 번호의 다른 행 — 택시처럼 표시명이 결제마다 달라지는 가맹점 때문이다.
        //    사전이 이미 두 중분류를 알고 있으면 판정을 기다리지 않고 여기서 막는다.
        List<MerchantCategory> siblings = repository.findByBusinessNumberOrdered(biz);
        if (splitsIntoSeveralCategories(siblings)) {
            return Optional.empty();
        }
        return siblings.stream().filter(accept).findFirst();
    }

    /**
     * <b>사전이 이 번호를 이미 여러 중분류로 알고 있는가</b> — 그러면 목록에 없어도 복합이다.
     *
     * <p>정적 목록({@code 복합사업자-사업자번호.tsv})만으로는 <b>처음 보는 백화점</b>을 못 막는다.
     * 그런데 사고가 나는 흐름을 뜯어 보면 <b>사용자의 교정 자체가 신호</b>다 —
     *
     * <pre>
     *   1월  그 번호로 '무인양품'만 결제       → 상호 하나 → 완화해도 오염될 대상이 없다
     *   2월  같은 번호로 '러쉬' 결제           → 완화로 '쇼핑'이 붙는다(틀렸다)
     *        사용자가 '러쉬'를 '미용'으로 고친다 → 사전이 그 번호를 두 중분류로 알게 된다
     *        ↳ **그 순간 복합이다.** 무인양품까지 미용이 되는 사고를 여기서 막는다.
     * </pre>
     *
     * <p>세는 것은 <b>확정만</b>이다. 추정끼리 갈렸다고 복합으로 보면, 모델이 한 번 흔들릴 때마다
     * 완화가 꺼져 사전 재사용이 무너진다 — 추정은 그런 무게를 질 수 없다.
     *
     * <p>질의가 늘지 않는다. 완화가 어차피 그 번호의 행을 전부 가져오므로 세기만 하면 된다.
     */
    private static boolean splitsIntoSeveralCategories(List<MerchantCategory> siblings) {
        return siblings.stream()
                .filter(MerchantCategory::isConfirmed)
                .map(MerchantCategory::getCategory2)
                .distinct()
                .limit(2)
                .count() >= 2;
    }

    /**
     * LLM 추정을 사전에 남긴다 — <b>같은 가맹점을 두 번 묻지 않기 위해서다.</b>
     *
     * <p>확정 행은 절대 덮지 않는다. 사실(국세청 등록)과 사람의 확인이 추정보다 위다.
     * 그리고 {@link #confirmFrom} 과 같은 이유로 <b>실제 사람의 결제일 때만</b> 쌓는다 —
     * 더미 사용자의 사업자번호는 생성기가 만든 것이라 실재하지 않는다.
     *
     * @return 남겼으면 그 행, 확정이 이미 있거나 더미 결제라 남기지 않았으면 empty
     */
    @Transactional
    public Optional<MerchantCategory> rememberGuess(UserPayment payment, String category2) {
        if (payment == null || !payment.isFromRealPerson()
                || category2 == null || category2.isBlank()) {
            return Optional.empty();
        }
        String normalized = MerchantCategory.normalize(payment.getBusinessNumber());
        final String biz = mapper.isPaymentAgency(normalized) ? "" : normalized;
        String name = payment.getMerchantName();
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }

        Optional<MerchantCategory> existing =
                repository.findByBusinessNumberAndMerchantName(biz, name);
        if (existing.isPresent()) {
            MerchantCategory row = existing.get();
            if (row.isConfirmed()) {
                return Optional.empty();     // 사실·사람의 확인을 추정으로 덮지 않는다
            }
            row.reclassify(category2, MerchantCategory.Source.LLM_GUESS, null);
            return Optional.of(row);
        }
        return Optional.of(repository.save(new MerchantCategory(
                biz, name, category2, MerchantCategory.Source.LLM_GUESS, null)));
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
        String normalized = MerchantCategory.normalize(businessNumber);
        // PG 번호는 <b>키에서 지운다</b> — 조회가 PG 번호를 버리는 것과 같은 규칙이라야 한다.
        // 지우지 않으면 사람이 "맞아요"를 눌러 준 넷플릭스가 (KG이니시스, 넷플릭스…) 로 박혀,
        // 같은 넷플릭스인데 NHNKCP 를 거친 4건에는 안 붙는다. 쌓이는 자리와 찾는 자리가
        // 어긋나면 사전이 커져도 적중이 안 는다 — 아무 오류도 없이.
        final String biz = mapper.isPaymentAgency(normalized) ? "" : normalized;
        return repository.findByBusinessNumberAndMerchantName(biz, merchantName)
                .map(existing -> {
                    existing.reclassify(category2, source, confirmedBy);
                    return existing;
                })
                .orElseGet(() -> repository.save(
                        new MerchantCategory(biz, merchantName, category2, source, confirmedBy)));
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

    /** 조회 서열 — 사람의 확인이 먼저다. 리포지토리의 {@code ORDER BY CASE source} 와 같은 값. */
    private static int sourceRank(MerchantCategory m) {
        String src = m.getSource();
        if (MerchantCategory.Source.USER_CONFIRMED.name().equals(src)) return 0;
        if (MerchantCategory.Source.USER_CSV.name().equals(src)) return 1;
        return 2;
    }

    /** 한 시점의 사전 사본. 적재하는 동안만 산다. */
    public static final class Snapshot {
        private final Map<String, String> exact = new HashMap<>();       // key(번호, 풀네임) → 중분류
        private final Map<String, String> byBusiness = new HashMap<>();  // 번호 → 중분류(PG 아닌 것만)
        private final Map<String, String> byNameOnly = new HashMap<>();  // 번호 없는 해외 가맹점
        // 추정층 — 확정과 **같은 키 규칙**으로 따로 담는다. 판정에는 안 쓰고, 연동할 때
        // 결제 행의 `category2_llm` 을 다시 칠하는 데만 쓴다.
        private final Map<String, String> guessExact = new HashMap<>();
        private final Map<String, String> guessByBusiness = new HashMap<>();
        private final Map<String, String> guessByNameOnly = new HashMap<>();
        private final IndustryCategoryMapper mapper;

        Snapshot(List<MerchantCategory> rows, IndustryCategoryMapper mapper) {
            this.mapper = mapper;
            // **사람의 확인 > 국세청 등록 > 추정**, 같은 등급이면 먼저 들어온 것 —
            // 조회(`findByBusinessNumberOrdered`)와 **같은 서열**이라야 한다. id 순으로만 두면
            // 먼저 들어온 씨앗이 영원히 이겨, 사용자가 고쳐도 안 고쳐진다(2026-08-05 티머니).
            // **갈린 번호는 완화층에 담지 않는다** — 조회(`find`)와 같은 규칙이라야 한다.
            //   조회는 막는데 적재는 담으면, 연동할 때 굳은 값과 화면이 다시 계산한 값이 갈린다.
            java.util.Map<String, java.util.Set<String>> confirmedKinds = new HashMap<>();
            rows.stream().filter(MerchantCategory::isConfirmed).forEach(m ->
                    confirmedKinds.computeIfAbsent(m.getBusinessNumber(), k -> new java.util.TreeSet<>())
                            .add(m.getCategory2()));

            rows.stream().sorted(java.util.Comparator
                            .comparingInt(MerchantCategoryService::sourceRank)
                            .thenComparing(MerchantCategory::getId,
                                    java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                    .forEach(m -> {
                        // 추정은 스냅샷에 담지 않는다. 여기 담긴 값은 적재 때 Consumption 의
                        // 카테고리로 **굳어** 리포트·판정에 그대로 쓰이므로, 사람이 확인하지
                        // 않은 추정이 섞이면 원칙 1(판단은 설명가능한 모델이)이 깨진다.
                        String biz = m.getBusinessNumber();
                        if (!m.isConfirmed()) {
                            // 추정은 판정층에 담지 않는다 — 담기면 Consumption 카테고리로 굳는다.
                            guessExact.putIfAbsent(key(biz, m.getMerchantName()), m.getCategory2());
                            if (biz.isEmpty()) {
                                guessByNameOnly.putIfAbsent(m.getMerchantName(), m.getCategory2());
                            } else if (!mapper.isPaymentAgency(biz)) {
                                guessByBusiness.putIfAbsent(biz, m.getCategory2());
                            }
                            return;
                        }
                        exact.putIfAbsent(key(biz, m.getMerchantName()), m.getCategory2());
                        if (biz.isEmpty()) {
                            byNameOnly.putIfAbsent(m.getMerchantName(), m.getCategory2());
                        } else if (!mapper.isPaymentAgency(biz) && !mapper.isMultiBusiness(biz)
                                && confirmedKinds.getOrDefault(biz, java.util.Set.of()).size() < 2) {
                            // PG·복합 사업자 번호는 여기 담지 않는다. 담는 순간 그 번호를 거친
                            // 모든 결제가 한 분류로 오염된다 — lookup 이 둘을 걸러 내는 것과
                            // 같은 이유다. 두 곳의 규칙이 갈리면 연동할 때마다 답이 달라진다.
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
            // 번호가 없거나(해외) PG 의 것이면 이름으로 본다 — lookup ②·③ 과 같은 규칙이다.
            if (biz.isEmpty() || mapper.isPaymentAgency(biz)) {
                return Optional.ofNullable(byNameOnly.get(merchantName));
            }
            return Optional.ofNullable(byBusiness.get(biz));
        }

        /**
         * <b>이 가맹점을 이미 물어봤는가</b> — 물어봤다면 그때의 추정.
         *
         * <p>재연동은 결제 행을 통째로 지우고 다시 만든다. 추정이 결제 행에만 있으면 그때
         * 전부 날아가고, 사용자는 <i>"AI가 분류했다더니 안 보인다"</i>를 겪는다 —
         * 사전에는 멀쩡히 남아 있는데도 그렇다(2026-08-05 운영에서 실제로 발생).
         * 복구를 별도 화면 방문에 맡기지 않고 <b>연동할 때 같이 칠한다.</b>
         */
        public Optional<String> guess(String businessNumber, String merchantName) {
            if (merchantName == null || merchantName.isBlank()) return Optional.empty();
            String biz = MerchantCategory.normalize(businessNumber);
            String hit = guessExact.get(key(biz, merchantName));
            if (hit != null) return Optional.of(hit);
            if (biz.isEmpty() || mapper.isPaymentAgency(biz)) {
                return Optional.ofNullable(guessByNameOnly.get(merchantName));
            }
            return Optional.ofNullable(guessByBusiness.get(biz));
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
