package com.finntech.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 확정 분류 사전 한 줄 — <b>(사업자번호, 가맹점 풀네임) → 중분류</b>.
 *
 * <p>실제 명세서에는 업종코드가 없어 그대로 넣으면 전부 '카테고리없음'이 된다. 사람이 확실하게
 * 정해 준 것을 여기 쌓아 두고 <b>같은 가맹점을 두 번 묻지 않는다.</b>
 *
 * <p><b>키가 풀네임인 것이 설계의 전부다.</b> {@code GS25} 가 아니라 {@code GS25 강남역점} 이
 * 들어간다. 그래서 PG(전자지급결제대행)를 거친 결제도 안전하다 —
 * {@code KG모빌리언스 번호 + 삼성물산리조트(주)에버랜드} 와 {@code 같은 번호 + 다른 가맹점} 은
 * 서로 다른 행이라, 한 PG 에 업종 하나가 박히는 사고가 구조적으로 안 난다.
 *
 * <p>사람마다 나누지 않는다. {@link UserMerchantStance}(<i>"이 가게가 <b>나에게</b> 낭비인가"</i>)와
 * 달리 여기 담기는 것은 <i>"이 점포의 업종이 무엇인가"</i> 라 <b>사람에 따라 달라지지 않는 사실</b>이다.
 * 그래서 전역이고 만료도 없다 — 캐시가 아니라 자산이다.
 */
@Entity
@Table(name = "merchant_category")
public class MerchantCategory {

    /** 이 분류가 어디서 왔나. <b>LLM 추정만으로는 확정이 되지 못한다</b> — 그 하나만 예외다. */
    public enum Source {
        /** 사용자가 업종코드를 직접 준 것. 국세청 등록 정보라 추정이 아니라 사실이다. */
        USER_CSV,
        /** LLM 추정을 사람이 "맞다"고 확인한 것. */
        USER_CONFIRMED,
        /**
         * <b>사업자등록번호로 등록 업종을 조회해 얻은 것</b>(분류 순위 ②-b).
         *
         * <p>추정이 아니라 <b>사실</b>이라 확정으로 들어온다. 얻은 것은 국세청에 등록된 업종이고,
         * 거기서 중분류로 옮기는 일은 사람이 검토한 대조표({@code nts-mid.tsv})가 한다 —
         * 모델이 끼는 자리가 없다(마스터 §4 원칙 1). 정답을 이미 아는 가맹점 28곳에 걸어 본
         * 실측에서 <b>붙은 20건이 전부 맞았다</b>(2026-08-07).
         *
         * <p>그래도 사람 아래다. 등록 업종은 "이 사업자가 무슨 일을 하는가"이지 "이 결제가 무엇에
         * 쓴 돈인가"가 아니라서, 사람이 고치면 그 판단이 이긴다({@link #USER_CONFIRMED} 로 덮인다).
         */
        REGISTRY,
        /**
         * <b>LLM 이 추정만 한 것 — 확정이 아니다.</b> 판정에 참여하지 않으며
         * {@code lookup} 이 이 행을 돌려주지 않는다. 여기 있는 이유는 하나다:
         * <b>같은 가맹점을 두 번 묻지 않기 위해서</b>. 추정을 결제 행에만 적어 두면
         * 다음 달 같은 넷플릭스가 새 결제로 들어올 때 또 묻게 된다.
         *
         * <p>사람이 나중에 "맞아요"를 누르면 이 행이 {@link #USER_CONFIRMED} 로 승격되고,
         * 국세청 등록 정보가 들어오면 {@link #USER_CSV} 가 덮는다 — 사실이 추정을 이긴다.
         */
        LLM_GUESS,
        /**
         * <b>분류가 아니라 시도 이력이다</b> — "이 가맹점을 건드려 봤다"만 말한다.
         *
         * <p>이 행이 없으면 <b>같은 일을 매번 다시 한다.</b> 조회처에 물어 답을 받았지만 그 업종이
         * 소비 업종이 아니어서 못 붙였을 때, 결과를 어디에도 남기지 않으면 연동할 때마다 같은
         * 번호를 다시 조회한다. 붙은 것만 사전에 쌓고 못 붙인 것을 버리면, 사전이 커져도
         * 바깥 호출은 안 줄어든다.
         *
         * <p>분류로 취급하지 않는다 — {@code category2} 는 카테고리없음이고 조회에도 안 잡힌다.
         * 여기 담기는 것은 {@code lookupAttempts}·{@code llmAttempts}·{@code registryIndustry} 다.
         */
        ATTEMPTED,
        /**
         * <b>다 해 봤지만 알 수 없었다</b> — 종결 상태이며 {@code category2} 는 '기타'다.
         *
         * <p>기준은 <b>LLM 질문 {@value #GIVE_UP_AFTER}회</b>다. 조회 실패는 종결 사유가 아니다 —
         * 조회처가 모른다고 LLM 도 모르는 것이 아니고, LLM 은 다시 물으면 답이 달라질 수 있어
         * 한두 번으로 단정할 수 없기 때문이다(사용자 확인 2026-08-07). 그만큼 물어도 안 되면
         * 더 묻는 것은 비용만 든다.
         *
         * <p>'카테고리없음'과 갈라 두는 것이 요점이다. 그 값 하나가 <i>"아직 안 물어봤다"</i>와
         * <i>"다 물어봤는데 모른다"</i>를 같이 담고 있었고, 그래서 뒤엣것을 매번 다시 처리했다.
         *
         * <p><b>영구가 아니다.</b> 사람이 화면에서 고치면 {@link #USER_CONFIRMED} 가 덮고
         * {@code confirmedBy}·{@code updatedAt} 에 그 사실이 남는다.
         */
        UNRESOLVED
    }

    /** 이만큼 LLM 에 묻고도 답이 없으면 '기타'로 종결한다. */
    public static final int GIVE_UP_AFTER = 3;

    /**
     * 판정에 참여할 수 있는 출처인가 — 추정과 시도 이력은 아니다(마스터 §4 원칙 1).
     *
     * <p>{@link Source#UNRESOLVED} 는 <b>참여한다</b>. '기타'는 추정이 아니라 <i>"다 해 봤지만
     * 알 수 없었다"</i>는 결론이고, 그 결론이 나와야 화면이 같은 결제를 계속 '카테고리없음'으로
     * 두지 않는다. 낭비 판정에서 빠지는 것은 출처가 아니라 <b>값</b>이 정한다 —
     * {@code IndustryCategoryMapper.isUnknown} 이 '카테고리없음'과 '기타'를 함께 뺀다.
     */
    public boolean isConfirmed() {
        return !Source.LLM_GUESS.name().equals(source) && !Source.ATTEMPTED.name().equals(source);
    }

    /** LLM 추정인가 — {@code guess} 가 이 행만 돌려준다. 시도 이력과 섞이면 안 된다. */
    public boolean isGuess() {
        return Source.LLM_GUESS.name().equals(source);
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 하이픈 없는 10자리. 해외 본사처럼 번호가 없으면 <b>빈 문자열</b>이다 —
     * {@code null} 로 두면 UNIQUE 가 NULL 끼리 다르다고 봐서 같은 가맹점이 여러 번 쌓인다.
     */
    @Column(name = "business_number", nullable = false, length = 10)
    private String businessNumber = "";

    /** <b>풀네임</b>. 'GS25' 가 아니라 'GS25 강남역점'. */
    @Column(name = "merchant_name", nullable = false, length = 120)
    private String merchantName;

    @Column(name = "category2", nullable = false, length = 30)
    private String category2;

    @Column(name = "source", nullable = false, length = 20)
    private String source;

    /**
     * <b>소분류</b> — 중분류보다 작고 브랜드보다 큰 칸.
     *
     * <p>사전이 이 값을 들고 있어야 원장 재작성이 싸다. 그리고 <b>{@code category2} 가
     * {@code midOfSub(category3)} 와 다르면 그 자체가 오분류의 증거다</b> — 소분류가 정확히
     * 한 중분류에만 속한다는 불변식의 대우라서 따로 판단할 것이 없다.
     */
    @Column(name = "category3", length = 30)
    private String category3;

    /** 오입력을 되돌릴 때 근거가 된다. CSV 적재분은 사람이 없어 null 이다. */
    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 조회처가 말한 <b>등록 업종 이름</b> — 붙였든 못 붙였든 남긴다.
     *
     * <p>못 붙인 것을 남기는 이유가 더 크다. '아파트 건설업'이라는 답을 받고도 버리면 다음
     * 연동에서 같은 번호를 또 조회한다. 그리고 이 이름은 <b>LLM 에게 줄 근거</b>이기도 하다 —
     * 상호만 주는 것보다 "등록 업종은 이렇다"를 같이 주는 편이 답이 낫다.
     */
    @Column(name = "registry_industry", length = 80)
    private String registryIndustry;

    /**
     * 모델이 말한 <b>업종 이름</b>(V43) — 추정이다.
     *
     * <p><b>{@link #registryIndustry} 와 갈라 둔다.</b> 그 칸은 국세청 등록 업종, 곧 사실이고
     * {@link #registryAnswered()} 가 그 칸으로 <i>"조회를 이미 했다"</i>를 판정한다. 추정을
     * 거기 적으면 순위가 뒤집힌다 — ③ 추정이 ②-b 등록 조회(사실)를 영영 막는다(§13-12).
     *
     * <p>이 칸이 없던 동안 {@code rememberGuess} 는 모델의 답에서 중분류만 계산하고 이름을
     * 버렸다. 그래서 브랜드가 안 붙는 개인 상호는 소분류를 찾을 길이 없었다 — 운영 사전에서
     * 브랜드도 소분류도 없는 <b>280곳 중 260곳</b>에 업종 이름이 아예 없었다(2026-08-25 실측).
     */
    @Column(name = "llm_industry", length = 80)
    private String llmIndustry;

    /**
     * 등록처가 알려 준 <b>가맹점 주소</b>(V26).
     *
     * <p>업종과 <b>같은 문서</b>에서 뽑는다 — 주소를 따로 부르지 않으므로 바깥 호출이 안 는다.
     * 한 번 적으면 다음부터는 조회 없이 답한다({@code registryIndustry} 와 같은 이치다).
     */
    @Column(length = 200)
    private String address;

    /** 바깥 조회처에 물어본 횟수. 답을 받아 {@link #registryIndustry} 가 차면 더 묻지 않는다. */
    @Column(name = "lookup_attempts", nullable = false)
    private int lookupAttempts;

    /** LLM 에 물어본 횟수. {@link #GIVE_UP_AFTER} 에 닿으면 '기타'로 종결한다. */
    @Column(name = "llm_attempts", nullable = false)
    private int llmAttempts;

    /** 마지막으로 무엇이든 시도한 시각 — 통로가 죽었는지 보는 단서다. */
    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    /**
     * 주소를 물었는데 <b>조회처에 없더라</b>를 센 횟수(V27).
     *
     * <p>{@code lookupAttempts} 와 따로 세는 이유는 <b>끝나는 조건이 다르기 때문</b>이다. 업종
     * 조회는 답을 받으면 {@code registryIndustry} 가 차서 저절로 안 물어보게 되지만, 주소는
     * <b>답이 없는 것이 정상인 번호</b>가 있다 — 조회처에 주소칸이 비어 있거나(롯데아울렛
     * 서울역점) 사업자 자체가 안 나온다. 그런 번호는 성공이 영영 안 오므로 세어 두지 않으면
     * 회차마다 다시 묻는다.
     *
     * <p>실제로 그랬다(2026-08-08 운영): 7곳이 5분마다 헛물을 켜 하루 2,016회가 남의 서버로
     * 나갔다. {@value #GIVE_UP_AFTER} 회에 닿으면 {@code findMissingAddress} 가 그 행을 빼므로
     * 멎는다. 주소만 안 채워질 뿐 업종 조회·분류는 이 값을 보지 않는다.
     */
    @Column(name = "address_misses", nullable = false)
    private int addressMisses;

    /**
     * 가맹점명에서 뽑은 <b>브랜드</b> — {@code GS25 강남역점} 의 {@code GS25}.
     *
     * <p>사전에 들어온 가맹점은 브랜드도 여기 함께 산다. 대기 장소({@code merchant_brand})에서
     * 옮겨 오며, 그러면 그 브랜드를 다시 물어볼 일이 없다.
     */
    @Column(name = "brand", length = 60)
    private String brand;

    /**
     * 이 분류를 낳은 <b>국세청 업종코드들</b> — 6자리를 쉼표로 이은 것(V29).
     *
     * <p><b>비어 있는 것도 뜻이다.</b> 값이 있으면 이 행의 중분류가 <b>표에서 유도</b>됐다는
     * 말이고({@link Source#USER_CSV}·{@link Source#REGISTRY}), {@code null} 이면 표에서 나오지
     * 않았다는 말이다 — 사람이 정했거나({@link Source#USER_CONFIRMED}) 애초에 유도가 없다.
     * 그래서 재계산 대상이 {@code source} 하나로 떨어진다.
     *
     * <p><b>왜 대표 하나가 아니라 목록인가.</b> 한 업종명에 코드가 여럿 달리고, 살아 있는 경로는
     * 그 코드들의 중분류가 <b>만장일치일 때만</b> 답한다
     * ({@code IndustryCategoryMapper.midOfCodes}). 대표 하나만 적으면 재계산이 그 판단을 못 해
     * <i>"모르겠다"</i>고 해야 할 자리에서 자신 있게 답한다.
     *
     * <p>형식은 6자리 0채움 · 쉼표 · 공백 없음 · 오름차순이다. <b>쓰기는 그 형식으로, 읽기는
     * 관대하게</b> — {@link #ntsCodeList()} 가 쉼표로 자르고 다듬는다.
     */
    @Column(name = "nts_codes", length = 120)
    private String ntsCodes;

    protected MerchantCategory() {
    }

    public MerchantCategory(String businessNumber, String merchantName,
                            String category2, Source source, Long confirmedBy,
                            java.util.List<String> ntsCodes) {
        this.businessNumber = normalize(businessNumber);
        this.merchantName = merchantName;
        this.category2 = category2;
        this.source = source.name();
        this.confirmedBy = confirmedBy;
        this.ntsCodes = joinCodes(ntsCodes);
    }

    /** 원장이 하이픈을 넣어 보관하기도 한다. 키가 갈라지지 않게 숫자만 남긴다. */
    public static String normalize(String businessNumber) {
        return businessNumber == null ? "" : businessNumber.replaceAll("\\D", "");
    }

    /**
     * 코드 목록을 칸에 담을 문자열로 — 없으면 {@code null}(빈 문자열이 아니다).
     *
     * <p>정렬하고 중복을 없앤다. 같은 근거가 순서만 달라 다른 값처럼 보이면 재계산의 diff 가
     * 이유 없이 흔들린다(§4-3 재현성).
     */
    private static String joinCodes(java.util.List<String> codes) {
        if (codes == null || codes.isEmpty()) return null;
        String joined = codes.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .distinct().sorted()
                .collect(java.util.stream.Collectors.joining(","));
        return joined.isBlank() ? null : joined;
    }

    /**
     * 담긴 업종코드들 — 없으면 빈 목록.
     *
     * <p><b>읽기는 관대하다.</b> 쉼표로 자르고 다듬어, 쓰는 쪽이 언젠가 {@code ", "} 로 적어도
     * 재계산이 안 죽는다.
     */
    public java.util.List<String> ntsCodeList() {
        if (ntsCodes == null || ntsCodes.isBlank()) return java.util.List.of();
        return java.util.Arrays.stream(ntsCodes.split(","))
                .map(String::trim).filter(c -> !c.isEmpty()).toList();
    }

    public String getNtsCodes() { return ntsCodes; }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 사람이 다시 확인해 분류를 바꾼다 — 오입력을 되돌릴 길이다.
     *
     * <p><b>{@code ntsCodes} 를 인자로 받는 것이 요점이다.</b> 오버로드를 두지 않아 부르는 쪽이
     * 매번 <i>"이 판단이 표에서 나왔는가"</i>를 말하게 한다. 사람이 정한 것이면 {@code null} 을
     * 넘겨 <b>근거를 지운다</b> — 지금 값과 무관한 코드가 남으면, 설명가능성을 위해 만든 칸이
     * 오해를 만드는 칸이 된다. 잃는 것은 없다({@link #registryIndustry} 가 그대로 남는다).
     *
     * <p>근거는 분류와 <b>함께</b> 움직여야 한다. 분류만 갈아 끼우고 코드를 두면 그 행은 새
     * 분류에 옛 근거를 붙인 채로 굳는다.
     */
    public void reclassify(String category2, Source source, Long confirmedBy,
                           java.util.List<String> ntsCodes) {
        this.category2 = category2;
        this.source = source.name();
        this.confirmedBy = confirmedBy;
        this.ntsCodes = joinCodes(ntsCodes);
    }

    /**
     * 바깥 조회처에 한 번 물었다고 적는다 — 답을 얻었으면 그 업종 이름도 같이.
     *
     * <p>답을 얻은 뒤에는 다시 묻지 않는다({@link #registryAnswered}). 등록 업종은 잘 안 바뀌고,
     * 바뀐다 해도 그때는 사람이 고치는 편이 빠르다.
     */
    public void noteLookup(String industryName, LocalDateTime at) {
        this.lookupAttempts++;
        this.lastAttemptAt = at;
        if (industryName != null && !industryName.isBlank()) {
            this.registryIndustry = industryName.length() > 80
                    ? industryName.substring(0, 80) : industryName;
        }
    }

    /** 이미 조회처의 답을 받아 둔 가맹점인가 — 그러면 또 부르지 않는다. */
    public boolean registryAnswered() {
        return registryIndustry != null && !registryIndustry.isBlank();
    }

    /**
     * 모델이 답한 업종 이름을 적는다 — <b>비어 있을 때만</b>.
     *
     * <p>덮지 않는 이유는 이 값이 <b>소분류의 근거</b>이기 때문이다. 같은 가맹점을 다시 물으면
     * 모델은 다른 이름을 줄 수 있고(제과점업 / 빵류 소매업), 그때마다 갈아 끼우면 소분류가
     * 흔들린다. 먼저 받은 답 하나로 고정한다 — 바꿔야 할 만큼 틀렸다면 사람이 고칠 일이다.
     */
    public void noteLlmIndustry(String industryName) {
        if (industryName == null || industryName.isBlank()) return;
        if (llmIndustry != null && !llmIndustry.isBlank()) return;
        this.llmIndustry = industryName.length() > 80
                ? industryName.substring(0, 80) : industryName;
    }

    /**
     * LLM 에 한 번 물었다고 적는다. {@link #GIVE_UP_AFTER} 번째부터는 '기타'로 종결한다.
     *
     * <p>세는 것은 <b>답이 없었던 질문</b>이다. 답을 얻으면 그 행은 {@link Source#LLM_GUESS} 가
     * 되므로 여기까지 오지 않는다.
     *
     * @return 이번 호출로 종결됐으면 true
     */
    public boolean noteLlmMiss(LocalDateTime at) {
        this.llmAttempts++;
        this.lastAttemptAt = at;
        if (llmAttempts >= GIVE_UP_AFTER && !Source.UNRESOLVED.name().equals(source)) {
            this.category2 = com.finntech.engine.IndustryCategoryMapper.OTHER;
            this.source = Source.UNRESOLVED.name();
            // '기타'는 표의 답이 아니라 **종결 표시**다. 근거를 남기면 재계산이 그 행을
            // 대상으로 잡아 종결을 되돌린다 — 근거는 분류와 함께 움직인다({@code reclassify}).
            this.ntsCodes = null;
            return true;
        }
        return false;
    }

    /** 더 시도할 필요가 없는 가맹점인가 — 사람이 고치기 전까지 조회도 질문도 멈춘다. */
    public boolean settledAsOther() {
        return Source.UNRESOLVED.name().equals(source);
    }

    public String getBrand() { return brand; }

    /** 대기 장소에서 옮겨 올 때. 이미 있으면 덮지 않는다 — 먼저 들어온 것이 사람의 손일 수 있다. */
    public void adoptBrand(String value) {
        if (brand == null || brand.isBlank()) this.brand = value;
    }

    public String getCategory3() { return category3; }

    /**
     * <b>소분류를 적는다.</b> 빈 값이면 지운다 — 근거가 없어졌는데 답이 남아 있으면 안 된다.
     *
     * <p>값을 정하는 것은 이 엔티티가 아니라 {@code MerchantCategoryService} 다. 소분류는
     * (브랜드, 업종 이름)에서 표로 나오는데 그 표를 아는 것은 {@code IndustryCategoryMapper}
     * 이고, 엔티티가 그것을 들면 도메인이 리소스 파일에 매인다.
     */
    public void applySub(String sub) {
        this.category3 = (sub == null || sub.isBlank()) ? null : sub;
    }

    /**
     * <b>중분류가 소분류와 어긋나는가</b> — 어긋나면 이 행은 잘못 적힌 것이다.
     *
     * <p>소분류는 정확히 한 중분류에만 속하므로, 소분류를 알면 중분류가 결정된다. 그러니
     * 둘이 다르다는 것은 <b>둘 중 하나가 틀렸다는 뜻</b>이고, 확정 지식인 소분류 쪽을 믿는다.
     * 새 규칙이 아니라 그 불변식의 대우(對偶)라서 따로 판단할 것이 없다.
     *
     * @param midOfSub 소분류 → 중분류 ({@code IndustryCategoryMapper::midOfSub})
     */
    public boolean midDisagreesWithSub(java.util.function.UnaryOperator<String> midOfSub) {
        if (category3 == null || category3.isBlank()) return false;
        String expected = midOfSub.apply(category3);
        return !com.finntech.engine.IndustryCategoryMapper.isUnknown(expected)
                && !expected.equals(category2);
    }

    public String getRegistryIndustry() { return registryIndustry; }

    public String getLlmIndustry() { return llmIndustry; }
    public String getAddress() { return address; }

    /**
     * 주소를 적는다 — <b>이미 있으면 덮지 않는다.</b>
     *
     * <p>덮지 않는 이유는 조회처가 답을 바꿀 때 화면의 주소가 이유 없이 흔들리기 때문이다.
     * 바꿔야 할 일이 생기면 그때 지우고 다시 채운다.
     *
     * @return 이번에 새로 적었으면 true
     */
    public boolean noteAddress(String value) {
        if (value == null || value.isBlank()) return false;
        if (address != null && !address.isBlank()) return false;
        this.address = value.length() > 200 ? value.substring(0, 200) : value;
        return true;
    }
    /**
     * 주소를 물었는데 없었다고 적는다 — {@value #GIVE_UP_AFTER} 회면 백필이 그 행을 놓는다.
     *
     * <p>물어보지도 않고 적으면 안 된다. PG·복합 번호는 {@code askable} 이 먼저 막으므로 여기
     * 들어오지 않는다({@code IndustryLookupService.askable} 의 같은 원칙).
     */
    public void noteAddressMiss(LocalDateTime at) {
        this.addressMisses++;
        this.lastAttemptAt = at;
    }

    public int getAddressMisses() { return addressMisses; }
    public int getLookupAttempts() { return lookupAttempts; }
    public int getLlmAttempts() { return llmAttempts; }
    public LocalDateTime getLastAttemptAt() { return lastAttemptAt; }

    public Long getId() { return id; }
    public String getBusinessNumber() { return businessNumber; }
    public String getMerchantName() { return merchantName; }
    public String getCategory2() { return category2; }
    public String getSource() { return source; }
    public Long getConfirmedBy() { return confirmedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
