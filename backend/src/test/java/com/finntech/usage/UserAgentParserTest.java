package com.finntech.usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UA 를 굵게 줄여 읽는 순수 함수.
 *
 * <p>가장 값진 시험은 <b>순서</b>다 — 크로미움 계열은 전부 자기 UA 에 {@code Chrome} 과
 * {@code Safari} 를 함께 적어서, 규칙 순서가 한 칸만 어긋나도 모든 브라우저가 Chrome 이 된다.
 * 그것은 오류 없이 조용히 틀린다.
 */
class UserAgentParserTest {

    private static final String CHROME_MAC =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/124.0.0.0 Safari/537.36";
    private static final String SAFARI_IPHONE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15"
                    + " (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1";
    private static final String SAMSUNG_ANDROID =
            "Mozilla/5.0 (Linux; Android 14; SM-S911N) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " SamsungBrowser/24.0 Chrome/117.0.0.0 Mobile Safari/537.36";
    private static final String EDGE_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/124.0.0.0 Safari/537.36 Edg/124.0.2478.51";
    private static final String IPAD =
            "Mozilla/5.0 (iPad; CPU OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko)"
                    + " Version/17.4 Mobile/15E148 Safari/604.1";

    @Test
    @DisplayName("크로미움 계열이 Chrome 으로 뭉개지지 않는다 — 규칙 순서가 지켜진다")
    void 구체적인_것부터_본다() {
        assertThat(UserAgentParser.parse(SAMSUNG_ANDROID, null).browser()).isEqualTo("Samsung Internet");
        assertThat(UserAgentParser.parse(EDGE_WINDOWS, null).browser()).isEqualTo("Edge");
        assertThat(UserAgentParser.parse(CHROME_MAC, null).browser()).isEqualTo("Chrome");
        // 사파리는 Chrome 을 안 적지만 Safari 는 모두가 적는다 — 마지막에 봐야 맞는다.
        assertThat(UserAgentParser.parse(SAFARI_IPHONE, null).browser()).isEqualTo("Safari");
    }

    @Test
    @DisplayName("버전은 주버전까지만 — 그보다 잘게 쪼갠 값은 지문이 된다")
    void 주버전만_읽는다() {
        assertThat(UserAgentParser.parse(EDGE_WINDOWS, null).browserVersion()).isEqualTo("124");
        assertThat(UserAgentParser.parse(SAFARI_IPHONE, null).browserVersion()).isEqualTo("17");
        assertThat(UserAgentParser.parse(SAMSUNG_ANDROID, null).browserVersion()).isEqualTo("24");
    }

    @Test
    void OS_와_버전() {
        assertThat(UserAgentParser.parse(SAMSUNG_ANDROID, null).os()).isEqualTo("Android");
        assertThat(UserAgentParser.parse(SAMSUNG_ANDROID, null).osVersion()).isEqualTo("14");
        assertThat(UserAgentParser.parse(SAFARI_IPHONE, null).os()).isEqualTo("iOS");
        assertThat(UserAgentParser.parse(SAFARI_IPHONE, null).osVersion()).isEqualTo("17");
        assertThat(UserAgentParser.parse(CHROME_MAC, null).os()).isEqualTo("macOS");
        // 윈도우는 커널 번호를 적는다 — 사람이 아는 이름으로 바뀌어야 한다.
        assertThat(UserAgentParser.parse(EDGE_WINDOWS, null).osVersion()).isEqualTo("10/11");
    }

    @Test
    @DisplayName("기기 종류 — Mobi 없는 안드로이드는 태블릿이다")
    void 기기_종류() {
        assertThat(UserAgentParser.parse(SAFARI_IPHONE, null).deviceCategory()).isEqualTo("mobile");
        assertThat(UserAgentParser.parse(SAMSUNG_ANDROID, null).deviceCategory()).isEqualTo("mobile");
        assertThat(UserAgentParser.parse(CHROME_MAC, null).deviceCategory()).isEqualTo("desktop");
        assertThat(UserAgentParser.parse(IPAD, null).deviceCategory()).isEqualTo("tablet");
        assertThat(UserAgentParser.parse(
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36",
                null).deviceCategory()).isEqualTo("tablet");
    }

    @Test
    @DisplayName("앱이 스스로 말한 플랫폼이 UA 보다 세다 — 웹뷰는 UA 로 구별이 안 된다")
    void 플랫폼_힌트가_웹뷰를_구한다() {
        String bareWebView = "Mozilla/5.0 (Linux) AppleWebKit/537.36 (KHTML, like Gecko)";
        assertThat(UserAgentParser.parse(bareWebView, null).deviceCategory()).isEqualTo("desktop");
        assertThat(UserAgentParser.parse(bareWebView, "android").deviceCategory()).isEqualTo("mobile");
        assertThat(UserAgentParser.parse(bareWebView, "ios").deviceCategory()).isEqualTo("mobile");
    }

    @Test
    @DisplayName("못 알아보면 억지로 채우지 않는다 — 억지로 채운 값은 통계를 거짓으로 만든다")
    void 모르면_비운다() {
        assertThat(UserAgentParser.parse(null, null)).isEqualTo(UserAgentParser.Agent.UNKNOWN);
        assertThat(UserAgentParser.parse("  ", null)).isEqualTo(UserAgentParser.Agent.UNKNOWN);
        UserAgentParser.Agent odd = UserAgentParser.parse("무언가 이상한 문자열", null);
        assertThat(odd.browser()).isNull();
        assertThat(odd.os()).isNull();
    }

    @Test
    @DisplayName("아주 긴 UA 도 잘라서 다룬다 — 512자 상한")
    void 긴_문자열도_안전하다() {
        String long_ = "Mozilla/5.0 " + "x".repeat(5_000) + " Chrome/124.0.0.0";
        // 512자에서 잘리므로 뒤쪽의 Chrome 은 안 보인다. 터지지 않는 것이 요점이다.
        assertThat(UserAgentParser.parse(long_, null)).isNotNull();
    }
}
