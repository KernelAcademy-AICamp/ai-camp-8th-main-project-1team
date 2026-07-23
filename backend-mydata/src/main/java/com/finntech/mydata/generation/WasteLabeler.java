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
    private final Set<String> essential;

    public WasteLabeler(GenerationProperties props) {
        this.cfg = props.getLabel();
        this.imp = cfg.getImpulse();
        this.essential = new HashSet<>(cfg.getEssentialCategories());
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
        if (essential.contains(category2)) {
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
