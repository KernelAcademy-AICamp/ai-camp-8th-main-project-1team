package com.finntech.usage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * User-Agent 를 <b>GA4 가 보여 주는 굵기까지만</b> 줄여 읽는다 — 원문은 어디에도 안 남긴다.
 *
 * <p>UA 원문은 그 자체로 지문이다(브라우저 빌드·기기 모델·웹뷰 버전이 다 들어 있다). 그래서
 * 받아서 <b>브라우저 이름·주버전 / OS 이름·주버전 / 기기 종류</b> 다섯 값으로 줄이고 버린다.
 * 그보다 잘게 쪼갤 수 있어도 안 쪼갠다 — 담을 수 있다는 것과 담아야 한다는 것은 다르다.
 *
 * <p><b>순수 함수라 시험이 쉽다.</b> 라이브러리를 들이지 않은 이유가 그것이다 — UA 파싱
 * 라이브러리는 수천 줄의 정규식 표를 들고 오는데, 우리가 보는 것은 한국의 모바일 웹뷰와
 * 데스크톱 브라우저 몇 가지뿐이다.
 *
 * <h2>순서가 규칙이다</h2>
 *
 * <p>거의 모든 브라우저가 자기 UA 에 {@code Safari} 와 {@code Chrome} 을 함께 적는다
 * (엣지·삼성인터넷·오페라 전부 크로미움이다). 그래서 <b>가장 구체적인 것부터</b> 본다 —
 * 순서를 뒤집으면 모든 것이 Chrome 이 된다.
 */
public final class UserAgentParser {

    private UserAgentParser() {
    }

    /** 줄여 읽은 결과. 못 알아본 자리는 null 이다 — 억지로 채우면 통계가 거짓말을 한다. */
    public record Agent(String browser, String browserVersion,
                        String os, String osVersion, String deviceCategory) {

        static final Agent UNKNOWN = new Agent(null, null, null, null, null);
    }

    /** 이름 → 그 이름 뒤의 버전을 잡는 꼴. <b>위에서부터</b> 본다. */
    private record Rule(String label, Pattern pattern) {}

    private static final Rule[] BROWSERS = {
            new Rule("Edge", Pattern.compile("Edg(?:A|iOS)?/(\\d+)")),
            new Rule("Samsung Internet", Pattern.compile("SamsungBrowser/(\\d+)")),
            new Rule("Opera", Pattern.compile("OPR/(\\d+)")),
            new Rule("Whale", Pattern.compile("Whale/(\\d+)")),
            new Rule("Firefox", Pattern.compile("(?:Firefox|FxiOS)/(\\d+)")),
            // 크로미움 계열의 마지막. 위 넷을 먼저 걸러야 여기 안 걸린다.
            new Rule("Chrome", Pattern.compile("(?:Chrome|CriOS)/(\\d+)")),
            // 사파리는 자기 버전을 Version/ 에 적고 Safari/ 에는 엔진 빌드를 적는다.
            new Rule("Safari", Pattern.compile("Version/(\\d+)[\\d.]*\\s+(?:Mobile/\\S+\\s+)?Safari")),
    };

    private static final Pattern ANDROID = Pattern.compile("Android\\s+(\\d+)");
    private static final Pattern IOS = Pattern.compile("(?:iPhone )?OS (\\d+)[_.]");
    private static final Pattern WINDOWS = Pattern.compile("Windows NT (\\d+(?:\\.\\d+)?)");
    private static final Pattern MACOS = Pattern.compile("Mac OS X (\\d+)[_.]");

    /** 윈도우는 커널 번호를 적는다 — 사람이 아는 이름으로 바꾼다. */
    private static String windowsName(String nt) {
        return switch (nt) {
            case "10.0" -> "10/11";          // 윈도우 11 도 NT 10.0 이다. 둘을 UA 로는 못 가른다
            case "6.3" -> "8.1";
            case "6.2" -> "8";
            case "6.1" -> "7";
            default -> nt;
        };
    }

    /**
     * @param ua      브라우저가 보낸 원문. null·빈 값이면 전부 null 인 결과
     * @param hintedPlatform Capacitor 가 말한 플랫폼({@code android}·{@code ios}·{@code web}).
     *                       웹뷰의 UA 는 브라우저와 구별이 잘 안 돼서, 앱이 스스로 말한 값이
     *                       있으면 그것을 기기 종류 판정의 <b>근거로 먼저</b> 쓴다
     */
    public static Agent parse(String ua, String hintedPlatform) {
        if (ua == null || ua.isBlank()) return Agent.UNKNOWN;
        String s = ua.length() > 512 ? ua.substring(0, 512) : ua;

        String browser = null;
        String browserVersion = null;
        for (Rule rule : BROWSERS) {
            Matcher m = rule.pattern().matcher(s);
            if (m.find()) {
                browser = rule.label();
                browserVersion = m.group(1);
                break;
            }
        }

        String os = null;
        String osVersion = null;
        Matcher m;
        if ((m = ANDROID.matcher(s)).find()) {
            os = "Android";
            osVersion = m.group(1);
        } else if (s.contains("iPhone") || s.contains("iPad") || s.contains("iPod")) {
            os = "iOS";
            if ((m = IOS.matcher(s)).find()) osVersion = m.group(1);
        } else if ((m = WINDOWS.matcher(s)).find()) {
            os = "Windows";
            osVersion = windowsName(m.group(1));
        } else if ((m = MACOS.matcher(s)).find()) {
            os = "macOS";
            osVersion = m.group(1);
        } else if (s.contains("Linux")) {
            os = "Linux";
        }

        return new Agent(browser, browserVersion, os, osVersion, category(s, os, hintedPlatform));
    }

    /**
     * mobile · tablet · desktop.
     *
     * <p>아이패드가 어렵다 — iPadOS 13 부터 사파리가 <b>데스크톱 맥인 척</b> 하는 UA 를 보낸다
     * (애플이 "데스크톱 사이트 요청"을 기본으로 만들면서 그렇게 됐다). UA 만으로는 못 가르고,
     * 그래서 아이패드는 <b>맥으로 잡힐 수 있다.</b> 이것은 GA4 도 겪는 한계이고, 억지로 터치
     * 지원 여부 같은 것으로 추측하면 진짜 맥까지 태블릿이 된다.
     */
    private static String category(String ua, String os, String hintedPlatform) {
        if (ua.contains("iPad") || ua.contains("Tablet")
                || (ua.contains("Android") && !ua.contains("Mobi"))) {
            return "tablet";
        }
        if (ua.contains("Mobi") || ua.contains("iPhone") || ua.contains("iPod")) return "mobile";
        // 앱이 스스로 안드로이드·iOS 라고 말했으면 UA 가 무슨 소리를 하든 손안의 기기다.
        if ("android".equals(hintedPlatform) || "ios".equals(hintedPlatform)) return "mobile";
        if ("Android".equals(os) || "iOS".equals(os)) return "mobile";
        return "desktop";
    }
}
