package com.finntech.mydata.generation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** 낭비 라벨러(재량≠낭비·취미 보호) + 개선 곡선 검증. */
class WasteModelTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final GenerationProperties props = new GenerationProperties();
    private final WasteLabeler labeler = new WasteLabeler(props);

    private PersonaVariant overspender() {
        var base = new CatalogLoader(mapper).personas().stream()
                .filter(p -> p.name().equals("과소비형")).findFirst().orElseThrow();
        return PersonaExpander.expand(base, 2, 1, 42L).get(0);
    }

    private int wasteCount(String cat2, int amount, double typical, int hour, boolean planned,
                           boolean hobby, PersonaVariant v, double curve) {
        Random r = new Random(20260721L);   // 단일 스트림(순차 시드 상관 회피)
        int w = 0;
        for (int i = 0; i < 1000; i++) {
            var res = labeler.label(cat2, amount, typical, hour, planned, hobby, false, false, v, curve, r);
            if (res.label().equals("WASTE")) w++;
        }
        return w;
    }

    @Test
    void 필수무대는_낭비_아님_재량충동은_낭비_취미는_보호() {
        var v = overspender();
        // 1) 생존필수(대형마트) → 거의 낭비 아님
        assertThat(wasteCount("대형마트", 20000, 20000, 14, true, false, v, 1.0)).isLessThan(80);
        // 2) 재량(의류패션) + 심야·과다·미계획 + 높은 곡선 → 낭비 많음
        int impulsive = wasteCount("의류패션", 200000, 50000, 2, false, false, v, 1.4);
        assertThat(impulsive).isGreaterThan(400);
        // 3) 본인 취미(공연전시, 비과다·비심야) → 보호 → 낭비 적음, 같은 비취미보다 낮음
        int hobbyProtected = wasteCount("공연전시", 80000, 80000, 20, false, true, v, 1.0);
        int notHobby = wasteCount("공연전시", 80000, 80000, 20, false, false, v, 1.0);
        assertThat(hobbyProtected).isLessThan(notHobby);
        assertThat(hobbyProtected).isLessThan(200);
    }

    @Test
    void 곡선은_시작높음_하강_반등한다() {
        var p = new WasteCurve.Params(1.4, 0.4, 30, 0.9, 0.2, false);
        double t0 = WasteCurve.factor(p, 0);
        double t30 = WasteCurve.factor(p, 30);
        double t90 = WasteCurve.factor(p, 90);
        assertThat(t0).isCloseTo(1.4, org.assertj.core.data.Offset.offset(0.05)); // 시작 높음
        assertThat(t30).isLessThan(t0);   // 1개월 최저로 하강
        assertThat(t90).isGreaterThan(t30); // 반등·플래토
        // 무개선 사용자는 유지
        var flat = new WasteCurve.Params(1.4, 0.4, 30, 0.9, 0.2, true);
        assertThat(WasteCurve.factor(flat, 0)).isEqualTo(WasteCurve.factor(flat, 60));
    }
}
