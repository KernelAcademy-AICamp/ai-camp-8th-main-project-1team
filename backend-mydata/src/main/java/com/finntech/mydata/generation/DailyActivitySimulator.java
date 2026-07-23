package com.finntech.mydata.generation;

import com.finntech.mydata.generation.CatalogModels.RegionEntry;
import com.finntech.mydata.generation.CatalogSampler.ResolvedMerchant;
import com.finntech.mydata.generation.CatalogSampler.ResolvedProduct;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 하루 활동·동선 시뮬레이터 — 한 사용자의 시작일부터 생성지평까지 하루 단위로 결제를 만든다.
 * 개연성: 카테고리믹스(지출비중)·방문빈도·시간대·요일·집/직장/이동 앵커·취미 주입·정기구독·
 * 다층 랜덤성(조용한날·치팅데이·금액지터)·낭비 라벨(충동·과다·후회+취미보호+곡선). 결정론(userSeed).
 */
@Component
public class DailyActivitySimulator {

    /** 대분류 평균 결제액(원) — 카테고리믹스(지출비중)를 방문가중으로 환산할 때 사용. */
    private static final Map<String, Integer> AVG_PRICE = Map.of(
            "식비", 13000, "카페/간식", 6000, "편의점", 5000, "쇼핑", 45000,
            "생활", 30000, "여가", 35000, "온라인", 25000);
    private static final Set<String> MULTI_QTY = Set.of("편의점", "대형마트", "이커머스");
    private static final Set<String> RECURRING = Set.of("통신비", "공과금", "스트리밍");

    private final CatalogSampler sampler;
    private final WasteLabeler labeler;
    private final GenerationProperties props;
    private final Map<String, List<String>> hobbySignature = new LinkedHashMap<>();

    public DailyActivitySimulator(CatalogSampler sampler, WasteLabeler labeler,
                                  CatalogLoader catalog, GenerationProperties props) {
        this.sampler = sampler;
        this.labeler = labeler;
        this.props = props;
        for (var h : catalog.hobbies()) hobbySignature.put(h.type(), h.signatureCategories());
    }

    /** 사용자 u의 [startDate, genEnd] 결제 목록(결정론). */
    public List<GenTxn> simulate(GeneratedUser u, LocalDate genEnd) {
        PersonaVariant v = u.variant();
        Random r = new Random(u.userSeed());
        List<GenTxn> out = new ArrayList<>();

        // 취미 signature 카테고리 합집합
        Set<String> hobbyCats = new HashSet<>();
        for (String hob : v.hobbies()) hobbyCats.addAll(hobbySignature.getOrDefault(hob, List.of()));

        // 대분류 방문가중(지출비중/평균가 → 지출share≈mix)
        Map<String, Double> visitW = new LinkedHashMap<>();
        double wsum = 0;
        for (var e : v.categoryMix().entrySet()) {
            double w = e.getValue() / Math.max(1000, AVG_PRICE.getOrDefault(e.getKey(), 20000));
            visitW.put(e.getKey(), w);
            wsum += w;
        }

        // 개선 곡선 파라미터(사용자 1회 표본)
        WasteCurve.Params curve = sampleCurve(v, r);
        // 정기구독(고정일·고정 서비스)
        int subDay = 1 + r.nextInt(28);
        int subCount = GenSeed.uniformInt(r, v.subscriptionCount()[0], v.subscriptionCount()[1]);

        double baseDaily = v.txPerMonthMean() / 30.0;
        var day = props.getRandomness().getDay();

        long span = ChronoUnit.DAYS.between(u.startDate(), genEnd);
        for (long d = 0; d <= span; d++) {
            LocalDate date = u.startDate().plusDays(d);
            double cf = WasteCurve.factor(curve, (int) d);

            // 정기구독(월 1회)
            if (date.getDayOfMonth() == subDay) {
                for (int s = 0; s < subCount; s++) {
                    out.add(subscriptionTxn(u, v, date, cf, r));
                }
            }

            if (r.nextDouble() < day.getQuietDayProb()) continue;        // 조용한 날
            double factor = weekendFactor(v.dayBias(), date, r);
            double cheat = (r.nextDouble() < day.getCheatDayProb())
                    ? GenSeed.uniform(r, day.getCheatDayMultiplier()[0], day.getCheatDayMultiplier()[1]) : 1.0;
            int n = (int) Math.round(baseDaily * factor * cheat * GenSeed.jitter(r, 0.3));
            for (int i = 0; i < n; i++) {
                out.add(oneTxn(u, v, date, cf, hobbyCats, visitW, wsum, cheat > 1.0, r));
            }
        }
        return out;
    }

    private GenTxn oneTxn(GeneratedUser u, PersonaVariant v, LocalDate date, double cf,
                          Set<String> hobbyCats, Map<String, Double> visitW, double wsum,
                          boolean cheatDay, Random r) {
        // 카테고리 선택: 취미 주입 / 프로파일 밖 / 일반
        String cat1, cat2;
        var amt = props.getRandomness().getAmount();
        if (!hobbyCats.isEmpty() && r.nextDouble() < 0.06 * v.hobbyIntensityMult()) {
            cat2 = pickFrom(hobbyCats, r);
            cat1 = sampler.context(cat2) != null ? sampler.context(cat2).category1() : "여가";
        } else if (r.nextDouble() < amt.getOutOfProfileProb()) {
            cat1 = pickWeighted(visitW, wsum, r);
            cat2 = sampler.pickCategory2(cat1, r);
        } else {
            cat1 = pickWeighted(visitW, wsum, r);
            cat2 = sampler.pickCategory2(cat1, r);
        }
        if (cat2 == null) { cat1 = "식비"; cat2 = "한식"; }

        RegionEntry anchor = anchor(u, v, date, r);
        ResolvedMerchant m = sampler.resolveMerchant(cat2, anchor, r);
        ResolvedProduct p = sampler.resolveProduct(cat2, r);

        int qty = MULTI_QTY.contains(cat2) ? GenSeed.uniformInt(r, 1, 3) : 1;
        double sigma = GenSeed.uniform(r, amt.getSigmaLog()[0], amt.getSigmaLog()[1]);
        int amount = snapAmount(Math.max(500, (int) Math.round(p.unitPrice() * qty * GenSeed.jitter(r, sigma))), r);

        int hour = sampleHour(v, r);
        LocalDateTime when = date.atTime(hour, r.nextInt(60));
        boolean planned = RECURRING.contains(cat2) || r.nextDouble() < v.plannedRatio();
        boolean hobbyMatch = hobbyCats.contains(cat2);
        boolean deliveryOveruse = cat2.equals("배달") && r.nextDouble() < 0.3 * v.deliveryOveruseMult();
        boolean subLeak = cat2.equals("스트리밍") && r.nextDouble() < 0.2 * v.subscriptionLeakMult();
        double typical = AVG_PRICE.getOrDefault(cat1, 20000);

        var lab = labeler.label(cat2, amount, typical, hour, planned, hobbyMatch, deliveryOveruse, subLeak, v, cf, r);
        return new GenTxn(r.nextInt(u.cardCount()), when, cat1, cat2, amount, m.name(), m.channel(),
                p.name(), p.unitPrice(), qty, lab.label(), round4(lab.pWaste()), m.address(), m.lat(), m.lon());
    }

    private GenTxn subscriptionTxn(GeneratedUser u, PersonaVariant v, LocalDate date, double cf, Random r) {
        String cat2 = "스트리밍";
        ResolvedMerchant m = sampler.resolveMerchant(cat2, null, r);
        ResolvedProduct p = sampler.resolveProduct(cat2, r);
        int amount = snapAmount(p.unitPrice(), r);
        int hour = GenSeed.uniformInt(r, 0, 23);
        boolean leak = r.nextDouble() < 0.2 * v.subscriptionLeakMult();
        var lab = labeler.label(cat2, amount, p.unitPrice(), hour, true, false, false, leak, v, cf, r);
        return new GenTxn(r.nextInt(u.cardCount()), date.atTime(hour, 0), "온라인", cat2, amount,
                m.name(), "ONLINE", p.name(), p.unitPrice(), 1, lab.label(), round4(lab.pWaste()), null, null, null);
    }

    /**
     * 결제금액을 현실적 단위로 스냅한다(§13-11 개선) — 실제 카드내역은 1원 단위가 거의 없다.
     * 고액일수록 1000원 단위가 보편, 소액은 100원 단위가 보편, 10원 단위는 간헐, 1원 단위는 없앤다.
     * 확률적으로 단위를 골라 스냅하므로 "전부 딱 떨어지는" 기계적 인상도 피한다.
     */
    static int snapAmount(int amt, Random r) {
        if (amt < 10) return amt;
        double u = r.nextDouble();
        int unit;
        if (amt >= 50_000)      unit = (u < 0.80) ? 1000 : 100;   // 고액: 1000원 보편
        else if (amt >= 10_000) unit = (u < 0.55) ? 1000 : 100;   // 중액: 1000/100 혼재
        else if (amt >= 3_000)  unit = (u < 0.75) ? 100 : (u < 0.92 ? 1000 : 10); // 소액: 100 보편, 간헐 1000/10
        else                    unit = (u < 0.80) ? 100 : 10;     // 극소액: 100 보편, 간헐 10
        int snapped = (int) Math.round((double) amt / unit) * unit;
        return Math.max(unit, snapped);                            // 1원 단위 없음(최소 단위 이상)
    }

    // ── 앵커(집/직장/이동) ──
    private RegionEntry anchor(GeneratedUser u, PersonaVariant v, LocalDate date, Random r) {
        boolean weekday = date.getDayOfWeek().getValue() <= 5;
        if (u.work() != null && weekday && r.nextDouble() < 0.45) return u.work();   // 통근 낮
        if (v.wideMovement() && r.nextDouble() < 0.15) return u.home();               // (여행지는 orchestrator 확장 여지)
        return u.home();
    }

    private WasteCurve.Params sampleCurve(PersonaVariant v, Random r) {
        var c = props.getRandomness().getCurve();
        double startAmp = v.initialWasteMult() * GenSeed.uniform(r, c.getStartAmplitude()[0], c.getStartAmplitude()[1]);
        double plateau = GenSeed.uniform(r, c.getPlateauLevel()[0], c.getPlateauLevel()[1]);
        int minPhase = GenSeed.uniformInt(r, c.getMinPhaseDays()[0], c.getMinPhaseDays()[1]);
        double decline = v.declineSpeedMult() * GenSeed.uniform(r, c.getDeclineRate()[0], c.getDeclineRate()[1]);
        double rebound = GenSeed.uniform(r, c.getReboundStrength()[0], c.getReboundStrength()[1]);
        boolean noImp = r.nextDouble() < v.noImprovementProb();
        return new WasteCurve.Params(startAmp, plateau, minPhase, decline, rebound, noImp);
    }

    private int sampleHour(PersonaVariant v, Random r) {
        double pNight = 0.05 * v.nightImpulseMult();
        if (r.nextDouble() < pNight) {                       // 심야 충동
            int[] night = {23, 0, 1, 2, 3};
            return night[r.nextInt(night.length)];
        }
        int a = v.activeHours()[0], b = Math.min(23, v.activeHours()[1]);
        return GenSeed.uniformInt(r, a, Math.max(a, b));
    }

    private double weekendFactor(String dayBias, LocalDate date, Random r) {
        boolean weekend = date.getDayOfWeek().getValue() >= 6;
        return switch (dayBias == null ? "EVEN" : dayBias) {
            case "WEEKDAY" -> weekend ? 0.5 : 1.2;
            case "WEEKEND" -> weekend ? 1.6 : 0.8;
            case "RANDOM" -> GenSeed.uniform(r, 0.6, 1.4);
            default -> 1.0; // EVEN
        };
    }

    private String pickWeighted(Map<String, Double> w, double sum, Random r) {
        double x = r.nextDouble() * sum, acc = 0;
        for (var e : w.entrySet()) { acc += e.getValue(); if (x < acc) return e.getKey(); }
        return w.keySet().iterator().next();
    }

    private String pickFrom(Set<String> s, Random r) {
        int idx = r.nextInt(s.size()), i = 0;
        for (String x : s) { if (i++ == idx) return x; }
        return s.iterator().next();
    }

    private static double round4(double v) { return Math.round(v * 1e4) / 1e4; }
}
