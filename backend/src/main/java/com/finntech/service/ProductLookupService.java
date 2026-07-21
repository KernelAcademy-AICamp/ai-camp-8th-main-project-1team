package com.finntech.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 상품 정보 조회 (문서 §5-5, 폴센트 응용). 화면을 훔쳐보지 않고 <b>사용자가 건넨 URL/스크린샷</b>에서
 * 상품명·가격을 얻는다.
 *
 * <ul>
 *   <li><b>URL</b>: OpenGraph/JSON-LD 메타를 서버가 읽는다. 임의 URL을 서버가 가져오므로 <b>SSRF 방어</b>
 *       (내부망·로컬 주소 차단)를 건다. 실쇼핑몰 스크래핑(쿠팡 등)은 ToS·법적 리스크로 하지 않는다 —
 *       공개 메타가 없으면 값을 못 채우고, 그럴 땐 사용자가 직접 입력한다.</li>
 *   <li><b>스크린샷</b>: 멀티모달 Gemini로 상품명·가격·카테고리를 추출한다. 키가 없거나 실패하면 조용히
 *       빈 결과로 폴백해 화면이 죽지 않게 한다(개인정보 5번: 사용자가 넘긴 이미지만 전송).</li>
 * </ul>
 */
@Service
public class ProductLookupService {

    private static final int MAX_HTML = 512 * 1024;

    private final String apiKey;
    private final String model;
    private final RestClient gemini;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public ProductLookupService(
            @Value("${finntech.gemini.api-key:}") String apiKey,
            @Value("${finntech.gemini.model:gemini-2.0-flash}") String model,
            @Value("${finntech.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.gemini = RestClient.builder().baseUrl(baseUrl).build();
    }

    public boolean aiEnabled() { return apiKey != null && !apiKey.isBlank(); }

    public record LookupResult(String name, BigDecimal price, String imageUrl, String categoryCode, String note) {
        static LookupResult empty() { return new LookupResult(null, null, null, null, null); }
        static LookupResult blocked(String note) { return new LookupResult(null, null, null, null, note); }
    }

    /** 사용자가 건넨 상품 URL의 공개 메타/임베드 상태에서 상품 정보를 읽는다. */
    public LookupResult fromUrl(String url) {
        validatePublicUrl(url);
        String html;
        try {
            html = fetch(url);
        } catch (IllegalArgumentException e) {
            // 안티봇·네트워크 실패는 예외로 던지지 않고 안내 메모로 돌려, 화면이 스크린샷/수동으로 폴백하게 한다.
            return LookupResult.blocked(e.getMessage());
        }
        return parseHtml(html);
    }

    /** 스크린샷을 Gemini 비전으로 분석해 상품 정보를 추출한다. 키 없으면 빈 결과(수동 입력 유도). */
    public LookupResult fromImage(String base64, String mimeType) {
        if (!aiEnabled() || base64 == null || base64.isBlank()) return LookupResult.empty();
        try {
            String prompt = """
                    이 상품 스크린샷을 보고 JSON만 출력하세요(설명·마크다운 금지).
                    {"name": 상품명, "price": 가격_숫자만_정수_원, "category": 코드}
                    category는 다음 중 하나: FOOD, CAFE, SHOPPING, TRANSPORT, HOUSING, MEDICAL, CULTURE, ETC.
                    가격을 못 찾으면 price는 0.
                    """;
            Map<?, ?> resp = gemini.post()
                    .uri("/v1beta/models/{model}:generateContent?key={key}", model, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("contents", List.of(Map.of("parts", List.of(
                            Map.of("inline_data", Map.of("mime_type",
                                    mimeType == null ? "image/png" : mimeType, "data", base64)),
                            Map.of("text", prompt))))))
                    .retrieve().body(Map.class);
            String text = extractText(resp);
            return text == null ? LookupResult.empty() : parseAiJson(text);
        } catch (Exception e) {
            return LookupResult.empty();  // 시연 중 네트워크·쿼터 문제로 죽지 않는다.
        }
    }

    // ---- 순수 파싱 (단위 테스트 진입점) -----------------------------------

    /**
     * HTML에서 상품명·가격·이미지를 추출한다(카테고리는 URL로 판별 불가 → null).
     *
     * <p>가격은 눈에 보이는 OG 메타뿐 아니라 페이지에 <b>임베드된 JSON 상태</b>(`__NEXT_DATA__`·
     * `__PRELOADED_STATE__` 등)·JSON-LD `offers`에서도 뽑는다 — 요즘 쇼핑몰(SPA)은 가격을 그쪽에 싣기 때문.
     * 정가·배송비 오탐을 줄이려고 <b>할인가 키를 먼저</b> 본다.
     */
    static LookupResult parseHtml(String html) {
        if (html == null) return LookupResult.empty();
        String name = firstNonBlank(meta(html, "og:title"), meta(html, "twitter:title"), titleTag(html),
                jsonKey(html, "productName"), jsonKey(html, "name"));
        if (name != null && BLOCK.matcher(name).find()) name = null;  // 차단 페이지 제목을 상품명으로 오인하지 않음
        String image = firstNonBlank(meta(html, "og:image"), meta(html, "twitter:image"), jsonKey(html, "image"));
        BigDecimal price = parsePrice(firstNonBlank(
                meta(html, "product:price:amount"), meta(html, "og:price:amount"), structuredPrice(html)));
        return new LookupResult(blankToNull(name), price, blankToNull(image), null, null);
    }

    /** 안티봇 차단·로봇 확인 페이지 제목 패턴 — 상품명으로 오인하지 않게 걸러낸다. */
    private static final Pattern BLOCK = Pattern.compile(
            "(?i)access denied|forbidden|접근이 거부|차단되|robot check|are you (a )?human|잠시 후 다시");

    /** 우선순위(할인가 먼저)로 임베드 JSON 상태/JSON-LD offers에서 가격 문자열을 찾는다. */
    static String structuredPrice(String html) {
        for (String key : PRICE_KEYS) {
            String v = jsonNumber(html, key);
            if (v != null) return v;
        }
        return null;
    }

    private static final String[] PRICE_KEYS = {
            "salePrice", "finalPrice", "couponPrice", "discountedPrice",
            "sellPrice", "lowPrice", "price",
    };

    /** 임의 URL을 서버가 가져오기 전 SSRF 방어 — http/https만, 내부망·로컬 주소 차단. */
    static void validatePublicUrl(String url) {
        URI uri;
        try { uri = URI.create(url.trim()); }
        catch (Exception e) { throw new IllegalArgumentException("올바른 URL이 아니에요"); }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException("http/https URL만 가능해요");
        }
        String host = uri.getHost();
        if (host == null) throw new IllegalArgumentException("호스트가 없는 URL이에요");
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                        || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
                        || addr.isMulticastAddress()) {
                    throw new IllegalArgumentException("내부 주소는 조회할 수 없어요");
                }
            }
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("주소를 찾을 수 없어요");
        }
    }

    /** "179,000원" · "179000.00" 등에서 정수 원화를 뽑는다. 못 뽑으면 null. */
    static BigDecimal parsePrice(String raw) {
        if (raw == null) return null;
        Matcher m = Pattern.compile("([0-9][0-9,]*)(\\.[0-9]+)?").matcher(raw);
        if (!m.find()) return null;
        String digits = m.group(1).replace(",", "");
        if (digits.isBlank()) return null;
        try {
            BigDecimal v = new BigDecimal(digits);
            return v.signum() > 0 ? v : null;
        } catch (NumberFormatException e) { return null; }
    }

    // ---- 내부 헬퍼 ---------------------------------------------------------

    private String fetch(String url) {
        HttpResponse<String> res;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
                    .GET().build();
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalArgumentException("상품 페이지를 불러오지 못했어요 — 스크린샷이나 직접 입력을 써주세요");
        }
        // 안티봇 차단(403 등)은 본문(=차단 페이지)을 상품으로 오인하지 않도록 여기서 걸러 안내로 돌린다.
        int sc = res.statusCode();
        if (sc == 401 || sc == 403 || sc == 429) {
            throw new IllegalArgumentException("이 사이트가 서버 접근을 막았어요(안티봇). 스크린샷이나 직접 입력을 써주세요");
        }
        if (sc / 100 != 2) {
            throw new IllegalArgumentException("상품 페이지를 불러오지 못했어요(HTTP " + sc + ") — 스크린샷이나 직접 입력을 써주세요");
        }
        String body = res.body();
        if (body == null) return "";
        return body.length() > MAX_HTML ? body.substring(0, MAX_HTML) : body;
    }

    /** property/name=KEY 인 meta의 content. 속성 순서 양쪽 다 시도. */
    private static String meta(String html, String key) {
        String k = Pattern.quote(key);
        Matcher m1 = Pattern.compile(
                "<meta[^>]+(?:property|name)=[\"']" + k + "[\"'][^>]+content=[\"']([^\"']*)[\"']",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (m1.find()) return m1.group(1);
        Matcher m2 = Pattern.compile(
                "<meta[^>]+content=[\"']([^\"']*)[\"'][^>]+(?:property|name)=[\"']" + k + "[\"']",
                Pattern.CASE_INSENSITIVE).matcher(html);
        return m2.find() ? m2.group(1) : null;
    }

    private static String titleTag(String html) {
        Matcher m = Pattern.compile("<title[^>]*>([^<]*)</title>", Pattern.CASE_INSENSITIVE).matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /** JSON(임베드 상태·JSON-LD)에서 "key": 123 / "key":"123" 숫자값 첫 매치. 구분 쉼표는 캡처하지 않는다(숫자로 끝남). */
    private static String jsonNumber(String html, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"?([0-9][0-9,]*[0-9]|[0-9])")
                .matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /** JSON에서 "key":"문자열" 첫 매치(이름·이미지 폴백용). */
    private static String jsonKey(String html, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static LookupResult parseAiJson(String text) {
        String name = group(text, "\"name\"\\s*:\\s*\"([^\"]*)\"");
        BigDecimal price = parsePrice(group(text, "\"price\"\\s*:\\s*\"?([0-9][0-9,]*)"));
        String cat = group(text, "\"category\"\\s*:\\s*\"([^\"]*)\"");
        return new LookupResult(blankToNull(name), price, null, blankToNull(cat), null);
    }

    private static String group(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    @SuppressWarnings("unchecked")
    private String extractText(Map<?, ?> resp) {
        try {
            var candidates = (List<Map<?, ?>>) resp.get("candidates");
            var content = (Map<?, ?>) candidates.get(0).get("content");
            var parts = (List<Map<?, ?>>) content.get("parts");
            Object t = parts.get(0).get("text");
            return t == null ? null : t.toString();
        } catch (Exception e) { return null; }
    }

    private static String firstNonBlank(String... vs) {
        for (String v : vs) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v; }
}
