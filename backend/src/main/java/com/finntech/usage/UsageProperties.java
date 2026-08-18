package com.finntech.usage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 행태 통계 설정 — 지금은 <b>전환(핵심 이벤트)</b> 목록 하나뿐이다.
 *
 * <h2>왜 설정인가</h2>
 *
 * <p>GA4 는 "이 이벤트를 핵심 이벤트로 표시" 를 <b>관리 화면에서</b> 정한다 — 무엇이 성공인지는
 * 서비스가 달라지면 같이 달라지기 때문이다. 우리도 코드에 박지 않는다. 마스터 §4 원칙 4가
 * "임계치는 전부 {@code application.yml}" 이라고 한 것과 같은 이유다.
 *
 * <h2>꼴</h2>
 *
 * <pre>
 * finntech:
 *   usage:
 *     key-events:
 *       "SCREEN_VIEW:done": 챌린지 확정
 *       "CLICK:connect:link-start": 연동 시작
 * </pre>
 *
 * <p>왼쪽은 {@code 종류:화면} 또는 {@code 종류:화면:요소}, 오른쪽은 화면에 뜰 이름이다.
 * 요소를 안 적으면 그 화면의 그 종류를 전부 센다.
 */
@Component
@ConfigurationProperties(prefix = "finntech.usage")
public class UsageProperties {

    /** {@code 종류:화면[:요소]} → 사람이 읽을 이름. 순서를 유지한다(화면이 받은 순서대로 그린다). */
    private Map<String, String> keyEvents = new LinkedHashMap<>();

    public Map<String, String> getKeyEvents() { return keyEvents; }

    public void setKeyEvents(Map<String, String> keyEvents) {
        this.keyEvents = keyEvents == null ? new LinkedHashMap<>() : keyEvents;
    }

    /** 설정 한 줄을 뜯은 것. 꼴이 안 맞으면 {@code null} — 오타 하나로 기동이 막히면 안 된다. */
    public record KeyEvent(String label, String kind, String screen, String element) {

        static KeyEvent parse(String spec, String label) {
            if (spec == null) return null;
            String[] parts = spec.split(":", 3);
            if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) return null;
            String element = parts.length == 3 && !parts[2].isBlank() ? parts[2] : null;
            return new KeyEvent(label == null || label.isBlank() ? spec : label,
                    parts[0].trim(), parts[1].trim(), element);
        }
    }

    /** 설정을 읽을 수 있는 꼴로. 잘못 적힌 줄은 조용히 빠진다. */
    public java.util.List<KeyEvent> parsedKeyEvents() {
        java.util.List<KeyEvent> out = new java.util.ArrayList<>();
        keyEvents.forEach((spec, label) -> {
            KeyEvent parsed = KeyEvent.parse(spec, label);
            if (parsed != null) out.add(parsed);
        });
        return out;
    }
}
