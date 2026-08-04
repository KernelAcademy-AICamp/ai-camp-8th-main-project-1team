package com.finntech.service;

import com.finntech.domain.UserPayment;
import com.finntech.engine.IndustryCategoryMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM 보조 분류 — <b>물어볼 것을 고르는 규칙</b>과 <b>받은 답을 믿는 범위</b>를 못박는다.
 * (LLM 호출 자체는 키가 필요해 여기서 하지 않는다. 순수 함수만 본다.)
 */
class MerchantClassifierServiceTest {

    private final IndustryCategoryMapper mapper = new IndustryCategoryMapper(new ObjectMapper());
    private final MerchantClassifierService service =
            new MerchantClassifierService(mapper, "", "", "http://localhost");

    @Test
    @DisplayName("PG 상호는 물어보지 않는다 — 무엇을 샀는지 이름이 말해 주지 않는다")
    void agencyNamesAreNotAsked() {
        // 실데이터 미분류의 상당수가 이것이다. 거르지 않으면 통째로 LLM 에 보낸다.
        for (String pg : new String[]{"토스페이먼츠", "카카오페이", "NHN KCP", "(주)다날",
                                      "나이스페이먼츠", "KG모빌리언스"}) {
            assertThat(service.worthAsking(pg, null)).as(pg).isFalse();
        }
    }

    @Test
    @DisplayName("가맹점명이 실제 가게면 사업자번호가 PG 라도 물어본다 — 이름이 답을 들고 있다")
    void realStoreNameIsAskedEvenUnderAgencyNumber() {
        // KG모빌리언스 번호로 찍히지만 이름은 에버랜드다. 이름을 보면 명백하다.
        assertThat(service.worthAsking("삼성물산리조트(주)에버랜드", "2208182546")).isTrue();
        assertThat(service.worthAsking("GS25 강남역점", "5278800686")).isTrue();
    }

    @Test
    @DisplayName("이름이 없거나 너무 짧으면 물어보지 않는다")
    void blankOrTinyNamesAreSkipped() {
        assertThat(service.worthAsking(null, null)).isFalse();
        assertThat(service.worthAsking("  ", null)).isFalse();
        assertThat(service.worthAsking("A", null)).isFalse();
    }

    @Test
    @DisplayName("축에 없는 분류는 버린다 — 모델이 이름을 지어내도 들어오지 못한다")
    void inventedCategoriesAreRejected() {
        var names = List.of("GS25 강남역점", "이상한가게", "스타벅스 역삼점");
        var got = service.parseJson("""
                {"1": "편의점/잡화", "2": "우주여행", "3": "카페/간식"}
                """, names);

        assertThat(got).containsEntry("GS25 강남역점", "편의점/잡화")
                .containsEntry("스타벅스 역삼점", "카페/간식");
        assertThat(got).as("'우주여행' 은 우리 축에 없다").doesNotContainKey("이상한가게");
    }

    @Test
    @DisplayName("범위 밖 번호·깨진 응답은 조용히 버린다 — 화면이 깨지지 않는다")
    void malformedResponsesAreIgnored() {
        var names = List.of("GS25 강남역점");
        assertThat(service.parseJson("{\"9\": \"식비\"}", names)).isEmpty();
        assertThat(service.parseJson("{\"0\": \"식비\"}", names)).isEmpty();
        assertThat(service.parseJson("{\"가\": \"식비\"}", names)).isEmpty();
        assertThat(service.parseJson("JSON 이 아니다", names)).isNull();
    }

    @Test
    @DisplayName("코드펜스가 붙어 와도 JSON 본문만 읽는다")
    void codeFenceIsStripped() {
        var got = service.parseJson("""
                ```json
                {"1": "식비"}
                ```
                """, List.of("김밥천국 역삼점"));
        assertThat(got).containsEntry("김밥천국 역삼점", "식비");
    }

    @Test
    @DisplayName("키가 없으면 아무것도 부르지 않는다 — 빈 결과일 뿐 실패가 아니다")
    void noKeyMeansNoCall() {
        assertThat(service.aiEnabled()).isFalse();
        assertThat(service.classify(List.of("GS25 강남역점"))).isEmpty();
    }

    @Test
    @DisplayName("추정은 category2 를 덮지 않는다 — 판정이 AI 를 타지 않게")
    void suggestionNeverOverwritesJudgedCategory() {
        var p = new UserPayment("k", 1L, "S1", 9001L, LocalDateTime.now(),
                null, IndustryCategoryMapper.UNCLASSIFIED, 5000, "GS25 강남역점", 0, null);

        p.suggestCategory2("편의점/잡화");
        assertThat(p.getCategory2()).as("판정이 읽는 칸은 그대로다")
                .isEqualTo(IndustryCategoryMapper.UNCLASSIFIED);
        assertThat(p.getCategory2Llm()).isEqualTo("편의점/잡화");
        assertThat(p.getCategory2Source()).isEqualTo("LLM");

        // 사람이 확인하면 그때 비로소 판정이 읽는 칸이 바뀐다.
        p.confirmCategory2("편의점/잡화", "USER");
        assertThat(p.getCategory2()).isEqualTo("편의점/잡화");
        assertThat(p.getCategory2Source()).isEqualTo("USER");
    }
}
