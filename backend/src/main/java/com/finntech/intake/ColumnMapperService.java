package com.finntech.intake;

import com.finntech.config.GeminiModels;
import com.finntech.config.TempClassifierProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 명세서 <b>칸 이름</b>을 모델에게 물어 연결한다 — 별칭표가 실패했을 때만.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>칸 이름은 카드사마다 다르다. 별칭표({@code statement.ts} 의 {@code COLUMN_ALIASES})는
 * 우리가 <b>실제로 본 파일</b>에서만 자란다. 못 본 카드사의 파일은 "칸을 못 찾았어요"로 막히고,
 * 사용자는 <b>무엇을 어떻게 고쳐야 하는지 알 수 없다</b> — 파일은 멀쩡한데 우리 표가 모를 뿐이다.
 * 그 자리에서 신청이 끝난다.
 *
 * <h2>순서가 설계다 — 표가 먼저다</h2>
 *
 * <pre>
 *   ① 별칭표         브라우저 · 즉시 · 공짜 · 같은 파일이면 늘 같은 답
 *   ② 여기(모델)      ①이 실패했을 때만 · 사람이 미리보기에서 확인
 *   ③ 거부           ②도 모르면 종전대로 사유를 말하고 멈춘다
 * </pre>
 *
 * <p>①을 건너뛰고 늘 모델에게 물으면 <b>재현성이 사라진다</b>(§4 원칙 3). 아는 파일은 표로
 * 답해야 매번 같은 답이 나온다. 모델은 <b>모르는 것을 만났을 때만</b> 부른다.
 *
 * <h2>나가는 것은 칸 이름뿐이다</h2>
 *
 * <p>§4 원칙 1 이 AI 로 나갈 수 있다고 정한 것은 집계 수치와 가맹점명이다. 여기서 나가는
 * <b>칸 이름</b>은 그보다 약하다 — 사용자의 자료가 아니라 <b>카드사가 정한 파일 형식</b>이고,
 * 같은 카드사를 쓰는 모든 사람에게 똑같다. 그래도 "약하니까 괜찮다"로 두지 않고
 * {@link #isHeaderCandidate} 로 <b>기계가 막는다</b>:
 *
 * <ul>
 *   <li>값이 든 줄은 안 나간다 — 날짜꼴·금액꼴·사업자번호꼴이 한 칸이라도 있으면 그 줄을 버린다</li>
 *   <li>{@code 성명 : 이*원} 같은 머리말도 안 나간다 — 채워진 칸이 3개 미만이면 버린다</li>
 *   <li>칸 하나가 30자를 넘으면 그 줄을 버린다 — 칸 이름은 짧다. 문장이면 값이거나 안내문이다</li>
 *   <li>줄 5개·칸 40개·전체 1200자를 넘기지 않는다</li>
 * </ul>
 *
 * <p>브라우저도 같은 검사를 하지만 그것은 편의다. <b>여기가 권위</b>다 — 브라우저 코드는
 * 사용자가 고칠 수 있으므로 신뢰의 근거가 될 수 없다({@link StatementValidator} 와 같은 태도).
 *
 * <h2>모델의 답은 믿지 않고 검사한다</h2>
 *
 * <p>돌아온 번호가 범위 밖이거나 서로 겹치면 버린다. 모델이 <b>있지도 않은 칸</b>을 답하면
 * 엉뚱한 칸이 금액으로 읽히고, 그것은 조용히 틀린 합계가 된다. 검사에 걸리면 ③으로 간다.
 *
 * <h2>왼쪽 통로를 먼저 쓰되 큐에는 넣지 않는다</h2>
 *
 * <p>무료 통로(NVIDIA)를 먼저 부르고 안 되면 유료(Gemini)로 넘어간다. 다만
 * {@code FreeChannelQueue} 를 <b>거치지 않는다</b> — 그 문에 들어갈 자격은 "사라져도
 * 다시 넣어질 근거가 코드에 있을 것"인데, 여기는 <b>사람이 화면 앞에서 기다리는</b> 요청이라
 * 다시 넣어 줄 배경 스캔이 없고 답을 되받아야 한다. 대신 예산을 축내지 않도록 스스로 조인다 —
 * 짧은 시한(6초), IP 당 시간당 20회, 그리고 같은 머리글은 캐시에서 답한다.
 */
@Service
public class ColumnMapperService {

    private static final Logger log = LoggerFactory.getLogger(ColumnMapperService.class);

    /** 보낼 수 있는 줄·칸·글자의 상한. 넘으면 자른다 — 값이 새는 통로를 좁게 유지한다. */
    private static final int MAX_ROWS = 5;
    private static final int MAX_CELLS = 40;
    private static final int MAX_CELL_CHARS = 30;
    private static final int MAX_TOTAL_CHARS = 1200;
    /** 머리글 줄로 인정하는 최소 채움. {@code 성명 : 이*원} 같은 머리말을 걸러 낸다. */
    private static final int MIN_FILLED_CELLS = 3;

    /** 사람이 기다린다 — 백그라운드 통로의 20초를 그대로 쓰면 화면이 멈춘 것처럼 보인다. */
    private static final int TIMEOUT_MS = 6_000;
    /** IP 당 시간당 상한. 신청 자체가 하루 10건이라 이보다 더 필요할 일이 없다. */
    private static final int HOURLY_PER_IP = 20;

    /** 날짜꼴 — {@code 2026.06.14} · {@code 2026-06-14} · {@code 20260614} · {@code 26.06.14} */
    private static final Pattern LOOKS_LIKE_DATE =
            Pattern.compile("^\\d{2,4}[-./]\\d{1,2}[-./]\\d{1,2}$|^\\d{8}$");
    /** 금액꼴 — 숫자·쉼표·통화기호·부호만으로 이루어졌고 숫자가 하나라도 있다. */
    private static final Pattern LOOKS_LIKE_AMOUNT =
            Pattern.compile("^[\\d,.\\s원₩\\-+()]*\\d[\\d,.\\s원₩\\-+()]*$");
    /** 사업자등록번호꼴 — {@code 411-86-01799} */
    private static final Pattern LOOKS_LIKE_BIZ = Pattern.compile("\\d{3}-\\d{2}-\\d{5}");

    private final TempClassifierProperties free;
    private final ObjectMapper json;
    private final RestClient http;
    private final RestClient gemini;
    private final String geminiKey;
    private final String geminiModel;

    /** 머리글 줄 원문 → 답. <b>메모리에만</b> 산다 — 재기동하면 비고, 그래도 무해하다. */
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    /** IP → 이번 시간의 호출 수. */
    private final Map<String, Counter> quota = new ConcurrentHashMap<>();

    private record Cached(Mapping mapping, Instant at) {}
    private record Counter(long hour, int count) {}

    /**
     * 모델이 고른 연결. 칸 번호는 <b>0부터</b> 세고, 없으면 -1 이다.
     *
     * <p>{@code row} 는 <b>부른 쪽이 보낸 목록</b>에서의 번호다 — 우리가 거르고 남은 목록의
     * 번호가 아니다. 거른 뒤의 번호를 돌려주면 화면이 엉뚱한 줄을 머리글로 알고 그 다음 줄부터
     * 자료로 읽는다. 걸러 낸 만큼 어긋나므로 <b>조용히</b> 틀린다.
     *
     * @param header 그 줄의 칸 이름들 — 무엇을 어느 칸으로 읽었는지 화면에 보여 주기 위해
     * @param source {@code "무료"} 또는 {@code "유료"} — 어느 통로가 답했는지 밝힌다
     */
    public record Mapping(int row, int date, int merchant, int amount, int biz,
                          List<String> header, String source) {}

    /** 거르고 남은 후보 하나 — <b>원래 몇 번째였는지</b>를 잃지 않는다. */
    private record Candidate(int originalIndex, List<String> cells) {}

    public ColumnMapperService(
            TempClassifierProperties free, ObjectMapper json,
            @Value("${finntech.gemini.api-key:}") String geminiKey,
            @Value("${finntech.gemini.model:}") String geminiModel,
            @Value("${finntech.gemini.base-url:https://generativelanguage.googleapis.com}") String geminiBase) {
        this.free = free;
        this.json = json;
        this.geminiKey = geminiKey == null ? "" : geminiKey;
        // **`@Value` 의 기본값을 쓰면 안 된다.** compose 가 `${GEMINI_MODEL:-}` 로 *빈 문자열을*
        // 넣어 주므로 프로퍼티는 "없는" 것이 아니라 "비어 있는" 것이 되고, `:기본값` 은 발동하지
        // 않는다. 그러면 URI 가 `/v1beta/models/:generateContent` 로 나가 404 다.
        // 기본값은 {@link GeminiModels#DEFAULT} 한 곳에만 둔다 (application.yml 주석 참조).
        this.geminiModel = GeminiModels.orDefault(geminiModel);
        this.http = RestClient.builder().requestFactory(factory()).build();
        this.gemini = RestClient.builder().baseUrl(geminiBase).requestFactory(factory()).build();
    }

    private static org.springframework.http.client.ClientHttpRequestFactory factory() {
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofMillis(TIMEOUT_MS));
        f.setReadTimeout(Duration.ofMillis(TIMEOUT_MS));
        return f;
    }

    /** 부를 수 있는 통로가 하나라도 있는가. 둘 다 없으면 이 기능은 조용히 없는 것이 된다. */
    public boolean usable() {
        return free.usable() || !geminiKey.isBlank();
    }

    /**
     * 머리글 후보들을 받아 칸 연결을 돌려준다. 못 찾으면 빈 값이다.
     *
     * @param candidates 브라우저가 고른 후보 줄들. 여기서 <b>다시</b> 거른다
     * @param ip         호출 상한을 세는 키
     */
    public Optional<Mapping> map(List<List<String>> candidates, String ip) {
        if (!usable() || candidates == null || candidates.isEmpty()) return Optional.empty();

        List<Candidate> rows = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (rows.size() >= MAX_ROWS) break;
            List<String> trimmed = clean(candidates.get(i));
            if (isHeaderCandidate(trimmed)) rows.add(new Candidate(i, trimmed));
        }
        if (rows.isEmpty()) return Optional.empty();

        String key = rows.stream().map(Candidate::cells).toList().toString();
        Cached hit = cache.get(key);
        if (hit != null && hit.at().isAfter(Instant.now().minus(Duration.ofHours(12)))) {
            return Optional.ofNullable(hit.mapping());
        }
        if (!allow(ip)) {
            log.info("칸 연결 문의가 IP 상한에 걸렸다");
            return Optional.empty();
        }

        String prompt = prompt(rows);
        if (prompt.length() > MAX_TOTAL_CHARS) return Optional.empty();

        Mapping answer = null;
        String text = free.usable() ? askFree(prompt) : null;
        if (text != null) answer = parse(text, rows, "무료");
        if (answer == null && !geminiKey.isBlank()) {
            text = askGemini(prompt);
            if (text != null) answer = parse(text, rows, "유료");
        }
        cache.put(key, new Cached(answer, Instant.now()));
        if (answer == null) {
            log.info("칸 연결을 모델도 찾지 못했다 — 후보 {}줄", rows.size());
        } else {
            // **여기 로그가 별칭표를 키운다.** 모델의 답은 DB 에 남기지 않는다(추정층). 대신
            // 사람이 이 로그를 보고 `COLUMN_ALIASES` 에 이름을 넣으면 그때부터 ①이 답한다.
            log.info("칸 연결을 모델이 찾았다({}) — 날짜='{}' 가맹점='{}' 금액='{}' 사업자='{}'"
                            + " · 별칭표에 넣으면 다음부터는 안 물어도 된다",
                    answer.source(), at(answer.header(), answer.date()),
                    at(answer.header(), answer.merchant()), at(answer.header(), answer.amount()),
                    at(answer.header(), answer.biz()));
        }
        return Optional.ofNullable(answer);
    }

    private static String at(List<String> cells, int index) {
        return index < 0 || index >= cells.size() ? "" : cells.get(index);
    }

    private static List<String> clean(List<String> row) {
        List<String> out = new ArrayList<>();
        for (String cell : row) {
            if (out.size() >= MAX_CELLS) break;
            String value = cell == null ? "" : cell.replaceAll("\\s+", " ").trim();
            out.add(value.length() > MAX_CELL_CHARS ? value.substring(0, MAX_CELL_CHARS) : value);
        }
        return out;
    }

    /**
     * <b>이 줄을 내보내도 되는가.</b> 값이 한 톨이라도 섞였으면 안 된다.
     *
     * <p>머리글은 <b>짧은 낱말들</b>이다. 날짜도 금액도 사업자번호도 없고, 여러 칸이 차 있다.
     * 자료 줄과 머리말 줄은 이 셋 중 하나에 반드시 걸린다.
     */
    static boolean isHeaderCandidate(List<String> cells) {
        int filled = 0;
        for (String cell : cells) {
            if (cell.isEmpty()) continue;
            filled++;
            if (cell.length() > MAX_CELL_CHARS) return false;
            if (LOOKS_LIKE_DATE.matcher(cell).matches()) return false;
            if (LOOKS_LIKE_AMOUNT.matcher(cell).matches()) return false;
            if (LOOKS_LIKE_BIZ.matcher(cell).find()) return false;
        }
        return filled >= MIN_FILLED_CELLS;
    }

    /**
     * <b>어느 칸이 무엇인지</b>만 묻는다 — 값을 해석해 달라고 하지 않는다.
     *
     * <p>헷갈리는 자리를 미리 못 박는다. 명세서에는 날짜가 둘(거래일·확정일), 금액이 넷
     * (이용금액·공급가액·부가세·비과세)씩 있고, 잘못 고르면 <b>조용히 틀린 합계</b>가 된다.
     */
    private String prompt(List<Candidate> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                카드 명세서 CSV 의 머리글 후보다. 각 줄은 `줄번호> 칸0 | 칸1 | ...` 이고 칸 번호는 0부터 센다.

                """);
        for (int i = 0; i < rows.size(); i++) {
            sb.append(i).append("> ").append(String.join(" | ", rows.get(i).cells())).append('\n');
        }
        sb.append("""

                결제 내역의 머리글 줄을 하나 고르고, 아래 항목에 해당하는 칸 번호를 답하라.

                - date: 결제가 **일어난** 날짜. 거래일·이용일·승인일·매출일이다.
                        확정일·청구일·결제일·정산일은 **아니다**.
                - merchant: 가맹점(사용처·상호) 이름. 카드 이름이나 상품 구분이 아니다.
                - amount: 결제한 **총액**. 이용금액·승인금액·거래금액이다.
                        공급가액·부가세·비과세금액·수수료·할부금·잔액·한도는 **아니다**.
                - biz: 사업자등록번호.

                없는 항목은 -1. 머리글 줄이 후보에 없으면 row 를 -1 로.
                설명·마크다운 없이 JSON 만:
                {"row":0,"date":0,"merchant":0,"amount":0,"biz":-1}
                """);
        return sb.toString();
    }

    /** 무료 통로(OpenAI 호환). {@code temperature 0} — 같은 머리글에 같은 답이라야 한다(§4-3). */
    private String askFree(String prompt) {
        try {
            String body = http.post()
                    .uri(free.getBaseUrl())
                    .header("Authorization", "Bearer " + free.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(Map.of(
                            "model", free.getModel(),
                            "messages", List.of(Map.of("role", "user", "content", prompt)),
                            "max_tokens", 96,
                            "temperature", 0)))
                    .retrieve()
                    .body(String.class);
            if (body == null) return null;
            return json.readTree(body).path("choices").path(0)
                    .path("message").path("content").asString("").trim();
        } catch (RuntimeException e) {
            log.debug("칸 연결 — 무료 통로 실패: {}", e.toString());
            return null;
        }
    }

    /** 유료 통로. 무료가 느리거나 막혔을 때만 온다. */
    private String askGemini(String prompt) {
        try {
            String body = gemini.post()
                    .uri("/v1beta/models/{model}:generateContent?key={key}", geminiModel, geminiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                                 "generationConfig", Map.of("temperature", 0)))
                    .retrieve()
                    .body(String.class);
            if (body == null) return null;
            return json.readTree(body).path("candidates").path(0).path("content")
                    .path("parts").path(0).path("text").asString("").trim();
        } catch (RuntimeException e) {
            log.debug("칸 연결 — 유료 통로 실패: {}", e.toString());
            return null;
        }
    }

    /**
     * 모델의 답을 <b>검사해서</b> 받아들인다.
     *
     * <p>번호가 범위 밖이거나 날짜·가맹점·금액이 서로 같은 칸을 가리키면 버린다. 없는 칸을
     * 답하는 것은 흔한 실패이고, 그대로 쓰면 엉뚱한 칸이 금액으로 읽혀 <b>조용히 틀린 합계</b>가
     * 된다. 여기서 걸러 "못 찾았다"로 돌리면 사용자는 최소한 <b>틀렸다는 것을</b> 안다.
     */
    private Mapping parse(String text, List<Candidate> rows, String source) {
        try {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) return null;
            JsonNode node = json.readTree(text.substring(start, end + 1));

            int row = node.path("row").asInt(-1);
            if (row < 0 || row >= rows.size()) return null;
            Candidate chosen = rows.get(row);
            int width = chosen.cells().size();

            int date = index(node, "date", width);
            int merchant = index(node, "merchant", width);
            int amount = index(node, "amount", width);
            int biz = index(node, "biz", width);
            if (date < 0 || merchant < 0 || amount < 0) return null;
            if (date == merchant || date == amount || merchant == amount) return null;
            if (biz == date || biz == merchant || biz == amount) biz = -1;

            // 번호는 **부른 쪽이 보낸 목록** 기준으로 되돌린다. 거른 뒤의 번호가 아니다.
            return new Mapping(chosen.originalIndex(), date, merchant, amount, biz,
                    chosen.cells(), source);
        } catch (RuntimeException e) {
            log.debug("칸 연결 — 답을 못 읽었다: {}", e.toString());
            return null;
        }
    }

    private static int index(JsonNode node, String field, int width) {
        int value = node.path(field).asInt(-1);
        return (value < 0 || value >= width) ? -1 : value;
    }

    /** IP 당 시간당 상한. 시간이 바뀌면 저절로 0 이 된다 — 따로 비우지 않는다. */
    private boolean allow(String ip) {
        long hour = Instant.now().getEpochSecond() / 3600;
        String key = ip == null ? "?" : ip;
        Counter next = quota.compute(key, (k, old) ->
                (old == null || old.hour() != hour) ? new Counter(hour, 1)
                                                    : new Counter(hour, old.count() + 1));
        if (quota.size() > 10_000) quota.clear();       // 무한히 자라지 않게
        return next.count() <= HOURLY_PER_IP;
    }

    /** 화면에 돌려줄 모양 — 칸 번호와 <b>그 칸의 이름</b>을 함께 준다. */
    public static Map<String, Object> describe(Mapping mapping) {
        List<String> header = mapping.header();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("row", mapping.row());
        out.put("date", mapping.date());
        out.put("merchant", mapping.merchant());
        out.put("amount", mapping.amount());
        out.put("biz", mapping.biz());
        out.put("source", mapping.source());
        Map<String, String> names = new LinkedHashMap<>();
        names.put("날짜", at(header, mapping.date()));
        names.put("가맹점", at(header, mapping.merchant()));
        names.put("금액", at(header, mapping.amount()));
        if (mapping.biz() >= 0) names.put("사업자번호", at(header, mapping.biz()));
        out.put("names", names);
        return out;
    }
}
