package com.finntech.mydata.generation;

import com.finntech.mydata.generation.CatalogModels.PersonaProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 기본 페르소나 1종 → 유사 변형 프로파일 N개(파라미터 섭동). 결정론(마스터 시드+페르소나·변형 인덱스).
 * "모두 같은 형태" 금지: 변형마다 월지출·카테고리믹스·온라인율·충동성이 조금씩 다르다.
 */
public final class PersonaExpander {
    private PersonaExpander() {}

    /** base → variantsPerBase개 변형. personaIndex는 시드 안정용(이름 해시 대신). */
    public static List<PersonaVariant> expand(PersonaProfile base, int personaIndex,
                                              int variantsPerBase, long masterSeed) {
        List<PersonaVariant> out = new ArrayList<>(variantsPerBase);
        for (int v = 0; v < variantsPerBase; v++) {
            Random r = GenSeed.rng(masterSeed, 101, personaIndex, v);
            out.add(one(base, v, r));
        }
        return out;
    }

    private static PersonaVariant one(PersonaProfile base, int variantIndex, Random r) {
        // 예산 비례(§13-11): 결제 건수는 개인 예산에 비례한다 — '돈이 많은 사람이 더 자주 결제'.
        // 예산 지터 인자를 결제 건수에도 곱해(상관) 같은 페르소나 안에서도 예산 큰 사람의 건수가 많아지게 하고,
        // 작은 독립 노이즈(0.06)만 추가로 흔든다. (페르소나 간 비례는 personas.json txPerMonth∝예산으로 반영.)
        double budgetJitter = GenSeed.jitter(r, 0.12);
        long monthly = Math.round(base.monthlyTotalMean() * budgetJitter);
        int txPerMonth = Math.max(5, (int) Math.round(base.txPerMonthMean() * budgetJitter * GenSeed.jitter(r, 0.06)));
        double online = clamp01(base.onlineRatio() + r.nextGaussian() * 0.05);
        double planned = clamp01(base.plannedRatio() + r.nextGaussian() * 0.05);
        double impulsivity = Math.max(0.1, base.impulsivity() * GenSeed.jitter(r, 0.10));

        return new PersonaVariant(
                base.name(), variantIndex,
                monthly, base.monthlyCV(),
                jitterMix(base.categoryMix(), r),
                txPerMonth, base.ticketTendency(),
                online, base.activeHours(), base.dayBias(), planned,
                impulsivity,
                PersonaResolver.impulseMult(base.nightImpulse()),
                PersonaResolver.impulseMult(base.deliveryOveruse()),
                PersonaResolver.impulseMult(base.subscriptionLeak()),
                base.subscriptionCount(),
                base.hobbies(), PersonaResolver.hobbyIntensity(base.hobbyIntensity()),
                PersonaResolver.wasteLevel(base.initialWasteLevel()),
                PersonaResolver.declineSpeed(base.improvementSpeed(), r),
                base.noImprovementPct(),
                base.cards(), base.hasVehicle(),
                base.region().mode(),
                base.region().workCity(),
                Boolean.TRUE.equals(base.region().commute()),
                Boolean.TRUE.equals(base.region().wideMovement()));
    }

    /** 7대분류 비중(%) → 분수(합1)로 변환하며 변형별 소폭 지터 + 재정규화. */
    private static Map<String, Double> jitterMix(Map<String, Double> mixPct, Random r) {
        Map<String, Double> j = new LinkedHashMap<>();
        double sum = 0;
        for (Map.Entry<String, Double> e : mixPct.entrySet()) {
            double val = Math.max(0.001, e.getValue() * GenSeed.jitter(r, 0.10));
            j.put(e.getKey(), val);
            sum += val;
        }
        for (Map.Entry<String, Double> e : j.entrySet()) {
            e.setValue(e.getValue() / sum);
        }
        return j;
    }

    private static double clamp01(double x) {
        return x < 0 ? 0 : (x > 1 ? 1 : x);
    }
}
