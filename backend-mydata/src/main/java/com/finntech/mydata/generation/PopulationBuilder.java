package com.finntech.mydata.generation;

import com.finntech.mydata.generation.CatalogModels.PersonaProfile;
import com.finntech.mydata.generation.CatalogModels.RegionEntry;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 합성 사용자 인구 생성 — 결정론(마스터 시드). 페르소나 비중대로 사용자 수 배분, 시작일 7/1~9/1 매일 균등,
 * 거주 지역(regionMode별 가중 표본)·통근·데이터 분리(60/15/15/10)를 배정한다.
 */
@Component
public class PopulationBuilder {

    private static final Set<String> METRO = Set.of(
            "서울특별시", "부산광역시", "대구광역시", "인천광역시", "광주광역시", "대전광역시", "울산광역시");
    private static final Set<String> CAPITAL_SUBURB = Set.of("경기도", "인천광역시");

    private final CatalogLoader catalog;
    private final GenerationProperties props;

    public PopulationBuilder(CatalogLoader catalog, GenerationProperties props) {
        this.catalog = catalog;
        this.props = props;
    }

    /** userCount명을 결정론 생성. 거래는 생성하지 않음(하루활동 시뮬레이터가 이 사용자들로 만든다). */
    public List<GeneratedUser> build(long masterSeed, int userCount) {
        List<PersonaProfile> bases = catalog.personas();
        List<RegionEntry> regions = catalog.regions();
        int variantsPerBase = props.getPersona().getVariantsPerBase();

        // 페르소나별 변형 풀
        List<List<PersonaVariant>> variantsByPersona = new ArrayList<>();
        for (int i = 0; i < bases.size(); i++) {
            variantsByPersona.add(PersonaExpander.expand(bases.get(i), i, variantsPerBase, masterSeed));
        }
        // 지역 버킷(누적 가중)
        WeightedRegions all = new WeightedRegions(regions, r -> true);
        WeightedRegions metro = new WeightedRegions(regions, r -> METRO.contains(r.sido()));
        WeightedRegions suburb = new WeightedRegions(regions, r -> CAPITAL_SUBURB.contains(r.sido()));
        WeightedRegions seoul = new WeightedRegions(regions, r -> "서울특별시".equals(r.sido()));

        LocalDate from = props.getStartDate().getFrom();
        long dayspan = ChronoUnit.DAYS.between(from, props.getStartDate().getTo());

        // 페르소나별 사용자 수(비중), 마지막 페르소나가 잔여 흡수
        int[] perPersona = new int[bases.size()];
        int assigned = 0;
        for (int i = 0; i < bases.size(); i++) {
            perPersona[i] = (i == bases.size() - 1) ? (userCount - assigned)
                    : (int) Math.round(userCount * bases.get(i).populationShare());
            assigned += perPersona[i];
        }

        List<GeneratedUser> users = new ArrayList<>(userCount);
        int idx = 0;
        for (int p = 0; p < bases.size(); p++) {
            List<PersonaVariant> variants = variantsByPersona.get(p);
            for (int u = 0; u < perPersona[p]; u++, idx++) {
                Random r = GenSeed.rng(masterSeed, 202, idx);
                PersonaVariant variant = variants.get(u % variants.size());
                LocalDate start = from.plusDays(GenSeed.uniformInt(r, 0, (int) dayspan));

                WeightedRegions homeBucket = switch (variant.regionMode()) {
                    case "METRO" -> metro;
                    case "CAPITAL_SUBURB" -> suburb;
                    default -> all; // POP_WEIGHTED / ALL
                };
                RegionEntry home = homeBucket.sample(r);
                RegionEntry work = variant.commute() ? seoul.sample(r) : null;

                boolean vehicle = PersonaResolver.hasVehicle(variant.hasVehicleMode(), r);
                int cards = GenSeed.uniformInt(r, variant.cards()[0], variant.cards()[1]);
                String split = dataSplit(r);
                long userSeed = GenSeed.mix(masterSeed, 303, idx);

                users.add(new GeneratedUser(GenSeed.ci(masterSeed, idx), variant, start,
                        home, work, vehicle, cards, split, userSeed));
            }
        }
        return users;
    }

    private String dataSplit(Random r) {
        var s = props.getSplitRatios();
        double x = r.nextDouble();
        double t = s.getTrain();
        if (x < t) return "TRAIN";
        if (x < t + s.getVal()) return "VAL";
        if (x < t + s.getVal() + s.getTest()) return "TEST";
        return "SERVICE";
    }

    /** 필터된 지역의 누적 가중 표본기(가중=시도 인구 기반 weight). */
    private static final class WeightedRegions {
        private final List<RegionEntry> items = new ArrayList<>();
        private final double[] cumulative;

        WeightedRegions(List<RegionEntry> all, java.util.function.Predicate<RegionEntry> filter) {
            List<Double> cum = new ArrayList<>();
            double acc = 0;
            for (RegionEntry e : all) {
                if (!filter.test(e)) continue;
                acc += Math.max(1e-9, e.weight());
                items.add(e);
                cum.add(acc);
            }
            cumulative = new double[cum.size()];
            for (int i = 0; i < cum.size(); i++) cumulative[i] = cum.get(i) / acc; // 정규화 [0,1]
        }

        RegionEntry sample(Random r) {
            double x = r.nextDouble();
            int lo = 0, hi = cumulative.length - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (cumulative[mid] < x) lo = mid + 1;
                else hi = mid;
            }
            return items.get(lo);
        }
    }
}
