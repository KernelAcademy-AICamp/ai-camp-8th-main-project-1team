package com.finntech.config;

import com.finntech.guardian.GuardianNarrative;
import com.finntech.service.NarrativeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 모델 이름이 <b>빈 문자열로 굳는 함정</b>을 막는다.
 *
 * <p>compose 는 {@code GEMINI_MODEL: ${GEMINI_MODEL:-}} 로 빈 값을 넣어 주고, Spring 의
 * {@code ${VAR:기본값}} 은 변수가 <b>없을 때만</b> 기본값을 쓴다. 그래서 yml 에 기본값을 두면
 * 배포된 컨테이너에서만 모델 이름이 {@code ""} 가 된다 — 로컬 테스트로는 절대 안 잡힌다.
 */
class GeminiModelsTest {

    @Test
    @DisplayName("Spring 은 '빈 값'과 '없음'을 다르게 푼다 — 이 전제가 깨지면 아래 설계가 무의미하다")
    void springTreatsEmptyDifferentlyFromAbsent() {
        StandardEnvironment withEmpty = new StandardEnvironment();
        Map<String, Object> m = new HashMap<>();
        m.put("GEMINI_MODEL", "");
        withEmpty.getPropertySources().addFirst(new MapPropertySource("t", m));

        assertEquals("", withEmpty.resolvePlaceholders("${GEMINI_MODEL:기본값}"),
                "빈 값이면 기본값이 안 먹는다 — 이것이 이 클래스가 존재하는 이유다");
        assertEquals("기본값", new StandardEnvironment().resolvePlaceholders("${GEMINI_MODEL:기본값}"),
                "없을 때만 기본값이 먹는다");
    }

    @Test
    @DisplayName("빈 설정값은 기본 모델로 — null·빈문자·공백 전부")
    void blankFallsBackToDefault() {
        assertEquals(GeminiModels.DEFAULT, GeminiModels.orDefault(null));
        assertEquals(GeminiModels.DEFAULT, GeminiModels.orDefault(""));
        assertEquals(GeminiModels.DEFAULT, GeminiModels.orDefault("   "));
    }

    @Test
    @DisplayName("값이 있으면 그대로 — 배포 없이 갈아타는 길을 막지 않는다")
    void keepsConfiguredModel() {
        assertEquals("gemini-flash-latest", GeminiModels.orDefault("gemini-flash-latest"));
        assertEquals("gemini-3.6-flash", GeminiModels.orDefault("  gemini-3.6-flash  "), "공백은 걷어낸다");
    }

    @Test
    @DisplayName("기본 모델 이름 자체가 비어 있으면 안 된다 — URI 가 /models/: 로 나간다")
    void defaultIsUsable() {
        assertNotNull(GeminiModels.DEFAULT);
        assertFalse(GeminiModels.DEFAULT.isBlank());
        assertFalse(GeminiModels.DEFAULT.contains("/"), "경로 조각이 섞이면 URI 가 깨진다");
    }

    @Test
    @DisplayName("실제 서비스도 빈 값을 받으면 기본 모델을 쓴다 — 배포 상태 그대로")
    void servicesNormaliseBlankModel() {
        // 생성자에 빈 문자열을 주는 것이 곧 '컨테이너가 GEMINI_MODEL= 로 뜬 상태'다.
        assertEquals(GeminiModels.DEFAULT, modelOf(new NarrativeService("", "", "http://localhost")));
        assertEquals(GeminiModels.DEFAULT, modelOf(new GuardianNarrative("", "", "http://localhost")));
    }

    /** 모델은 화면에 안 나가는 내부 값이라 접근자가 없다 — 리플렉션으로 확인한다. */
    private static String modelOf(Object service) {
        try {
            var f = service.getClass().getDeclaredField("model");
            f.setAccessible(true);
            return (String) f.get(service);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("model 필드를 못 읽었다 — 이름이 바뀌었으면 테스트도 고친다", e);
        }
    }
}
