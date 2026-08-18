package com.finntech.usage;

import tools.jackson.databind.ObjectMapper;
import com.finntech.freechannel.FreeChannelQueue;
import com.finntech.freechannel.Lane;
import com.finntech.service.TempClassifierService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 통계 용어를 <b>친절한 말로</b> 옮긴다 — 무료 통로(NVIDIA)를 쓴다.
 *
 * <h2>사실은 우리가, 말투는 모델이</h2>
 *
 * <p>{@code r-compare} 가 무슨 화면인지, 참여 세션을 어떻게 세는지는 <b>우리만 안다.</b>
 * 그래서 {@link UsageGlossary} 가 사실을 대고, 모델은 그것을 <b>다시 쓰기만</b> 한다 —
 * 마스터 §4 원칙 1(판단은 설명가능한 모델이, 표현은 AI가) 그대로다.
 *
 * <p>모델이 새 사실을 지어내지 못하게 프롬프트에 못을 박는다. 그래도 지어낼 수 있으므로
 * <b>다듬어진 문장이 없으면 원문이 그대로 뜬다</b> — 어느 쪽이든 내용은 같고, 다듬어진 쪽이
 * 더 읽기 쉬울 뿐이다.
 *
 * <h2>기다리게 하지 않는다</h2>
 *
 * <p>첫 조회는 원문을 즉시 준다. 다듬기는 뒤에서 돌고({@link Lane#ADMIN} — 맨 뒤 차선이라
 * 사용자의 문장을 안 밀어낸다) 다음 조회 때 바뀐다.
 *
 * <p>한 번에 <b>여러 항목을 묶어</b> 묻는다. 항목이 팔십여 개인데 하나씩 물으면 팔십 번이다.
 */
@Service
public class UsageGlossaryService {

    private static final Logger log = LoggerFactory.getLogger(UsageGlossaryService.class);

    /** 한 번에 묶어 묻는 항목 수. 프롬프트가 너무 길면 모델이 뒤쪽을 흘린다. */
    private static final int BATCH = 12;

    /**
     * 말투를 정하는 자리 — <b>사용자가 그대로 요구한 문구</b>다.
     *
     * <p>통계를 처음 보는 사람이 읽을 글이라, 정확한 것보다 <b>겁먹지 않는 것</b>이 먼저다.
     */
    private static final String TONE = """
            최대한 쉽고 친근감 있게, 친절하고 자세한 설명을 하세요.
            읽는 사람이 이 용어를 전혀 모를 수도 있으니 친절히 대답하세요.
            """;

    private final TempClassifierService free;
    private final FreeChannelQueue queue;
    private final ObjectMapper json;

    /** 열쇠 → 다듬어진 문장. 메모리에만 산다 — 재기동하면 원문부터 다시 시작한다. */
    private final Map<String, String> polished = new ConcurrentHashMap<>();

    /** 이미 큐에 올린 열쇠. 조회할 때마다 다시 올리면 통로가 같은 일로 막힌다. */
    private final Map<String, Boolean> queued = new ConcurrentHashMap<>();

    public UsageGlossaryService(TempClassifierService free, FreeChannelQueue queue,
                                ObjectMapper json) {
        this.free = free;
        this.queue = queue;
        this.json = json;
    }

    /**
     * 사전 전체.
     *
     * @return {@code {"screens": {id: {title, text}}, "terms": {…}, "polished": n, "total": n}}
     */
    public Map<String, Object> glossary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("screens", render(UsageGlossary.SCREENS));
        out.put("terms", render(UsageGlossary.TERMS));
        out.put("polished", polished.size());
        out.put("total", UsageGlossary.SCREENS.size() + UsageGlossary.TERMS.size());
        // 아직 안 다듬은 것을 뒤에서 다듬는다. 화면은 기다리지 않는다.
        schedule();
        return out;
    }

    private Map<String, Object> render(Map<String, UsageGlossary.Entry> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, entry) -> {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("title", entry.title());
            String nice = polished.get(key);
            one.put("text", nice != null ? nice : entry.fact());
            one.put("source", nice != null ? "AI" : "BASE");
            out.put(key, one);
        });
        return out;
    }

    // ── 다듬기 ────────────────────────────────────────────────────────────────

    private void schedule() {
        if (!free.usable()) return;
        List<Map.Entry<String, UsageGlossary.Entry>> todo = new ArrayList<>();
        UsageGlossary.SCREENS.entrySet().forEach(e -> collect(todo, e));
        UsageGlossary.TERMS.entrySet().forEach(e -> collect(todo, e));
        if (todo.isEmpty()) return;

        for (int from = 0; from < todo.size(); from += BATCH) {
            List<Map.Entry<String, UsageGlossary.Entry>> chunk =
                    todo.subList(from, Math.min(from + BATCH, todo.size()));
            List<Map.Entry<String, UsageGlossary.Entry>> batch = List.copyOf(chunk);
            String key = "glossary:" + batch.get(0).getKey() + ":" + batch.size();
            if (!queue.submit(Lane.ADMIN, key, () -> polish(batch))) {
                // 큐가 넘쳤다 — 다음 조회가 다시 올린다. 표시를 되돌려 놔야 그게 된다.
                batch.forEach(e -> queued.remove(e.getKey()));
            }
        }
    }

    private void collect(List<Map.Entry<String, UsageGlossary.Entry>> todo,
                         Map.Entry<String, UsageGlossary.Entry> entry) {
        if (polished.containsKey(entry.getKey())) return;
        if (queued.putIfAbsent(entry.getKey(), true) != null) return;
        todo.add(entry);
    }

    private void polish(List<Map.Entry<String, UsageGlossary.Entry>> batch) {
        String answer = free.sentence(prompt(batch)).orElse(null);
        if (answer == null) {
            batch.forEach(e -> queued.remove(e.getKey()));   // 다음 조회가 다시 시도한다
            return;
        }
        Map<String, String> parsed = parse(answer);
        int kept = 0;
        for (var entry : batch) {
            String text = parsed.get(entry.getKey());
            if (text == null || text.isBlank()) {
                queued.remove(entry.getKey());
                continue;
            }
            polished.put(entry.getKey(), text.trim());
            kept++;
        }
        log.debug("통계 용어 해설 {}건 다듬음 (요청 {}건)", kept, batch.size());
    }

    private String prompt(List<Map.Entry<String, UsageGlossary.Entry>> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                아래는 어떤 앱의 이용 통계 화면에 나오는 용어와 그 뜻입니다.
                각 뜻을 처음 보는 사람도 이해할 수 있게 다시 써 주세요.

                """);
        sb.append(TONE);
        sb.append("""

                지켜야 할 것:
                - **주어진 뜻에 없는 사실을 새로 만들지 마세요.** 같은 내용을 쉬운 말로 옮기기만 합니다.
                - 한국어 2~4문장. 존댓말.
                - 필요하면 짧은 예를 하나 들어도 좋지만, 주어진 내용에서 벗어나면 안 됩니다.
                - 답은 아래 JSON 형식으로만 주세요. 다른 말은 붙이지 마세요.

                {"용어키": "다시 쓴 설명", ...}

                용어:
                """);
        for (var entry : batch) {
            sb.append("- ").append(entry.getKey())
              .append(" (").append(entry.getValue().title()).append("): ")
              .append(entry.getValue().fact()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 모델의 답에서 JSON 만 건져 낸다.
     *
     * <p>형식을 못 지키는 경우가 흔하다(코드 울타리를 두르거나 앞말을 붙인다). 첫 {@code {} 부터
     * 마지막 {@code }} 까지 잘라 본다 — 그래도 안 되면 <b>조용히 포기</b>하고 원문이 남는다.
     */
    private Map<String, String> parse(String answer) {
        int from = answer.indexOf('{');
        int to = answer.lastIndexOf('}');
        if (from < 0 || to <= from) return Map.of();
        try {
            Map<?, ?> raw = json.readValue(answer.substring(from, to + 1), Map.class);
            Map<String, String> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                if (k != null && v instanceof String s) out.put(String.valueOf(k), s);
            });
            return out;
        } catch (RuntimeException e) {
            log.debug("용어 해설 응답을 못 읽었다 — {}", e.toString());
            return Map.of();
        }
    }
}
