package com.finntech.mydata.generation;

import com.finntech.mydata.generation.CatalogModels.BrandEntry;
import com.finntech.mydata.generation.CatalogModels.CatalogContext;
import com.finntech.mydata.generation.CatalogModels.ProductEntry;
import com.finntech.mydata.generation.CatalogModels.RegionEntry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 카탈로그 표본기 — 거래 1건의 (category2 선택, 가맹점 상호, 상품·가격, 위치)를 실 데이터에서 뽑는다.
 * 가맹점: 소상공인 실상호 풀 / 브랜드+동점 합성 / 온라인 플랫폼 / 운영사. 상호는 표기 변형(forms) 노이즈.
 * 위치: 오프라인이면 앵커 행정동 실좌표+지터, 온라인이면 null.
 */
@Component
public class CatalogSampler {

    private final Map<String, CatalogContext> ctxByCat2 = new LinkedHashMap<>();
    private final Map<String, List<String>> cat2ByKsic = new LinkedHashMap<>();     // 업종코드 → 맥락들
    private final Map<String, double[]> cat2Cumul = new LinkedHashMap<>();          // 업종코드 → 누적 freq
    private final Map<String, List<BrandEntry>> brands;
    private final Map<String, List<ProductEntry>> products;
    /** KSIC 세분류 → 실제 상호. 키가 업종코드라 맥락이 바뀌어도 풀을 다시 가를 필요가 없다. */
    private final Map<String, List<String>> independents;
    /** 우리 중분류 → 그 중분류에 속하며 맥락이 있는 업종코드들(midmap.json). */
    private final Map<String, List<String>> ksicByMid;
    /** 업종코드 → 그 업종 맥락들의 빈도가중 합. 중분류 비중을 업종별로 나눌 때 쓴다. */
    private final Map<String, Double> freqByKsic = new LinkedHashMap<>();
    private final MerchantRegistry registry;
    /** 시도|시군구 → 그 시군구의 동 목록. 시군구 단위 사업자(ㅇㅇ시설공단)의 소재지 후보다. */
    private final Map<String, List<RegionEntry>> sigunguIndex = new LinkedHashMap<>();
    /**
     * 담당 지역이 정해진 사업자와 전국 사업자가 한 맥락에 섞일 때, 지역 사업자를 고를 확률.
     *
     * <p>지하철이 그렇다 — 서울에서 타면 서울교통공사지만, 수도권 광역전철은 코레일이 운영한다.
     * 둘 중 하나만 남기면 어느 쪽이든 사실과 어긋나므로 섞는다.
     */
    private final double regionalShare;

    @SuppressWarnings("unchecked")
    public CatalogSampler(CatalogLoader catalog, MerchantRegistry registry, GenerationProperties props) {
        this.registry = registry;
        this.regionalShare = props.getAddress().getRegionalOperatorShare();
        for (RegionEntry rg : catalog.regions()) {
            sigunguIndex.computeIfAbsent(rg.sido() + "|" + rg.sigungu(), k -> new ArrayList<>()).add(rg);
        }
        this.brands = catalog.brands();
        this.products = catalog.products();
        this.independents = (Map<String, List<String>>) catalog.independents().get("namePoolByKsic");
        this.ksicByMid = (Map<String, List<String>>) catalog.midmap().get("ksicByMid");
        Map<String, List<Double>> weights = new LinkedHashMap<>();
        for (CatalogContext c : catalog.contexts()) {
            ctxByCat2.put(c.category2(), c);
            cat2ByKsic.computeIfAbsent(c.ksicCode(), k -> new ArrayList<>()).add(c.category2());
            weights.computeIfAbsent(c.ksicCode(), k -> new ArrayList<>()).add(c.frequencyWeight());
            freqByKsic.merge(c.ksicCode(), c.frequencyWeight(), Double::sum);
        }
        for (var e : weights.entrySet()) {
            List<Double> w = e.getValue();
            double[] cum = new double[w.size()];
            double acc = 0;
            for (int i = 0; i < w.size(); i++) { acc += w.get(i); cum[i] = acc; }
            for (int i = 0; i < cum.length; i++) cum[i] /= acc;
            cat2Cumul.put(e.getKey(), cum);
        }
    }

    public CatalogContext context(String category2) { return ctxByCat2.get(category2); }

    /** 업종코드 목록 — 페르소나 가중이 이 위에서 돈다. */
    public java.util.Set<String> ksicCodes() { return cat2ByKsic.keySet(); }

    /** 중분류에 속하며 <b>맥락이 존재하는</b> 업종코드들. midmap.json이 원천이다. */
    public List<String> ksicOf(String mid) {
        List<String> codes = ksicByMid.get(mid);
        return codes == null ? List.of() : codes;
    }

    /** 업종코드의 빈도가중 합 — 중분류 비중을 업종별로 배분할 때 쓴다. */
    public double freqOf(String ksicCode) { return freqByKsic.getOrDefault(ksicCode, 0.0); }

    /** 맥락의 상품 목록 — 업종 평균 단가를 실측할 때 쓴다. */
    public List<ProductEntry> productsOf(String category2) {
        List<ProductEntry> p = products.get(category2);
        return p == null ? List.of() : p;
    }

    /**
     * 업종코드(예: 5611 한식 음식점업) 안에서 방문빈도 가중으로 맥락(예: 한식) 선택.
     *
     * <p>예전에는 대분류(7개)를 받았다. 그 축이 소비 카테고리를 겸하고 있어서, 대분류를 손대면
     * 생성과 판정이 함께 흔들렸다. 이제 업종코드로 묶으므로 소비 카테고리는 앱이 따로 붙인다.
     */
    public String pickCategory2(String ksicCode, Random r) {
        List<String> cats = cat2ByKsic.get(ksicCode);
        if (cats == null || cats.isEmpty()) return null;
        double[] cum = cat2Cumul.get(ksicCode);
        double x = r.nextDouble();
        for (int i = 0; i < cum.length; i++) if (x < cum[i]) return cats.get(i);
        return cats.get(cats.size() - 1);
    }

    /** 가맹점 해석 결과: 표시상호(명세서 표기)·채널·고정 좌표·지번주소·사업자등록번호. */
    public record ResolvedMerchant(String name, String channel, Double lat, Double lon,
                                   String address, String businessNumber) {}

    /** 상품 해석 결과: 품목명·단가·재량성. */
    public record ResolvedProduct(String name, int unitPrice, double discretionary) {}

    /**
     * category2 + 앵커 행정동 → 가맹점. 표시상호(display, 명세서 표기 노이즈 포함)와 정규신원(base+동)을 분리해,
     * 사업자번호·주소·좌표는 신원에서 결정론 파생({@link MerchantRegistry})한다 → 같은 점포는 항상 같은 번호·주소.
     * 온라인이거나 앵커가 없으면 전국 본사(HQ) 결제로 처리.
     */
    /** 품목을 가리지 않는 옛 호출부용. 새 코드는 품목을 함께 넘긴다. */
    public ResolvedMerchant resolveMerchant(String category2, RegionEntry anchor, Random r) {
        return resolveMerchant(category2, anchor, null, r);
    }

    /**
     * 그 맥락의 상호를 하나 고른다. {@code productName} 을 주면 <b>그 품목을 파는 사업자</b>만 고른다.
     *
     * <p>한 맥락에 서로 다른 운영주체가 섞이는 경우가 있다 — 대중교통은 도시철도(서울교통공사·한국철도공사)와
     * 버스·충전(티머니·캐시비)이 한 칸에 있다. 상호와 품목을 따로 뽑으면 <b>`지하철`이 `시내버스` 요금을
     * 받는</b> 명세서가 나온다(실제로 그렇게 1,600만 건이 생성됐다). 짝을 맞춘다.
     */
    public ResolvedMerchant resolveMerchant(String category2, RegionEntry anchor, String productName, Random r) {
        CatalogContext ctx = ctxByCat2.get(category2);
        String source = ctx == null ? "INDEPENDENT" : ctx.merchantSource();
        String channel = ctx == null ? "OFFLINE" : ctx.channel();
        String locationType = ctx == null ? "POI" : ctx.locationType();
        boolean useBrand = switch (source) {
            case "BRAND", "ONLINE", "OPERATOR" -> true;
            case "MIXED" -> r.nextBoolean();
            default -> false; // INDEPENDENT
        };

        // 독립 상호는 **업종코드**로 찾는다. 상호를 분류한 근거가 인허가 업태이고,
        // 업태는 업종코드로 정리돼 있기 때문이다(scripts/ksic/ksic-mapping.tsv).
        String ksic = ctx == null ? null : ctx.ksicCode();

        // 브랜드에서 뽑았을 때만 non-null. 채널·주소 규칙이 브랜드에 붙어 있다.
        BrandEntry brand = (useBrand || !hasIndependents(ksic)) && hasBrands(category2)
                ? pickBrand(brands.get(category2), productName, anchor, r) : null;

        String base;         // 정규 신원의 이름 부분(정식 브랜드명 또는 독립상호)
        String display;      // 결제 명세서 표시상호(forms 노이즈·동점 포함 가능)
        boolean branchable = false;
        if (brand != null) {
            base = brand.name();
            branchable = brand.branchable();
            display = displayName(brand, branchable, anchor, r);
            // 브랜드가 값을 들고 있으면 맥락보다 우선한다. 예전에는 이 필드가 읽히지 않아
            // 애플·스팀·예스24가 '디지털가전'(OFFLINE) 맥락에 묶여 동네 지번주소를 받았다.
            if (brand.channel() != null && !brand.channel().isBlank()) channel = brand.channel();
            if (brand.locationType() != null && !brand.locationType().isBlank()) locationType = brand.locationType();
        } else if (hasIndependents(ksic)) {
            base = pick(independents.get(ksic), r);
            display = base;
        } else {
            base = category2;   // 최후 폴백
            display = category2;
        }

        // ── 주소 규칙 ── 채널(결제수단)과는 다른 축이다.
        if (brand != null && brand.hq() != null) {              // 본사·시설 실주소
            Merchant m = registry.resolveFixed(base, brand.hq());
            return new ResolvedMerchant(display, channel, m.lat(), m.lon(), m.address(), m.businessNumber());
        }
        if ("DISTRICT".equals(locationType) && anchor != null) { // 시군구마다 다른 사업자
            String districtName = districtName(anchor);
            Merchant m = registry.resolveDistrict(base, districtName + base, anchor,
                    sigunguIndex.get(anchor.sido() + "|" + anchor.sigungu()));
            return new ResolvedMerchant(districtName + base, channel, m.lat(), m.lon(),
                    m.address(), m.businessNumber());
        }
        if ("ONLINE".equals(channel) || anchor == null) {        // hq 없는 온라인 — 옛 경로(전국 해시)
            Merchant m = registry.resolveOnline(base, base);
            return new ResolvedMerchant(display, channel, m.lat(), m.lon(), m.address(), m.businessNumber());
        }
        String canonicalName = branchable ? base + " " + anchor.dong() + "점" : base;
        Merchant m = registry.resolveOffline(base, canonicalName, anchor);
        return new ResolvedMerchant(display, channel, m.lat(), m.lon(), m.address(), m.businessNumber());
    }

    /**
     * 시군구 이름에서 사업자 이름의 앞부분을 만든다 — {@code 강남구}→{@code 강남}, {@code 양평군}→{@code 양평}.
     *
     * <p>두 가지를 예외로 둔다. ① {@code 고양시덕양구}처럼 시·구가 붙은 특례시는 <b>시 단위</b>로
     * 자른다(공단이 구마다 있지 않다). ② {@code 중구}·{@code 동구}처럼 떼면 한 글자만 남는 이름은
     * 그대로 둔다 — "중시설공단"은 이름으로 읽히지 않는다.
     */
    static String districtName(RegionEntry anchor) {
        String s = anchor.sigungu();
        int si = s.indexOf('시');
        if (si > 0 && si < s.length() - 1) return s.substring(0, si);   // 고양시덕양구 → 고양
        String cut = s.length() > 1 && "시군구".indexOf(s.charAt(s.length() - 1)) >= 0
                ? s.substring(0, s.length() - 1) : s;
        return cut.length() >= 2 ? cut : s;                             // 중구 → 중구
    }

    /**
     * 품목·지역을 함께 맞춰 사업자를 고른다.
     *
     * <p><b>지역이 왜 필요한가.</b> 대중교통에는 시도별 교통공사가 들어 있다. 부산에서 지하철을 타고
     * 서울교통공사가 찍히면 안 된다. 그렇다고 지역 공사만 남기면 수도권 광역전철을 운영하는
     * 코레일이 사라진다 — 그래서 담당 지역이 있는 쪽과 전국 쪽을 {@code regionalOperatorShare}로 섞는다.
     */
    private BrandEntry pickBrand(List<BrandEntry> pool, String productName, RegionEntry anchor, Random r) {
        List<BrandEntry> fit = new ArrayList<>();
        for (BrandEntry b : pool) if (b.canSell(productName)) fit.add(b);
        if (fit.isEmpty()) fit = pool;

        String sido = anchor == null ? null : anchor.sido();
        List<BrandEntry> regional = new ArrayList<>(), national = new ArrayList<>();
        for (BrandEntry b : fit) {
            if (!b.isRegional()) national.add(b);
            else if (b.servesRegion(sido)) regional.add(b);
        }
        if (regional.isEmpty()) return pick(national.isEmpty() ? fit : national, r);
        if (national.isEmpty()) return pick(regional, r);
        return pick(r.nextDouble() < regionalShare ? regional : national, r);
    }

    /**
     * category2 → 상품(품목·단가·재량성). 단가는 [저,고] 균등.
     *
     * <p><b>품목은 가중 추출한다.</b> 지하철은 기본요금(1,550원)이 대부분이고 거리비례 추가는
     * 가끔이다 — 균등 추출이면 장거리 요금이 기본요금만큼 자주 나와 현실과 어긋난다.
     * 가중치를 안 준 품목은 1.0이라 기존 동작 그대로다.
     */
    /**
     * 품목 이름이 {@code prefix} 로 시작하는 것 중에서 뽑는다 — 통근·출장처럼 <b>무엇을 타는지 이미
     * 정해진</b> 결제용. 예: 통근이면 "지하철", 원거리 출장이면 "KTX". 해당 품목이 없으면 전체에서.
     */
    public ResolvedProduct resolveProduct(String category2, String prefix, Random r) {
        List<ProductEntry> list = products.get(category2);
        if (prefix == null || list == null || list.isEmpty()) return resolveProduct(category2, r);
        List<ProductEntry> fit = new ArrayList<>();
        for (ProductEntry p : list) if (p.name().startsWith(prefix)) fit.add(p);
        if (fit.isEmpty()) return resolveProduct(category2, r);
        ProductEntry chosen = pickByWeight(fit, r);
        return new ResolvedProduct(chosen.name(),
                GenSeed.uniformInt(r, chosen.priceLow(), chosen.priceHigh()), chosen.discretionary());
    }

    public ResolvedProduct resolveProduct(String category2, Random r) {
        List<ProductEntry> list = products.get(category2);
        if (list == null || list.isEmpty()) return new ResolvedProduct(category2, 10000, 0.5);
        ProductEntry chosen = pickByWeight(list, r);
        int price = GenSeed.uniformInt(r, chosen.priceLow(), chosen.priceHigh());
        return new ResolvedProduct(chosen.name(), price, chosen.discretionary());
    }

    /** 품목 가중치대로 하나 뽑는다. 가중치를 안 준 품목은 1.0이라 균등과 같다. */
    private static ProductEntry pickByWeight(List<ProductEntry> list, Random r) {
        double total = 0;
        for (ProductEntry p : list) total += p.weight();
        double x = r.nextDouble() * total, acc = 0;
        for (ProductEntry p : list) {
            acc += p.weight();
            if (x < acc) return p;
        }
        return list.get(list.size() - 1);
    }

    // ── 내부 ──
    private boolean hasBrands(String c) { List<BrandEntry> b = brands.get(c); return b != null && !b.isEmpty(); }
    /** 업종코드로 조회한다. 맥락에 코드가 없으면(비정상) 브랜드 쪽으로 흐르게 false. */
    private boolean hasIndependents(String ksic) {
        if (ksic == null) return false;
        List<String> i = independents.get(ksic);
        return i != null && !i.isEmpty();
    }

    /**
     * 결제 명세서 표시상호: branchable면 "브랜드 {동}점"(앵커 동), 아니면 표기 변형(forms) 중 택.
     * <b>표시상호만</b> 흔들고(명세서 노이즈 재현), 사업자번호·주소는 정식 base+동에서 파생하므로 같은 점포는 일관.
     */
    private String displayName(BrandEntry b, boolean branchable, RegionEntry anchor, Random r) {
        if (branchable && anchor != null) {
            String shown = (!b.forms().isEmpty() && r.nextDouble() < 0.25) ? pick(b.forms(), r) : b.name();
            return shown + " " + anchor.dong() + "점";
        }
        if (!b.forms().isEmpty() && r.nextDouble() < 0.35) return pick(b.forms(), r);
        return b.name();
    }

    private static <T> T pick(List<T> list, Random r) { return list.get(r.nextInt(list.size())); }
}
