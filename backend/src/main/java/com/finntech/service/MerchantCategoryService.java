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
    /** 브랜드 대기 장소 — 사전에 들어오는 순간 이쪽에서 옮겨 온다. */
    private final MerchantBrandService brands;
    /** 사람의 확정을 한 표로 세는 곳(V30). 전역 분류는 다수가 정한다. */
    private final MerchantCategoryVoteService votes;

    public MerchantCategoryService(MerchantCategoryRepository repository,
                                   IndustryCategoryMapper mapper,
                                   BusinessNumberKindService kinds,
                                   MerchantBrandService brands,
                                   MerchantCategoryVoteService votes,
                                   java.time.Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.kinds = kinds;
        this.brands = brands;
        this.votes = votes;
        this.clock = clock;
    }

    /** 백오프가 시각을 읽는다. 엔진처럼 {@code now()} 를 직접 안 읽는다(§4 원칙 3 재현성). */
    private final java.time.Clock clock;

    /**
     * 사전·표가 함께 쓰는 <b>키 규칙</b> — PG 번호는 남의 것이라 지운다.
     *
     * <p>한 곳에만 둔다. 쌓는 자리와 찾는 자리와 <b>표를 세는 자리</b>가 같은 키를 써야
     * 한다 — 갈리면 표는 쌓이는데 사전은 못 찾는, 오류 없는 실패가 난다.
     */
    public String keyOf(String businessNumber) {
        String normalized = MerchantCategory.normalize(businessNumber);
        return mapper.isPaymentAgency(normalized) ? "" : normalized;
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
        // **추정만** 본다. `!isConfirmed()` 로 두면 시도 이력 행(ATTEMPTED — 분류가 아니라
        // "건드려 봤다"는 기록)까지 걸려 들어 '카테고리없음'이 추정인 양 화면에 나간다.
        Optional<String> byKey = find(businessNumber, merchantName, MerchantCategory::isGuess)
                .map(MerchantCategory::getCategory2);
        if (byKey.isPresent()) return byKey;
        // **브랜드로 한 번 더 본다.** 사전 키가 (번호, 풀네임)이라 같은 브랜드의 새 지점은
        // 매번 새 항목이다 — 그 브랜드가 만장일치면 다시 물을 이유가 없다(2026-08-21).
        return brands.usableBrandOf(merchantName).flatMap(this::byBrand);
    }

    /** 브랜드가 사전에서 만장일치일 때의 중분류 — 아니면 비어 있다. */
    public Optional<String> byBrand(String brand) {
        if (brand == null || brand.isBlank() || NO_BRAND.equals(brand)) return Optional.empty();
        return snapshot().guessOfBrand(brand);
    }

    /**
     * <b>이 가맹점에 더 손댈 필요가 있는가</b> — 조회·질문을 시작하기 전에 묻는다.
     *
     * <p>여기서 막지 않으면 연동할 때마다 같은 번호를 조회하고 같은 상호를 다시 묻는다.
     * 사전이 커져도 바깥 호출이 안 줄어드는 것이 그 증상이다.
     *
     * <p><b>추정만 있는 것은 아직 할 일이 남은 것으로 본다.</b> 조회로 사실을 얻으면 추정을
     * 덮을 수 있기 때문이다 — 사실이 추정을 이긴다.
     */
    @Transactional(readOnly = true)
    public boolean needsWork(String businessNumber, String merchantName) {
        if (merchantName == null || merchantName.isBlank()) return false;
        return find(businessNumber, merchantName, m -> true)
                .map(m -> !m.isConfirmed())     // 확정(사람·조회)도 종결('기타')도 아니면 남았다
                .orElse(true);                  // 처음 보는 가맹점
    }

    /**
     * <b>지금 등록 업종 조회를 걸어도 되는가</b> — {@link #needsWork} 에 쉬는 시간을 얹은 것.
     *
     * <p>{@code needsWork} 를 그대로 고치지 않은 이유는 <b>그 판단을 LLM 통로도 쓰기 때문</b>이다.
     * 시각 기록({@code lastAttemptAt})은 두 통로가 나눠 쓰므로, 거기에 백오프를 걸면
     * 모델에 물어본 것이 조회를 막고 그 반대도 된다. 끝나는 조건이 다른 둘을 한 시계로 묶으면
     * 안 된다 — 그래서 <b>부르는 자리에서만</b> 얹는다.
     */
    @Transactional(readOnly = true)
    public boolean needsRegistryLookup(String businessNumber, String merchantName) {
        if (!needsWork(businessNumber, merchantName)) return false;
        return find(businessNumber, merchantName, m -> true)
                .map(m -> !backingOff(m))
                .orElse(true);
    }

    /**
     * <b>물어봤는데 답이 없던 곳은 쉬었다 묻는다.</b>
     *
     * <p>여기가 없어서 답 없는 가맹점을 <b>영원히 2분마다</b> 다시 물었다. 운영 로그가 그대로
     * 보여줬다 — {@code 대상 33, 물어본 곳 24, 분류된 가맹점 0} 이 끝없이 반복됐고, 하루 약
     * 7,000회가 남의 서버로 헛나갔다(2026-08-13 실측).
     *
     * <p>원인은 <b>시도 이력 행이 "아직 할 일 남음"으로 판정된 것</b>이다. 시도를 적어 두면서도
     * 그 기록을 읽지 않았으니, 기록은 있고 효과는 없었다. 클래스 주석은 "시도 이력이 남아 다시
     * 묻지 않는다"고 적혀 있었다 — 문서와 동작이 갈라져 있었다.
     *
     * <h2>영구히 막지는 않는다</h2>
     *
     * <p>지금 답이 없다고 앞으로도 없는 것은 아니다 — 사업자가 나중에 등록될 수도 있고,
     * 조회처가 고쳐질 수도 있다. 그래서 <b>간격만 벌린다</b>: 시도 1회면 한 시간, 2회면 두 시간,
     * 이런 식으로 두 배씩 늘리되 <b>하루에서 멈춘다</b>. 24곳을 하루 한 번 묻는 것은 낭비가 아니다.
     *
     * <p><b>답을 얻은 곳은 이 판단에 오지 않는다</b> — 확정이 되어 {@code isConfirmed()} 에서
     * 이미 걸러진다. 여기 오는 것은 "물었는데 못 얻은" 곳뿐이다.
     */
    private boolean backingOff(MerchantCategory row) {
        java.time.LocalDateTime last = row.getLastAttemptAt();
        if (last == null) return false;                 // 아직 한 번도 안 물어봤다
        int attempts = Math.max(1, row.getLookupAttempts());
        // 1회 1시간 · 2회 2시간 · 3회 4시간 … 상한 24시간. 지수라 금방 하루에 닿는다.
        long hours = Math.min(BACKOFF_MAX_HOURS, 1L << Math.min(attempts - 1, 20));
        return last.plusHours(hours).isAfter(java.time.LocalDateTime.now(clock));
    }

    /** 아무리 여러 번 실패해도 하루에 한 번은 다시 묻는다 — 영구 차단이 아니다. */
    private static final long BACKOFF_MAX_HOURS = 24;

    /**
     * 시도를 적을 행을 가져온다 — 없으면 <b>시도 이력 행</b>을 만든다(분류가 아니다).
     *
     * <p>{@link #confirmFrom}·{@link #rememberGuess} 와 같은 이유로 <b>실제 사람의 결제일
     * 때만</b> 만든다. 더미 결제에도 실재하는 사업자번호가 섞여 있어 조회는 성공할 수 있는데,
     * 그렇게 들어온 행은 아무도 결제한 적 없는 가맹점을 사전에 앉힌다.
     */
    @Transactional
    public Optional<MerchantCategory> attemptRow(UserPayment payment) {
        if (payment == null || !payment.isFromRealPerson()) return Optional.empty();
        String name = payment.getMerchantName();
        if (name == null || name.isBlank()) return Optional.empty();
        final String biz = keyOf(payment.getBusinessNumber());
        MerchantCategory row = repository.findByBusinessNumberAndMerchantName(biz, name)
                .orElseGet(() -> repository.save(new MerchantCategory(
                        biz, name, com.finntech.engine.IndustryCategoryMapper.UNCLASSIFIED,
                        MerchantCategory.Source.ATTEMPTED, null,
                        null)));   // 시도 이력이다 — 표에서 유도된 것이 없다(V29)

        // 이 가맹점이 사전에 자리를 얻었으니 대기 장소의 브랜드를 옮기고 그쪽은 지운다.
        // **attemptRow·rememberGuess·rememberRegistry 도 confirm 과 같아야 한다** — 한 곳만 옮기면 브랜드가 두 곳에 남아
        // 어느 쪽이 정본인지 알 수 없게 된다(2026-08-07 감사에서 발견).
        brands.promote(row);
        return Optional.of(row);
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
        final String biz = keyOf(payment.getBusinessNumber());
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
            // **추정에는 업종코드를 적지 않는다**(V29). 모델이 답한 업종 이름을 표로 옮겨
            // 코드를 역산하는 것은 되지만, 그러면 그 칸이 "표에서 유도"와 "모델의 말을 표로
            // 옮김"을 한꺼번에 담아 읽을 수 없게 된다.
            row.reclassify(category2, MerchantCategory.Source.LLM_GUESS, null, null);
            brands.promote(row);
            return Optional.of(row);
        }
        MerchantCategory row = repository.save(new MerchantCategory(
                biz, name, category2, MerchantCategory.Source.LLM_GUESS, null, null));
        brands.promote(row);
        return Optional.of(row);
    }

    /**
     * <b>조회를 한 번 했다고 적는다</b> — 답을 얻었으면 그 업종 이름도 같이.
     *
     * <p>{@code industryName} 이 null 이어도 부른다. 조회처가 모른다고 답한 것도 시도이고,
     * 그것까지 세야 죽은 통로를 눈치챌 수 있다.
     */
    @Transactional
    public void noteLookup(UserPayment payment, String industryName, java.time.LocalDateTime at) {
        attemptRow(payment).ifPresent(row -> row.noteLookup(industryName, at));
    }

    /**
     * 조회처가 알려 준 <b>주소</b>를 사전에 적는다 — 업종과 같은 문서에서 온 것이다.
     *
     * <p>사전에 자리가 없으면 만들지 않는다. 주소만 아는 가맹점을 사전에 앉히면 <i>"이 점포의
     * 업종이 무엇인가"</i> 라는 약속이 깨진다(브랜드에 대기 장소를 따로 둔 것과 같은 이유).
     * 업종을 알아내는 흐름이 자리를 만들고, 주소는 그 자리에 얹힌다.
     *
     * @return 새로 적었으면 true
     */
    @Transactional
    public boolean rememberAddress(String businessNumber, String merchantName, String address) {
        if (address == null || address.isBlank()) return false;
        String biz = MerchantCategory.normalize(businessNumber);
        if (biz.isEmpty() || mapper.isPaymentAgency(biz)) return false;   // PG 번호는 남의 것이다
        boolean[] wrote = {false};
        repository.findByBusinessNumberAndMerchantName(biz, merchantName)
                .ifPresent(row -> wrote[0] = row.noteAddress(address));
        return wrote[0];
    }

    /** 주소가 아직 없는 사전 행 — 순차 백필이 채울 대상이다. */
    @Transactional(readOnly = true)
    public List<MerchantCategory> missingAddress(int limit) {
        return repository.findMissingAddress(
                org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit)));
    }

    /**
     * <b>LLM 에 물었는데 답이 없었다고 적는다</b> — {@value MerchantCategory#GIVE_UP_AFTER}회째면 '기타'로 종결한다.
     *
     * <p>답을 얻은 경우는 여기 오지 않는다({@link #rememberGuess} 가 받는다). 세는 것은
     * <b>헛물켠 질문</b>뿐이다.
     *
     * @return 이번 호출로 '기타'가 됐으면 true
     */
    @Transactional
    public boolean noteLlmMiss(UserPayment payment, java.time.LocalDateTime at) {
        return attemptRow(payment)
                .filter(row -> !row.isConfirmed() || row.settledAsOther())
                .map(row -> row.noteLlmMiss(at))
                .orElse(false);
    }

    /**
     * <b>등록 업종 조회로 얻은 분류를 사전에 남긴다</b>(순위 ②-b) — 같은 가맹점을 두 번 묻지 않는다.
     *
     * <p>남기는 것이 이 통로의 절반이다. 조회는 남의 서버를 두드리는 일이라 매번 하면 느리고
     * 무례하고 언제 막힐지 모른다. 한 번 알아낸 사실을 사전에 넣어 두면 그다음부터는
     * {@link #lookup} 이 바로 답하므로 조회가 아예 일어나지 않는다 —
     * {@link Source#LLM_GUESS} 를 남기는 이유와 같은 이유다.
     *
     * <p><b>사람이 정한 것은 덮지 않는다.</b> 등록 업종은 사실이지만 "이 결제가 무엇에 쓴 돈인가"에
     * 대한 답은 아니라, 사용자가 직접 고친 것이 위에 있다. 반대로 <b>추정은 덮는다</b> —
     * 사실이 추정을 이긴다.
     *
     * <p>그리고 {@link #confirmFrom}·{@link #rememberGuess} 와 같은 이유로 <b>실제 사람의 결제일
     * 때만</b> 쌓는다. 더미 사용자의 결제에도 실재하는 사업자번호가 섞여 있어 조회 자체는
     * 성공할 수 있는데, 그렇게 들어온 행은 <i>"이 사전은 실제 명세서에서 자란다"</i>는 약속을
     * 어긴다 — 아무도 결제한 적 없는 가맹점이 사전에 앉는다.
     *
     * <p><b>업종코드도 함께 남긴다</b>(V29). 이 분류는 표에서 유도된 것이므로 그 원재료를
     * 적어 두어야 나중에 대조표를 고쳤을 때 다시 계산할 수 있다 — 이 통로로 들어온 행은
     * 씨앗 파일에 없어 <b>DB 에만 살기 때문에</b>, 여기서 안 적으면 재계산할 길이 영영 없다.
     *
     * @param ntsCodes 이 중분류를 낳은 국세청 업종코드들({@code IndustryCategoryMapper.codesOfFineName})
     * @return 남겼으면 그 행, 사람이 정한 것이 이미 있거나 더미 결제라 남기지 않았으면 empty
     */
    @Transactional
    public Optional<MerchantCategory> rememberRegistry(UserPayment payment, String category2,
                                                       List<String> ntsCodes) {
        if (payment == null || !payment.isFromRealPerson()
                || category2 == null || category2.isBlank()) {
            return Optional.empty();
        }
        String merchantName = payment.getMerchantName();
        if (merchantName == null || merchantName.isBlank()) {
            return Optional.empty();
        }
        // PG 번호는 조회 단계에서 이미 걸러지지만, 쌓는 자리와 찾는 자리의 규칙을 같게 둔다.
        final String biz = keyOf(payment.getBusinessNumber());

        Optional<MerchantCategory> existing =
                repository.findByBusinessNumberAndMerchantName(biz, merchantName);
        if (existing.isPresent()) {
            MerchantCategory row = existing.get();
            if (row.isConfirmed() && !MerchantCategory.Source.REGISTRY.name().equals(row.getSource())) {
                return Optional.empty();     // 사람이 준 것·확인한 것은 그대로 둔다
            }
            row.reclassify(category2, MerchantCategory.Source.REGISTRY, null, ntsCodes);
            brands.promote(row);
            return Optional.of(row);
        }
        MerchantCategory row = repository.save(new MerchantCategory(
                biz, merchantName, category2, MerchantCategory.Source.REGISTRY, null, ntsCodes));
        brands.promote(row);
        return Optional.of(row);
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
     * <p><b>덮어쓰지 않는다 — 한 표를 던진다</b>(V30). 예전에는 여기가 곧장 전역 분류를 갈아
     * 끼웠고, 그래서 <b>마지막에 누른 사람이 이겼다</b> — 앞사람의 판단은 흔적도 없이 사라졌다.
     * 사람은 오분류하고 착각하는데 사전은 전역 자산이라 한 번의 실수가 모두에게 간다.
     * 이제 표를 적고 <b>다수가 정한 값</b>만 사전에 앉는다({@link MerchantCategoryVoteService}).
     *
     * <p><b>져도 잃는 것이 없다.</b> 본인의 결제는 {@code category2_source='USER'} 가 지키고
     * 사전은 그것을 덮지 않는다 — 전역은 <i>처음 보는 결제에 붙는 기본값</i>일 뿐이다.
     * 그래서 동률이어도 행을 돌려준다: 부르는 쪽이 <b>본인의</b> 나머지 결제를 자기 표로
     * 맞춰야 하기 때문이다.
     *
     * @return 사전에서 이 가맹점의 행(동률이라 안 바뀌었어도 준다),
     *         더미 결제라 거절했으면 {@link Optional#empty()}
     */
    @Transactional
    public Optional<MerchantCategory> confirmFrom(UserPayment payment, String category2,
                                                  Long confirmedBy) {
        if (payment == null || !payment.isFromRealPerson()) {
            return Optional.empty();
        }
        String name = payment.getMerchantName();
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        // 표의 키와 사전의 키는 **같은 규칙**이라야 한다 — PG 번호는 양쪽에서 지워진다.
        final String biz = keyOf(payment.getBusinessNumber());

        Optional<String> majority = votes.castAndTally(biz, name, confirmedBy, category2);
        if (majority.isEmpty()) {
            // **동률이면 아무것도 안 바꾼다.** 갈렸다는 것은 그 가맹점이 사람마다 다르게
            // 보인다는 뜻이고(배달의민족 — 등록은 통신판매업, 쓴 돈은 밥값), 그럴 때 억지로
            // 고르면 진 쪽의 전역 화면이 이유 없이 흔들린다.
            return repository.findByBusinessNumberAndMerchantName(biz, name);
        }
        String winner = majority.get();
        // **이긴 값에 동의한 사람만 `confirmedBy` 에 남는다.** 진 표를 적으면 그 행은
        // "이 사람이 정했다"고 말하는데 값은 그 사람 것이 아니게 된다.
        Long credited = winner.equals(category2)
                ? confirmedBy
                : repository.findByBusinessNumberAndMerchantName(biz, name)
                        .map(MerchantCategory::getConfirmedBy).orElse(null);
        return Optional.of(confirm(biz, name, winner,
                MerchantCategory.Source.USER_CONFIRMED, credited));
    }

    /** 그 사람이 이 가맹점에 던진 표 — 본인 결제는 전역 다수보다 이것이 먼저다. */
    @Transactional(readOnly = true)
    public Optional<String> voteOf(String businessNumber, String merchantName, Long userId) {
        return votes.voteOf(keyOf(businessNumber), merchantName, userId);
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
        // PG 번호는 <b>키에서 지운다</b>({@link #keyOf}) — 조회가 PG 번호를 버리는 것과 같은
        // 규칙이라야 한다. 지우지 않으면 사람이 "맞아요"를 눌러 준 넷플릭스가 (KG이니시스,
        // 넷플릭스…) 로 박혀, 같은 넷플릭스인데 NHNKCP 를 거친 4건에는 안 붙는다. 쌓이는
        // 자리와 찾는 자리가 어긋나면 사전이 커져도 적중이 안 는다 — 아무 오류도 없이.
        final String biz = keyOf(businessNumber);
        // **업종코드를 지운다**(V29). 여기로 오는 것은 사람이 화면에서 정한 것이거나 CSV 일괄
        // 적재다. 사람의 판단은 표에서 유도된 것이 아니므로 근거 칸이 비는 것이 정상이고,
        // 옛 코드를 남기면 지금 값과 무관한 근거가 붙어 있는 행이 된다. 씨앗 적재는 자기 코드를
        // 직접 넣으므로(load_merchant_seed.py) 이 경로를 타지 않는다.
        MerchantCategory row = repository.findByBusinessNumberAndMerchantName(biz, merchantName)
                .map(existing -> {
                    existing.reclassify(category2, source, confirmedBy, null);
                    return existing;
                })
                .orElseGet(() -> repository.save(
                        new MerchantCategory(biz, merchantName, category2, source, confirmedBy, null)));
        // 이 가맹점이 사전에 들어왔으니 대기 장소의 브랜드를 옮기고 그쪽은 지운다 —
        // 두 곳에 남으면 어느 쪽이 정본인지 알 수 없게 된다.
        brands.promote(row);
        return row;
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
    /**
     * 브랜드 추출기가 "브랜드가 없다"를 <b>값으로</b> 적어 둔 것 — 브랜드가 아니다.
     *
     * <p>{@code null} 이 아니라 이 문자열이 들어 있는 행이 실제로 있다(2026-08-21 실측:
     * {@code 사당쌀빵}·{@code 황금마차}·{@code 고향양꼬치}). 브랜드로 취급하면 서로 무관한
     * 가맹점들이 한 덩어리가 된다.
     */
    static final String NO_BRAND = "브랜드없음";

    /** 브랜드로 인정할 최소 지점 수 — 하나뿐이면 브랜드가 아니라 그냥 그 가맹점이다. */
    static final int BRAND_MIN_BRANCHES = 2;

    public static final class Snapshot {
        private final Map<String, String> exact = new HashMap<>();       // key(번호, 풀네임) → 중분류
        private final Map<String, String> byBusiness = new HashMap<>();  // 번호 → 중분류(PG 아닌 것만)
        private final Map<String, String> byNameOnly = new HashMap<>();  // 번호 없는 해외 가맹점
        // 추정층 — 확정과 **같은 키 규칙**으로 따로 담는다. 판정에는 안 쓰고, 연동할 때
        // 결제 행의 `category2_llm` 을 다시 칠하는 데만 쓴다.
        private final Map<String, String> guessExact = new HashMap<>();
        private final Map<String, String> guessByBusiness = new HashMap<>();
        private final Map<String, String> guessByNameOnly = new HashMap<>();
        /**
         * 브랜드 → 중분류. <b>그 브랜드의 모든 지점이 같은 답일 때만</b> 담는다.
         *
         * <p>사전 키가 {@code (사업자번호, 가맹점명 전체)} 라서 <b>같은 브랜드의 새 지점마다
         * 처음부터 다시 묻는다</b>. 실측(2026-08-21): 사전에 CU 20지점·세븐일레븐 11지점·
         * 이마트24 10지점이 전부 {@code 편의점/잡화} 하나로 만장일치인데, 라진우의
         * {@code 씨유(CU)낙원점} 은 미분류였다. 그 사람 미분류 40종 중 12종이 이 경우였다.
         *
         * <p>사용자가 늘수록 빨라져야 하는데, 브랜드 재사용이 없으면 같은 값을 계속 다시 치른다.
         */
        private final Map<String, String> guessByBrand = new HashMap<>();
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

            // **브랜드가 만장일치일 때만 담는다.** 한 브랜드에 두 분류가 섞여 있으면
            // (실측: GS25 에 쇼핑·식비·편의점/잡화 셋) 그 브랜드로는 아무것도 말할 수 없다.
            // 지점이 하나뿐인 브랜드도 담지 않는다 — 그것은 그냥 그 가맹점이지 브랜드가 아니다.
            java.util.Map<String, java.util.Set<String>> brandKinds = new HashMap<>();
            java.util.Map<String, Integer> brandCounts = new HashMap<>();
            for (MerchantCategory m : rows) {
                String brand = m.getBrand();
                if (brand == null || brand.isBlank() || NO_BRAND.equals(brand)) continue;
                if (m.getCategory2() == null || IndustryCategoryMapper.isUnknown(m.getCategory2())) continue;
                brandKinds.computeIfAbsent(brand, k -> new java.util.TreeSet<>()).add(m.getCategory2());
                brandCounts.merge(brand, 1, Integer::sum);
            }
            brandKinds.forEach((brand, kinds) -> {
                if (kinds.size() != 1 || brandCounts.getOrDefault(brand, 0) < BRAND_MIN_BRANCHES) return;
                guessByBrand.put(brand, kinds.iterator().next());
            });

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
        /** 브랜드가 만장일치일 때의 중분류. {@link #guessByBrand} 참조. */
        public Optional<String> guessOfBrand(String brand) {
            if (brand == null || brand.isBlank() || NO_BRAND.equals(brand)) return Optional.empty();
            return Optional.ofNullable(guessByBrand.get(brand));
        }

        public Optional<String> guess(String businessNumber, String merchantName) {
            return guess(businessNumber, merchantName, null);
        }

        /**
         * @param brand 이 가맹점의 브랜드. 이름·번호로 못 찾았을 때 <b>마지막으로</b> 본다 —
         *              같은 브랜드의 지점이 사전에서 만장일치면 그 값을 쓴다.
         */
        public Optional<String> guess(String businessNumber, String merchantName, String brand) {
            if (merchantName == null || merchantName.isBlank()) return Optional.empty();
            String biz = MerchantCategory.normalize(businessNumber);
            String hit = guessExact.get(key(biz, merchantName));
            if (hit != null) return Optional.of(hit);
            String byKey = (biz.isEmpty() || mapper.isPaymentAgency(biz))
                    ? guessByNameOnly.get(merchantName)
                    : guessByBusiness.get(biz);
            if (byKey != null) return Optional.of(byKey);
            // **브랜드는 맨 마지막이다.** 이름·번호로 짚이는 것이 있으면 그것이 더 가깝다.
            if (brand == null || brand.isBlank() || NO_BRAND.equals(brand)) return Optional.empty();
            return Optional.ofNullable(guessByBrand.get(brand));
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
