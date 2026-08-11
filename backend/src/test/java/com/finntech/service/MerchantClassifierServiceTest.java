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
    @DisplayName("업종 이름을 중분류로 옮긴다 — 축은 우리 표가 정한다")
    void industryNameBecomesCategory() {
        // 모델은 "이 가게가 무엇을 파는가"만 답하고, 축 배정은 대조표가 한다.
        // 그래서 표를 고치면 모델의 답도 함께 따라온다(백화점을 대형마트→쇼핑으로 옮긴 것처럼).
        var names = List.of("GS25 강남역점", "이상한가게", "김밥천국 역삼점");
        var got = service.parseJson("""
                {"1": "체인화 편의점", "2": "우주여행업", "3": "한식 일반 음식점업"}
                """, names);

        assertThat(got).containsEntry("GS25 강남역점", "편의점/잡화")
                .containsEntry("김밥천국 역삼점", "식비");
        assertThat(got).as("'우주여행업' 은 우리 표에 없다").doesNotContainKey("이상한가게");
    }

    @Test
    @DisplayName("거의 맞는 이름은 회수한다 — 목록 밖 답의 대부분이 표기 차이다")
    void nearMissNamesAreRecovered() {
        // 2026-08-05 실측에서 버려진 6건이 이런 모양이었다. 오타·축약이라 사람이 보면 명백한데
        // 정확일치만 받으면 통째로 잃는다.
        assertThat(service.toMid("화장품, 비누 및 방향제 소매업")).isEqualTo("미용");
        assertThat(service.toMid("화장품 비누 및 방향제 소매업")).as("쉼표가 빠져도").isEqualTo("미용");
        assertThat(service.toMid("체인화편의점")).as("공백이 빠져도").isEqualTo("편의점/잡화");
    }

    @Test
    @DisplayName("중분류를 곧장 답해도 받는다 — 모델이 대괄호 안의 것을 쓰기도 한다")
    void answeringWithCategoryDirectlyWorks() {
        assertThat(service.toMid("식비")).isEqualTo("식비");
        assertThat(service.toMid("카페/간식")).isEqualTo("카페/간식");
    }

    @Test
    @DisplayName("근사 일치가 갈리면 버린다 — 모르는 것을 아는 척하지 않는다")
    void ambiguousNearMissIsDropped() {
        // 너무 짧은 조각은 여러 업종에 걸린다. 그때는 어느 중분류인지 알 수 없으므로 안 받는다.
        assertThat(service.toMid("업")).isNull();
        assertThat(service.toMid("")).isNull();
        assertThat(service.toMid(null)).isNull();
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
                null, IndustryCategoryMapper.UNCLASSIFIED, 5000, "GS25 강남역점", null);

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

    @Test
    @DisplayName("키가 없으면 재질문도 안 한다 — 중요하다고 없는 키를 부르지 않는다")
    void retryAlsoRespectsMissingKey() {
        assertThat(service.classify(List.of("넷플릭스"), java.util.Set.of("넷플릭스"))).isEmpty();
    }

    @Test
    @DisplayName("중요 목록을 안 주면 예전과 같이 동작한다 — 기존 호출부가 안 깨진다")
    void classifyWithoutImportanceStillWorks() {
        assertThat(service.classify(List.of("GS25 강남역점"))).isEmpty();   // 키가 없어 빈 결과
    }
}
