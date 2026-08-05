package com.finntech.web;

import com.finntech.engine.AnalysisEngine;
import com.finntech.engine.AnalysisResult;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.ml.WasteScoringService;
import com.finntech.service.MyDataLinkService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 온보딩이 보는 <b>하나의 창</b> — 최근 {@code windowDays}일.
 *
 * <p><b>왜 따로 두는가.</b> 온보딩1은 최근 90일을 월로 환산해 보여줬고, 온보딩2는 전 기간을
 * 관측 개월수로 나눠 보여줬다. 그래서 같은 '취미/여가'가 한 화면에서 691,150원, 다음 화면에서
 * 745,118원이었다(2026-07-31 실측). 사용자는 어느 쪽이 진짜인지 알 길이 없고, 서버가 챌린지
 * 기준으로 삼는 값은 또 그 둘과 달랐다.
 *
 * <p>여기서는 <b>실측 하나만</b> 낸다. 화면에 보이는 금액과, 그 아래 펼치는 결제 목록과,
 * 서버가 잡는 기준 지출이 전부 같은 구간의 같은 합계다. 그래야 "이 결제는 낭비가 아니다"를
 * 눌렀을 때 금액이 정확히 그만큼 줄어드는 것이 설명된다.
 *
 * <p>창은 챌린지 기간과 같다(기본 30일). 관측 달의 길이에 좌우되지 않고, 월초에 들어와도
 * 데이터가 며칠치뿐인 문제가 없다.
 */
@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {

    /** 기본 창 — 챌린지가 30일이므로 최근 30일 쓴 만큼을 앞으로 30일 기준으로 잡는다. */
    private static final int DEFAULT_WINDOW_DAYS = 30;

    private final AnalysisEngine engine;
    private final MyDataLinkService myDataLinkService;
    private final WasteScoringService wasteScoringService;
    private final IndustryCategoryMapper industryMapper;
    private final com.finntech.config.AnalysisProperties props;
    private final Clock clock;

    public OnboardingController(AnalysisEngine engine, MyDataLinkService myDataLinkService,
                                WasteScoringService wasteScoringService,
                                IndustryCategoryMapper industryMapper,
                                com.finntech.config.AnalysisProperties props, Clock clock) {
        this.engine = engine;
        this.myDataLinkService = myDataLinkService;
        this.wasteScoringService = wasteScoringService;
        this.industryMapper = industryMapper;
        this.props = props;
        this.clock = clock;
    }

    /**
     * 카테고리별 최근 창 실측 + 그 안의 결제 전부(ML 낭비 판정 포함).
     *
     * <p>화면은 이 응답 하나로 ①요약 금액 ②줄일 후보 ③펼침 목록을 모두 그린다.
     */
    @GetMapping("/window")
    public Map<String, Object> window(@RequestParam Long userId,
                                      @RequestParam(defaultValue = "0") int windowDays) {
        int days = windowDays > 0 ? windowDays : DEFAULT_WINDOW_DAYS;
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime from = now.minusDays(days);

        AnalysisResult analysis = engine.analyze(userId, now, days);

        // 결제별 ML 낭비 판정. 모델이 없으면 빈 맵이고, 그때는 전부 '미판정'으로 내려간다
        // — 화면이 죽지 않아야 하고, 사용자가 직접 고르는 길은 그대로 열려 있다.
        Map<String, WasteScoringService.WasteJudgment> waste = new java.util.HashMap<>();
        for (WasteScoringService.WasteJudgment j : wasteScoringService.scoreUser(userId)) {
            waste.put(j.paymentId(), j);
        }

        // 창 안의 결제만. allPayments 는 개월 단위라 넉넉히 받아 놓고 여기서 정확히 자른다.
        int monthsBack = Math.max(1, (days + 30) / 30);
        Map<String, List<Map<String, Object>>> byCategory = new TreeMap<>();
        for (MyDataLinkService.PaymentHistoryRow p : myDataLinkService.allPayments(userId, monthsBack)) {
            if (p.date().isBefore(from) || p.date().isAfter(now)) continue;
            // 확정이 없으면 **AI 추정 자리에 놓는다.** 온보딩은 사용자가 하나씩 보고 고르는
            // 화면이라, 추정을 보여 주는 것이 곧 확인을 받는 길이다(원칙 1 — 표현은 AI가).
            // 이걸 안 하면 "카테고리없음"이 금액 1위로 맨 위에 앉는데, 사용자가 행동으로 옮길
            // 수 없는 덩어리다 — 실측에서 425만원 중 220만원이 추정으로 이미 답이 있었다
            // (2026-08-05). 확정(category2)은 그대로 두고, 화면에만 반영한다.
            boolean estimated = isUnclassified(p.category2()) && p.category2Llm() != null;
            String cat = estimated ? p.category2Llm()
                    : (p.category2() == null ? IndustryCategoryMapper.UNCLASSIFIED : p.category2());
            WasteScoringService.WasteJudgment j = waste.get(p.paymentId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("paymentId", p.paymentId());
            row.put("date", p.date());
            row.put("merchantName", p.merchantName());
            row.put("businessNumber", p.businessNumber());
            row.put("amount", p.amount());
            // 화면이 "AI 추정" 배지를 달 수 있게 함께 내린다 — 확정과 추정이 같아 보이면 안 된다.
            row.put("categoryEstimated", estimated);
            row.put("cardName", p.cardName());
            row.put("cardColor", p.cardColor());
            // waste=null 이면 모델이 판정하지 못한 결제다. 화면은 체크를 안 한 채로 둔다.
            row.put("waste", j == null ? null : j.waste());
            row.put("wasteProbability", j == null ? null : j.wasteProbability());
            row.put("reason", j == null ? null : j.explanation());
            /* 판정 근거 — 문구가 아니라 <b>확인할 수 있는 숫자</b>다(2026-08-02).
               "평소보다 큰 금액"까지만 말하면 사용자는 동의도 반박도 할 수 없다.
               "평소 23,000원 → 78,000원(3.4배)"이라야 "그날은 회식이었다"고 답할 수 있고,
               그 답이 성향(§8-S)의 교정 신호가 된다. */
            row.put("factors", j == null ? List.of() : j.factors().stream()
                    .map(f -> Map.of("label", f.label(), "detail", f.detail(),
                            "weight", f.contribution()))
                    .toList());
            byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(row);
        }

        // 카테고리 묶음은 **위에서 재배치한 결제들로부터** 만든다. 분석 엔진의 집계
        // (`categoryStats`)를 그대로 쓰면 추정으로 옮긴 결제가 반영되지 않아, 목록은 쪼개졌는데
        // 금액만 '카테고리없음'에 남는 어긋남이 생긴다. 화면이 세는 것과 보여 주는 것은
        // 같은 줄에서 나와야 한다.
        List<Map<String, Object>> categories = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byCategory.entrySet()) {
            List<Map<String, Object>> rows = e.getValue();
            long amount = rows.stream().mapToLong(r -> ((Number) r.get("amount")).longValue()).sum();
            long wasteAmount = rows.stream()
                    .filter(r -> Boolean.TRUE.equals(r.get("waste")))
                    .mapToLong(r -> ((Number) r.get("amount")).longValue()).sum();
            AnalysisResult.CategoryStat s = analysis.categoryStats().get(e.getKey());
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("categoryCode", e.getKey());
            c.put("displayName", s != null ? s.displayName() : e.getKey());
            // amount 는 **창 안의 실제 합계**다. 월 환산도, 관측월 나눗셈도 하지 않는다.
            // 취소(음수)도 그대로 더해 상쇄시킨다 — 안 쓴 돈이 소비로 보이면 안 된다.
            c.put("amount", amount);
            // 건수는 실제 소비 건만 센다(취소는 '한 번 더 썼다'가 아니다).
            c.put("count", rows.stream().filter(
                    r -> ((Number) r.get("amount")).longValue() > 0).count());
            c.put("wasteAmount", wasteAmount);
            // 화면이 '줄이라고 권하지 않아요'를 붙일 근거. 재량성이 낮은 카테고리(교통·통신·의료)는
            // 줄이라고 권하지 않는다 — 판정은 코드가 하고(원칙 1) 화면은 그 결과를 표시만 한다.
            // 절약 후보 선정(CutCandidateSelector)이 쓰는 것과 **같은 임계**여야 두 화면이 안 갈린다.
            c.put("protectedCategory",
                    industryMapper.discretionaryOf(e.getKey()) < props.getCutCandidate().getProtectedBelow());
            c.put("payments", rows);
            categories.add(c);
        }
        // 금액 큰 순. 다만 **'카테고리없음'은 금액이 커도 맨 아래**로 보낸다 — 무엇을 줄일지
        // 말해 주지 못하는 덩어리가 첫 화면 맨 위에 앉으면 사용자가 할 수 있는 일이 없다.
        categories.sort(Comparator
                .comparing((Map<String, Object> c) -> isUnclassified((String) c.get("categoryCode")))
                .thenComparing(Comparator.comparingLong(
                        (Map<String, Object> c) -> ((Number) c.get("amount")).longValue()).reversed()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("windowDays", days);
        body.put("from", from);
        body.put("to", now);
        body.put("categories", categories);
        return body;
    }

    /** 카테고리 이름을 코드에 박지 않는다(마스터 §4 원칙 4) — 상수는 매퍼가 갖는다. */
    private static boolean isUnclassified(String category2) {
        return category2 == null || IndustryCategoryMapper.UNCLASSIFIED.equals(category2);
    }
}
