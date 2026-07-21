package com.finntech.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 사용자 테스트 계측 (RFP {@code C13} · {@code D20} — <b>필수 제출물</b>).
 *
 * <p>RFP가 요구하는 지표는 네 가지다: 추천 만족도 / 서비스 체류 시간 / 모의 상품 가입 의향 / 상품 추천 클릭률.
 * 이 중 <b>클릭률과 체류시간은 이벤트 지표라 응답자 수에 상대적으로 강건</b>하고,
 * 만족도·가입의향은 n에 취약하다. 그래서 <b>구분해서</b> 집계한다 (D-10 확정).
 *
 * <p>GA4/Amplitude로도 보내지만 서버에도 쌓는다. 외부 도구는 계정·키·네트워크에 의존하는데,
 * 발표 당일 그게 막히면 <b>필수 제출물이 통째로 사라진다.</b> 서버 집계는 그 보험이다.
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);

    /** 데모 규모(인터뷰 2~3명)에서는 인메모리로 충분하다. 재시작하면 사라지므로 수집 후 즉시 내보낸다. */
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final List<Event> events = Collections.synchronizedList(new ArrayList<>());
    private final List<Survey> surveys = Collections.synchronizedList(new ArrayList<>());
    private final Clock clock;

    public AnalyticsController(Clock clock) {
        this.clock = clock;
    }

    public record TrackRequest(
            @NotBlank String event,
            Long userId,
            Map<String, Object> properties
    ) {}

    public record Event(String event, Long userId, Map<String, Object> properties, LocalDateTime at) {}

    /** 프론트가 부르는 이벤트 수집. 클릭률의 분자·분모가 여기서 만들어진다. */
    @PostMapping("/track")
    public Map<String, Object> track(@Valid @RequestBody TrackRequest req) {
        LocalDateTime at = LocalDateTime.now(clock);
        counters.computeIfAbsent(req.event(), k -> new AtomicLong()).incrementAndGet();
        events.add(new Event(req.event(), req.userId(),
                req.properties() == null ? Map.of() : req.properties(), at));
        log.debug("analytics: {} userId={} props={}", req.event(), req.userId(), req.properties());
        return Map.of("ok", true, "event", req.event());
    }

    public record SurveyRequest(
            Long userId,
            /** 추천 만족도 1~5 (RFP C13) */
            Integer recommendationSatisfaction,
            /** 리포트 만족도 1~5 (RFP D24 — 정성 피드백) */
            Integer reportSatisfaction,
            /** 모의 상품 가입 의향 1~5 (RFP C13·D20) */
            Integer signupIntent,
            String freeText
    ) {}

    /** 설문 응답 수집. n을 함께 기록해 정성 자료임을 숨기지 않는다. */
    @PostMapping("/survey")
    public Map<String, Object> survey(@RequestBody SurveyRequest req) {
        surveys.add(new Survey(req.userId(), req.recommendationSatisfaction(),
                req.reportSatisfaction(), req.signupIntent(), req.freeText(),
                LocalDateTime.now(clock)));
        return Map.of("ok", true, "responseCount", surveys.size());
    }

    public record Survey(Long userId, Integer recommendationSatisfaction, Integer reportSatisfaction,
                         Integer signupIntent, String freeText, LocalDateTime at) {}

    /**
     * RFP 산출물용 집계. 그대로 "사용자 테스트 결과"에 붙일 수 있게 만든다.
     *
     * <p>만족도·가입의향에는 반드시 {@code n}과 경고 문구를 함께 내보낸다 —
     * n=2에서 나온 평균을 통계인 척 제시하면 심사자에게 정확히 반박당한다.
     */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        long recommendViews = count("recommend_view");
        long productClicks = count("product_click");
        long reportViews = count("report_view");
        long alertViews = count("alert_view");

        Map<String, Object> ctr = new LinkedHashMap<>();
        ctr.put("recommendViews", recommendViews);
        ctr.put("productClicks", productClicks);
        ctr.put("clickThroughRate", recommendViews == 0 ? null
                : Math.round(productClicks * 10000.0 / recommendViews) / 100.0);
        ctr.put("note", "이벤트 기반 지표 — 응답자 수에 상대적으로 강건함");

        Map<String, Object> qual = new LinkedHashMap<>();
        qual.put("n", surveys.size());
        qual.put("recommendationSatisfactionAvg", avg(Survey::recommendationSatisfaction));
        qual.put("reportSatisfactionAvg", avg(Survey::reportSatisfaction));
        qual.put("signupIntentAvg", avg(Survey::signupIntent));
        qual.put("warning", surveys.size() < 30
                ? "표본 " + surveys.size() + "명 — 통계적 유의성이 없다. RFP가 요구한 '정성 피드백'으로만 해석할 것."
                : null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clickMetrics", ctr);
        body.put("qualitativeMetrics", qual);
        body.put("pageViews", Map.of("report", reportViews, "alert", alertViews));
        Map<String, Long> raw = new TreeMap<>();
        counters.forEach((k, v) -> raw.put(k, v.get()));
        body.put("rawEventCounts", raw);
        body.put("freeTextResponses", surveys.stream()
                .map(Survey::freeText).filter(t -> t != null && !t.isBlank()).toList());
        return body;
    }

    private long count(String event) {
        AtomicLong c = counters.get(event);
        return c == null ? 0 : c.get();
    }

    private Double avg(java.util.function.Function<Survey, Integer> f) {
        List<Integer> vals = surveys.stream().map(f).filter(Objects::nonNull).toList();
        if (vals.isEmpty()) return null;
        return Math.round(vals.stream().mapToInt(Integer::intValue).average().orElse(0) * 100.0) / 100.0;
    }
}
