package com.finntech.service;

import com.finntech.service.SavingsMatchInputs.EarlyTermination;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 중도해지이율 스냅샷(M10) 적재 — 저장소에 실제로 들어 있는
 * `savings/early-termination.json`을 읽는다(수집기가 만든 그 파일).
 */
class EarlyTerminationSourceTest {

    private final EarlyTerminationSource source = new EarlyTerminationSource(new ObjectMapper());

    @Test
    void 스냅샷을_읽는다() {
        assertThat(source.size()).isPositive();
    }

    /** 금감원 표기(`주식회사 케이뱅크`)와 은행 공시 표기(`케이뱅크`)가 달라도 이어져야 한다. */
    @Test
    void 금융사_표기가_달라도_상품을_찾는다() {
        assertThat(source.find("주식회사 케이뱅크", "코드K 자유적금")).isNotNull();
    }

    /** 금감원 상품명에는 생 개행이 섞여 온다 — 공백을 지우고 대조한다. */
    @Test
    void 상품명의_공백은_무시한다() {
        assertThat(source.find("주식회사 케이뱅크", "코드K\n자유적금")).isNotNull();
    }

    /** 실제 공시 값과 같아야 한다 — 파싱이 어긋나면 여기서 걸린다. */
    @Test
    void 수집한_구간이_공시와_같다() {
        EarlyTermination t = source.find("주식회사 케이뱅크", "코드K 자유적금");

        assertThat(t.rateAt(0, 12, 3.7)).isEqualTo(0.1);          // 1개월 미만 연 0.10%
        assertThat(t.rateAt(2, 12, 3.7)).isEqualTo(0.3);          // 1개월 이상 연 0.30%
        assertThat(t.rateAt(6, 12, 3.7)).isEqualTo(3.7 * 0.7 * 0.5);   // 기본금리×70%×경과/계약
        assertThat(t.asOf()).isNotNull();                         // 화면에 병기할 수집 기준일
    }

    /** 구간표 없이 한 줄로만 공시하는 상품도 있다(`데굴데굴 농장` 연 0.10%). */
    @Test
    void 단일_이율_상품도_읽는다() {
        EarlyTermination t = source.find("주식회사 케이뱅크", "데굴데굴 농장");

        assertThat(t).isNotNull();
        assertThat(t.rateAt(3, 12, 3.0)).isEqualTo(0.1);
    }

    /**
     * 토스뱅크는 구간을 <b>`초과`(하한 미포함)</b> 로 끊는다 — `3개월 초과 6개월 이하`.
     * 12개월 상품을 6개월에 깨면 50% 구간이지 70% 구간이 아니다.
     */
    @Test
    void 하한이_미포함인_은행도_경계를_제대로_읽는다() {
        EarlyTermination t = source.find("토스뱅크 주식회사", "토스뱅크 먼저 이자 받는 정기예금");

        assertThat(t).isNotNull();
        assertThat(t.rateAt(6, 12, 3.6)).isEqualTo(3.6 * 0.5 * 0.5);
        assertThat(t.rateAt(7, 12, 3.6)).isEqualTo(3.6 * 0.7 * (7.0 / 12));
    }

    /**
     * `기본 금리`처럼 띄어 쓴 페이지가 있어 구간 넷을 흘린 적이 있다(토스뱅크 자유 적금).
     * <b>남은 둘로도 그럴듯해 보여서 안 걸린다</b> — 구간 수로 못을 박아 둔다.
     */
    @Test
    void 띄어쓴_기본금리_표기도_흘리지_않는다() {
        EarlyTermination t = source.find("토스뱅크 주식회사", "토스뱅크 자유 적금");

        assertThat(t).isNotNull();
        assertThat(t.tiers()).hasSize(6);
    }

    /** 못 구한 상품은 null이다 — 0으로 메우면 "깨도 손해 없다"는 거짓말이 된다. */
    @Test
    void 수집_안_된_상품은_null이다() {
        assertThat(source.find("우리은행", "WON적금")).isNull();
        assertThat(source.find("주식회사 케이뱅크", "궁금한 적금")).isNull();   // 공시에 값이 없다
    }

    @Test
    void 금융사명을_못_알아보면_찾지_않는다() {
        assertThat(EarlyTerminationSource.key("  ", "코드K 자유적금")).isNull();
        assertThat(EarlyTerminationSource.key("케이뱅크", " ")).isNull();
    }
}
