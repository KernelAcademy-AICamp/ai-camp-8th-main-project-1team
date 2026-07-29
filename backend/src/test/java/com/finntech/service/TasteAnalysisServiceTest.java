package com.finntech.service;

import com.finntech.domain.UserPayment;
import com.finntech.service.TasteAnalysisService.HobbyScore;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 취향 집계·요약의 순수 함수만 검증한다(DB·LLM 없음).
 *
 * <p>역매핑의 키는 <b>업종코드</b>다 — 앱이 제공자에게서 받는 것이 거기까지이기 때문이다.
 * 실제 taste/hobbies.json 구조를 본뜬다(여행=7521 여행사/5110 항공, 6031 스트리밍은
 * 디지털게임·영화관람 두 취미의 signature라 중복).
 */
class TasteAnalysisServiceTest {

    private static final Map<String, List<String>> REVERSE = Map.of(
            "7521", List.of("여행"),          // 여행사업
            "5110", List.of("여행"),          // 항공 여객 운송업
            "9011", List.of("문화공연"),       // 공연시설 운영업
            "5612", List.of("미식탐방"),       // 외국식 음식점업
            "6031", List.of("디지털게임", "영화관람"),   // 한 업종코드가 두 취미의 signature
            "5914", List.of("영화관람"));      // 영화 상영업

    /** 취향 분석은 업종코드만 본다. 중분류는 같은 결제에 실려 있어도 집계에 관여하지 않는다. */
    private static UserPayment pay(String ksic, int amount, String merchant) {
        return new UserPayment("id-" + ksic + "-" + amount + "-" + merchant, 1L, "card", 1L,
                LocalDateTime.of(2026, 7, 1, 12, 0), ksic, "중분류", amount, merchant, 0, null);
    }

    @Test
    void 취미성_소비만_집계되고_일상소비는_무시된다() {
        List<UserPayment> ps = List.of(
                pay("7521", 200000, "호텔A"),
                pay("5110", 150000, "대한항공"),
                pay("5611", 9000, "김밥천국"),      // 한식 — 매핑에 없음 → 무시
                pay("4712", 3000, "CU"));           // 편의점 — 매핑에 없음 → 무시

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
                pay("7521", 200000, "호텔"),
                pay("5612", 12000, "스시집"),
                pay("5612", 15000, "파스타"),
                pay("5612", 8000, "돈카츠"));

        List<HobbyScore> out = TasteAnalysisService.aggregate(ps, REVERSE);

        assertThat(out).extracting(HobbyScore::type).containsExactly("미식탐방", "여행");
        assertThat(out.get(0).count()).isEqualTo(3);
    }

    @Test
    void 한_결제가_여러_취미의_signature면_모두에_카운트된다() {
        // 6031(스트리밍) → 디지털게임·영화관람 둘 다.
        List<UserPayment> ps = List.of(pay("6031", 14900, "넷플릭스"));

        List<HobbyScore> out = TasteAnalysisService.aggregate(ps, REVERSE);

        assertThat(out).extracting(HobbyScore::type)
                .containsExactlyInAnyOrder("디지털게임", "영화관람");
        assertThat(out).allSatisfy(s -> assertThat(s.count()).isEqualTo(1));
    }

    @Test
    void 비중은_취미신호_전체_대비_건수_비율이다() {
        List<UserPayment> ps = List.of(
                pay("5612", 10000, "a"), pay("5612", 10000, "b"), pay("7521", 10000, "c"));
        // 미식탐방 2건, 여행 1건 → 총 3 → 미식탐방 2/3, 여행 1/3
        List<HobbyScore> out = TasteAnalysisService.aggregate(ps, REVERSE);
        assertThat(out.get(0).ratio()).isEqualTo(2.0 / 3.0);
        assertThat(out.get(1).ratio()).isEqualTo(1.0 / 3.0);
    }

    @Test
    void 대표_가맹점은_최대_3개_중복없이() {
        List<UserPayment> ps = List.of(
                pay("5612", 1, "스시A"), pay("5612", 1, "스시A"),   // 중복 → 1개로
                pay("5612", 1, "스시B"), pay("5612", 1, "스시C"), pay("5612", 1, "스시D"));
        List<HobbyScore> out = TasteAnalysisService.aggregate(ps, REVERSE);
        assertThat(out.get(0).sampleMerchants()).hasSize(3).doesNotHaveDuplicates();
    }

    @Test
    void 취미신호가_없으면_빈_결과와_안내문구() {
        List<UserPayment> ps = List.of(pay("5611", 9000, "김밥"), pay("4712", 3000, "CU"));
        List<HobbyScore> out = TasteAnalysisService.aggregate(ps, REVERSE);
        assertThat(out).isEmpty();
        assertThat(TasteAnalysisService.summarize(out)).contains("아직");
    }

    @Test
    void 요약은_상위_두_취미를_문장으로_옮긴다() {
        List<UserPayment> ps = List.of(
                pay("5612", 1, "a"), pay("5612", 1, "b"), pay("7521", 1, "c"));
        String s = TasteAnalysisService.summarize(TasteAnalysisService.aggregate(ps, REVERSE));
        assertThat(s).contains("미식탐방").contains("여행");
    }
}
