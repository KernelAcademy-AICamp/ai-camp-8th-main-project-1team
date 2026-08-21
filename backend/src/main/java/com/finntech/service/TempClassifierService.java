package com.finntech.service;

import com.finntech.config.TempClassifierProperties;
import com.finntech.engine.IndustryCategoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <b>임시 분류</b> — 무료 추론 API 로 미분류 결제에 잠정 업종을 붙인다(순위 ②-c).
 *
 * <pre>
 *   ① 사전                      즉시
 *   ②-b 등록 업종 조회            즉시
 *   ②-c 임시 분류 (여기)          즉시 · 무료 · <b>DB 에 안 남는다</b>
 *   ③  LLM 확정 추정 (Gemini)    40곳 쌓이면 · 사전에 남는다
 * </pre>
 *
 * <p><b>왜 있나.</b> ③은 40곳이 쌓여야 부른다 — 프롬프트의 76%가 업종 목록이라 1곳씩 부르면
 * 40배가 들기 때문이다. 그 사이 새 결제는 '카테고리없음'으로 남는데, 무료 통로는 그 값이 0
 * 이라 결제가 들어오는 대로 물어도 손해가 없다.
 *
 * <p><b>DB 에 안 남기는 것이 설계의 핵심이다.</b> 사전에는 유료 모델의 답과 사람의 확정만
 * 들어간다. 두 모델의 답을 한 사전에 섞으면 "어느 쪽이 넣은 값인가"가 판정·리포트까지 번지고,
 * 무료 통로가 막히는 날 사전의 절반이 근거를 잃는다. 그래서 여기 답은 <b>메모리에만</b> 산다.
 *
 * <p><b>실패는 조용하다.</b> 답이 안 오든 형식이 깨지든 막히든, 그 가맹점은 그냥 '카테고리없음'
 * 으로 남는다 — 사용자 화면에 새 상태가 생기지 않으므로 오류 배너도 띄우지 않는다. "일시 실패"를
 * 보여 봐야 사용자가 할 수 있는 일이 없고, 다음 회차에 자동으로 다시 시도된다.
 *
 * <p><b>실측 근거</b>(2026-08-07, 정답 아는 가맹점 191곳): 무료 모델 정확도 73%,
 * 유료 모델 75% — <b>사실상 동급</b>이며 틀리는 항목까지 거의 같았다(둘 다 러쉬를 미용,
 * 베스킨라빈스를 카페로 답했는데 이는 사용자 확정값과 다를 뿐 상식적으로는 오히려 맞다).
 * 속도는 40곳 한 묶음에 28초 대 3초로 열 배 차이지만, 이 통로는 백그라운드라 상관없다.
 */
@Service
public class TempClassifierService {

    private static final Logger log = LoggerFactory.getLogger(TempClassifierService.class);

    /** 한 번에 담는 가맹점 수. 입력 토큰의 대부분이 업종 목록이라 묶을수록 이득이다. */
    private static final int BATCH = 40;

    private final TempClassifierProperties props;
    private final IndustryCategoryMapper mapper;
    private final MerchantClassifierService classifier;
    private final ObjectMapper json;
    private final RestClient client;

    /** 가맹점명 → 답. <b>메모리에만</b> 산다 — 재기동하면 비고, 그래도 무해하다. */
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    /** 연속 실패 수. 문턱을 넘으면 이 프로세스가 사는 동안 스스로 끈다. */
    private final AtomicInteger failures = new AtomicInteger();

    /** 한 가맹점의 임시 답 — <b>업종 이름</b>과 그것이 떨어진 중분류. */
    public record Guess(String industryName, String category2) {}

    private record Cached(Guess guess, Instant at) {}

    /**
     * <b>모델 하나에 통로를 걸지 않는다.</b> 앞 모델이 죽으면 다음으로 넘어가고, 날짜가
     * 바뀌면 처음으로 돌아간다({@link ModelChain}). 운영에서 모델 하나가 무응답이라 로딩이
     * 40초가 됐던 것이 이 사슬을 만든 이유다(2026-08-20).
     */
    private final ModelChain chain;

    public TempClassifierService(TempClassifierProperties props, IndustryCategoryMapper mapper,
                                 MerchantClassifierService classifier, ObjectMapper json,
                                 java.time.Clock clock) {
        this.props = props;
        this.mapper = mapper;
        this.classifier = classifier;
        this.json = json;
        this.client = props.usable()
                ? RestClient.builder().requestFactory(factory(props.getTimeoutMs())).build()
                : null;
        this.chain = props.usable()
                ? new ModelChain(ModelChain.parse(props.chainSpec(), props.getFailureCutoff()), clock)
                : null;
        if (props.usable()) {
            log.info("임시 분류 통로 켜짐 — 모델 {}종({}), 회차당 최대 {}곳",
                    ModelChain.parse(props.chainSpec(), props.getFailureCutoff()).size(),
                    chain.current(), props.getMaxPerRun());
        }
    }

    /** 지금 쓸 모델 — 사슬이 정한다. */
    private String model() { return chain == null ? props.getModel() : chain.current(); }

    private static org.springframework.http.client.ClientHttpRequestFactory factory(int timeoutMs) {
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofMillis(timeoutMs));
        f.setReadTimeout(Duration.ofMillis(timeoutMs));
        return f;
    }

    /**
     * 켜져 있고 <b>지금</b> 쓸 수 있는가.
     *
     * <p><b>영구히 끄지 않는다.</b> 예전에는 연속 실패가 문턱을 넘으면 이 프로세스가 사는 동안
     * 통로를 껐다. 그런데 429 는 <i>잠깐 너무 많이 불렀다</i>는 뜻이지 <i>통로가 죽었다</i>가
     * 아니라서, 그러면 <b>재기동 전까지 분류도 브랜드도 문장도 전부 멈춘다.</b> 큐가 예산껏
     * 두드리는 구조에서는 연속 5회가 순식간이라 더 그렇다(2026-08-08).
     *
     * <p>대신 실패가 쌓인 만큼 <b>쉬었다 다시 본다.</b> 문턱을 넘으면 그때부터 유예가 걸리고,
     * 한 번 성공하면 {@code failures} 가 0 으로 돌아가 유예도 사라진다.
     */
    public boolean usable() {
        if (client == null) return false;
        if (failures.get() < props.getFailureCutoff()) return true;
        return System.currentTimeMillis() >= restAfterMillis;
    }

    /** 문턱을 넘긴 뒤의 유예 종료 시각. 실패가 쌓일수록 길어지되 상한이 있다. */
    private volatile long restAfterMillis;

    private void noteFailure() {
        int n = failures.incrementAndGet();
        if (n < props.getFailureCutoff()) return;
        // 문턱 초과분마다 배로 늘리되 5분에서 멈춘다. 무한히 늘리면 회복을 못 본다.
        long wait = Math.min(300_000L, 15_000L * (1L << Math.min(5, n - props.getFailureCutoff())));
        restAfterMillis = System.currentTimeMillis() + wait;
        log.warn("임시 통로가 {}회 연속 실패해 {}초 쉰다 — 성공하면 곧바로 회복한다", n, wait / 1000);
    }

    /** 이미 물어본 가맹점의 임시 답 — 없거나 오래됐으면 비어 있다. */
    public Optional<Guess> cached(String merchantName) {
        Cached c = cache.get(merchantName);
        if (c == null) return Optional.empty();
        if (c.at().plus(Duration.ofMinutes(props.getCacheMinutes())).isBefore(Instant.now())) {
            cache.remove(merchantName);
            return Optional.empty();
        }
        return Optional.of(c.guess());
    }

    /**
     * 아직 답이 없는 가맹점들을 묶어서 묻고, 알아낸 것만 돌려준다.
     *
     * <p>이미 캐시에 있는 것은 묻지 않고 그대로 얹어 준다. 물어봐야 소용없는 상호(PG)는
     * {@link MerchantClassifierService#worthAsking} 이 걸러 낸 뒤 들어오는 것을 전제한다.
     *
     * @return 가맹점명 → 임시 답. 실패했거나 꺼져 있으면 캐시에 있던 것만.
     */
    public Map<String, Guess> classify(List<String> merchantNames) {
        Map<String, Guess> out = new LinkedHashMap<>();
        if (merchantNames == null || merchantNames.isEmpty()) return out;

        List<String> ask = new ArrayList<>();
        for (String n : merchantNames.stream().distinct().toList()) {
            Optional<Guess> hit = cached(n);
            if (hit.isPresent()) out.put(n, hit.get());
            else ask.add(n);
        }
        if (ask.isEmpty() || !usable()) return out;

        // **남은 것을 전부 훑는다.** 회차당 상한을 두지 않는다 — 무료 통로라 아낄 것이 없고,
        // 자르면 그만큼이 다음 회차(5분 뒤)로 밀려 미분류가 오래 남는다. 유료 통로가 40곳씩
        // 묶는 것과 혼동하기 쉬운데, 그쪽은 프롬프트의 76%가 업종 목록이라 묶어야 이득인 것이고
        // 여기는 그런 이유가 없다.
        // **한 곳씩 묻는다.** 묶으면 모델이 앞 답에 끌려가고(앞이 카페면 뒤도 카페),
        // JSON 형식이 깨지면 묶음 전체를 잃었다. 한 곳씩이면 답은 단어 하나이고 실패해도
        // 그 하나만 잃는다({@code IndustryPrompt} 설계 주석).
        String catalog = IndustryPrompt.industryList(mapper);
        for (int i = 0; i < ask.size(); i += BATCH) {
            List<String> chunk = ask.subList(i, Math.min(i + BATCH, ask.size()));
            Map<String, String> got = callOnce(catalog, chunk);
            if (got == null) return out;                 // 실패 — 남은 묶음도 포기한다
            Instant now = Instant.now();
            got.forEach((name, industry) -> {
                String mid = mapper.midOfIndustryName(industry);
                if (IndustryCategoryMapper.isUnknown(mid)) return;   // 목록 밖 이름은 버린다
                Guess g = new Guess(industry, mid);
                cache.put(name, new Cached(g, now));
                out.put(name, g);
            });
        }
        return out;
    }

    /**
     * 가맹점명 하나에서 <b>브랜드</b>를 뽑는다 — 못 뽑으면 빈 값.
     *
     * <p><b>하나씩 묻는다.</b> 묶어 물으면 모델이 지점명을 흘리거나 엉뚱한 것을 브랜드로
     * 잡는다. 이 질문은 프롬프트가 짧아(업종 목록이 필요 없다) 하나씩 물어도 싸고, 부르는 곳이
     * 무료 통로라 회수를 아낄 이유도 없다.
     *
     * <p>답이 이름보다 길거나 원문에 없는 글자를 만들어 내면 버린다 — 지어낸 브랜드가
     * 들어오지 못하게 하는 최소한의 방벽이다.
     */
    public Optional<String> brandOf(String merchantName) {
        if (!usable() || merchantName == null || merchantName.isBlank()) return Optional.empty();
        String name = merchantName.trim();
        String prompt = """
                아래는 한국 카드 명세서에 찍힌 가맹점명입니다. 여기서 **브랜드 이름만** 뽑으세요.

                가맹점명: %s

                규칙
                - 지점명·지역명·법인격 표기를 떼고 브랜드만 남기세요.
                  예) "GS25 강남역점" -> GS25   "스타벅스 포항공대점" -> 스타벅스
                      "(주)우아한형제들" -> 배달의민족   "롯데백화점 본점" -> 롯데백화점
                - **프랜차이즈·체인이 아닌 개인 상호로 보이면** 브랜드가 없는 것입니다.
                  그럴 때는 없음 이라고만 쓰세요. 예) "물고기자리" -> 없음
                - 무엇인지 모르겠으면 빈 문자열로 두세요.
                - 설명 없이 브랜드만 한 줄로 쓰세요.
                """.formatted(name);
        String answer = askOnce(prompt);
        if (answer == null) return Optional.empty();
        String brand = answer.replaceAll("[\r\n]+", " ").replaceAll("[\"'`]", "").trim();
        if (brand.isBlank() || brand.length() > 60) return Optional.empty();
        // 프랜차이즈가 아닌 개인 상호 — "아직 안 물어봤다"와 구별해 적어 둔다.
        if ("없음".equals(brand) || "NONE".equalsIgnoreCase(brand)) {
            return Optional.of(MerchantBrandService.NONE);
        }
        // 이름보다 긴 답은 지어낸 것이다 — 브랜드는 이름에서 잘라 낸 조각이라야 한다.
        if (brand.length() > name.length()) return Optional.empty();
        return Optional.of(brand);
    }

    /**
     * <b>2차 — 뽑아낸 브랜드가 이미 아는 브랜드와 같은 것인가.</b>
     *
     * <p><b>왜 두 단계인가.</b> 1차에서 목록을 함께 주면 모델이 <b>거기 있는 것 중에 고르려고</b>
     * 해서 엉뚱한 기존 브랜드에 끌려간다(앵커링) — 업종 목록에서 겪은 것과 같은 일이다.
     * 그래서 1차는 이름만 보고 자유롭게 뽑게 하고, <b>표기를 통일하는 일만</b> 2차로 미룬다.
     *
     * <p>이것이 없으면 같은 브랜드가 {@code GS25}·{@code 지에스25}·{@code GS리테일} 로 갈려
     * 쌓인다. 브랜드를 두는 목적이 "같은 것을 같다고 아는 것"인데 그 목적이 무너진다.
     *
     * @param candidate 1차에서 뽑은 브랜드
     * @param known     이미 아는 브랜드들
     * @return 같은 것이 있으면 <b>기존 이름</b>, 없으면 {@code candidate} 그대로
     */
    public String unify(String candidate, java.util.Collection<String> known) {
        if (candidate == null || candidate.isBlank()) return candidate;
        if (!usable() || known == null || known.isEmpty()) return candidate;
        // 글자가 같으면 물어볼 것도 없다.
        for (String k : known) {
            if (candidate.equalsIgnoreCase(k)) return k;
        }
        String list = String.join(" · ", known);
        String prompt = """
                브랜드 이름 하나와 이미 알고 있는 브랜드 목록이 있습니다.
                그 브랜드가 목록에 **이미 있는 것과 같은 브랜드**인지 판단하세요.

                판단할 브랜드: %s

                알고 있는 브랜드 목록:
                %s

                규칙
                - 표기만 다르고 같은 브랜드면(예: GS25 / 지에스25) 목록에 있는 **그 이름**을 쓰세요.
                - 목록에 같은 브랜드가 없으면 NONE 이라고만 쓰세요.
                - 비슷해 보여도 다른 브랜드면 NONE 입니다. 확실할 때만 같다고 하세요.
                - 설명 없이 한 줄로만 답하세요.
                """.formatted(candidate, list);
        String answer = askOnce(prompt);
        if (answer == null) return candidate;
        String picked = answer.replaceAll("[\r\n]+", " ").replaceAll("[\"'`]", "").trim();
        if (picked.isBlank() || "NONE".equalsIgnoreCase(picked)) return candidate;
        // 목록에 실제로 있는 이름만 받는다 — 지어낸 이름이 들어오지 못한다.
        for (String k : known) {
            if (picked.equalsIgnoreCase(k)) return k;
        }
        return candidate;
    }

    /** 프롬프트 하나를 보내고 답 문자열만 받는다 — 실패는 {@code null}. */
    private String askOnce(String prompt) {
        return askOnce(prompt, 64, 0);
    }

    /**
     * <b>사용자에게 보일 문장</b>을 하나 받아 온다 — 분류와 달리 길고, 매번 달라도 된다.
     *
     * <p>분류는 {@code max_tokens 64}·{@code temperature 0} 이다. 같은 입력에 같은 답이 나와야
     * 하기 때문이다(§4 원칙 3 재현성). 문장은 반대다 — 두세 문장이 들어갈 길이가 필요하고,
     * <b>매일 조금씩 달라지는 것이 이 기능의 목적</b>이라 온도를 준다.
     *
     * <p><b>숫자는 프롬프트에 이미 들어 있다.</b> 모델은 그것을 말로 옮길 뿐 새로 만들지도
     * 바꾸지도 않는다(§4 원칙 1). 부르는 쪽이 집계만 담아 보내는 것이 그 전제다.
     */
    public java.util.Optional<String> sentence(String prompt) {
        if (!usable() || prompt == null || prompt.isBlank()) return java.util.Optional.empty();
        String out = askOnce(prompt, 400, 0.7);
        return (out == null || out.isBlank()) ? java.util.Optional.empty() : java.util.Optional.of(out);
    }

    private String askOnce(String prompt, int maxTokens, double temperature) {
        try {
            String body = client.post()
                    .uri(props.getBaseUrl())
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(json.writeValueAsString(Map.of(
                            "model", model(),
                            "messages", List.of(Map.of("role", "user", "content", prompt)),
                            "max_tokens", maxTokens,
                            "temperature", temperature)))
                    .retrieve()
                    .body(String.class);
            if (body == null) return null;
            failures.set(0);            // 한 번 성공하면 유예가 사라진다
            if (chain != null) chain.succeeded();
            return json.readTree(body).path("choices").path(0)
                    .path("message").path("content").asString("").trim();
        } catch (RuntimeException e) {
            noteFailure();
            log.debug("임시 통로 호출 실패 — {}", e.toString());
            return null;
        }
    }

    /** 중분류로 묶은 업종 목록 — {@code MerchantClassifierService} 와 같은 형식이라야 한다. */
    private String catalog() {
        StringBuilder sb = new StringBuilder();
        mapper.industryNamesByMid().forEach((mid, list) ->
                sb.append('[').append(mid).append("] ").append(String.join(" · ", list)).append('\n'));
        return sb.toString();
    }

    /**
     * 한 묶음을 묻는다 — 실패하면 {@code null}(예외를 밖으로 던지지 않는다).
     *
     * <p><b>중분류가 아니라 업종을 묻는다.</b> 모델이 우리 축을 직접 고르면 축 배정까지 AI 가
     * 하는 셈이라 마스터 §4 원칙 1 과 어긋난다. 업종으로 받으면 모델은 "이 가게가 무엇을
     * 파는가"라는 사실만 말하고 축은 우리 표가 정한다 — 유료 통로와 같은 규칙이다.
     */
    /**
     * 묶음을 <b>한 곳씩</b> 물어 모은다.
     *
     * <p>이름은 {@code callOnce} 로 두지만 하는 일은 "이 묶음을 처리한다"이다. 안에서는
     * 가맹점마다 한 번씩 부른다 — 그것이 {@code IndustryPrompt} 가 정한 형태다.
     * 한 곳이 실패해도 나머지는 계속한다. 다만 <b>모델을 갈아탔으면</b> 그 사실을 살려
     * 바로 다음 가맹점부터 새 모델로 묻는다.
     */
    private Map<String, String> callOnce(String industryList, List<String> names) {
        Map<String, String> out = new LinkedHashMap<>();
        int consecutive = 0;
        for (String name : names) {
            String industry = classifyOne(name, industryList);
            if (industry == null) {
                // 한 묶음이 통째로 죽는 것은 통로가 죽은 것이다 — 다음 회차로 미룬다.
                if (++consecutive >= 3 && out.isEmpty()) return null;
                continue;
            }
            consecutive = 0;
            if (!industry.isEmpty()) out.put(name, industry);
        }
        return out;
    }

    /**
     * <b>한 가맹점을 세 단계로 분류한다</b>(2026-08-21 사용자 설계).
     *
     * <pre>
     *   1단계  업종 385종을 **통째로** 주고 묻는다        → A
     *   2단계  상호와 겹치는 것으로 **추린 30종**을 주고 묻는다 → B
     *   3단계  A·B 와 **가맹점명만** 주고 둘 중 하나를 고르게 한다
     * </pre>
     *
     * <h2>왜 두 번 묻고 또 묻는가</h2>
     *
     * <p>추리면 모델이 볼 것이 줄어 정확해지지만 <b>추리다 정답을 흘릴 수 있다.</b>
     * 그래서 추림 없이 본 답(A)을 옆에 세워 둔다. 둘이 갈리면 3단계가 고르고, <b>3단계가
     * 엉뚱한 답을 하면 A 를 쓴다</b> — 추림의 위험을 안 지는 쪽이 기본값이다.
     *
     * <p><b>A 와 B 가 같으면 3단계를 건너뛴다.</b> 같은 답에 판정을 물을 이유가 없고,
     * 호출이 세 배가 되는 것을 2.x 배로 줄인다.
     *
     * <p>3단계에는 <b>가맹점명만</b> 준다 — 브랜드도 목록도 주지 않는다. 앞 두 단계가 이미
     * 그것을 보고 답했으므로 여기서는 새 눈으로 둘만 견주게 한다.
     *
     * @return 업종 이름, 목록 밖이면 빈 문자열, 통로가 죽었으면 {@code null}
     */
    private String classifyOne(String name, String industryList) {
        String brand = brandCache.get(name);
        String a1 = askOne(IndustryPrompt.of(name, brand, industryList));
        if (a1 == null) return null;                       // 통로가 죽었다
        String a = IndustryPrompt.pickIndustry(a1, mapper);

        String shortList = String.join(", ", IndustryPrompt.narrow(name, mapper, NARROW));
        String b1 = askOne(IndustryPrompt.of(name, brand, shortList));
        String b = b1 == null ? null : IndustryPrompt.pickIndustry(b1, mapper);

        if (b == null || b.equals(a)) return a == null ? "" : a;   // 갈릴 것이 없다
        if (a == null) return b;

        String c1 = askOne(IndustryPrompt.tieBreak(name, a, b));
        String c = c1 == null ? null : IndustryPrompt.pickIndustry(c1, mapper);
        // **A 아니면 B 만 받는다.** 셋째 것을 답하면 버리고 추림을 안 탄 A 로 돌아간다.
        return (b.equals(c)) ? b : a;
    }

    /** 브랜드를 알면 프롬프트에 함께 준다 — 없으면 {@code null} 이고 그냥 안 준다. */
    private final Map<String, String> brandCache = new ConcurrentHashMap<>();

    /** 2단계가 보는 후보 수. 좁히면 놓치고 넓히면 1단계와 같아진다. */
    private static final int NARROW = 30;

    /** 한 번 묻고 답 문자열만 받는다 — 실패는 {@code null}. 사슬이 모델을 정한다. */
    private String askOne(String prompt) {
        try {
            String body = client.post()
                    .uri(props.getBaseUrl())
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(json.writeValueAsString(Map.of(
                            "model", model(),
                            "messages", List.of(Map.of("role", "user", "content", prompt)),
                            // 답은 업종 이름 하나다 — 길게 줄 이유가 없고, 짧게 두면
                            // 모델이 문장을 쓰려다 잘려 군말이 덜 붙는다.
                            "max_tokens", 32,
                            "temperature", 0)))
                    .retrieve()
                    .body(String.class);
            if (body == null) return null;
            String answer = json.readTree(body).path("choices").path(0)
                    .path("message").path("content").asString();
            failures.set(0);
            if (chain != null) chain.succeeded();
            return answer;
        } catch (RuntimeException e) {
            int n = failures.incrementAndGet();
            // **모델을 갈아탔으면 곧바로 한 번 더 본다.** 다음 회차까지 미루면 그 사이의
            // 요청은 죽은 모델 때문에 여전히 빈손이다.
            if (chain != null && chain.failed()) {
                failures.set(0);
                log.info("모델을 바꿔 곧바로 다시 묻는다 — {}", chain.current());
                return askOne(prompt);
            }
            log.debug("임시 분류 실패({}회) — {}", n, e.toString());
            return null;
        }
    }

    /**
     * 응답에서 번호→업종 이름을 꺼낸다.
     *
     * <p><b>제어문자를 지우고 파싱한다.</b> 실측에서 여섯 묶음 중 하나가 제어문자 때문에
     * JSON 파싱에 실패했다(2026-08-07). 모델이 문자열 안에 줄바꿈을 그대로 흘리는 경우가
     * 있는데, 그 한 묶음을 통째로 버리면 40곳이 날아간다.
     */
    Map<String, String> parse(String body, List<String> names) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return out;
        JsonNode root = json.readTree(body);
        String text = root.path("choices").path(0).path("message").path("content").asString("");
        if (text.isBlank()) return out;

        int open = text.indexOf('{'), close = text.lastIndexOf('}');
        if (open < 0 || close <= open) return out;
        String block = text.substring(open, close + 1)
                .replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "")     // 보이지 않는 쓰레기만 지운다
                .replaceAll("[\r\n\t]+", " ");                  // 문자열 안 줄바꿈은 공백으로
        JsonNode obj;
        try {
            obj = json.readTree(block);
        } catch (RuntimeException e) {
            log.debug("임시 분류 응답을 읽지 못했다 — {}", e.toString());
            return out;
        }
        obj.propertyStream().forEach(e -> {
            int idx;
            try {
                idx = Integer.parseInt(e.getKey().trim());
            } catch (NumberFormatException ignored) {
                return;
            }
            if (idx < 1 || idx > names.size()) return;
            String industry = e.getValue().asString("").trim();
            String name = names.get(idx - 1);
            // 상호 자체가 PG 면 답이 와도 버린다 — 무엇을 샀는지 알 수 없는 결제다.
            if (!industry.isBlank() && !classifier.isPaymentAgencyMerchant(name)) {
                out.put(name, industry);
            }
        });
        return out;
    }
}
