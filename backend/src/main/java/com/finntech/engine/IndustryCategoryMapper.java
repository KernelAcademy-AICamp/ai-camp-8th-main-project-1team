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
 * 들어가는 왜곡이 났고, 지킴이 챌린지에서 배달을 줄이려면 지하철 요금까지 한도에 잡혔다.
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

    private final Map<String, String> midByIndustry;
    private final Map<String, Double> discretionaryByMid;
    private final Map<String, String> pgBusinessNumbers;
    /** 업종 <b>이름</b> → 중분류. LLM 이 축을 직접 고르지 않게 하려고 둔다. */
    private final Map<String, String> midByIndustryName;

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
            Map<String, String> names = (Map<String, String>) root.get("midByIndustryName");
            this.midByIndustryName = names == null ? Map.of() : names;
        } catch (IOException e) {
            throw new UncheckedIOException("업종코드 대조표를 읽지 못했다: " + PATH, e);
        }
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
