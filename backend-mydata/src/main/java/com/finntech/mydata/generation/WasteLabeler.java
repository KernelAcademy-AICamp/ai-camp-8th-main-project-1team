package com.finntech.mydata.generation;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * 낭비/필수 라벨러 — 재량 ≠ 낭비. 생존필수 무대는 낭비 아님, 재량 무대는 '충동·과다·후회'로만 낭비.
 * 본인 취미(비과다)는 보호. 재량성 점수는 무대 판정에만 쓰고 여기 p_waste에 직접 넣지 않는다.
 * 곡선(curveFactor)이 시간에 따라 충동성을 변조(서비스 효과).
 */
@Component
public class WasteLabeler {

    private final GenerationProperties.Label cfg;
    private final GenerationProperties.Impulse imp;
    /**
     * 필수 무대로 볼 재량성 상한. 이보다 낮으면 낭비 판정을 하지 않는다.
     *
     * <p>예전에는 필수 <b>맥락 이름 10개</b>가 yml에 박혀 있었고, 같은 목록이 Java·Python에도
     * 손으로 복사돼 있었다. 이제 카탈로그가 이미 갖고 있는 {@code discretionaryBase}로 판단한다 —
     * 맥락을 추가할 때 목록을 따로 고칠 일이 없고, 학습(train.py)과 기준이 갈라지지 않는다.
     * 값은 {@code scripts/ksic/build_resources.py}의 ESSENTIAL_THRESHOLD와 같아야 한다.
     */
    private static final double ESSENTIAL_MAX_DISCRETIONARY = 0.30;

    private final CatalogSampler sampler;

    public WasteLabeler(GenerationProperties props, CatalogSampler sampler) {
        this.cfg = props.getLabel();
        this.imp = cfg.getImpulse();
        this.sampler = sampler;
    }

    /** 이 맥락이 '생존필수 무대'인가 — 카탈로그의 재량성이 정한다. */
    private boolean isEssential(String category2) {
        var ctx = sampler.context(category2);
        return ctx != null && ctx.discretionaryBase() < ESSENTIAL_MAX_DISCRETIONARY;
    }

    /** 라벨 + 잔재 확률(p_waste, discretionary_score 컬럼에 저장 — ML 특징 아님). */
    public record Result(String label, double pWaste) {}

    /**
     * @param typicalAmount 이 사용자의 해당 category2 평소 결제액(과다 판정 기준)
     * @param hobbyMatch    이 거래가 사용자 배정 취미의 signatureCategories에 속하는지
     */
    public Result label(String category2, int amount, double typicalAmount, int hour,
                        boolean planned, boolean hobbyMatch, boolean deliveryOveruse,
                        boolean subscriptionLeak, PersonaVariant v, double curveFactor, Random r) {
        double p;
        if (isEssential(category2)) {
            p = cfg.getBaseWasteProb();                       // 필수 무대: 낭비 아님
        } else {
            boolean excess = typicalAmount > 0 && amount > imp.getExcessAmountMultiplier() * typicalAmount;
            double impulse = 0;
            if (isNight(hour)) impulse += imp.getNightWeight() * v.nightImpulseMult();
            if (!planned) impulse += imp.getUnplannedWeight();
            if (excess) impulse += imp.getExcessWeight();
            if (deliveryOveruse) impulse += imp.getDeliveryOveruseWeight() * v.deliveryOveruseMult();
            if (subscriptionLeak) impulse += imp.getSubscriptionLeakWeight() * v.subscriptionLeakMult();
            if (hobbyMatch && !excess) impulse *= cfg.getHobbyProtection();  // 본인 취미(비과다) 보호
            p = clamp(impulse * v.impulsivity() * curveFactor, 0, 1);
        }
        String label = r.nextDouble() < p ? "WASTE" : "ESSENTIAL";
        return new Result(label, p);
    }

    private boolean isNight(int hour) {
        int a = imp.getNightHours()[0], b = imp.getNightHours()[1]; // 예: [23,4] = 23시~익일4시
        return a <= b ? (hour >= a && hour <= b) : (hour >= a || hour <= b);
    }

    private static double clamp(double x, double lo, double hi) { return x < lo ? lo : (x > hi ? hi : x); }
}
