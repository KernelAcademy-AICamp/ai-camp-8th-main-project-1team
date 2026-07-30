package com.finntech.service;

import com.finntech.domain.UserPayment;
import com.finntech.service.TasteAnalysisService.HobbyScore;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 취향 집계·요약·세분의 순수 함수만 검증한다(DB·LLM 없음).
 * 역매핑·세분은 hobbies.json 실제 구조를 본뜬다 — '스트리밍'은 직접 취미로 매핑하지 않고
 * refineByMerchant로 음악감상/영상시청/독서구독으로 가른 뒤 취미유형에 매핑한다.
 */
class TasteAnalysisServiceTest {

    private static final Map<String, List<String>> REVERSE = Map.of(
            "여행숙박", List.of("여행"),
            "항공", List.of("여행"),
            "공연전시", List.of("문화공연"),
            "일식", List.of("미식탐방"),
            "양식", List.of("미식탐방"),
            "음악감상", List.of("음악감상"),   // 세분유형 → 취미
            "영상시청", List.of("영화관람"),
            "영화", List.of("영화관람"));

    /** category2 "스트리밍"을 가맹점명으로 세분(부분일치). 챗지피티(AI)는 어디에도 없어 취미 신호가 아니다. */
    private static final Map<String, Map<String, List<String>>> REFINE = Map.of(
            "스트리밍", Map.of(
                    "음악감상", List.of("멜론", "스포티파이", "지니", "애플뮤직"),
                    "영상시청", List.of("넷플릭스", "유튜브", "디즈니", "티빙"),
                    "독서구독", List.of("밀리의서재", "리디")));

    private static UserPayment pay(String category2, int amount, String merchant) {
        return new UserPayment("id-" + category2 + "-" + amount, 1L, "card", 1L,
                LocalDateTime.of(2026, 7, 1, 12, 0), "대분류", category2, amount, merchant, 0, null);
    }

    @Test
    void 취미성_소비만_집계되고_일상소비는_무시된다() {
        List<UserPayment> ps = List.of(
                pay("여행숙박", 200000, "호텔A"),
                pay("항공", 150000, "대한항공"),
                pay("식비", 9000, "김밥천국"),      // 매핑에 없음 → 무시
                pay("편의점", 3000, "CU"));         // 매핑에 없음 → 무시

        List<HobbyScore> out = TasteAnalysisService.aggregate(ps, REVERSE);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).type()).isEqualTo("여행");
        assertThat(out.get(0).count()).isEqualTo(2);
        assertThat(out.get(0).amount()).isEqualTo(350000);
    }

    @Test
    void 건수_내림차순으로_정렬된다_금액이_아니라() {
        // 미식탐방 3건(합 3만) vs 여행 1건(합 20만) → 취미는 빈도가 신호라 미식탐방이 위.
        List<UserPayment> ps = List.of(
                pay("여행숙박", 200000, "호텔"),
                pay("일식", 12000, "스시집"),
                pay("양식", 15000, "파스타"),
                pay("일식", 8000, "돈카츠"));

        List<HobbyScore> out = TasteAnalysisService.aggregate(ps, REVERSE);

        assertThat(out).extracting(HobbyScore::type).containsExactly("미식탐방", "여행");
        assertThat(out.get(0).count()).isEqualTo(3);
    }

    @Test
    void 스트리밍은_가맹점명으로_세분된다_멜론은_음악_넷플릭스는_영상_챗지피티는_제외() {
        List<UserPayment> ps = List.of(
                pay("스트리밍", 10900, "멜론 스트리밍"),    // 음악감상 → 음악감상
                pay("스트리밍", 13500, "넷플릭스 스탠다드"), // 영상시청 → 영화관람
                pay("스트리밍", 29000, "챗지피티플러스"));   // 매칭 없음 → 스트리밍 유지 → 취미 아님

        List<HobbyScore> out = TasteAnalysisService.aggregate(ps, REVERSE, REFINE);

        assertThat(out).extracting(HobbyScore::type)
                .containsExactlyInAnyOrder("음악감상", "영화관람");   // 챗지피티는 빠진다
        assertThat(out).allSatisfy(s -> assertThat(s.count()).isEqualTo(1));
    }

    @Test
    void refineCategory2는_두_생성기_어휘를_모두_커버하고_비대상은_그대로_둔다() {
        // seed 어휘(맨이름) — Catalog.MERCHANTS 스트리밍 = [넷플릭스, 유튜브프리미엄, 멜론]
        assertThat(TasteAnalysisService.refineCategory2("스트리밍", "멜론", REFINE)).isEqualTo("음악감상");
        assertThat(TasteAnalysisService.refineCategory2("스트리밍", "유튜브프리미엄", REFINE)).isEqualTo("영상시청");
        // generation 어휘(서비스명) — products.json
        assertThat(TasteAnalysisService.refineCategory2("스트리밍", "멜론 스트리밍", REFINE)).isEqualTo("음악감상");
        assertThat(TasteAnalysisService.refineCategory2("스트리밍", "밀리의서재", REFINE)).isEqualTo("독서구독");
        // 매칭 없음(AI) → 원 category2 유지
        assertThat(TasteAnalysisService.refineCategory2("스트리밍", "챗지피티플러스", REFINE)).isEqualTo("스트리밍");
        // 세분 대상 아닌 category2는 손대지 않음
        assertThat(TasteAnalysisService.refineCategory2("일식", "스시집", REFINE)).isEqualTo("일식");
        // 가맹점명 null 방어 → 원 category2 유지
        assertThat(TasteAnalysisService.refineCategory2("스트리밍", null, REFINE)).isEqualTo("스트리밍");
    }

    @Test
    void 비중은_취미신호_전체_대비_건수_비율이다() {
        List<UserPayment> ps = List.of(
                pay("일식", 10000, "a"), pay("양식", 10000, "b"), pay("여행숙박", 10000, "c"));
        // 미식탐방 2건, 여행 1건 → 총 3 → 미식탐방 2/3, 여행 1/3
        List<HobbyScore> out = TasteAnalysisService.aggregate(ps, REVERSE);
        assertThat(out.get(0).ratio()).isEqualTo(2.0 / 3.0);
        assertThat(out.get(1).ratio()).isEqualTo(1.0 / 3.0);
    }

    @Test
    void 대표_가맹점은_최대_3개_중복없이() {
        List<UserPayment> ps = List.of(
                pay("일식", 1, "스시A"), pay("일식", 1, "스시A"),   // 중복 → 1개로
                pay("일식", 1, "스시B"), pay("일식", 1, "스시C"), pay("일식", 1, "스시D"));
        List<HobbyScore> out = TasteAnalysisService.aggregate(ps, REVERSE);
        assertThat(out.get(0).sampleMerchants()).hasSize(3).doesNotHaveDuplicates();
    }

    @Test
    void 취미신호가_없으면_빈_결과와_안내문구() {
        List<UserPayment> ps = List.of(pay("식비", 9000, "김밥"), pay("편의점", 3000, "CU"));
        List<HobbyScore> out = TasteAnalysisService.aggregate(ps, REVERSE);
        assertThat(out).isEmpty();
        assertThat(TasteAnalysisService.summarize(out)).contains("아직");
    }

    @Test
    void 요약은_상위_두_취미를_문장으로_옮긴다() {
        List<UserPayment> ps = List.of(
                pay("일식", 1, "a"), pay("양식", 1, "b"), pay("여행숙박", 1, "c"));
        String s = TasteAnalysisService.summarize(TasteAnalysisService.aggregate(ps, REVERSE));
        assertThat(s).contains("미식탐방").contains("여행");
    }
}
