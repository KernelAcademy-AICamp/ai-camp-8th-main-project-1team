package com.finntech.mydata.generation;

import com.finntech.mydata.generation.CatalogModels.PersonaProfile;
import com.finntech.mydata.generation.CatalogModels.RegionEntry;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        // 시도별 버킷 — 회사를 '집과 같은 시도'에서 뽑기 위해 미리 갈라 둔다.
        Map<String, WeightedRegions> byS = new LinkedHashMap<>();
        for (RegionEntry rg : regions) {
            byS.computeIfAbsent(rg.sido(), s -> new WeightedRegions(regions, x -> s.equals(x.sido())));
        }

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
                RegionEntry work = variant.commute() ? workplace(home, seoul, byS, r) : null;

                boolean vehicle = PersonaResolver.hasVehicle(variant.hasVehicleMode(), r);
                int cards = GenSeed.uniformInt(r, variant.cards()[0], variant.cards()[1]);
                String split = dataSplit(r);
                long userSeed = GenSeed.mix(masterSeed, 303, idx);

                // 교통카드는 한 장으로 고정한다 — 사람은 지하철 요금을 날마다 다른 카드로 내지 않는다.
                int transitCard = GenSeed.uniformInt(r, 0, cards - 1);
                users.add(new GeneratedUser(GenSeed.ci(masterSeed, idx), variant, start,
                        home, work, vehicle, cards, split, userSeed, transitCard));
            }
        }
        return users;
    }

    /**
     * 통근자의 회사 위치.
     *
     * <p><b>예전에는 전원이 서울에서 뽑혔다</b>({@code seoul.sample(r)}). 그래서 부산에 사는 사람도
     * 제주에 사는 사람도 회사가 서울이었고, 근무시간대 결제가 전부 서울에 찍혔다. 동선이 통째로
     * 거짓이 되는 자리였다.
     *
     * <p>이제 <b>수도권 거주자만</b> 서울로 통근하고(경기·인천→서울은 실제로 흔하다),
     * 그 밖의 지역은 <b>집과 같은 시도 안에서</b> 회사를 뽑는다. 같은 시도 안이어도 시군구가 다르면
     * 통근 거리가 생기므로, 시내 대중교통·광역버스·기차 중 무엇을 타는지는 거리로 갈린다.
     */
    private static RegionEntry workplace(RegionEntry home, WeightedRegions seoul,
                                         Map<String, WeightedRegions> bySido, Random r) {
        boolean capital = CAPITAL_SUBURB.contains(home.sido()) || "서울특별시".equals(home.sido());
        if (capital) return seoul.sample(r);
        WeightedRegions same = bySido.get(home.sido());
        return same != null ? same.sample(r) : home;
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
