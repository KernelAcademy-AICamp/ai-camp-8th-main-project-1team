package com.finntech.engine;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * <b>국세청 업종코드(6자리)</b> → 우리 소비 중분류. 결정론 1:1 표다.
 *
 * <p><b>KSIC가 아니다.</b> 2026-08-04 이전에는 KSIC 세분류 4자리를 썼는데, `4781` 하나에
 * "의약품, 의료용 기구, <b>화장품</b> 및 방향제 소매업"이 다 들어 있어 올리브영이 '의료'가 됐다.
 * 생성기에서 화장품에 일부러 다른 코드를 붙여 우회했지만, 그건 우리가 만드는 데이터에서만
 * 통하는 방법이라 실데이터가 들어오면 그대로 터진다. 국세청 6자리는 `523131`(화장품) /
 * `523111`(의약품)으로 가른다. 두 체계는 세대가 달라 번호가 겹치지 않으므로 섞어 쓰면 안 된다.
 *
 * <p><b>왜 여기가 경계인가.</b> 마이데이터 제공자가 아는 것은 "이 가맹점이 무슨 업종인가"까지고,
 * "이 소비가 사용자에게 무엇인가"는 앱이 정한다. 예전에는 제공자가 7대분류를 그대로 넘겼고
 * 그 값이 곧 소비 카테고리가 됐다 — 한 축이 업종과 소비종류를 겸하다 보니 교통이 '온라인'에
 * 들어가는 왜곡이 났고, 지킴이 챌린지에서 배달을 줄이려면 지하철 요금까지 예산에 잡혔다.
 *
 * <p><b>왜 ML이 아닌가.</b> 매핑의 정답을 우리가 만들어야 하므로, 학습을 시키면 우리 표를
 * 외울 뿐이다(순환). ML은 낭비/필수 판정에만 쓴다. 표 자체가 곧 "왜 이 소비가 이 카테고리인가"의
 * 설명이 되므로 설명가능성도 함께 얻는다.
 *
 * <p>원천은 {@code scripts/industry/nts-mid.tsv} 하나이고 {@code build_industry.py}가
 * 이 JSON을 만든다. 마이데이터 서버도 같은 표를 읽는다 — 둘이 갈라지면 혜택 계산이 어긋난다.
 *
 * <p><b>DB 컬럼 이름은 아직 {@code ksic_code}다.</b> 이름은 KSIC 시절 것이고 값은 국세청
 * 6자리다 — 이미 적용된 마이그레이션이라 바꾸지 않았다(CLAUDE.md 규칙 3).
 */
@Component
public class IndustryCategoryMapper {

    private static final String PATH = "industry-mid.json";

    /** 업종코드를 모를 때. 알 수 없는 가맹점·비소비 업종·간편결제가 여기로 온다. */
    public static final String UNCLASSIFIED = "카테고리없음";

    /**
     * <b>다 해 봤지만 알 수 없었다</b> — 조회도 하고 LLM 에도 물었는데 답이 없던 가맹점.
     *
     * <p>{@link #UNCLASSIFIED} 와 갈라 둔 이유가 전부다. 그 값 하나가 <i>"아직 안 물어봤다"</i>와
     * <i>"다 물어봤는데 모른다"</i>를 같이 담고 있었고, 그래서 뒤엣것을 연동할 때마다 다시
     * 조회하고 다시 물었다. 종결을 적을 자리가 없으면 파이프라인은 영원히 같은 일을 한다.
     */
    public static final String OTHER = "기타";

    /**
     * <b>결제대행사 자신</b> — 무엇을 샀는지 <b>원리적으로</b> 알 수 없는 결제.
     *
     * <p>{@link #UNCLASSIFIED}·{@link #OTHER} 와 무엇이 다른가.
     *
     * <pre>
     *   카테고리없음  아직 못 했다        나중에 알 수 있다      총액 O · 정리목록 O
     *   기타          다 물어봤는데 모른다  실재 가맹점이다        총액 O · 정리목록 X
     *   간편결제      물어볼 대상이 아니다  영원히 알 수 없다      총액 O · 정리목록 X
     * </pre>
     *
     * <p><b>왜 값을 따로 두나.</b> 셋을 섞으면 <i>"언젠가 줄어들 목록"</i> 에 절대 안 줄어드는
     * 것이 쌓인다. 그리고 더 나쁜 일이 실제로 벌어졌다 — 결제대행사 179건 중 <b>142건에
     * 카테고리가 붙어</b> 있었고, {@code NICE_통신판매} 79건이 <b>쇼핑</b>으로 집계돼 낭비
     * 판정까지 받았다(2026-08-26 운영 실측). 무엇을 샀는지 모르는 돈이 쇼핑 지출이 됐다.
     *
     * <p><b>총액에서는 안 뺀다.</b> 실제로 나간 돈이다 — 빼면 <i>"월소득 − 월평균지출"</i> 로
     * 구하는 가용 여유자금이 부풀려져 <b>없는 여유를 있다고 권하게 된다</b>
     * ({@code RecommendService}). 소비 <b>분석</b>에서만 뺀다.
     */
    public static final String SIMPLE_PAY = "간편결제";

    /**
     * <b>무엇을 샀는지 모르는 분류인가</b> — 낭비 판정·절약 후보에서 빼야 할 값들.
     *
     * <p>둘을 한 자리에서 판정하는 것이 요점이다. '기타'를 새 분류로 들이면서 이 함수를 안 만들면,
     * 종결 표시가 <b>판정 대상으로 흘러 들어간다</b> — 재량성 표에 없으니 기본값 0.5 를 받아
     * "모르는 소비의 절반이 낭비"라는 값이 리포트에 실린다. 실제로 같은 사고가 한 번 있었다:
     * 알 수 없는 간편결제가 전부 ML 판정에 들어갔는데 문자열만 안 맞을 뿐이라 크래시가 없어
     * 아무도 몰랐다.
     *
     * <p>"카테고리없음을 줄이세요"가 행동으로 옮길 수 없는 조언인 것처럼 "기타를 줄이세요"도
     * 그렇다. 사람이 그 결제를 직접 고쳐 주기 전까지는 판정의 재료가 아니다.
     */
    public static boolean isUnknown(String mid) {
        return mid == null || mid.isBlank() || UNCLASSIFIED.equals(mid)
                || OTHER.equals(mid) || SIMPLE_PAY.equals(mid);
    }

    /**
     * <b>카테고리별 분석에서 빼는가</b> — 도넛·비중·챌린지가 보는 자리.
     *
     * <p>{@link #isUnknown} 과 갈라 둔다. 저쪽은 <i>"판정의 재료가 아니다"</i>(낭비·절약 후보)를
     * 말하고 여기는 <i>"카테고리 합에서 뺀다"</i>를 말한다. {@link #OTHER} 는 판정에서는 빠지지만
     * <b>실재 가맹점의 소비</b>라 카테고리 합에는 남는다 — 어디에 썼는지만 모를 뿐이다.
     *
     * <p><b>총액과 혼동하면 안 된다.</b> 실제로 나간 돈은 무엇이든 총액에 남는다.
     */
    public static boolean isOutsideCategories(String mid) {
        return SIMPLE_PAY.equals(mid);
    }

    private final Map<String, String> midByIndustry;
    private final Map<String, Double> discretionaryByMid;
    private final Map<String, String> pgBusinessNumbers;
    private final Map<String, String> multiBusinessNumbers;
    /** 업종 <b>이름</b> → 중분류. LLM 이 축을 직접 고르지 않게 하려고 둔다. */
    private final Map<String, String> midByIndustryName;
    /** 세세분류 이름(정규화) → 국세청 업종코드들. 바깥 조회처의 답을 우리 번호로 옮기는 칸. */
    private final Map<String, java.util.List<String>> ntsByFineName;
    /** 업종코드 → <b>카드혜택 축</b>. 중분류와 다른 축이다 — {@link #cardAxisOf} 참조. */
    private final Map<String, String> cardAxisByIndustry;
    /** 소분류 → 중분류. <b>소분류는 정확히 한 중분류에만 속한다</b> — {@link #midOfSub} 참조. */
    private final Map<String, String> midBySub;
    /** 업종 이름 → 소분류. 이미 가진 이름에서 뽑으므로 새로 물어볼 것이 없다. */
    private final Map<String, String> subByIndustryName;
    /** 브랜드 → 소분류. 업종 이름이 답을 못 주는 자리를 메운다({@code 배달의민족 → 배달}). */
    private final Map<String, String> subByBrand;

    @SuppressWarnings("unchecked")
    public IndustryCategoryMapper(ObjectMapper objectMapper) {
        try (InputStream is = new ClassPathResource(PATH).getInputStream()) {
            Map<String, Object> root = objectMapper.readValue(is, Map.class);
            this.midByIndustry = (Map<String, String>) root.get("midByIndustry");
            Map<String, Number> disc = (Map<String, Number>) root.get("discretionaryByMid");
            Map<String, Double> d = new java.util.LinkedHashMap<>();
            if (disc != null) disc.forEach((k, v) -> d.put(k, v.doubleValue()));
            this.discretionaryByMid = d;
            Map<String, String> pg = (Map<String, String>) root.get("pgBusinessNumbers");
            this.pgBusinessNumbers = pg == null ? Map.of() : pg;
            @SuppressWarnings("unchecked")
            Map<String, String> multi = (Map<String, String>) root.get("multiBusinessNumbers");
            this.multiBusinessNumbers = multi == null ? Map.of() : multi;
            Map<String, String> names = (Map<String, String>) root.get("midByIndustryName");
            this.midByIndustryName = names == null ? Map.of() : names;
            Map<String, java.util.List<String>> fine =
                    (Map<String, java.util.List<String>>) root.get("ntsByFineName");
            this.ntsByFineName = fine == null ? Map.of() : fine;
            Map<String, String> axes = (Map<String, String>) root.get("cardAxisByIndustry");
            this.cardAxisByIndustry = axes == null ? Map.of() : axes;
            Map<String, String> subMid = (Map<String, String>) root.get("midBySub");
            this.midBySub = subMid == null ? Map.of() : subMid;
            Map<String, String> subName = (Map<String, String>) root.get("subByIndustryName");
            this.subByIndustryName = subName == null ? Map.of() : subName;
            Map<String, String> subBrand = (Map<String, String>) root.get("subByBrand");
            this.subByBrand = subBrand == null ? Map.of() : subBrand;
            verifySubInvariant();
        } catch (IOException e) {
            throw new UncheckedIOException("업종코드 대조표를 읽지 못했다: " + PATH, e);
        }
    }

    /**
     * <b>소분류 표가 스스로 어긋나 있으면 기동을 세운다.</b>
     *
     * <p>빌드 스크립트가 이미 검사하지만, JSON 은 손으로도 고쳐지고 다른 브랜치에서 온 파일이
     * 섞이기도 한다. 이 표가 어긋나면 <b>조용히 틀린다</b> — 소분류가 두 중분류에 걸치면
     * 같은 브랜드가 통로에 따라 갈리는데, 그건 화면에 그냥 다른 카테고리로 보일 뿐이라
     * 아무도 눈치채지 못한다. 기동에서 멈추는 편이 낫다.
     */
    private void verifySubInvariant() {
        java.util.List<String> bad = new java.util.ArrayList<>();
        subByIndustryName.forEach((name, sub) -> {
            if (!midBySub.containsKey(sub)) bad.add("업종 '" + name + "' 의 소분류 '" + sub + "' 이 midBySub 에 없다");
            String mid = midByIndustryName.get(name);
            String bySub = midBySub.get(sub);
            if (mid != null && bySub != null && !mid.equals(bySub)) {
                bad.add("업종 '" + name + "' 은 " + mid + " 인데 소분류 '" + sub + "' 은 " + bySub + " 다");
            }
        });
        subByBrand.forEach((brand, sub) -> {
            if (!midBySub.containsKey(sub)) bad.add("브랜드 '" + brand + "' 의 소분류 '" + sub + "' 이 midBySub 에 없다");
        });
        if (!bad.isEmpty()) throw new IllegalStateException("소분류 표가 어긋난다: " + bad);
    }

    /**
     * <b>소분류 → 중분류.</b> 모르는 소분류면 {@link #UNCLASSIFIED}.
     *
     * <p><b>이 메서드가 소분류 층의 전부다.</b> 소분류는 정확히 한 중분류에만 속하므로
     * (빌드와 {@link #verifySubInvariant} 가 잠근다) <b>소분류를 알면 중분류가 결정된다</b>.
     * 그래서 브랜드에 소분류가 붙으면 그 브랜드의 모든 지점이 같은 중분류가 되고,
     * 통로(업종코드·등록 조회·LLM)가 달라도 갈리지 않는다.
     *
     * <p>거꾸로, 사전에 이미 적힌 중분류가 이 값과 다르면 <b>그 자체가 오분류의 증거다</b> —
     * 새 규칙이 아니라 위 불변식의 대우(對偶)라서 따로 판단할 것이 없다.
     */
    public String midOfSub(String sub) {
        if (sub == null || sub.isBlank()) return UNCLASSIFIED;
        return midBySub.getOrDefault(sub.trim(), UNCLASSIFIED);
    }

    /** 업종 <b>이름</b> → 소분류. 모르는 이름이면 빈 문자열. */
    public String subOfIndustryName(String industryName) {
        if (industryName == null) return "";
        return subByIndustryName.getOrDefault(industryName.trim(), "");
    }

    /**
     * <b>브랜드 → 소분류.</b> 모르거나 <b>붙이면 안 되는 브랜드</b>면 빈 문자열.
     *
     * <p>회사명(카카오·애플·구글·인터파크)과 결제수단(카카오페이·토스)은 표에서 <b>일부러
     * 빠져 있다</b>. 여러 업태를 겸해 소분류가 하나로 안 정해지는데, 대표 업태를 찍으면
     * 그 브랜드 전체가 한꺼번에 틀린다 — {@code 카카오} 가 멜론의 표기였을 때 실사용자의
     * 카카오택시 72곳이 전부 멜론이 된 사고가 그것이다(2026-08-08 운영 실측).
     */
    public String subOfBrand(String brand) {
        if (brand == null) return "";
        return subByBrand.getOrDefault(brand.trim(), "");
    }

    /**
     * <b>소분류가 가리키는 국세청 업종코드</b> — 애매하면 빈 목록.
     *
     * <p>소분류를 알면 업종코드도 따라올 때가 있다. {@code 커피} 는 {@code 커피 전문점} 하나뿐이고
     * 그 이름의 코드가 하나면 <b>역산이 확정</b>이다. 그러면 카드 혜택축이 살아난다 —
     * {@code cardAxisOf} 가 업종코드를 읽는데, 실 명세서에는 업종코드가 없어 자리채움값이
     * 들어가고 그래서 카드추천이 죽어 있었다(§13-12 곁가지).
     *
     * <p><b>만장일치일 때만 답한다.</b> 소분류 하나에 업종 이름이 여럿이면(예: {@code 한식} 은
     * 넷) 어느 것인지 알 방법이 없다. 억지로 고르면 <b>없는 사실을 만드는 것</b>이라,
     * 표에서 유도한 값과 지어낸 값이 한 칸에 섞인다(V29 가 막은 바로 그 일).
     * 170개 소분류 중 <b>62개</b>만 여기서 답한다.
     */
    public java.util.List<String> codesOfSub(String sub) {
        if (sub == null || sub.isBlank()) return java.util.List.of();
        java.util.List<String> names = new java.util.ArrayList<>();
        subByIndustryName.forEach((name, s) -> { if (s.equals(sub)) names.add(name); });
        if (names.size() != 1) return java.util.List.of();       // 갈리면 답하지 않는다
        java.util.List<String> codes = codesOfFineName(names.get(0));
        return codes.size() == 1 ? codes : java.util.List.of();  // 코드도 하나여야 확정이다
    }

    /**
     * <b>소분류가 가리키는 업종코드 후보를 중분류로 좁힌다</b> — 추정 칸에 쓸 값.
     *
     * <p>{@link #codesOfSub} 는 <b>1:1 일 때만</b> 답한다(170개 중 62개). 나머지는 소분류
     * 하나에 업종이 여럿이거나 코드가 여럿이라 확정이 아니다 — 그런데 <b>추정으로는 쓸 수
     * 있다</b>. 여기서 중분류가 맞는 것만 남기고 첫 것을 준다.
     *
     * <p>정렬이 고정돼 있어 같은 입력에 같은 답이 나온다(§4 원칙 3). 확정이 아니므로
     * 부르는 쪽은 반드시 <b>추정 칸</b>에 적어야 한다.
     */
    public String guessCodeOfSub(String sub, String mid) {
        if (sub == null || sub.isBlank()) return "";
        java.util.List<String> names = new java.util.ArrayList<>();
        subByIndustryName.forEach((name, s) -> { if (s.equals(sub)) names.add(name); });
        java.util.Collections.sort(names);
        java.util.List<String> candidates = new java.util.ArrayList<>();
        for (String name : names) {
            for (String code : codesOfFineName(name)) {
                if (mid == null || mid.equals(midOf(code))) candidates.add(code);
            }
        }
        java.util.Collections.sort(candidates);
        return candidates.isEmpty() ? "" : candidates.get(0);
    }

    /** 그 브랜드에 소분류가 있는가 — 회사명·결제수단은 거짓이다. */
    public boolean hasSub(String brand) {
        return brand != null && subByBrand.containsKey(brand);
    }

    /** 소분류가 붙은 브랜드 전부 — 회사명·결제수단은 여기 없다. 정렬 고정(§4-3 재현성). */
    public java.util.Set<String> brandsWithSub() {
        return new java.util.TreeSet<>(subByBrand.keySet());
    }

    /** 소분류 이름 전부 — 시험과 관리자 화면이 쓴다. 정렬 고정(§4-3 재현성). */
    public java.util.Set<String> subCategories() {
        return new java.util.TreeSet<>(midBySub.keySet());
    }

    /**
     * 전자지급결제대행(PG)·간편결제 사업자인가.
     *
     * <p><b>업종코드로는 못 가른다.</b> PG는 최소 세 업종에 흩어져 등록돼 있고 그 업종에는 진짜
     * 정보서비스·금융지원 업체가 함께 들어 있다. 특히 `724000`은 OTT(넷플릭스)와
     * '데이터베이스 및 온라인 정보 제공업'(NHN KCP·KG파이낸셜)이 <b>한 코드에 섞여</b> 있다.
     * 그래서 사업자번호로 막는다({@code scripts/industry/pg-사업자번호.tsv}).
     *
     * <p>왜 필요한가 — 업종코드는 "이 사업자가 무슨 일을 하는가"를 말하지 "이 결제가 무엇에 쓴
     * 돈인가"를 말해 주지 않는다. PG를 거치면 그 둘이 어긋난다: 사업자번호는 KG모빌리언스인데
     * 실제 결제처는 에버랜드다.
     */
    public boolean isPaymentAgency(String businessNumber) {
        if (businessNumber == null) return false;
        return pgBusinessNumbers.containsKey(businessNumber.replaceAll("\\D", ""));
    }

    /**
     * PG 상호 목록 — <b>가맹점명으로 걸러야 할 때</b> 쓴다.
     *
     * <p>실데이터에는 사업자번호가 없는 결제가 있고, 그때 남는 단서는 가맹점명뿐이다. 이름이
     * PG 상호면 무엇을 샀는지 알 수 없으므로 LLM 에 물어봐도 소용이 없다 —
     * {@code MerchantClassifierService} 가 여기서 대상을 추린다. 목록은 사업자번호 차단과
     * <b>같은 파일 하나</b>에서 온다({@code scripts/industry/pg-사업자번호.tsv}). 두 곳에 적으면
     * 갈라진다.
     */
    public java.util.Collection<String> paymentAgencyNames() {
        return pgBusinessNumbers.values();
    }

    /**
     * <b>이 번호의 결제대행사가 누구인가</b> — 모르면 빈 문자열.
     *
     * <p>{@link #isPaymentAgency} 가 <i>"PG 인가"</i>만 답하는 데 비해 여기는 <b>누구인지</b>를
     * 답한다. 화면이 <i>"토스페이먼츠 경유"</i> 라고 적으려면 이름이 필요하고, 그 이름은
     * <b>번호가 알려 준 사실</b>이라 추측이 아니다 — 상호 문자열에서 짐작하는 것과 다르다.
     */
    public String paymentAgencyOf(String businessNumber) {
        if (businessNumber == null) return "";
        return pgBusinessNumbers.getOrDefault(businessNumber.replaceAll("\\D", ""), "");
    }

    /**
     * 한 번호에 <b>성격이 다른 사업이 여럿</b> 붙은 곳인가 — 백화점 입점, 배 안의 편의점.
     *
     * <p>PG 와 다르다. PG 는 번호가 <b>남의 것</b>이라 아예 버리지만, 여기는 번호가 그 사업자의
     * 것이 맞다. 다만 <b>번호로 분류하면 안 된다</b> — 완화("같은 번호면 같은 분류")가 닿는 순간
     * 무인양품과 식품관이 한 분류가 되고, 사용자가 하나를 고치면 나머지까지 따라 바뀐다.
     *
     * <p><b>"상호가 여럿인가"로는 못 가른다.</b> 택시는 차량번호가 붙어 상호가 수만 종이지만
     * 전부 같은 사업이라 완화가 <b>꼭 필요하다</b>. 둘을 가르는 것은 "그 상호들이 같은 것을
     * 파는가"이고 그건 사람만 안다 — 그래서 PG 처럼 목록으로 둔다
     * ({@code scripts/industry/복합사업자-사업자번호.tsv}).
     */
    public boolean isMultiBusiness(String businessNumber) {
        if (businessNumber == null || businessNumber.isBlank()) return false;
        return multiBusinessNumbers.containsKey(businessNumber.replaceAll("\\D", ""));
    }

    /**
     * 업종 <b>이름</b>으로 중분류를 찾는다. 모르는 이름이면 {@link #UNCLASSIFIED}.
     *
     * <p>LLM 보조 분류가 우리 축(중분류)을 직접 고르지 않고 <b>"이 가맹점은 어느 업종인가"</b>를
     * 답하게 하려고 둔다. 그러면 축 배정은 이 표가 하고 모델은 업종의 사실만 말한다 —
     * 마스터 §4-1(판단은 설명가능한 모델이, 표현은 AI가)에 더 맞고, 표를 고치면 모델의 답도
     * 함께 따라온다(백화점을 대형마트에서 쇼핑으로 옮긴 것 같은 일).
     *
     * <p>이름 하나가 두 중분류에 걸리면 빌드가 실패하므로 1:1 이 보장된다.
     */
    public String midOfIndustryName(String industryName) {
        if (industryName == null) return UNCLASSIFIED;
        return midByIndustryName.getOrDefault(industryName.trim(), UNCLASSIFIED);
    }

    /**
     * <b>바깥 조회처가 답한 업종 이름</b>을 우리 중분류로 옮긴다 — 없거나 애매하면 {@link #UNCLASSIFIED}.
     *
     * <p>세 칸을 지난다: <b>세세분류 이름 → 국세청 업종코드 → 중분류.</b> 가운데 칸이 필요한 이유는
     * 세대가 다르기 때문이다. 사업자등록번호로 등록 업종을 돌려주는 조회처는 KSIC(한국표준산업분류)
     * 이름을 주고 우리 대조표는 국세청 업종코드 세대라, 번호끼리는 아예 겹치지 않는다. 그런데
     * <b>이름은 이어진다</b> — 국세청 업종코드표의 {@code 세세분류} 칸이 KSIC 세세분류 이름을 그대로
     * 쓴다(2026-08-07 실측: 조회된 업종명 19종 중 18종이 그 칸에 있었다).
     *
     * <p><b>만장일치일 때만 답한다.</b> 이름 하나에 국세청 코드가 여럿 달리는 일이 있고(같은 업종을
     * 규모로 쪼갠 것), 그 코드들의 중분류가 갈리면 어느 쪽인지 알 방법이 없다. 억지로 고르는 대신
     * 비워서 LLM 으로 내려보낸다 — 모르는 것을 모른다고 하는 편이 조용히 틀리는 것보다 낫다.
     *
     * <p><b>대조표에 없는 업종은 답하지 않는다.</b> 대조표는 소매·서비스처럼 개인이 직접 결제하는
     * 업종만 담는다(제조·도매·B2B 제외). 그래서 이 통로는 "법인 주업종을 소비로 읽는" 사고를
     * 구조적으로 안 낸다 — 삼성전자의 등록 업종은 영상기기 제조업이라 여기서 자동으로 빠진다.
     */
    public String midOfFineName(String fineName) {
        return midOfCodes(codesOfFineName(fineName));
    }

    /**
     * <b>바깥 조회처가 답한 업종 이름 → 국세청 업종코드들.</b> 모르는 이름이면 빈 목록.
     *
     * <p>{@link #midOfFineName} 이 속으로 하던 첫 칸을 떼어 낸 것이다. 떼는 이유는 <b>그 코드를
     * 사전에 적어 두기 위해서</b>다(V29) — 지금까지 이 목록은 만장일치 검사만 하고 사라지는
     * 지역변수였고, 그래서 사전은 답만 들고 근거를 안 들었다.
     *
     * <p>사본을 준다. 색인의 리스트를 그대로 내주면 부르는 쪽이 공유 상태를 만진다.
     */
    public java.util.List<String> codesOfFineName(String fineName) {
        java.util.List<String> codes = ntsByFineName.get(normalizeFineName(fineName));
        return codes == null ? java.util.List.of() : java.util.List.copyOf(codes);
    }

    /**
     * <b>업종코드들 → 중분류.</b> 만장일치일 때만 답하고, 갈리거나 비면 {@link #UNCLASSIFIED}.
     *
     * <p>{@link #midOfFineName} 과 재계산이 <b>이 함수 하나를 나눠 쓴다.</b> 두 벌로 적으면
     * 갈라지고, 갈라지면 "살아 있는 경로는 비우는데 재계산은 답하는" 조용한 어긋남이 난다.
     */
    public String midOfCodes(java.util.Collection<String> codes) {
        if (codes == null || codes.isEmpty()) return UNCLASSIFIED;
        String only = null;
        for (String code : codes) {
            String mid = midByIndustry.get(code);
            if (mid == null) continue;
            if (only == null) only = mid;
            else if (!only.equals(mid)) return UNCLASSIFIED;   // 갈렸다 — 고르지 않는다
        }
        return only == null ? UNCLASSIFIED : only;
    }

    /**
     * 이름 결합용 정규화 — <b>{@code scripts/industry/build_industry.py} 와 글자 하나까지 같아야 한다.</b>
     *
     * <p>한쪽만 고치면 색인은 멀쩡한데 아무것도 안 붙는 조용한 실패가 난다. 지우는 것은 세대가
     * 다를 때 흔히 어긋나는 글자들이다 — {@code 그 외 기타}↔{@code 그외 기타},
     * {@code 정보 제공업}↔{@code 정보제공업}, {@code 음ㆍ식료품}↔{@code 음·식료품}.
     */
    static String normalizeFineName(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isWhitespace(c) || "·‧ㆍ･․.,()（）[]/-\\".indexOf(c) >= 0) continue;
            sb.append(c);
        }
        return sb.toString();
    }

    /** 중분류로 묶은 업종 이름 — LLM 에게 보여 줄 목록이다. 정렬 고정(§4-3 재현성). */
    public java.util.Map<String, java.util.List<String>> industryNamesByMid() {
        java.util.Map<String, java.util.List<String>> out = new java.util.TreeMap<>();
        midByIndustryName.forEach((name, mid) ->
                out.computeIfAbsent(mid, k -> new java.util.ArrayList<>()).add(name));
        out.values().forEach(java.util.Collections::sort);
        return out;
    }

    /**
     * 결제 한 건의 소비 중분류 — <b>사업자번호까지 보고</b> 정한다.
     *
     * <p>PG를 거친 결제는 업종코드가 결제 성격을 말해 주지 않으므로 분류하지 않는다.
     * 그 결제의 실제 가맹점은 가맹점명에만 남아 있고, 그것을 읽는 것은 LLM 보조 분류의 몫이다.
     */
    public String midOf(String industryCode, String businessNumber) {
        return isPaymentAgency(businessNumber) ? UNCLASSIFIED : midOf(industryCode);
    }

    /**
     * 중분류의 <b>재량성</b>(0~1) — 낮을수록 생존필수, 높을수록 재량.
     *
     * <p>카탈로그의 {@code discretionaryBase}를 빈도가중 평균한 값이다. 절약 후보의 등급을
     * 여기서 유도하므로, 카테고리가 늘어도 목록을 고칠 일이 없다.
     * 모르는 중분류는 중간값(0.5) — 판단을 못 하겠으면 최적화가능으로 둔다.
     */
    public double discretionaryOf(String mid) {
        return discretionaryByMid.getOrDefault(mid, 0.5);
    }

    /**
     * 업종코드를 소비 중분류로 옮긴다. 모르는 코드는 {@link #UNCLASSIFIED}.
     *
     * <p>미분류를 null이나 예외로 두지 않는 이유: 분석 엔진이 카테고리 코드로 집계하는데
     * null이 섞이면 그 소비가 통째로 사라진다. 알 수 없다는 것도 하나의 분류다.
     */
    public String midOf(String industryCode) {
        if (industryCode == null || industryCode.isBlank()) return UNCLASSIFIED;
        return midByIndustry.getOrDefault(industryCode, UNCLASSIFIED);
    }

    /**
     * 업종코드를 <b>카드혜택 축</b>으로 옮긴다 — 중분류와 <b>다른 축</b>이다.
     *
     * <p>중분류는 소비분석용이라 <i>교통/자동차</i> 하나에 주유(505001)·시내버스(602103)·
     * 택시(602201)가 함께 들어 있는데, <b>카드는 셋을 전부 다르게 취급한다</b>(주유 리터당 할인 /
     * 대중교통 10% / 택시 별도). 그래서 {@code nts-mid.tsv} 4번째 칸에서 축이 따로 나온다 —
     * 소비분석은 {@link #midOf}, 카드추천은 이 메서드를 읽는다.
     *
     * <p><b>모르는 코드는 {@code null} 이고, 그것이 {@code 혜택축없음} 과 다르다.</b>
     * {@code 혜택축없음}은 <i>"그 업종에 걸리는 카드 혜택 축이 없다"</i>이고 <b>전월 실적에는
     * 그대로 들어간다</b>(동네 정육점은 혜택은 못 받아도 실적에는 든다). {@code null} 은
     * <i>"이 결제가 무엇인지 모른다"</i>라 실적에서도 뺀다 — 둘을 섞으면 실적에서 축 하나가
     * 통째로 빠지거나, 모르는 결제가 실적에 들어와 <b>"채운 줄 알았는데 못 채웠다"</b>가 난다.
     */
    public String cardAxisOf(String industryCode) {
        if (industryCode == null || industryCode.isBlank()) return null;
        return cardAxisByIndustry.get(industryCode);
    }

    /** 표에 있는 코드 수 — 기동 로그·테스트용. */
    public int size() {
        return midByIndustry.size();
    }

    /**
     * 이 체계가 내놓을 수 있는 소비 중분류 전부.
     *
     * <p>ML 모델이 <b>같은 체계로 학습됐는지</b> 대조할 때 쓴다. 체계를 바꾸고 재학습을 잊으면
     * 모델의 명목 특징이 통째로 죽는데, 크래시가 안 나서 알아채기 어렵다.
     */
    public java.util.Set<String> midCategories() {
        return new java.util.LinkedHashSet<>(midByIndustry.values());
    }
}
