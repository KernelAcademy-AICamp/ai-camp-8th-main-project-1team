package com.finntech.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>모델이 줄인 이름은 지어낸 것이 아니어야 한다</b> — 최후의 수단을 묶는 안전장치.
 *
 * <p>표시명은 규칙으로 정하는 것이 원칙이고, 규칙을 다 거치고도 긴 상호만 모델에게 간다.
 * 그런데 모델은 요약 대신 <b>추측</b>을 할 수 있다 — 그럴듯한 상호가 나오면 그것이 사실처럼
 * 화면에 앉고, 사용자는 가 본 적 없는 가게 이름을 본다(마스터 §4 원칙 1).
 *
 * <p>그래서 받은 답을 검사한다. 두 글자 이상 낱말이 <b>전부 원문 안에</b> 있어야 통과다.
 */
class ShortenedNameGuardTest {

    @Test
    @DisplayName("원문에서 낱말을 골라낸 답은 통과한다")
    void 원문의_낱말만_쓰면_통과() {
        assertThat(TempClassifierService.keepsWords(
                "주식회사 우리들곳간(해피베네핏 성수점)", "우리들곳간")).isTrue();
        assertThat(TempClassifierService.keepsWords(
                "미니말레 커피뢰스터 과천 지식정보타운점", "미니말레 커피뢰스터")).isTrue();
        assertThat(TempClassifierService.keepsWords(
                "ALP*Shanghai Disney", "Shanghai Disney")).isTrue();
    }

    /** 공백은 무시한다 — 모델이 띄어쓰기를 바꿔 답하는 일이 잦고, 그건 지어낸 것이 아니다. */
    @Test
    @DisplayName("띄어쓰기가 달라도 통과한다")
    void 띄어쓰기는_상관없다() {
        assertThat(TempClassifierService.keepsWords("로칼커피 (lokal coffee)", "로칼커피")).isTrue();
        assertThat(TempClassifierService.keepsWords("에이치디씨현대산업개발", "에이치디씨 현대산업개발"))
                .isTrue();
    }

    /** <b>여기가 요점이다.</b> 없는 낱말이 하나라도 섞이면 그 답은 못 쓴다. */
    @Test
    @DisplayName("원문에 없는 낱말이 섞이면 버린다")
    void 지어낸_답은_버린다() {
        assertThat(TempClassifierService.keepsWords("ALP*shanghaishihuangpu", "상하이 황푸 식당"))
                .as("모델이 뜻을 풀어 쓰면 그건 요약이 아니라 추측이다").isFalse();
        assertThat(TempClassifierService.keepsWords("NICE_통신판매", "네이버쇼핑")).isFalse();
        assertThat(TempClassifierService.keepsWords("DPP Tramv*PH0s002211", "프라하 트램")).isFalse();
    }

    /** 한 글자는 안 본다 — 조사·기호가 남는 것을 지어냈다고 볼 수는 없다. */
    @Test
    @DisplayName("한 글자 조각은 검사하지 않는다")
    void 한_글자는_안_본다() {
        assertThat(TempClassifierService.keepsWords("무신사 A", "무신사 B")).isTrue();
    }
}
