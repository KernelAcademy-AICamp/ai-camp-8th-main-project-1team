package com.finntech.service;

import com.finntech.config.TempClassifierProperties;
import com.finntech.engine.IndustryCategoryMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 임시 분류(순위 ②-c) — <b>꺼져 있는 것이 기본이고, 깨진 답을 통째로 버리지 않는다.</b>
 *
 * <p>여기 있는 것은 전부 실제로 겪은 실패다. 무료 통로는 언제 막힐지 모르고 응답 형식도
 * 우리가 통제하지 못하므로, "안 될 때 어떻게 되는가"가 계약이다.
 */
class TempClassifierServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final IndustryCategoryMapper mapper = new IndustryCategoryMapper(json);

    private TempClassifierService service(TempClassifierProperties props) {
        MerchantClassifierService classifier = mock(MerchantClassifierService.class);
        when(classifier.isPaymentAgencyMerchant(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(false);
        // 사슬이 날짜로 되돌아가므로 시계를 받는다 — 고정 시계면 시험이 시간에 안 흔들린다.
        return new TempClassifierService(props, mapper, classifier, json,
                java.time.Clock.fixed(java.time.Instant.parse("2026-08-21T03:00:00Z"),
                        java.time.ZoneId.of("Asia/Seoul")),
                new com.finntech.freechannel.FreeChannelQueue(40, 6, 500), brandProvider());
    }

    /**
     * <b>못 맞힌 가맹점을 5분마다 영원히 다시 묻던 것을 끊는다.</b>
     *
     * <p>예전에는 성공만 기록했다. 그래서 모델이 "모름"이라 한 가맹점은 아무 흔적도 안 남고,
     * 후속 회차가 5분 뒤 같은 질문을 또 냈다 — 운영에서 {@code PAYCO_NIC NICE정보통신㈜1} 이
     * 6시간 넘게 그랬다(2026-08-21 실측). 그 자리는 다른 가맹점이 썼어야 할 예산이다.
     */
    @Test
    @DisplayName("모름을 받은 가맹점은 한동안 다시 묻지 않는다")
    void 모름은_쉬었다_묻는다() {
        var props = new TempClassifierProperties();
        props.setEnabled(true);
        props.setBaseUrl("https://example.invalid/v1/chat/completions");
        props.setApiKey("k");
        props.setModel("m");
        var queue = mock(com.finntech.freechannel.FreeChannelQueue.class);
        var svc = new TempClassifierService(props, mapper,
                mock(MerchantClassifierService.class), json,
                java.time.Clock.systemUTC(), queue, brandProvider());

        svc.noteMiss("PAYCO_NIC NICE정보통신㈜1");

        assertThat(svc.recentlyMissed("PAYCO_NIC NICE정보통신㈜1")).isTrue();
        assertThat(svc.classify(List.of("PAYCO_NIC NICE정보통신㈜1"),
                com.finntech.freechannel.Lane.USER_BACKGROUND)).isEmpty();
        org.mockito.Mockito.verify(queue, org.mockito.Mockito.never()).submit(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
    }

    /** 시간이 지나면 다시 본다 — 사전·브랜드가 채워지면 그때는 맞힐 수 있다. */
    @Test
    @DisplayName("쉬는 시간이 지나면 다시 묻는다")
    void 쉬는_시간이_지나면_다시_묻는다() {
        var props = new TempClassifierProperties();
        props.setEnabled(true);
        props.setBaseUrl("https://example.invalid/v1/chat/completions");
        props.setApiKey("k");
        props.setModel("m");
        props.setMissMinutes(0);                       // 곧바로 만료
        var queue = mock(com.finntech.freechannel.FreeChannelQueue.class);
        var svc = new TempClassifierService(props, mapper,
                mock(MerchantClassifierService.class), json,
                java.time.Clock.systemUTC(), queue, brandProvider());

        svc.noteMiss("어떤 가게");

        assertThat(svc.recentlyMissed("어떤 가게")).isFalse();
        svc.classify(List.of("어떤 가게"), com.finntech.freechannel.Lane.USER_BACKGROUND);
        org.mockito.Mockito.verify(queue).submit(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("설정을 안 주면 꺼진 채로 있고 아무것도 부르지 않는다")
    void offByDefault() {
        // 주소·키·모델 중 하나만 비어도 꺼진다. 단위 시험이 바깥 서버를 안 부르는 것이
        // **설정이 아니라 기본값**이라야 한다.
        var props = new TempClassifierProperties();
        assertThat(props.usable()).isFalse();
        assertThat(service(props).usable()).isFalse();
        assertThat(service(props).classify(List.of("어떤 가게"))).isEmpty();

        props.setEnabled(true);
        props.setBaseUrl("https://example.invalid/v1/chat/completions");
        props.setApiKey("k");
        assertThat(props.usable()).as("모델 이름이 없으면 여전히 꺼짐").isFalse();
    }

    @Test
    @DisplayName("응답 속 제어문자 때문에 한 묶음을 통째로 버리지 않는다")
    void survivesControlCharacters() {
        // 2026-08-07 실측: 여섯 묶음 중 하나가 제어문자로 JSON 파싱에 실패해 40곳이 날아갔다.
        // 모델이 문자열 안에 줄바꿈을 그대로 흘리는 경우가 있다.
        var props = enabled();
        String body = """
                {"choices":[{"message":{"content":"{\\"1\\": \\"체인화 편의점\\",\\n \\"2\\": \\"한식 일반 음식점업\\"}"}}]}
                """;
        var got = service(props).parse(body, List.of("GS25 강남역점", "김밥천국"));
        assertThat(got).containsEntry("GS25 강남역점", "체인화 편의점")
                       .containsEntry("김밥천국", "한식 일반 음식점업");
    }

    @Test
    @DisplayName("설명이 섞여 와도 JSON 덩어리만 꺼낸다")
    void extractsJsonFromChatter() {
        String body = """
                {"choices":[{"message":{"content":"네, 분류했습니다.\\n\\n{\\"1\\": \\"체인화 편의점\\"}\\n\\n도움이 되었길."}}]}
                """;
        assertThat(service(enabled()).parse(body, List.of("GS25 강남역점")))
                .containsEntry("GS25 강남역점", "체인화 편의점");
    }

    @Test
    @DisplayName("범위를 벗어난 번호·빈 값·깨진 본문은 조용히 버린다")
    void ignoresGarbage() {
        var svc = service(enabled());
        List<String> names = List.of("가게1");

        // 번호가 목록 밖 — 지어낸 답이 들어오지 못한다.
        assertThat(svc.parse("""
                {"choices":[{"message":{"content":"{\\"7\\": \\"체인화 편의점\\"}"}}]}""", names)).isEmpty();
        // 값이 빈 문자열.
        assertThat(svc.parse("""
                {"choices":[{"message":{"content":"{\\"1\\": \\"\\"}"}}]}""", names)).isEmpty();
        // 아예 JSON 이 없다.
        assertThat(svc.parse("""
                {"choices":[{"message":{"content":"모르겠습니다"}}]}""", names)).isEmpty();
        // 본문이 비었다 — 예외를 던지지 않는다.
        assertThat(svc.parse("", names)).isEmpty();
    }

    @Test
    @DisplayName("목록에 없는 업종 이름은 중분류로 못 옮겨 버려진다")
    void unknownIndustryNamesAreDropped() {
        // 모델이 그럴듯한 이름을 지어내도 우리 표에 없으면 통과하지 못한다 —
        // 축 배정은 표가 하고 모델은 업종의 사실만 말한다(마스터 §4 원칙 1).
        assertThat(mapper.midOfIndustryName("체인화 편의점")).isEqualTo("편의점/잡화");
        assertThat(IndustryCategoryMapper.isUnknown(mapper.midOfIndustryName("우주 정거장 운영업")))
                .as("없는 업종은 중분류가 안 나온다").isTrue();
    }

    private static TempClassifierProperties enabled() {
        var p = new TempClassifierProperties();
        p.setEnabled(true);
        p.setBaseUrl("https://example.invalid/v1/chat/completions");
        p.setApiKey("k");
        p.setModel("some/model");
        return p;
    }

    /**
     * 브랜드 조회는 <b>고리를 끊으려고</b> {@code ObjectProvider} 로 받는다
     * ({@code MerchantBrandService} 가 이 서비스를 쓴다). 시험에서는 아무것도 안 준다 —
     * 브랜드가 없으면 프롬프트가 그 줄을 빼고 나가므로 여기 관심사가 아니다.
     */
    private static org.springframework.beans.factory.ObjectProvider<MerchantBrandService> brandProvider() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public MerchantBrandService getObject() { return null; }
            @Override public MerchantBrandService getObject(Object... args) { return null; }
            @Override public MerchantBrandService getIfAvailable() { return null; }
            @Override public MerchantBrandService getIfUnique() { return null; }
        };
    }
}
