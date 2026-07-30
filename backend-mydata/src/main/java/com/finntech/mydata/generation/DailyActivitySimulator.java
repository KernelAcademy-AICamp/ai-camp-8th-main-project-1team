package com.finntech.mydata.generation;

import com.finntech.mydata.generation.CatalogModels.RegionEntry;
import com.finntech.mydata.generation.CatalogSampler.ResolvedMerchant;
import com.finntech.mydata.generation.CatalogSampler.ResolvedProduct;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
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

    /**
     * 업종코드 평균 결제액(원) — 지출비중을 방문가중으로 환산할 때 쓴다(비중 ÷ 단가 ≈ 방문수).
     *
     * <p><b>상품 카탈로그에서 실측한다.</b> 예전에는 7대분류별 상수 7개가 코드에 박혀 있었고,
     * 그래서 `식비` 전체가 13,000원 기준이라 아귀찜(30~45,000)은 상시 '과다'로, 화장품은
     * 영영 과다가 아닌 것으로 판정됐다. 업종별 실제 품목가를 쓰면 그 왜곡이 사라지고,
     * 카탈로그를 고칠 때 코드를 함께 고칠 필요도 없어진다(마스터 §4 원칙 4).
     */
    private final Map<String, Integer> avgPriceByKsic = new LinkedHashMap<>();
    private static final Set<String> MULTI_QTY = Set.of("편의점", "대형마트", "이커머스");
    /** MULTI_QTY 맥락의 기대 수량 — {@code uniformInt(1,3)}의 평균. 계획단가를 실제 결제액에 맞춘다. */
    private static final double EXPECTED_MULTI_QTY = 2.0;
    private static final Set<String> RECURRING = Set.of("통신비", "공과금", "스트리밍");
    /** 교통카드로 내는 맥락 — 사용자마다 한 장으로 고정한다(택시도 대개 등록 카드 하나다). */
    private static final Set<String> TRANSIT_CATS = Set.of("대중교통", "택시");

    private final CatalogSampler sampler;
    private final WasteLabeler labeler;
    private final GenerationProperties props;
    private final Map<String, List<String>> hobbySignature = new LinkedHashMap<>();
    private final List<RegionEntry> allRegions;                              // 여행지 표본 풀(전국)
    private final Map<String, List<RegionEntry>> sigunguIndex = new LinkedHashMap<>(); // 시군구 → 동 목록(인접동)

    public DailyActivitySimulator(CatalogSampler sampler, WasteLabeler labeler,
                                  CatalogLoader catalog, GenerationProperties props) {
        this.sampler = sampler;
        this.labeler = labeler;
        this.props = props;
        for (var h : catalog.hobbies()) hobbySignature.put(h.type(), h.signatureCategories());
        this.allRegions = catalog.regions();
        for (RegionEntry rg : allRegions) {
            sigunguIndex.computeIfAbsent(rg.sido() + "|" + rg.sigungu(), k -> new ArrayList<>()).add(rg);
        }
        // 업종별 평균 단가를 상품 카탈로그에서 실측한다(코드에 상수를 박지 않는다).
        //
        // **가중 평균이어야 한다.** 방문가중은 `지출비중 ÷ 평균단가`라 평균단가가 부풀면 빈도가
        // 억눌린다. 대중교통은 지하철 1,550원이 대부분인데 '교통카드 충전(10,000~50,000)'이
        // 균등하게 섞이면 평균이 6,500원이 되어 실제보다 4배 덜 타게 된다.
        //
        // **기대 수량을 곱해야 한다.** 편의점·대형마트·이커머스는 한 번에 1~3개를 사므로(MULTI_QTY)
        // 결제액이 단가의 평균 2배다. 단가만 보고 방문을 배정하면 그 업종이 의도한 지출비중의
        // 2배를 먹는다 — 실측에서 대형마트가 2.8배였다.
        //
        // **계산 순서가 곧 뽑기 순서여야 한다.** 실제 거래는 두 단계로 정해진다:
        // ① pickCategory2 가 업종 안에서 맥락을 **빈도**로 뽑고 ② resolveProduct 가 그 맥락 안에서
        // 품목을 **품목 가중치**로 뽑는다. 그러니 계획단가도 같은 순서로 — 맥락 안에서 품목 평균을
        // 먼저 내고, 그 평균들을 맥락 빈도로 합쳐야 한다.
        //
        // 한 번에 (품목 가중치 × 빈도)로 섞으면 맥락의 실효 비중이 `품목수 × 빈도`가 되어,
        // 품목이 많은 맥락이 실제보다 지배한다. 4711(대형마트 12,796원 + 백화점 261,875원)처럼
        // 단가 차이가 20배인 업종에서 이 오차가 그대로 지출 왜곡이 된다.
        Map<String, double[]> acc = new LinkedHashMap<>();   // 코드 → [빈도가중 금액합, 빈도합]
        for (var c : catalog.contexts()) {
            var items = sampler.productsOf(c.category2());
            if (items.isEmpty()) continue;
            double wsumP = 0, priceSum = 0;
            for (var pe : items) {                          // ② 맥락 안의 품목 평균
                priceSum += (pe.priceLow() + pe.priceHigh()) / 2.0 * pe.weight();
                wsumP += pe.weight();
            }
            if (wsumP <= 0) continue;
            // 편의점·대형마트·이커머스는 한 번에 1~3개를 사므로 결제액이 단가의 평균 2배다.
            double qty = MULTI_QTY.contains(c.category2()) ? EXPECTED_MULTI_QTY : 1.0;
            double fw = Math.max(1e-9, c.frequencyWeight());
            double[] a = acc.computeIfAbsent(c.ksicCode(), k -> new double[2]);
            a[0] += priceSum / wsumP * qty * fw;            // ① 맥락을 빈도로 합친다
            a[1] += fw;
        }
        for (var e : acc.entrySet()) {
            if (e.getValue()[1] > 0) {
                avgPriceByKsic.put(e.getKey(), (int) Math.round(e.getValue()[0] / e.getValue()[1]));
            }
        }
    }

    /** 업종 평균 단가. 카탈로그에 품목이 없는 업종은 중간값으로 둔다. */
    private int avgPrice(String ksicCode) {
        return avgPriceByKsic.getOrDefault(ksicCode, 20000);
    }

    /**
     * '평소 이 정도 쓴다'의 기준액 — 맥락의 실제 품목가 평균. 맥락에 품목이 없으면 업종 평균.
     * 같은 업종 안에서 상대 비교가 되도록, 대분류 상수가 아니라 여기서 낸다.
     */
    private double typicalOf(String category2, String ksicCode) {
        var items = sampler.productsOf(category2);
        if (items.isEmpty()) return compressTypical(avgPrice(ksicCode));
        long sum = 0;
        for (var p : items) sum += (p.priceLow() + p.priceHigh()) / 2L;
        return compressTypical(Math.max(1000.0, (double) sum / items.size()));
    }

    /**
     * 기준액에도 결제와 <b>같은</b> 압축을 건다.
     *
     * <p>안 걸면 판정이 무너진다 — 결제만 눌리고 기준이 그대로면 "평소의 몇 배인가"가 통째로
     * 작아져 과다 판정이 거의 발화하지 않는다. 둘 다 누르면 배수가 {@code m → m^α} 로 단조롭게
     * 바뀔 뿐이라, 임계를 {@code E^α} 로 옮기는 것만으로 판정 대상이 그대로 유지된다.
     */
    private double compressTypical(double typical) {
        var a = props.getAddress();
        return typical <= a.getCompressThreshold() ? typical
                : a.getCompressThreshold() * Math.pow(typical / a.getCompressThreshold(), a.getCompressAlpha());
    }

    /**
     * '프로파일 밖' 지출용 방문가중 — 전 업종을, <b>빈도 ÷ 단가</b>로 저울질한다.
     *
     * <p><b>균등 추출이면 안 된다.</b> 예전에 이 자리는 정상 경로와 본문이 같아 죽어 있었고,
     * 되살리면서 업종을 균등하게 뽑게 했더니 여행(20만원)과 카페(4천원)가 같은 확률로 나왔다.
     * 건수는 같아도 금액은 50배라, 결제의 8%가 지출 구조를 통째로 흔들었다 —
     * 실측에서 교통 4.07배·여행 3.39배로 부풀고 식비는 0.54배로 눌렸다.
     * 평소 안 가던 곳에 가더라도 사람은 비행기표보다 커피를 더 자주 산다.
     */
    private Map<String, Double> globalVisitW;
    private double globalVisitSum;

    private void ensureGlobalVisitWeights() {
        if (globalVisitW != null) return;
        Map<String, Double> w = new LinkedHashMap<>();
        double sum = 0;
        for (String code : sampler.ksicCodes()) {
            double x = Math.max(1e-9, sampler.freqOf(code)) / Math.max(1000, avgPrice(code));
            w.put(code, x);
            sum += x;
        }
        globalVisitW = w;
        globalVisitSum = sum;
    }

    /** 전체 업종에서 균등 추출 — 더 이상 쓰지 않는다(위 설명 참조). 테스트 보존용. */
    static String pickAny(Set<String> codes, Random r) {
        if (codes.isEmpty()) return null;
        int idx = r.nextInt(codes.size());
        for (String c : codes) {
            if (idx-- == 0) return c;
        }
        return null;
    }

    /** 사용자 u의 [startDate, genEnd] 결제 목록(결정론). */
    public List<GenTxn> simulate(GeneratedUser u, LocalDate genEnd) {
        PersonaVariant v = u.variant();
        Random r = new Random(u.userSeed());
        List<GenTxn> out = new ArrayList<>();

        // 취미 signature 카테고리 합집합.
        //
        // 뽑을 때는 **균등하게 고르면 안 된다.** '여행' 취미의 signature는 여행숙박(33만원)·
        // 렌터카(16만원)·항공(12만원)·철도·고속버스인데, 균등하게 뽑으면 결제의 6%가 거의 다
        // 고액 항목으로 나가 지출 구조가 뒤집힌다(실측 교통 3.1배·여행 3.0배). 취미가 있어도
        // 사람은 비행기표보다 커피를 더 자주 산다 — 일반 경로와 같은 저울을 쓴다.
        Set<String> hobbyCats = new HashSet<>();
        for (String hob : v.hobbies()) hobbyCats.addAll(hobbySignature.getOrDefault(hob, List.of()));
        Map<String, Double> hobbyW = new LinkedHashMap<>();
        double hobbySum = 0;
        for (String c : hobbyCats) {
            var hc = sampler.context(c);
            if (hc == null) continue;
            double x = Math.max(1e-9, hc.frequencyWeight()) / Math.max(1000, avgPrice(hc.ksicCode()));
            hobbyW.put(c, x);
            hobbySum += x;
        }

        // 업종코드 방문가중.
        //
        // 페르소나는 **중분류**(우리 소비 축)로 지출비중을 말한다 — "쇼핑에 30%". 그런데 거래는
        // 업종코드 단위로 일어나므로, 중분류 비중을 그 중분류에 속한 업종코드들로 풀어야 한다.
        // 배분은 맥락의 빈도가중에 비례한다(midmap.json + contexts.json).
        // 예전에는 이 자리가 7대분류였고, 그 축이 소비 카테고리를 겸하고 있었다.
        Map<String, Double> visitW = new LinkedHashMap<>();
        double wsum = 0;
        for (var e : v.categoryMix().entrySet()) {
            List<String> codes = sampler.ksicOf(e.getKey());
            if (codes.isEmpty()) continue;                 // 맥락이 없는 중분류는 건너뛴다
            double freqTotal = 0;
            for (String code : codes) freqTotal += sampler.freqOf(code);
            for (String code : codes) {
                double portion = freqTotal > 0 ? sampler.freqOf(code) / freqTotal : 1.0 / codes.size();
                double w = e.getValue() * portion / Math.max(1000, avgPrice(code));
                visitW.merge(code, w, Double::sum);
                wsum += w;
            }
        }

        // 개선 곡선 파라미터(사용자 1회 표본)
        WasteCurve.Params curve = sampleCurve(v, r);
        // 정기구독(고정일·고정 서비스)
        int subDay = 1 + r.nextInt(28);
        int subCount = GenSeed.uniformInt(r, v.subscriptionCount()[0], v.subscriptionCount()[1]);

        double baseDaily = v.txPerMonthMean() / 30.0;
        var day = props.getRandomness().getDay();
        var addr = props.getAddress();
        Map<LocalDate, RegionEntry> travel = buildTravelSchedule(u, genEnd);   // 여행 일정(결정론)
        Map<LocalDate, Boolean> trips = buildBusinessTrips(u, genEnd);         // 출장 일정(직장인만)
        String commuteCat = u.work() != null ? commuteCategory(u, GenSeed.rng(u.userSeed(), 58)) : null;
        boolean longCommute = "철도".equals(commuteCat) || "고속버스".equals(commuteCat);
        int refuelEvery = GenSeed.uniformInt(GenSeed.rng(u.userSeed(), 59),
                addr.getRefuelIntervalDays()[0], addr.getRefuelIntervalDays()[1]);

        long span = ChronoUnit.DAYS.between(u.startDate(), genEnd);
        for (long d = 0; d <= span; d++) {
            LocalDate date = u.startDate().plusDays(d);
            double cf = WasteCurve.factor(curve, (int) d);
            boolean weekday = date.getDayOfWeek().getValue() <= 5;
            boolean away = travel.containsKey(date);
            Boolean trip = trips.get(date);

            // 정기구독(월 1회)
            if (date.getDayOfMonth() == subDay) {
                for (int s = 0; s < subCount; s++) {
                    out.add(subscriptionTxn(u, v, date, cf, r));
                }
            }

            // ── 출장 — 회사 일로 타지에 간다. 이동수단 결제 + 1박이면 숙박 ──
            if (trip != null) {
                RegionEntry dest = farRegion(u.home(), GenSeed.rng(u.userSeed(), 60 + (int) d));
                boolean outbound = trip != null && trips.getOrDefault(date.minusDays(1), null) == null;
                String mode = r.nextBoolean() ? "철도" : "고속버스";
                out.add(scriptedTxn(u, v, date, GenSeed.uniformInt(r, 7, 10), mode,
                        outbound ? u.home() : dest, u.transitCard(), true, cf, null, r));
                if (Boolean.TRUE.equals(trip)) {
                    out.add(scriptedTxn(u, v, date, GenSeed.uniformInt(r, 18, 22), "여행숙박",
                            dest, r.nextInt(u.cardCount()), true, cf, "모텔", r));
                }
            }

            // ── 통근 — 가장 규칙적인 축. 출장·여행일에는 없다 ──
            if (u.work() != null && weekday && !away && trip == null) {
                if (u.hasVehicle()) {
                    // 차를 타면 대중교통이 안 보인다. 대신 주유가 주기적으로, 금액이 크게 찍힌다.
                    if (d % refuelEvery == 0) {
                        out.add(scriptedTxn(u, v, date, GenSeed.uniformInt(r, 7, 21), "주유소",
                                maybeAdjacent(u.home(), r), r.nextInt(u.cardCount()), true, cf, null, r));
                        if (r.nextDouble() < addr.getTollProb()) {
                            out.add(scriptedTxn(u, v, date, GenSeed.uniformInt(r, 7, 21), "통행료",
                                    maybeAdjacent(u.work(), r), r.nextInt(u.cardCount()), true, cf, null, r));
                        }
                    }
                } else if (r.nextDouble() < addr.getCommuteProb()) {
                    // 원거리 통근은 매일이 아니다 — 주 2~4회 오간다.
                    boolean ride = !longCommute || r.nextDouble()
                            < GenSeed.uniformInt(r, addr.getLongCommuteTripsPerWeek()[0],
                                                    addr.getLongCommuteTripsPerWeek()[1]) / 5.0;
                    if (ride) {
                        String prod = "대중교통".equals(commuteCat) ? transitProduct(u, r) : null;
                        out.add(scriptedTxn(u, v, date, addr.getCommuteHours()[0], commuteCat,
                                u.home(), u.transitCard(), true, cf, prod, r));
                        out.add(scriptedTxn(u, v, date, addr.getCommuteHours()[1], commuteCat,
                                u.work(), u.transitCard(), true, cf, prod, r));
                    }
                }
            }

            if (r.nextDouble() < day.getQuietDayProb()) continue;        // 조용한 날
            double factor = weekendFactor(v.dayBias(), date, r);
            double cheat = (r.nextDouble() < day.getCheatDayProb())
                    ? GenSeed.uniform(r, day.getCheatDayMultiplier()[0], day.getCheatDayMultiplier()[1]) : 1.0;
            int n = (int) Math.round(baseDaily * factor * cheat * GenSeed.jitter(r, 0.3));
            for (int i = 0; i < n; i++) {
                out.add(oneTxn(u, v, date, cf, hobbyCats, hobbyW, hobbySum, visitW, wsum,
                        cheat > 1.0, travel, r));
            }
        }
        return out;
    }

    private GenTxn oneTxn(GeneratedUser u, PersonaVariant v, LocalDate date, double cf,
                          Set<String> hobbyCats, Map<String, Double> hobbyW, double hobbySum,
                          Map<String, Double> visitW, double wsum,
                          boolean cheatDay, Map<LocalDate, RegionEntry> travel, Random r) {
        // 업종·맥락 선택: 취미 주입 / 프로파일 밖 / 일반
        String ksic, cat2;
        var amt = props.getRandomness().getAmount();
        if (!hobbyW.isEmpty() && r.nextDouble() < 0.06 * v.hobbyIntensityMult()) {
            cat2 = pickWeighted(hobbyW, hobbySum, r);
            var ctx = sampler.context(cat2);
            ksic = ctx != null ? ctx.ksicCode() : null;
        } else if (r.nextDouble() < amt.getOutOfProfileProb()) {
            // 프로파일 밖 지출 — 페르소나가 평소 안 쓰는 업종에서도 가끔 결제한다.
            // 예전에는 이 분기와 아래 일반 분기의 본문이 **완전히 같아서** 난수만 소모하고
            // 기능이 없었다. 이제 전 업종에서 뽑되, 아래 일반 분기와 같은 '빈도 ÷ 단가' 저울을
            // 쓴다 — 균등하게 뽑으면 비싼 업종이 지출 구조를 흔든다(ensureGlobalVisitWeights 참조).
            ensureGlobalVisitWeights();
            ksic = pickWeighted(globalVisitW, globalVisitSum, r);
            cat2 = sampler.pickCategory2(ksic, r);
        } else {
            ksic = pickWeighted(visitW, wsum, r);
            cat2 = sampler.pickCategory2(ksic, r);
        }
        if (cat2 == null) { ksic = "5611"; cat2 = "한식"; }   // 최후 폴백: 한식 음식점업

        // 시간대를 먼저 뽑아 앵커(집/직장/인접동/여행지)를 시간대별로 결정한다.
        int hour = sampleHour(v, r);
        RegionEntry anchor = anchor(u, date, hour, travel, r);
        // 품목을 먼저 뽑고, **그 품목을 파는** 사업자를 고른다. 순서가 뒤집히면
        // 지하철이 시내버스 요금을 받는다.
        ResolvedProduct p = sampler.resolveProduct(cat2, r);
        ResolvedMerchant m = sampler.resolveMerchant(cat2, anchor, p.name(), r);

        int qty = MULTI_QTY.contains(cat2) ? GenSeed.uniformInt(r, 1, 3) : 1;
        // 고시요금은 흔들지 않는다. 지하철 1,550원에 로그정규 지터(sigma 0.20~0.30)를 곱하고
        // 100원 단위로 스냅하면 850~2,800원이 나오는데, 그런 요금은 존재하지 않는다.
        // fares.json의 실 요금을 products.json에 정확히 옮겨 놓고도 그 값을 망가뜨리고 있었다.
        var ctx = sampler.context(cat2);
        boolean fixed = ctx != null && ctx.fixedTariff();
        int amount;
        if (fixed) {
            amount = fixedFare(p.unitPrice() * qty);
        } else {
            double sigma = GenSeed.uniform(r, amt.getSigmaLog()[0], amt.getSigmaLog()[1]);
            int raw = Math.max(500, (int) Math.round(p.unitPrice() * qty * GenSeed.jitter(r, sigma)));
            amount = snapAmount(compressHigh(raw, props.getAddress().getCompressThreshold(),
                    props.getAddress().getCompressAlpha()), r);
        }

        LocalDateTime when = date.atTime(hour, r.nextInt(60));
        boolean planned = RECURRING.contains(cat2) || r.nextDouble() < v.plannedRatio();
        boolean hobbyMatch = hobbyCats.contains(cat2);
        boolean deliveryOveruse = cat2.equals("배달") && r.nextDouble() < 0.3 * v.deliveryOveruseMult();
        boolean subLeak = cat2.equals("스트리밍") && r.nextDouble() < 0.2 * v.subscriptionLeakMult();
        // '과다' 판정의 기준액. 예전에는 7대분류 상수(식비 13,000원 등)를 썼는데,
        // 그러면 아귀찜(30~45,000)은 상시 과다이고 화장품은 영영 과다가 아니게 된다.
        // **그 맥락의 실제 단가**를 쓰면 업종 안에서 상대 비교가 된다.
        double typical = typicalOf(cat2, ksic);

        var lab = labeler.label(cat2, amount, typical, hour, planned, hobbyMatch, deliveryOveruse, subLeak, v, cf, r);
        // 교통 결제는 늘 같은 카드다 — 사람은 교통카드를 한 장으로 쓴다.
        int card = TRANSIT_CATS.contains(cat2) ? u.transitCard() : r.nextInt(u.cardCount());
        return new GenTxn(card, when, ksic, cat2, amount, m.name(), m.channel(),
                p.name(), p.unitPrice(), qty, lab.label(), round4(lab.pWaste()),
                m.address(), m.lat(), m.lon(), m.businessNumber());
    }

    /**
     * <b>무엇을 살지 이미 정해진</b> 결제 1건 — 통근·주유·출장처럼 동선이 부르는 소비.
     *
     * <p>{@link #oneTxn}과 갈라 두는 이유: 저쪽은 "카테고리 믹스에서 뽑는" 확률적 소비이고,
     * 이쪽은 "출근하니까 지하철을 탄다"는 <b>결정된</b> 소비다. 섞으면 통근이 확률에 묻힌다.
     *
     * @param cardIndex   교통은 사용자의 교통카드로 고정, 나머지는 임의
     * @param productHint 품목 이름 접두사(예: "지하철"). null이면 그 맥락에서 가중 추출
     */
    private GenTxn scriptedTxn(GeneratedUser u, PersonaVariant v, LocalDate date, int hour,
                               String cat2, RegionEntry anchor, int cardIndex, boolean planned,
                               double cf, String productHint, Random r) {
        var ctx = sampler.context(cat2);
        String ksic = ctx != null ? ctx.ksicCode() : "4921";
        ResolvedProduct p = sampler.resolveProduct(cat2, productHint, r);
        ResolvedMerchant m = sampler.resolveMerchant(cat2, anchor, p.name(), r);
        int amount = ctx != null && ctx.fixedTariff()
                ? fixedFare(p.unitPrice())
                : snapAmount(compressHigh(Math.max(500, (int) Math.round(p.unitPrice()
                        * GenSeed.jitter(r, GenSeed.uniform(r, 0.05, 0.12)))),
                        props.getAddress().getCompressThreshold(),
                        props.getAddress().getCompressAlpha()), r);
        var lab = labeler.label(cat2, amount, typicalOf(cat2, ksic), hour, planned,
                false, false, false, v, cf, r);
        return new GenTxn(cardIndex, date.atTime(hour, r.nextInt(60)), ksic, cat2, amount,
                m.name(), m.channel(), p.name(), p.unitPrice(), 1, lab.label(), round4(lab.pWaste()),
                m.address(), m.lat(), m.lon(), m.businessNumber());
    }

    private GenTxn subscriptionTxn(GeneratedUser u, PersonaVariant v, LocalDate date, double cf, Random r) {
        String cat2 = "스트리밍";
        var ctx = sampler.context(cat2);
        ResolvedMerchant m = sampler.resolveMerchant(cat2, null, r);
        ResolvedProduct p = sampler.resolveProduct(cat2, r);
        // 구독료는 정찰가다 — 넷플릭스가 이번 달만 13,400원일 수 없다.
        int amount = p.unitPrice();
        int hour = GenSeed.uniformInt(r, 0, 23);
        boolean leak = r.nextDouble() < 0.2 * v.subscriptionLeakMult();
        var lab = labeler.label(cat2, amount, p.unitPrice(), hour, true, false, false, leak, v, cf, r);
        return new GenTxn(r.nextInt(u.cardCount()), date.atTime(hour, 0),
                ctx != null ? ctx.ksicCode() : "6031", cat2, amount,
                m.name(), "ONLINE", p.name(), p.unitPrice(), 1, lab.label(), round4(lab.pWaste()),
                m.address(), m.lat(), m.lon(), m.businessNumber());
    }

    /**
     * 고시요금은 <b>카탈로그 값 그대로</b>다. 반올림조차 하지 않는다.
     *
     * <p>한 번은 100원 단위로 스냅해 볼까 했는데, 그러면 지하철 25~30km 구간의 실제 요금
     * <b>1,950원이 2,000원</b>이 된다. 요금을 '정리'하려는 손길이 곧 요금을 틀리게 만든다 —
     * 예전에 로그정규 지터를 걸어 1,550원을 850~2,800원으로 흩뿌린 것과 같은 실수다.
     *
     * <p>존재하지 않는 요금(예전의 {@code 광역버스 3,261원})은 여기서 고칠 문제가 아니라
     * <b>카탈로그에 범위를 준 것</b>이 문제다. 정액 요금은 products.json 에서 단일 값으로 둔다.
     */
    static int fixedFare(int amount) {
        return Math.max(100, amount);
    }

    /**
     * <b>고액 구간 압축</b> — 총 소비 규모를 0.7배로 낮추되 건수는 그대로 둔다.
     *
     * <p><b>왜 단가가 아니라 여기인가.</b> 카탈로그의 지하철 1,550원·KTX 59,800원은 실제 고시요금이라
     * 사실이다. 거기에 0.7을 곱하면 1,085원 같은 존재하지 않는 요금이 나온다 — 예전에 지터를 걸어
     * 똑같은 사고를 냈다. 그래서 <b>결제 금액 단계</b>에서, 그것도 고시요금 맥락은 빼고 누른다.
     *
     * <p>{@code T} 아래는 손대지 않고 위로만 지수 압축한다. 배수가 {@code m → m^α} 로 바뀌므로,
     * 낭비 판정의 '평소 대비 배수' 임계도 {@code E → E^α} 로 함께 낮추면 <b>낭비로 잡히는 거래
     * 집합이 정확히 같다</b>(근사가 아니다). 그 값이 {@code impulse.excess-amount-multiplier} 다.
     */
    static int compressHigh(int amount, double threshold, double alpha) {
        if (amount <= threshold) return amount;
        return (int) Math.round(threshold * Math.pow(amount / threshold, alpha));
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

    // ── 앵커(집/직장/인접동/여행지) — 시간대·요일·이동 반영 ──

    /**
     * 결제 위치 앵커.
     *  - 여행일이면 여행지(먼 지역).
     *  - 직장인(work≠null) 평일 점심~저녁(workHours 구간)이면 직장, 그 외(아침·밤·주말·무직)면 집.
     *  - 어느 경우든 확률적으로 같은 시군구의 인접 동으로 이동할 수 있다.
     */
    private RegionEntry anchor(GeneratedUser u, LocalDate date, int hour,
                              Map<LocalDate, RegionEntry> travel, Random r) {
        RegionEntry dest = travel.get(date);
        if (dest != null) return maybeAdjacent(dest, r);                 // 여행 중
        var addr = props.getAddress();
        int[] work = addr.getWorkHours();
        boolean weekday = date.getDayOfWeek().getValue() <= 5;
        if (u.work() == null || !weekday) return maybeAdjacent(u.home(), r);

        // 출퇴근 시간대에는 집도 회사도 아닌 '경로상'에서 쓰는 일이 많다 —
        // 회사 앞 편의점, 환승역 카페. 이 소비가 동선의 상당 부분인데 예전에는 표현이 없었다.
        int[] ch = addr.getCommuteHours();
        boolean commuting = hour <= ch[0] + 1 || (hour >= ch[1] - 1 && hour <= ch[1] + 1);
        if (commuting && r.nextDouble() < addr.getCorridorProb()) return corridor(u, r);

        boolean atWork = hour >= work[0] && hour < work[1];
        return maybeAdjacent(atWork ? u.work() : u.home(), r);
    }

    // ── 통근·차량·출장 ─────────────────────────────────────────────────────────
    //
    // 예전에는 교통 결제가 **카테고리 추첨의 부산물**이었다. 그래서 매일 출퇴근하는 직장인인데도
    // 지하철 결제가 어떤 주에는 한 번도 없고 어떤 주에는 다섯 번 나왔다. 사람의 소비에서
    // 가장 규칙적인 축이 통근인데 그것이 없으니, 불규칙한 지출이 '낭비'로 도드라지지도 않았다.
    //
    // 이제 통근을 **일정으로** 만든다. 규칙적인 축이 생기면 그 밖의 지출이 상대적으로 드러난다.

    /** 두 행정동 사이 대략 거리(km) — 통근 수단을 가르는 데만 쓰므로 하버사인이면 충분하다. */
    private static double distanceKm(RegionEntry a, RegionEntry b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLon = Math.toRadians(b.lon() - a.lon());
        double la1 = Math.toRadians(a.lat()), la2 = Math.toRadians(b.lat());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(la1) * Math.cos(la2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    /**
     * 집↔회사 거리로 통근 수단을 정한다.
     * 가까우면 시내 대중교통, 시도를 넘으면 광역버스, 아주 멀면 기차·고속버스다.
     */
    private String commuteCategory(GeneratedUser u, Random r) {
        double km = distanceKm(u.home(), u.work());
        if (km >= props.getAddress().getLongCommuteKm()) return r.nextBoolean() ? "철도" : "고속버스";
        return "대중교통";
    }

    /** 대중교통 안에서 무엇을 타는가 — 같은 시군구면 버스가 흔하고, 시군구를 넘으면 지하철이 흔하다. */
    private static String transitProduct(GeneratedUser u, Random r) {
        boolean sameSigungu = u.home().sigungu().equals(u.work().sigungu())
                && u.home().sido().equals(u.work().sido());
        double pSubway = sameSigungu ? 0.35 : 0.70;
        return r.nextDouble() < pSubway ? "지하철" : (r.nextDouble() < 0.75 ? "시내버스" : "광역버스");
    }

    /**
     * 출퇴근 시간대의 '경로상' 앵커 — 집도 회사도 아닌, 그 사이 어딘가.
     *
     * <p>실제 소비의 상당 부분이 여기서 일어난다(회사 앞 편의점, 환승역 카페). 두 지점의 중점에
     * 가장 가까운 행정동을 쓰면 늘 같은 동이 나와 부자연스러우므로, 중점 근방에서 결정론으로 고른다.
     */
    private RegionEntry corridor(GeneratedUser u, Random r) {
        double mLat = (u.home().lat() + u.work().lat()) / 2, mLon = (u.home().lon() + u.work().lon()) / 2;
        RegionEntry best = null; double bestD = Double.MAX_VALUE;
        // 전국을 훑지 않는다 — 집·회사 시군구의 동들 중에서 중점에 가까운 쪽을 고른다.
        List<RegionEntry> pool = new ArrayList<>();
        pool.addAll(sigunguIndex.getOrDefault(u.home().sido() + "|" + u.home().sigungu(), List.of()));
        pool.addAll(sigunguIndex.getOrDefault(u.work().sido() + "|" + u.work().sigungu(), List.of()));
        if (pool.isEmpty()) return u.work();
        for (RegionEntry rg : pool) {
            double d = Math.abs(rg.lat() - mLat) + Math.abs(rg.lon() - mLon);
            if (d < bestD) { bestD = d; best = rg; }
        }
        // 늘 같은 동이면 기계적이라, 그 동이 속한 시군구 안에서 한 번 더 흔든다.
        List<RegionEntry> sib = sigunguIndex.get(best.sido() + "|" + best.sigungu());
        return sib == null || sib.isEmpty() ? best : sib.get(r.nextInt(sib.size()));
    }

    /**
     * 출장 일정 — 여행과 별개로 <b>직장인에게만</b> 생긴다.
     *
     * <p>여행은 집에서 출발해 놀러 가는 것이고, 출장은 회사 일로 가서 업무 소비를 하고 돌아온다.
     * 값이 {@code true}면 1박(그날 숙박 결제가 붙는다).
     */
    private Map<LocalDate, Boolean> buildBusinessTrips(GeneratedUser u, LocalDate genEnd) {
        Map<LocalDate, Boolean> map = new HashMap<>();
        if (u.work() == null) return map;
        var addr = props.getAddress();
        int[] iv = addr.getBusinessTripIntervalWeeks();
        Random tr = GenSeed.rng(u.userSeed(), 57);
        LocalDate cursor = u.startDate().plusWeeks(GenSeed.uniformInt(tr, iv[0], iv[1]));
        while (!cursor.isAfter(genEnd)) {
            boolean overnight = tr.nextDouble() < addr.getBusinessTripOvernightProb();
            map.put(cursor, overnight);
            if (overnight && !cursor.plusDays(1).isAfter(genEnd)) map.put(cursor.plusDays(1), false);
            cursor = cursor.plusDays(overnight ? 2 : 1).plusWeeks(GenSeed.uniformInt(tr, iv[0], iv[1]));
        }
        return map;
    }

    /** 확률적으로 같은 시군구의 다른 동(인접 동)으로 이동. 후보 없으면 그대로. */
    private RegionEntry maybeAdjacent(RegionEntry base, Random r) {
        if (r.nextDouble() >= props.getAddress().getAdjacentDongProb()) return base;
        List<RegionEntry> sib = sigunguIndex.get(base.sido() + "|" + base.sigungu());
        if (sib == null || sib.size() <= 1) return base;
        return sib.get(r.nextInt(sib.size()));
    }

    /** 사용자별 여행/출장 일정(결정론) — 1~12주 간격마다 1~2일씩 먼 지역. userSeed 파생 RNG로 격리. */
    private Map<LocalDate, RegionEntry> buildTravelSchedule(GeneratedUser u, LocalDate genEnd) {
        var addr = props.getAddress();
        int[] iv = addr.getTravelIntervalWeeks(), du = addr.getTravelDurationDays();
        Random tr = GenSeed.rng(u.userSeed(), 55);
        Map<LocalDate, RegionEntry> map = new HashMap<>();
        LocalDate cursor = u.startDate().plusWeeks(GenSeed.uniformInt(tr, iv[0], iv[1]));
        while (!cursor.isAfter(genEnd)) {
            RegionEntry destination = farRegion(u.home(), tr);
            int dur = GenSeed.uniformInt(tr, du[0], du[1]);
            for (int d = 0; d < dur; d++) {
                LocalDate day = cursor.plusDays(d);
                if (day.isAfter(genEnd)) break;
                map.put(day, destination);
            }
            cursor = cursor.plusDays(dur).plusWeeks(GenSeed.uniformInt(tr, iv[0], iv[1]));
        }
        return map;
    }

    /** 집과 다른 시도(먼 지역)를 결정론적으로 뽑는다. 8회 내 못 찾으면 전국에서 임의. */
    private RegionEntry farRegion(RegionEntry home, Random tr) {
        for (int attempt = 0; attempt < 8; attempt++) {
            RegionEntry cand = allRegions.get(tr.nextInt(allRegions.size()));
            if (!cand.sido().equals(home.sido())) return cand;
        }
        return allRegions.get(tr.nextInt(allRegions.size()));
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
