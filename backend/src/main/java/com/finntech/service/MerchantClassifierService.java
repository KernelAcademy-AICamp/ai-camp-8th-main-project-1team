package com.finntech.service;

import com.finntech.config.GeminiModels;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.util.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 미분류 결제를 <b>가맹점명만 보고</b> 중분류로 추정한다 — 실데이터에는 업종코드가 없기 때문이다.
 *
 * <p><b>추정은 판정에 쓰지 않는다.</b> 결과는 {@code user_payment.category2_llm} 에만 담기고
 * {@code category2} 는 건드리지 않는다. {@code WasteScoringService} 가 {@code category2} 를 직접
 * 읽어 낭비를 판정하므로, 덮는 순간 <i>"판단은 설명가능한 모델이, 표현은 AI가"</i>(마스터 §4-1)가
 * 깨진다. 화면에는 "AI 추정" 배지로 보이고, <b>사람이 확인해야</b> 확정 분류가 된다
 * ({@link MerchantCategoryService#confirm}).
 *
 * <p>브랜드 일반화가 이쪽의 몫이다 — {@code GS25 역삼점} 을 처음 봐도 편의점인 줄 아는 것.
 * 사전({@link MerchantCategoryService})은 "같은 점포를 두 번 묻지 않는" 일을 맡는다.
 */
@Service
public class MerchantClassifierService {

    private static final Logger log = LoggerFactory.getLogger(MerchantClassifierService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 한 요청에서 부를 수 있는 <b>최대 가맹점 수</b>. 남는 것은 다음 요청이 잇는다.
     *
     * <p><b>이 상수의 뜻이 2026-08-21 에 바뀌었다.</b> 예전에는 40개씩 묶어 물었고 이 값은
     * <i>묶음</i> 수였다(5묶음 = 200곳). 지금은 {@code IndustryPrompt} 규약대로 <b>한 곳씩</b>
     * 묻는다 — 묶으면 모델이 앞 답에 끌려가고, 실측에서 넷플릭스를 단독으로는 맞히는데
     * 40개에 섞으면 빼먹었다(2026-08-05).
     *
     * <p>그래서 이 값은 이제 <b>실제 호출 수</b>다. 유료 통로라 이 구분이 곧 비용이고,
     * 사용자 요청 안에서 불리므로 최악 지연도 이 값이 정한다.
     *
     * <p><b>폭을 넓히지 않는다.</b> 묶어 물던 때와 같은 곳 수를 유지하려면 40 을 줘야 하지만,
     * 그러면 한 요청의 유료 호출이 여덟 배가 된다. 이 통로는 <b>무료가 못 잡은 것만</b> 받는
     * 자리다 — 앞에서 확정 사전·업종코드·등록 조회·무료 모델이 차례로 걸러 내고, 남는 것은
     * 대개 몇 곳이다. 못 잡은 나머지는 다음 요청이 잇는다.
     */
    private static final int MAX_LLM_CALLS_PER_REQUEST = 5;

    /**
     * 첫 판에서 못 잡은 것 중 <b>중요한 것</b>을 다시 물을 때의 추가 예산.
     *
     * <p>같은 곳을 두 번 묻는 것이라 첫 판보다 작게 준다. 무엇이 중요한가는 부르는 쪽이
     * 정한다 — 1건 200원짜리 카드 수수료를 다시 묻는 데 호출을 쓰지 않기 위한 구분이다.
     */
    private static final int MAX_RETRY_CALLS = 3;

    private final IndustryCategoryMapper mapper;
    private final String apiKey;
    private final String model;
    private final RestClient restClient;

    public MerchantClassifierService(
            IndustryCategoryMapper mapper,
            @Value("${finntech.gemini.api-key:}") String apiKey,
            @Value("${finntech.gemini.model:}") String model,
            @Value("${finntech.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl) {
        this.mapper = mapper;
        this.apiKey = apiKey;
        this.model = GeminiModels.orDefault(model);
        this.restClient = RestClient.builder().baseUrl(baseUrl)
                .requestFactory(HttpClients.factory(Duration.ofSeconds(3), Duration.ofSeconds(8)))
                .build();
    }

    public boolean aiEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 물어볼 가치가 있는 가맹점인가.
     *
     * <p><b>PG 는 물어봐도 소용없다.</b> 가맹점명이 PG 상호면 무엇을 샀는지 알 수 없다.
     * 걸러내지 않으면 미분류의 상당수를 차지하는 간편결제 건을 통째로 LLM 에 보내게 된다.
     * 다만 <b>가맹점명이 실제 가게면</b>({@code 삼성물산리조트(주)에버랜드}) 사업자번호가 PG 라도
     * 그 이름으로 물어본다 — 이름이 답을 들고 있기 때문이다.
     */
    public boolean worthAsking(String merchantName, String businessNumber) {
        if (merchantName == null || merchantName.isBlank()) return false;
        String n = merchantName.trim();
        if (n.length() < 2) return false;
        // businessNumber 는 **일부러 보지 않는다.** 인자로 받는 것은 그 사실을 못박기 위해서다 —
        // 여기에 `isPaymentAgency(businessNumber)` 를 넣고 싶어지는데, 넣으면 PG 를 거친 결제가
        // 통째로 빠진다. 그런데 그것들이야말로 이름에 답이 있는 결제다
        // (KG모빌리언스 번호 + `삼성물산리조트(주)에버랜드`). 번호가 PG 라는 것은
        // "업종코드를 믿지 말라"는 뜻이지 "이름도 쓸모없다"는 뜻이 아니다.
        return !isAgencyName(n);
    }

    /**
     * 상호 자체가 결제대행사 이름인가 — <b>그러면 무엇을 샀는지 원리적으로 알 수 없다.</b>
     *
     * <p>판정은 <b>번호가 아니라 이름</b>으로 한다. {@code Apple} 은 카카오페이 번호로 들어와도
     * 이름이 무엇을 샀는지 말해 주고, 반대로 상호가 {@code 네이버페이} 면 카드사가 준 정보가
     * 그것뿐이라 앱이 알 길이 없다. 이 둘을 똑같이 '카테고리없음'에 두면 사용자는
     * <i>"앱이 못 하는 것"</i>과 <i>"내가 알려주면 되는 것"</i>을 구분할 수 없다.
     *
     * <p>이 값은 <b>표시에만</b> 쓴다 — 카테고리 축을 늘리지 않는다(마스터 §4 원칙 4).
     */
    public boolean isPaymentAgencyMerchant(String merchantName) {
        return merchantName != null && !merchantName.isBlank() && isAgencyName(merchantName.trim());
    }

    /**
     * <b>PG 상호를 걷어낸 뒤에도 무엇이 남는가.</b> 남으면 그것이 진짜 가맹점이다.
     *
     * <p>예전에는 {@code contains} 하나였다 — <b>PG 이름이 어딘가 박혀 있기만 하면</b> 통째로
     * 뺐다. 그런데 실 명세서의 간편결제 상호는 {@code CGV_카카오페이}·{@code KICC(일반)-주식회사 설빙}
     * 처럼 <b>PG 이름 + 진짜 가맹점</b> 꼴이라, 위 javadoc 이 "상호 자체가 PG 이름인가"라고
     * 적어 둔 것과 코드가 달랐다. 실사용자 미분류 15종 중 <b>14종</b>이 여기서 빠졌고,
     * 그중 상당수는 모델이 이름만 보고 맞힌다(실측: {@code CGV_카카오페이} → 영화관 운영업).
     *
     * <p>그래서 <b>빼고 남은 것</b>으로 가른다. {@code (주)카카오페이} 는 빼면 아무것도 안 남아
     * 여전히 걸리고, {@code CGV_카카오페이} 는 {@code CGV} 가 남아 물어볼 값이 있다.
     * (2026-08-21 운영 실측)
     */
    private boolean isAgencyName(String name) {
        return residueOf(name).isEmpty();
    }

    /**
     * <b>PG 상호와 업태명을 걷어낸 나머지</b> — 이것이 모델에게 물어볼 이름이다.
     *
     * <p>비어 있으면 무엇을 샀는지 원리적으로 알 수 없다는 뜻이다.
     *
     * <p><b>업태명도 함께 뺀다</b>(2026-08-21 실측). {@code NICE_통신판매} 는 PG 를 빼면
     * {@code 통신판매} 가 남는데 그것은 <i>업태</i>이지 가맹점이 아니다. 그런데 모델은 거기서
     * {@code 전자상거래 소매업} 을 답했고, 홍상호의 21건 59,806원이 근거 없이 <b>쇼핑</b>으로
     * 붙었다. {@code 비인증_스마트로} 는 {@code 비인증} 이 남아 <b>주거/통신</b> 50,000원이
     * 됐다 — 그 사람 지출의 27%다.
     */
    public String residueOf(String name) {
        String n = bareName(name);
        if (n.isEmpty()) return "";
        boolean sawAgency = false;
        for (String pg : agencyForms()) {
            if (pg.isEmpty() || !n.contains(pg)) continue;
            sawAgency = true;
            n = n.replace(pg, "");
        }
        if (!sawAgency) return n;                 // PG 가 안 섞였으면 원문 그대로 물어본다
        for (String filler : TRADE_WORDS) {
            n = n.replace(bareName(filler), "");
        }
        return n.isBlank() ? "" : n;
    }

    /**
     * 상호에서 걷어낼 <b>결제대행사 표기</b> — 대조표의 이름에 <b>별칭</b>을 더한다.
     *
     * <p>대조표({@code industry-mid.json})는 <b>사업자번호</b>로 PG 를 가리려고 만든 것이라
     * 법인명만 있다({@code 네이버파이낸셜}·{@code 나이스페이먼츠}). 그런데 명세서에 찍히는
     * 것은 서비스명이다({@code 네이버페이}·{@code NICE}). 실측(2026-08-21)에서
     * {@code 구글_네이버페이} 가 안 깎여 그대로 프롬프트에 갔고 모델이 모름을 답했다.
     *
     * <p>대조표를 고치지 않는 이유는 <b>쓰임이 다르기 때문</b>이다 — 저기 이름을 더하면
     * {@code isPaymentAgency(번호)} 쪽 판정까지 흔들린다. 여기는 이름만 본다.
     */
    private java.util.List<String> agencyForms() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String pg : mapper.paymentAgencyNames()) out.add(bareName(pg));
        for (String alias : AGENCY_ALIASES) out.add(bareName(alias));
        // 긴 것부터 지운다 — `토스페이먼츠` 를 `토스페이` 가 먼저 깎으면 `먼츠` 가 남는다.
        out.sort((a, b) -> b.length() - a.length());
        return out;
    }

    /** 대조표에 없는 결제대행사 표기 — 명세서에 실제로 찍히는 서비스명들. */
    private static final java.util.List<String> AGENCY_ALIASES = java.util.List.of(
            "네이버페이", "토스페이", "카카오선물하기", "삼성페이", "애플페이", "페이코", "PAYCO",
            "스마트로", "KCP", "NICE", "나이스", "KIS", "올더게이트", "세틀뱅크", "다우데이타",
            "한국신용카드결제", "한국사이버결제", "갤럭시아", "모빌리언스", "스토리페이", "당근페이");

    /**
     * PG 를 뺀 자리에 남는 <b>업태·안내 문구</b> — 가맹점 이름이 아니다.
     *
     * <p>여기 있는 낱말만 남았다면 카드사가 준 정보는 "간편결제로 무언가를 샀다"뿐이다.
     * 목록을 늘릴 때는 <b>그 낱말만으로 무엇을 샀는지 말할 수 있는가</b>를 물어야 한다 —
     * {@code 무신사}·{@code 구글} 은 말할 수 있고 {@code 일반}·{@code 통신판매} 는 못 한다.
     */
    private static final List<String> TRADE_WORDS = List.of(
            "통신판매", "비인증", "일반", "오더", "결제", "쇼핑몰", "온라인", "정기결제", "자동이체",
            "상품권", "충전", "선불", "간편결제", "휴대폰", "계좌이체", "가맹점", "KIOSK", "POS");

    /**
     * 상호에서 <b>이름이 아닌 것</b>을 걷어낸다 — 공백·괄호·구분자와 법인격 표기.
     *
     * <p>법인격은 {@code 주식회사}·{@code ㈜} 처럼 <b>낱말째로</b> 뺀다. 글자 {@code 주}
     * 하나를 아무 데서나 빼면 {@code 네이버파이낸셜 주식회사} 가 {@code 네이버파이낸셜식회사}
     * 가 되어 PG 인데도 안 걸린다(옛 코드가 그랬다).
     *
     * <p>{@link #squash} 와는 쓰임이 다르다 — 저쪽은 <b>업종명</b> 대조용이라 법인격을 모른다.
     */
    private static String bareName(String s) {
        return s == null ? "" : DECOR.matcher(s).replaceAll("").toUpperCase();
    }

    private static final java.util.regex.Pattern DECOR = java.util.regex.Pattern.compile(
            "주식회사|유한회사|㈜|\\(주\\)|[\\s()（）\\[\\]{}·・/\\\\|,\\-_.]");

    /**
     * 가맹점명들을 중분류로 추정한다. <b>명백하지 않으면 그 이름은 결과에 없다</b> —
     * 억지로 붙이면 "AI 추정"이 오히려 방해가 된다.
     *
     * @return 가맹점명 → 중분류. 정렬 고정(§4-3 재현성).
     */
    public Map<String, String> classify(List<String> merchantNames) {
        return classify(merchantNames, java.util.Set.of());
    }

    /**
     * 가맹점명들을 중분류로 추정한다 — <b>두 판으로 나눈다.</b>
     *
     * <p>첫 판은 40개씩 묶어 훑는다. 그런데 모델은 <b>알고 있는데도 큰 묶음에서 흘린다</b>
     * (2026-08-05 실측: 넷플릭스를 단독으로 물으면 맞히는데 40개에 섞으면 빼먹었다).
     * 그래서 <b>못 잡은 것 중 중요한 것만</b> 작게 나눠 한 번 더 묻는다.
     *
     * <p>무엇이 중요한가는 여기서 정하지 않는다 — 결제 건수·금액을 아는 것은 부르는 쪽이다.
     * 1건 200원짜리 카드 수수료를 다시 묻는 데 호출을 쓰지 않기 위한 구분이다.
     *
     * @param important 못 잡았을 때 <b>다시 물을 값어치가 있는</b> 가맹점명
     * @return 가맹점명 → 중분류. 정렬 고정(§4-3 재현성).
     */
    public Map<String, String> classify(List<String> merchantNames, java.util.Set<String> important) {
        return classify(merchantNames, important, new TreeMap<>());
    }

    /**
     * {@link #classify} 와 같되 <b>모델이 답한 업종 이름을 함께 담아 준다.</b>
     *
     * <p>필요한 곳이 하나 있다 — 두 통로가 갈렸을 때의 재질의({@link #tieBreak})다. 후보를
     * 중분류로 주면 모델에게 <b>우리 축을 직접 고르게</b> 하는 셈이라 마스터 §4 원칙 1 과
     * 어긋난다. 업종 이름으로 물어야 모델은 "이 가게가 무엇을 파는가"라는 사실만 말하고
     * 축 배정은 표가 한다. 그런데 {@code classify} 는 이미 중분류로 바꿔 돌려주므로
     * 원본 이름이 남지 않는다 — 그래서 여기로 흘려 준다.
     *
     * @param industries 가맹점명 → 모델이 답한 업종 이름. 호출자가 준 지도에 채워 넣는다.
     */
    public Map<String, String> classify(List<String> merchantNames, java.util.Set<String> important,
                                        Map<String, String> industries) {
        return classify(merchantNames, important, industries, new java.util.HashSet<>());
    }

    /**
     * {@link #classify} 와 같되 <b>실제로 물어보고 답을 받은 가맹점</b>을 따로 담아 준다.
     *
     * <p>부르는 쪽이 이것을 알아야 하는 이유가 하나다 — <b>'헛물'을 세는 자리</b>. 답에 없는
     * 가맹점을 "모델이 침묵했다"로 세어 세 번이면 '기타'로 종결하는데, 그 세기가 성립하려면
     * <b>물어보기는 했다</b>가 참이라야 한다. 그런데 이 메서드는 <b>묻지 않고 빈손으로 돌아가는
     * 길이 셋</b>이다.
     *
     * <pre>
     *   ① 키가 없다        aiEnabled() 가 false 면 HTTP 를 한 번도 안 내고 빈 지도를 준다
     *   ② 호출이 실패했다   429·타임아웃이면 그 묶음이 통째로 null 이다
     *   ③ 상한을 넘었다     MAX_LLM_CALLS_PER_REQUEST × BATCH 를 넘은 뒤쪽은 프롬프트에 안 담긴다
     * </pre>
     *
     * <p>셋 다 "그 가맹점에 대해 모델이 침묵했다"가 아니라 <b>"우리가 못 물었다"</b>이다.
     * 구별하지 않으면 키를 안 넣은 환경에서 화면을 세 번 여는 것만으로 미분류 전부가 '기타'로
     * 종결되고, 종결은 결제와 소비 원장까지 고치므로 리포트에서 그 지출이 사라진다
     * (2026-08-07 재감사).
     *
     * @param answered 답을 받은 가맹점명이 여기 담긴다 — 호출자가 준 집합에 채워 넣는다.
     *                 표기가 다른 형제까지 함께 담는다(대표가 답을 받으면 형제도 받은 것이다).
     */
    public Map<String, String> classify(List<String> merchantNames, java.util.Set<String> important,
                                        Map<String, String> industries,
                                        java.util.Set<String> answered) {
        Map<String, String> out = new TreeMap<>();
        if (!aiEnabled() || merchantNames == null || merchantNames.isEmpty()) return out;

        // 법인격 표기만 다른 같은 가맹점은 **한 번만 묻는다.** '(주)우아한형제들'과 '우아한형제들'을
        // 따로 물으면 호출이 두 배로 들 뿐 아니라, 한쪽만 답을 받아 같은 가게가 갈린다 —
        // 2026-08-05 실측에서 실제로 갈렸다(하나는 쇼핑, 하나는 미분류). 이 명세서에서 이렇게
        // 갈린 곳이 4곳 54만원이었고, 그중 배달앱 한 곳이 26만원이다.
        Map<String, List<String>> variants = new LinkedHashMap<>();
        for (String n : merchantNames) {
            if (n == null || n.isBlank()) continue;
            variants.computeIfAbsent(corporateFormStripped(n), k -> new ArrayList<>()).add(n);
        }
        List<String> distinct = variants.values().stream().map(v -> v.get(0)).toList();

        Map<String, String> byRepresentative = new TreeMap<>();
        // 답을 받은 **대표**를 모은다. 형제로 펼치는 것은 아래 한 곳에서 한꺼번에 한다.
        java.util.Set<String> answeredReps = new java.util.HashSet<>();
        // **한 곳씩 묻는다.** 계수기도 가맹점마다 오른다 — 묶음을 세던 때의 계수기를 그대로
        // 두면 상한 5가 실제로는 200회를 허락한다(유료 통로라 그대로 비용이다).
        int calls = 0;
        for (String name : distinct) {
            if (calls >= MAX_LLM_CALLS_PER_REQUEST) break;
            calls++;
            Map<String, String> got = callGemini(List.of(name), industries);
            // **null 은 "물어봤다"가 아니다.** 429·타임아웃이면 프롬프트에 담겼어도 답이 없다 —
            // 그것을 가맹점의 침묵으로 세면 통로 장애가 데이터를 종결시킨다.
            if (got == null) continue;
            byRepresentative.putAll(got);
            answeredReps.add(name);
        }

        // 두 번째 판 — 못 잡은 것 중 중요한 것만, 작게.
        // 대표 하나가 중요하지 않아도 **표기가 다른 형제 중 하나라도** 중요하면 다시 묻는다.
        List<String> retry = new ArrayList<>();
        for (var e : variants.entrySet()) {
            String representative = e.getValue().get(0);
            if (!byRepresentative.containsKey(representative)
                    && e.getValue().stream().anyMatch(important::contains)) {
                retry.add(representative);
            }
        }
        int retryCalls = 0;
        for (String name : retry) {
            if (retryCalls >= MAX_RETRY_CALLS) break;
            retryCalls++;
            Map<String, String> got = callGemini(List.of(name), industries);
            if (got == null) continue;
            byRepresentative.putAll(got);
            answeredReps.add(name);
        }

        // 대표가 받은 답을 **표기가 다른 형제 전부**에 돌려준다. '물어봤다'도 같이 펼친다 —
        // 형제는 대표에 접혀서 안 물어본 것이라, 대표가 답을 받았으면 형제도 받은 것으로 본다.
        for (List<String> group : variants.values()) {
            String rep = group.get(0);
            if (answeredReps.contains(rep)) answered.addAll(group);
            String answer = byRepresentative.get(rep);
            if (answer != null) group.forEach(n -> out.put(n, answer));
            String ind = industries.get(rep);
            if (ind != null) group.forEach(n -> industries.put(n, ind));
        }
        return out;
    }

    /**
     * <b>두 모델이 갈렸을 때 둘 중 하나를 고르게 한다</b> — 385개 목록 대신 <b>후보 둘</b>만 준다.
     *
     * <p>무료 통로(임시 분류)와 유료 통로가 같은 가맹점에 다른 업종을 답하는 일이 있다. 어느
     * 쪽을 믿을지 우리가 정할 근거가 없고, 그렇다고 아무거나 쓰면 반은 틀린다. 그래서 <b>다시
     * 묻되 질문을 좁힌다</b> — 385개 중 고르라는 것과 둘 중 고르라는 것은 난이도가 다르다.
     *
     * <p>싸다. 프롬프트의 76%를 차지하던 업종 목록이 통째로 빠지고 후보 두 개만 남는다.
     * 갈린 가맹점만 대상이라 건수도 적다.
     *
     * <p><b>후보는 업종 이름이다.</b> 중분류를 주면 모델이 우리 축을 직접 고르는 셈이라 원칙 1 과
     * 어긋난다. 업종으로 물으면 모델은 "이 가게가 무엇을 파는가"만 말하고 축 배정은 표가 한다 —
     * 첫 질의와 같은 규칙이다.
     *
     * @param candidates 가맹점명 → {A안, B안} 업종 이름 둘
     * @return 가맹점명 → 고른 <b>업종 이름</b>. 못 고른 것은 빠진다.
     */
    public Map<String, String> tieBreak(Map<String, String[]> candidates) {
        Map<String, String> out = new TreeMap<>();
        if (!aiEnabled() || candidates == null || candidates.isEmpty()) return out;

        List<String> names = new ArrayList<>(new TreeMap<>(candidates).keySet());
        StringBuilder listing = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            String[] two = candidates.get(names.get(i));
            listing.append(i + 1).append(". ").append(names.get(i))
                    .append("  → A) ").append(two[0]).append("  B) ").append(two[1]).append('\n');
        }
        String prompt = """
                아래는 한국 카드 명세서의 가맹점명입니다. 각 가맹점의 업종으로 A 와 B 중 어느 쪽이
                맞는지 고르세요. 판단이 안 서면 빼세요.

                %s
                답은 JSON 하나로만 주세요. 값은 "A" 또는 "B" 입니다.
                {"1": "A", "2": "B"}
                """.formatted(listing);

        Map<String, String> picked = askRaw(prompt, names);
        picked.forEach((name, ab) -> {
            String[] two = candidates.get(name);
            if (two == null) return;
            if ("A".equalsIgnoreCase(ab.trim())) out.put(name, two[0]);
            else if ("B".equalsIgnoreCase(ab.trim())) out.put(name, two[1]);
        });
        return out;
    }

    /**
     * 법인격 표기를 걷어낸 비교용 이름 — {@code (주)우아한형제들}·{@code 주식회사 우아한형제들}·
     * {@code 우아한형제들}이 <b>같은 가맹점</b>임을 알아보기 위한 것이다.
     *
     * <p>사전의 키는 여전히 <b>풀네임</b>이다. 여기서 지우는 것은 "누구에게 물을지"를 정할 때뿐이라,
     * 지점명({@code GS25 강남역점})처럼 실제로 다른 점포를 뭉뚱그릴 위험이 없다.
     */
    static String corporateFormStripped(String name) {
        return name.replaceAll("\\(\\s*주\\s*\\)|\\(\\s*유\\s*\\)|㈜|㈠|주식회사|유한회사|합자회사", "")
                .replaceAll("\\s+", "")
                .toUpperCase();
    }

    /**
     * 유료 통로에 <b>한 곳씩</b> 묻는다.
     *
     * <p><b>중분류가 아니라 업종을 묻는다.</b> 모델이 우리 축을 직접 고르면 축 배정까지 AI 가
     * 하는 셈이라 원칙 1 과 어긋나고, 표를 고쳐도 모델의 옛 답은 안 따라온다.
     * 2026-08-05 전수 대조(실데이터 86종): 중분류를 직접 묻는 방식 36종 → 업종을 묻는 방식
     * <b>55종</b>이었다.
     *
     * <p><b>목록에서 중분류를 뺐다</b>(2026-08-21). 예전에는 {@code [카페/간식] 커피 전문점}
     * 처럼 축을 함께 보여줬는데, 그러면 모델이 업종이 아니라 축을 보고 답한다 — 실측에서
     * 상위 모델의 오답 대부분이 거기서 나왔다. 규칙 여섯 줄은 그대로 옮겼다
     * ({@link IndustryPrompt} 설계 주석에 그 내력이 있다).
     *
     * <p><b>묶어 묻지 않는다.</b> 40곳을 번호로 묶으면 모델이 앞 답에 끌려가고, JSON 형식이
     * 깨지면 묶음 전체를 잃었다. 한 곳씩이면 답은 단어 하나이고 실패해도 그 하나만 잃는다.
     */
    private Map<String, String> callGemini(List<String> names, Map<String, String> industries) {
        String industryList = IndustryPrompt.industryList(mapper);
        Map<String, String> out = new TreeMap<>();
        int consecutive = 0;
        for (String name : names) {
            String text = askOneRaw(IndustryPrompt.of(name, industryList));
            if (text == null) {
                // 연달아 셋이 죽고 하나도 못 얻었으면 통로가 죽은 것이다 — 다음 회차로 미룬다.
                if (++consecutive >= 3 && out.isEmpty()) return null;
                continue;
            }
            consecutive = 0;
            String industry = IndustryPrompt.pickIndustry(text, mapper);
            if (industry == null) continue;
            industries.put(name, industry);
            String mid = mapper.midOfIndustryName(industry);
            if (!IndustryCategoryMapper.isUnknown(mid)) out.put(name, mid);
        }
        return out;
    }

    /**
     * <b>최초 연동용 일괄 분류</b> — 40곳씩 끊어 유료 통로에 묻는다.
     *
     * <h2>왜 이 자리만 예외인가</h2>
     *
     * <p>평소에는 무료 통로가 <b>한 곳씩</b> 물어 채운다. 그런데 최초 연동은 <b>사람이 로딩
     * 화면 앞에서 기다린다</b>. 110종을 한 곳씩 물으면 호출 예산(분당 40)에 걸려 3분이고,
     * 40곳씩 묶으면 세 번이라 30초다. 그 차이가 첫인상을 가른다.
     *
     * <p>대신 <b>이 자리에서만</b> 쓴다. 이후 재분류는 그대로 한 곳씩이고, 여기서 못 맞힌 것도
     * 무료 통로가 이어받는다 — 이 메서드는 <b>먼저 크게 훑는 것</b>이지 유일한 통로가 아니다.
     *
     * @param merchantNames 물어볼 상호들. PG 를 걷어낸 나머지가 비는 것은 부르는 쪽이 이미 뺐다.
     * @param industries    (선택) 알아낸 <b>업종 이름</b>을 함께 받아 갈 지도 — 업종코드를 되찾는 데 쓴다
     * @return 가맹점명 → 중분류. 못 맞힌 것은 없다.
     */
    public Map<String, String> classifyInBulk(List<String> merchantNames,
                                              Map<String, String> industries) {
        Map<String, String> out = new TreeMap<>();
        if (!aiEnabled() || merchantNames == null || merchantNames.isEmpty()) return out;

        String industryList = IndustryPrompt.industryList(mapper);
        List<String> distinct = merchantNames.stream()
                .filter(n -> n != null && !n.isBlank())
                .distinct().sorted().toList();          // 정렬 고정 — §4 원칙 3 재현성

        int batches = 0;
        for (int from = 0; from < distinct.size(); from += BULK_BATCH) {
            if (batches >= MAX_BULK_BATCHES) {
                log.info("일괄 분류 — 묶음 상한 {}개를 채워 {}곳에서 멈춘다. 남은 것은 무료 통로가 잇는다",
                        MAX_BULK_BATCHES, from);
                break;
            }
            batches++;
            List<String> slice = distinct.subList(from, Math.min(from + BULK_BATCH, distinct.size()));
            // **묻는 이름은 PG 를 걷어낸 것이다.** 원문을 주면 "결제대행사는 모름" 규칙에 걸린다.
            List<String> asked = slice.stream().map(this::residueOf).toList();
            String text = askOneRaw(IndustryPrompt.ofMany(asked, industryList), BULK_MAX_TOKENS);
            if (text == null) continue;                 // 이 묶음만 건너뛴다 — 다음 묶음은 산다

            for (var e : IndustryPrompt.pickMany(text, mapper).entrySet()) {
                int at = e.getKey();
                if (at >= slice.size()) continue;       // 모델이 없는 번호를 지어냈다
                String name = slice.get(at);
                String industry = e.getValue();
                String mid = mapper.midOfIndustryName(industry);
                if (IndustryCategoryMapper.isUnknown(mid)) continue;
                if (industries != null) industries.put(name, industry);
                out.put(name, mid);
            }
        }
        log.info("일괄 분류 — 가맹점 {}곳, 묶음 {}개, 붙인 곳 {}", distinct.size(), batches, out.size());
        return out;
    }

    /** 한 묶음에 담는 가맹점 수. 늘리면 모델이 중간에서 흘리고, 줄이면 묶는 값이 없다. */
    private static final int BULK_BATCH = 40;

    /**
     * 최초 연동 한 번에 낼 <b>묶음 수 상한</b> — 유료 통로라 값이 든다.
     *
     * <p>40 × 5 = 200곳이면 실사용자 명세서 한 벌을 거의 덮는다(실측: 가장 많은 사람이 110종).
     * 넘는 것은 무료 통로가 이어받으므로 잃는 것은 시간뿐이다.
     */
    private static final int MAX_BULK_BATCHES = 5;

    /** 40줄 × 한 줄 30자 남짓 — 답이 잘리면 뒤쪽 가맹점이 통째로 날아간다. */
    private static final int BULK_MAX_TOKENS = 2048;

    /** 한 번 묻고 본문만 받는다 — 실패는 {@code null}. */
    private String askOneRaw(String prompt) {
        return askOneRaw(prompt, 32);
    }

    private String askOneRaw(String prompt, int maxOutputTokens) {
        try {
            Map<?, ?> response = restClient.post()
                    .uri("/v1beta/models/{model}:generateContent?key={key}", model, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    // temperature 0 — **같은 명세서를 두 번 넣으면 같은 답이 나와야 한다.**
                    // 기본 표집 온도로 두면 실행마다 39·55·59·61종으로 흔들렸다(2026-08-05 실측).
                    .body(Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                                 "generationConfig", Map.of("temperature", 0,
                                                            "maxOutputTokens", maxOutputTokens)))
                    .retrieve()
                    .body(Map.class);
            return extractText(response);
        } catch (Exception e) {
            // 미분류로 남을 뿐 화면이 깨지지는 않는다 — 추정은 부가 정보다.
            return null;
        }
    }

    /**
     * 프롬프트 하나를 보내고 <b>번호 → 값</b> JSON 을 가맹점명 → 값으로 되돌린다.
     *
     * <p>{@link #callGemini} 와 달리 값을 업종으로 해석하지 않는다 — 무엇을 묻든 쓸 수 있는
     * 통로다({@link #tieBreak} 는 "A"/"B" 를 받는다). 실패는 빈 지도다.
     */
    private Map<String, String> askRaw(String prompt, List<String> names) {
        Map<String, String> out = new TreeMap<>();
        try {
            Map<?, ?> response = restClient.post()
                    .uri("/v1beta/models/{model}:generateContent?key={key}", model, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                                 "generationConfig", Map.of("temperature", 0)))
                    .retrieve()
                    .body(Map.class);
            String text = extractText(response);
            if (text == null) return out;
            int s = text.indexOf('{'), e = text.lastIndexOf('}');
            if (s < 0 || e <= s) return out;
            Map<?, ?> m = MAPPER.readValue(text.substring(s, e + 1), Map.class);
            for (var entry : m.entrySet()) {
                int idx;
                try {
                    idx = Integer.parseInt(entry.getKey().toString().trim());
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (idx < 1 || idx > names.size() || entry.getValue() == null) continue;
                out.put(names.get(idx - 1), entry.getValue().toString());
            }
        } catch (Exception e) {
            return out;      // 못 고르면 그냥 유료 통로의 답을 쓴다 — 화면이 깨지지 않는다
        }
        return out;
    }

    /** 응답에서 <b>업종 이름 원문</b>만 따로 걷어 둔다 — 재질의가 그것을 후보로 쓴다. */
    private void collectIndustries(String text, List<String> names, Map<String, String> into) {
        if (into == null) return;
        int s = text.indexOf('{'), e = text.lastIndexOf('}');
        if (s < 0 || e <= s) return;
        try {
            Map<?, ?> m = MAPPER.readValue(text.substring(s, e + 1), Map.class);
            for (var entry : m.entrySet()) {
                int idx;
                try {
                    idx = Integer.parseInt(entry.getKey().toString().trim());
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (idx < 1 || idx > names.size() || entry.getValue() == null) continue;
                String ind = entry.getValue().toString().trim();
                if (!ind.isBlank()) into.put(names.get(idx - 1), ind);
            }
        } catch (Exception ignored) {
            // 업종 이름은 있으면 좋은 것이다 — 못 걷어도 분류 자체는 parseJson 이 한다.
        }
    }

    /**
     * 번호 → <b>업종 이름</b> JSON 을 가맹점명 → 중분류로 되돌린다.
     *
     * <p>모델은 업종만 답하고 축 배정은 우리 표가 한다. 못 옮기는 답은 버린다 —
     * 지어낸 이름이 들어오지 못한다.
     */
    Map<String, String> parseJson(String text, List<String> names) {
        int s = text.indexOf('{'), e = text.lastIndexOf('}');
        if (s < 0 || e <= s) return null;
        Map<String, String> out = new TreeMap<>();
        try {
            Map<?, ?> m = MAPPER.readValue(text.substring(s, e + 1), Map.class);
            for (var entry : m.entrySet()) {
                int idx;
                try {
                    idx = Integer.parseInt(entry.getKey().toString().trim());
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (idx < 1 || idx > names.size() || entry.getValue() == null) continue;
                String mid = toMid(entry.getValue().toString());
                if (mid != null) out.put(names.get(idx - 1), mid);
            }
            return out;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 모델이 답한 <b>업종 이름</b>을 우리 중분류로 옮긴다. 못 옮기면 {@code null} — 버린다.
     *
     * <p>세 단계로 본다.
     * <ol>
     *   <li>대조표에 <b>정확히</b> 있는 업종 이름</li>
     *   <li>중분류를 곧장 답한 경우(모델이 대괄호 안의 것을 그대로 쓰기도 한다)</li>
     *   <li><b>근사 일치</b> — 목록 밖 답의 대부분이 "거의 맞는" 이름이다. 2026-08-05 실측에서
     *       6건이 버려졌는데 {@code 기타 상품 전문 소매업}(우리 것은 {@code 그 외 기타 분류
     *       안된 상품 전문 소매업}), {@code 화장품, 비nu 및 방향제 소매업}(오타) 처럼 회수
     *       가능한 것이었다. 공백·쉼표를 지우고 <b>한쪽이 다른 쪽을 품으면</b> 같은 것으로 본다.</li>
     * </ol>
     *
     * <p>근사 일치는 <b>후보가 하나일 때만</b> 받는다. 여럿이면 어느 중분류인지 알 수 없어
     * 넘겨짚는 셈이 된다 — 모르는 것을 아는 척하지 않는다.
     */
    String toMid(String answer) {
        if (answer == null || answer.isBlank()) return null;
        String a = answer.trim();

        String exact = mapper.midOfIndustryName(a);
        if (!IndustryCategoryMapper.UNCLASSIFIED.equals(exact)) return exact;
        if (mapper.midCategories().contains(a)) return a;          // 중분류를 곧장 답한 경우

        String key = squash(a);
        if (key.isEmpty()) return null;
        String hit = null;
        for (var e : mapper.industryNamesByMid().entrySet()) {
            for (String name : e.getValue()) {
                String n = squash(name);
                if (!n.contains(key) && !key.contains(n)) continue;
                if (hit != null && !hit.equals(e.getKey())) return null;   // 갈리면 버린다
                hit = e.getKey();
            }
        }
        return hit;
    }

    /** 비교용 — 공백·쉼표·괄호를 지운다. 표기 차이로 같은 업종이 남이 되지 않게. */
    private static String squash(String s) {
        return s == null ? "" : s.replaceAll("[\\s,()（）·]", "");
    }

    @SuppressWarnings("unchecked")
    private static String extractText(Map<?, ?> response) {
        if (response == null) return null;
        try {
            var candidates = (List<Map<String, Object>>) response.get("candidates");
            if (candidates == null || candidates.isEmpty()) return null;
            var content = (Map<String, Object>) candidates.get(0).get("content");
            var parts = (List<Map<String, Object>>) content.get("parts");
            if (parts == null || parts.isEmpty()) return null;
            Object t = parts.get(0).get("text");
            return t == null ? null : t.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
