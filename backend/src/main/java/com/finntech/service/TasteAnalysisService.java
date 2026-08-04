package com.finntech.service;

import com.finntech.domain.UserPayment;
import com.finntech.repository.UserPaymentRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 취향·성향 분석 (③ 취향·추천 에이전트) — 마이데이터 결제내역에서 사용자의 취미 성향을 읽는다.
 *
 * <p><b>원리.</b> 일상 지출(식당·편의점·교통)만으로는 성향이 안 드러난다. 마이데이터 생성기는 취미성 지출을
 * '가끔이지만 뚜렷하게' 주입해 취향이 읽히게 설계돼 있다(mydata_catalog §4-B). 이 서비스는 그 신호를
 * {@link HobbyCatalog}의 역매핑(업종코드 → 취미유형)으로 되짚어 취미유형별로 집계한다.
 *
 * <p><b>①과의 경계.</b> ① 소비 분석은 카테고리별 <b>금액 구조</b>를 보고, ③은 <b>취미 유형</b>을 본다
 * (01_prd §11.3: "취향 분석은 ③ 담당"). 같은 거래를 보되 목적이 다르다 — ①은 "얼마 쓰나", ③은 "어떤 사람인가".
 *
 * <p><b>판단은 코드가, 표현은 AI가</b>(§4 원칙 1). 취미유형·건수·금액·비중 계산은 규칙 엔진({@link #aggregate})이
 * 하고, 그 결과를 자연스러운 문장으로 옮기는 것만 LLM(후속) 또는 템플릿이 한다. LLM은 순위·수치를 만들지 않는다.
 *
 * <p><b>점수 기준.</b> 취미는 '가끔이지만 뚜렷한' 신호라 <b>건수(빈도)</b>를 우선 정렬 기준으로 쓴다
 * (금액은 여행 한 번이 매달 카페보다 커서 왜곡됨). 동률이면 금액으로 가른다.
 */
@Service
public class TasteAnalysisService {

    /** 분석 기본 창(개월). 취미는 드물어 너무 짧으면 신호가 안 잡힌다. */
    private static final int DEFAULT_MONTHS = 6;

    private final UserPaymentRepository paymentRepository;
    private final HobbyCatalog hobbyCatalog;
    private final Clock clock;

    public TasteAnalysisService(UserPaymentRepository paymentRepository,
                                HobbyCatalog hobbyCatalog, Clock clock) {
        this.paymentRepository = paymentRepository;
        this.hobbyCatalog = hobbyCatalog;
        this.clock = clock;
    }

    /** 최근 {@code months}개월 마이데이터 결제에서 취향 프로필을 만든다. months≤0이면 기본값. */
    public TasteProfile analyze(Long userId, Integer months) {
        int window = (months == null || months <= 0) ? DEFAULT_MONTHS : months;
        LocalDateTime since = LocalDateTime.now(clock).minusMonths(window);

        List<UserPayment> recent = paymentRepository.findByUserIdOrderByPaymentDateDesc(userId).stream()
                .filter(p -> p.getPaymentDate() != null && !p.getPaymentDate().isBefore(since))
                .toList();

        List<HobbyScore> scores = aggregate(recent, hobbyCatalog.reverseMapping(),
                hobbyCatalog.refineByMerchant());
        String summary = summarize(scores);
        return new TasteProfile(userId, window, recent.size(), scores, summary);
    }

    // ======================================================================
    //  순수 계산 (단위 테스트 진입점)
    // ======================================================================

    /**
     * 결제내역을 취미유형별로 집계해 건수 내림차순(→금액→이름)으로 정렬. 순수·결정론. (가맹점명 세분 없음)
     *
     * @param payments      대상 결제 (industryCode·amount·merchantName 사용)
     * @param reverseMap    업종코드 → 취미유형들 (1:N 허용)
     * @return 취미유형별 점수. 취미 신호가 없으면 빈 리스트.
     */
    static List<HobbyScore> aggregate(List<UserPayment> payments, Map<String, List<String>> reverseMap) {
        return aggregate(payments, reverseMap, Map.of());
    }

    /**
     * 위와 같되, 모호한 업종코드를 가맹점명으로 먼저 세분한다({@link #refineKsic}).
     * 예: {@code 6031 스트리밍}은 음악(멜론)·영상(넷플릭스)·독서(밀리의서재)가 섞여 있어 그대로 매핑하면
     * 취향이 왜곡된다 → 가맹점명으로 음악감상/영상시청/독서구독으로 가른 뒤 취미유형에 매핑한다.
     *
     * @param refineByMerchant 업종코드 → (세분유형 → 가맹점명 키워드들). 부분일치.
     */
    static List<HobbyScore> aggregate(List<UserPayment> payments, Map<String, List<String>> reverseMap,
                                      Map<String, Map<String, List<String>>> refineByMerchant) {
        Map<String, Acc> byHobby = new LinkedHashMap<>();
        for (UserPayment p : payments) {
            String axis = refineKsic(p.getKsicCode(), p.getMerchantName(), refineByMerchant);
            List<String> hobbies = reverseMap.getOrDefault(axis, List.of());
            for (String hobby : hobbies) {
                // 한 결제가 여러 취미의 signature면 각 취미에 카운트된다(1:N, 성향 신호는 겹쳐도 유효).
                Acc a = byHobby.computeIfAbsent(hobby, k -> new Acc());
                a.count++;
                a.amount += Math.max(p.getAmount(), 0);
                if (a.sampleMerchants.size() < 3 && p.getMerchantName() != null
                        && !a.sampleMerchants.contains(p.getMerchantName())) {
                    a.sampleMerchants.add(p.getMerchantName());
                }
            }
        }

        long total = byHobby.values().stream().mapToLong(a -> a.count).sum();
        List<HobbyScore> scores = new ArrayList<>();
        for (Map.Entry<String, Acc> e : byHobby.entrySet()) {
            Acc a = e.getValue();
            double ratio = total == 0 ? 0.0 : (double) a.count / total;
            scores.add(new HobbyScore(e.getKey(), a.count, a.amount, ratio, List.copyOf(a.sampleMerchants)));
        }
        scores.sort(Comparator.comparingInt(HobbyScore::count).reversed()
                .thenComparing(Comparator.comparingLong(HobbyScore::amount).reversed())
                .thenComparing(HobbyScore::type));
        return scores;
    }

    /**
     * 모호한 업종코드를 가맹점명 부분일치로 세분한다. 매칭이 없으면 원래 업종코드를 그대로 돌려준다.
     * 순수 함수 — refineByMerchant는 데이터(리소스)로 주입되고 카테고리를 코드에 박지 않는다(설계원칙 4).
     *
     * <p>세분 축이 업종코드인 이유: 앱이 받는 것은 업종코드까지이고, {@code category2} 는 이제
     * 중분류 16개("취미/여가")로 채워져 소비맥락 이름("스트리밍")으로는 아무것도 맞지 않는다.
     *
     * <p>예: ("6031", "멜론 스트리밍") → "음악감상", ("6031", "넷플릭스") → "영상시청",
     * ("6031", "챗지피티플러스") → "6031"(매칭 없음, 취미 신호 아님), ("5611", …) → "5611"(세분 대상 아님).
     */
    static String refineKsic(String industryCode, String merchantName,
                             Map<String, Map<String, List<String>>> refineByMerchant) {
        if (industryCode == null || refineByMerchant == null) return industryCode;
        Map<String, List<String>> subtypes = refineByMerchant.get(industryCode);
        if (subtypes == null || merchantName == null) return industryCode;
        for (Map.Entry<String, List<String>> e : subtypes.entrySet()) {
            for (String keyword : e.getValue()) {
                if (!keyword.isEmpty() && merchantName.contains(keyword)) return e.getKey();
            }
        }
        return industryCode;
    }

    /**
     * 취향 요약 문장(규칙 템플릿). 판정·수치를 만들지 않고 이미 집계된 상위 취향을 문장으로만 옮긴다.
     * <p>LLM 요약은 후속으로 이 자리를 대체한다(§4 원칙 1: 표현은 AI). 지금은 결정론 템플릿이라 재현·테스트 가능.
     */
    static String summarize(List<HobbyScore> scores) {
        if (scores.isEmpty()) {
            return "아직 취향을 읽을 만한 취미성 소비가 보이지 않아요. 조금 더 쌓이면 알려드릴게요.";
        }
        HobbyScore top = scores.get(0);
        if (scores.size() == 1) {
            return top.type() + " 쪽 소비가 뚜렷해요.";
        }
        HobbyScore second = scores.get(1);
        return top.type() + "과(와) " + second.type() + " 쪽 소비가 자주 보여요.";
    }

    /** 집계용 가변 누산기(내부 전용). */
    private static final class Acc {
        int count;
        long amount;
        final List<String> sampleMerchants = new ArrayList<>();
    }

    // ======================================================================
    //  DTO
    // ======================================================================

    /**
     * 취미유형 하나의 점수. count=결제 건수(정렬 1순위), amount=합계 금액, ratio=취미 신호 중 비중,
     * sampleMerchants=대표 가맹점 최대 3개(근거 표시용).
     */
    public record HobbyScore(String type, int count, long amount, double ratio, List<String> sampleMerchants) {}

    /** analyzedPayments=창 안의 총 결제 수(취미 아닌 것 포함), hobbies=취미유형별 점수, summary=요약 문장. */
    public record TasteProfile(Long userId, int months, int analyzedPayments,
                               List<HobbyScore> hobbies, String summary) {}
}
