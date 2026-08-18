package com.finntech.web;

import com.finntech.usage.UsageGlossaryService;
import com.finntech.usage.UsageStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 행태 통계를 보는 문 — <b>admin 전용</b>.
 *
 * <p>{@code /api/admin/} 접두라야 {@code AuthFilter} 가 admin 쿠키를 요구하고, 사용자 토큰으로는
 * 403 이다. 여기서 나가는 것은 <b>남의 발자취</b>이므로 그 밖의 자리에 두면 안 된다 —
 * {@code /api/ops} 에 뒀다가 옮긴 소비 원장 문과 같은 이유다(그쪽은 로그인한 아무나 부를 수 있다).
 */
@RestController
@RequestMapping("/api/admin")
public class UsageAdminController {

    /** 기본 조회 기간. 데모 기간이 짧아 한 달이면 전부 본다. */
    private static final int DEFAULT_DAYS = 30;

    /** 상한 — 이 문이 표를 통째로 훑는 문이 되지 않게. */
    private static final int MAX_DAYS = 365;

    private final UsageStatsService stats;
    private final UsageGlossaryService glossary;

    public UsageAdminController(UsageStatsService stats, UsageGlossaryService glossary) {
        this.stats = stats;
        this.glossary = glossary;
    }

    /**
     * GA4 표준 보고서에 대응하는 집계 <b>전부</b>를 한 번에 준다.
     *
     * <p>요약(활성·신규·재방문·세션·참여율·이탈률·평균 참여시간·세션당 화면) · 일자별 · 화면별 ·
     * 진입 화면 · 이탈 화면 · 화면 이동 경로 · 이벤트 종류별 · 클릭 순위 · 시간대 · 요일 ·
     * 기기 · 리텐션 · 세션 길이 분포 · 사람별, 그리고 <b>GA4 에 있으나 우리가 못 내는 것</b>의
     * 목록과 이유({@code notCollected}).
     *
     * <p>한 번에 다 주는 이유는 화면이 열 번 왕복하지 않게 하려는 것이다. 표본이 두 자릿수라
     * 나눠 부를 이득이 없다.
     */
    @GetMapping("/usage/overview")
    public Map<String, Object> overview(@RequestParam(defaultValue = "" + DEFAULT_DAYS) int days) {
        return stats.overview(Math.min(Math.max(days, 1), MAX_DAYS));
    }

    /**
     * 실시간 — 최근 30분. GA4 와 같은 창이다.
     *
     * <p>{@code overview} 안에도 같은 값이 들어 있지만, 화면이 이것만 짧은 주기로 다시 부를 수
     * 있게 따로 연다. 전체 집계를 30초마다 다시 돌리는 것은 낭비다.
     */
    @GetMapping("/usage/realtime")
    public Map<String, Object> realtime() {
        return stats.realtime();
    }

    /**
     * 용어 사전 — 화면 id 와 통계 용어가 무슨 뜻인지.
     *
     * <p>통계는 처음 보는 사람에게 어렵고, {@code r-compare} 같은 화면 id 는 우리 말이라
     * 더 어렵다. 그래서 <b>줄마다 설명을 열어 볼 수 있게</b> 하고 그 문장을 여기서 받는다.
     *
     * <p>사실은 {@code UsageGlossary} 가 들고 있고, 무료 통로(NVIDIA)가 <b>말투만</b> 다듬는다.
     * 다듬어진 것이 아직 없으면 원문이 그대로 온다 — 어느 쪽이든 내용은 같다.
     */
    @GetMapping("/usage/glossary")
    public Map<String, Object> glossary() {
        return glossary.glossary();
    }

    /**
     * 한 사람의 최근 발자취 200건.
     *
     * <p>집계만으로는 "왜 이 화면 체류가 이렇게 긴가"에 답할 수 없어 원본을 볼 길을 둔다.
     * 200건으로 자른 것은 이 문이 개인정보를 통째로 내보내는 문이 되지 않게 하기 위해서다.
     */
    @GetMapping("/usage/trail/{userId}")
    public List<Map<String, Object>> trail(@PathVariable Long userId) {
        return stats.trail(userId);
    }
}
