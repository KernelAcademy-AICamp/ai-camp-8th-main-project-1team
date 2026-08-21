package com.finntech.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * <b>무료 통로의 모델을 순서대로 갈아탄다.</b>
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>운영에 {@code google/gemma-4-31b-it} 하나만 박혀 있었는데, 그 모델은 <b>404 도 안 주고
 * 매달렸다.</b> 매 호출이 타임아웃을 꽉 채우고 실패로 쌓여, 온보딩 로딩이 <b>40초</b>가 됐다
 * (2026-08-20 운영, userId=33). 모델 하나에 통로 전체를 걸어 두면 그 모델이 죽는 날 통로도
 * 같이 죽는다.
 *
 * <h2>순서는 실측으로 정했다</h2>
 *
 * <p>카탈로그 103종을 전부 두드려 18종만 살아 있었고, 그중 <b>운영의 사실 데이터 148건</b>
 * (국세청 등록업종·사람 확정)을 정답지로 20초 간격 순차 채점했다. 속도 제한이 실력처럼
 * 보이지 않게 한 건씩 물었다.
 *
 * <pre>
 *   1. ising-calibration-1.5-31b   72.3%  실패 0     ← 유료 Gemini(70.5%)보다 높다
 *   2. mistral-nemotron            62.7%  실패 38
 *   3. gemma-4-31b-it              68.0%  실패 48    ← 답하면 잘하는데 셋 중 하나는 무응답
 *   4. nemotron-3-nano-omni-30b    51.1%  실패 17
 *   5. laguna-xs-2.1               49.1%  실패 91
 * </pre>
 *
 * <p>덩치가 답이 아니었다 — 120b 는 0.0%, 49b 는 23.3% 였다. 형식을 안 지켜 업종 이름 대신
 * 설명을 붙이고, 그러면 대조표를 못 넘어간다.
 *
 * <h2>gemma 는 자리를 지키되 한 번만 준다</h2>
 *
 * <p>실력만 보면 3위라 뺄 이유가 없다. 다만 무응답이 잦은 것이 그 모델의 <b>성질</b>이라
 * (일시 장애가 아니었다 — 일주일 내내 그랬다) 다른 모델의 {@code failureCutoff} 를 그대로
 * 주면 다섯 번을 기다리는 동안 사용자가 기다린다. <b>한 번 실패하면 곧바로 넘긴다.</b>
 *
 * <h2>하루가 지나면 처음으로 돌아간다</h2>
 *
 * <p>넘어간 이유가 <i>그 모델이 영영 죽었다</i>가 아니라 <i>지금 안 된다</i>일 수 있다.
 * 돌아가 보지 않으면 1위 모델이 되살아나도 영영 5위를 쓰게 된다. 날짜가 바뀌면 처음으로
 * 되돌린다 — {@code Clock} 을 받으므로 시험이 시간을 옮겨 가며 검사할 수 있다(원칙 3).
 */
public class ModelChain {

    private static final Logger log = LoggerFactory.getLogger(ModelChain.class);

    /** 그 모델을 몇 번 실패하면 넘길 것인가. */
    public record Step(String model, int cutoff) {}

    private final List<Step> steps;
    private final Clock clock;

    private int index;
    private int failures;
    private LocalDate day;

    public ModelChain(List<Step> steps, Clock clock) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("모델이 하나도 없는 사슬은 만들 수 없다");
        }
        this.steps = List.copyOf(steps);
        this.clock = clock;
        this.day = LocalDate.now(clock);
    }

    /**
     * 지금 쓸 모델.
     *
     * <p>날짜가 바뀌었으면 <b>여기서</b> 처음으로 되돌린다. 별도의 스케줄러를 두지 않는 이유는
     * 그것이 또 하나의 살아 있어야 하는 부품이기 때문이다 — 어차피 쓸 때마다 부르는 자리다.
     */
    public synchronized String current() {
        rewindIfNewDay();
        return steps.get(index).model();
    }

    /** 성공했다 — 실패 계수를 지운다. 지금 모델을 계속 쓴다. */
    public synchronized void succeeded() {
        failures = 0;
    }

    /**
     * 실패했다.
     *
     * @return 모델을 갈아탔으면 {@code true}. 부르는 쪽이 <b>곧바로 다시 시도</b>할지 정한다.
     */
    public synchronized boolean failed() {
        rewindIfNewDay();
        Step step = steps.get(index);
        if (++failures < step.cutoff()) return false;
        failures = 0;
        if (index + 1 >= steps.size()) {
            // 끝까지 갔다. 되감지 않는다 — 되감으면 한 바퀴를 계속 돌며 모두를 두드린다.
            log.warn("무료 통로의 모델을 다 써 봤다 — 마지막({})에 머문다. 날짜가 바뀌면 처음으로 돌아간다",
                    step.model());
            return false;
        }
        index++;
        log.warn("무료 통로 모델을 바꾼다 — {}({}회 실패) → {}",
                step.model(), step.cutoff(), steps.get(index).model());
        return true;
    }

    /** 마지막 모델에서까지 문턱을 넘겼는가 — 부르는 쪽이 쉬어야 할 때를 안다. */
    public synchronized boolean exhausted() {
        return index + 1 >= steps.size() && failures == 0;
    }

    /** 지금 몇 번째를 쓰고 있나(1부터). 로그·시험이 읽는다. */
    public synchronized int position() {
        return index + 1;
    }

    private void rewindIfNewDay() {
        LocalDate today = LocalDate.now(clock);
        if (today.equals(day)) return;
        day = today;
        if (index != 0) {
            log.info("날짜가 바뀌어 무료 통로 모델을 처음({})으로 되돌린다", steps.get(0).model());
        }
        index = 0;
        failures = 0;
    }

    /**
     * 설정 문자열을 사슬로 읽는다 — {@code "모델A:1, 모델B, 모델C:3"}.
     *
     * <p>콜론 뒤가 그 모델의 실패 문턱이고, 없으면 {@code defaultCutoff} 다. gemma 처럼
     * "실력은 좋은데 자주 무응답"인 모델만 1 을 주려고 칸마다 따로 받는다.
     */
    public static List<Step> parse(String spec, int defaultCutoff) {
        List<Step> out = new java.util.ArrayList<>();
        for (String raw : spec.split(",")) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            int colon = s.lastIndexOf(':');
            // `https://` 같은 값이 잘못 들어와도 앞쪽을 모델로 삼지 않는다 — 콜론 뒤가 숫자일 때만 문턱이다.
            if (colon > 0 && s.substring(colon + 1).chars().allMatch(Character::isDigit)
                    && colon + 1 < s.length()) {
                out.add(new Step(s.substring(0, colon).trim(),
                        Math.max(1, Integer.parseInt(s.substring(colon + 1)))));
            } else {
                out.add(new Step(s, Math.max(1, defaultCutoff)));
            }
        }
        return out;
    }
}
