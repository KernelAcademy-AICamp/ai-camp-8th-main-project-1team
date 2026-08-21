package com.finntech.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>모델 하나가 죽어도 통로는 살아 있어야 한다.</b>
 *
 * <p>운영에 모델 하나만 박혀 있었고 그것이 무응답이라, 매 호출이 타임아웃을 채우고 온보딩
 * 로딩이 40초가 됐다(2026-08-20). 여기서 잠그는 것은 넷이다 — ① 문턱을 넘으면 갈아타는가
 * ② 성공하면 계수가 지워지는가 ③ 모델마다 문턱을 다르게 줄 수 있는가(gemma 는 1회)
 * ④ <b>날짜가 바뀌면 처음으로 돌아가는가</b>(안 돌아가면 1위가 되살아나도 영영 꼴찌를 쓴다).
 */
class ModelChainTest {

    private static final ZoneId Z = ZoneId.of("Asia/Seoul");

    /** 시험이 날짜를 옮길 수 있어야 ④를 검사한다(원칙 3 — 엔진은 now() 를 직접 안 읽는다). */
    private static final class MovableClock extends Clock {
        private Instant at;
        MovableClock(Instant at) { this.at = at; }
        void plus(Duration d) { at = at.plus(d); }
        @Override public ZoneId getZone() { return Z; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return at; }
    }

    private MovableClock clock() {
        return new MovableClock(Instant.parse("2026-08-21T03:00:00Z"));   // KST 정오
    }

    private ModelChain chain(MovableClock c) {
        return new ModelChain(List.of(
                new ModelChain.Step("일등", 5),
                new ModelChain.Step("이등", 5),
                new ModelChain.Step("젬마", 1),
                new ModelChain.Step("사등", 5)), c);
    }

    @Test
    @DisplayName("문턱에 닿기 전에는 같은 모델을 계속 쓴다")
    void 문턱_전에는_안_바꾼다() {
        ModelChain ch = chain(clock());
        for (int i = 0; i < 4; i++) assertThat(ch.failed()).isFalse();
        assertThat(ch.current()).isEqualTo("일등");
    }

    @Test
    @DisplayName("문턱을 넘기면 다음 모델로 간다")
    void 문턱을_넘기면_바꾼다() {
        ModelChain ch = chain(clock());
        for (int i = 0; i < 4; i++) ch.failed();

        assertThat(ch.failed()).as("다섯 번째가 갈아타는 순간").isTrue();
        assertThat(ch.current()).isEqualTo("이등");
        assertThat(ch.position()).isEqualTo(2);
    }

    /** 한 번 성공하면 앞서 쌓인 실패는 없던 것이 된다 — 안 그러면 멀쩡한 모델도 밀려난다. */
    @Test
    @DisplayName("성공하면 실패 계수가 지워진다")
    void 성공하면_계수가_지워진다() {
        ModelChain ch = chain(clock());
        for (int i = 0; i < 4; i++) ch.failed();

        ch.succeeded();

        for (int i = 0; i < 4; i++) assertThat(ch.failed()).isFalse();
        assertThat(ch.current()).isEqualTo("일등");
    }

    /**
     * <b>gemma 자리.</b> 실력은 3위인데 셋 중 하나는 무응답이라, 다섯 번을 기다리면
     * 그동안 사용자가 기다린다. 한 번으로 넘긴다.
     */
    @Test
    @DisplayName("문턱이 1인 모델은 한 번 실패로 넘어간다")
    void 문턱_1은_한_번에_넘어간다() {
        MovableClock c = clock();
        ModelChain ch = chain(c);
        for (int i = 0; i < 5; i++) ch.failed();     // 일등 → 이등
        for (int i = 0; i < 5; i++) ch.failed();     // 이등 → 젬마
        assertThat(ch.current()).isEqualTo("젬마");

        assertThat(ch.failed()).as("한 번 만에").isTrue();
        assertThat(ch.current()).isEqualTo("사등");
    }

    @Test
    @DisplayName("마지막에서 더 넘어가지 않는다 — 한 바퀴 돌며 모두를 두드리지 않는다")
    void 마지막에서_안_넘어간다() {
        ModelChain ch = chain(clock());
        for (int i = 0; i < 40; i++) ch.failed();

        assertThat(ch.current()).isEqualTo("사등");
        assertThat(ch.position()).isEqualTo(4);
    }

    /**
     * <b>이것이 이 사슬의 핵심이다.</b> 넘어간 이유가 "영영 죽었다"가 아니라 "지금 안 된다"일
     * 수 있다. 되돌아가지 않으면 1위가 되살아나도 영영 꼴찌를 쓴다.
     */
    @Test
    @DisplayName("날짜가 바뀌면 처음 모델로 돌아간다")
    void 하루가_지나면_처음으로() {
        MovableClock c = clock();
        ModelChain ch = chain(c);
        for (int i = 0; i < 20; i++) ch.failed();
        assertThat(ch.current()).isNotEqualTo("일등");

        c.plus(Duration.ofDays(1));

        assertThat(ch.current()).isEqualTo("일등");
        assertThat(ch.position()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 날 안에서는 안 되돌린다 — 몇 시간이 지나도 그대로다")
    void 같은_날에는_안_되돌린다() {
        MovableClock c = clock();
        ModelChain ch = chain(c);
        for (int i = 0; i < 5; i++) ch.failed();

        c.plus(Duration.ofHours(6));

        assertThat(ch.current()).isEqualTo("이등");
    }

    @Test
    @DisplayName("설정 문자열을 읽는다 — 콜론 뒤가 그 모델의 문턱이다")
    void 설정을_읽는다() {
        List<ModelChain.Step> steps =
                ModelChain.parse(" a/one , b/two:1 ,, c/three:3 ", 5);

        assertThat(steps).containsExactly(
                new ModelChain.Step("a/one", 5),
                new ModelChain.Step("b/two", 1),
                new ModelChain.Step("c/three", 3));
    }

    /** 모델 이름에 콜론이 들어와도 문턱으로 오해하지 않는다 — 뒤가 숫자일 때만 문턱이다. */
    @Test
    @DisplayName("이름 속 콜론을 문턱으로 읽지 않는다")
    void 이름_속_콜론() {
        assertThat(ModelChain.parse("vendor:model-x", 5))
                .containsExactly(new ModelChain.Step("vendor:model-x", 5));
    }

    @Test
    @DisplayName("빈 사슬은 만들 수 없다 — 통로가 있다고 말해 놓고 부를 것이 없으면 안 된다")
    void 빈_사슬은_거부한다() {
        assertThatThrownBy(() -> new ModelChain(List.of(), clock()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
