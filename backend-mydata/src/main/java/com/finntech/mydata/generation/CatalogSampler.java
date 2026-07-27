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
    private final Map<String, List<String>> cat2ByCat1 = new LinkedHashMap<>();     // 대분류 → category2들
    private final Map<String, double[]> cat2Cumul = new LinkedHashMap<>();          // 대분류 → 누적 freq
    private final Map<String, List<BrandEntry>> brands;
    private final Map<String, List<ProductEntry>> products;
    private final Map<String, List<String>> independents;
    private final MerchantRegistry registry;

    @SuppressWarnings("unchecked")
    public CatalogSampler(CatalogLoader catalog, MerchantRegistry registry) {
        this.registry = registry;
        this.brands = catalog.brands();
        this.products = catalog.products();
        this.independents = (Map<String, List<String>>) catalog.independents().get("namePoolByCategory2");
        Map<String, List<Double>> weights = new LinkedHashMap<>();
        for (CatalogContext c : catalog.contexts()) {
            ctxByCat2.put(c.category2(), c);
            cat2ByCat1.computeIfAbsent(c.category1(), k -> new ArrayList<>()).add(c.category2());
            weights.computeIfAbsent(c.category1(), k -> new ArrayList<>()).add(c.frequencyWeight());
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

    /** 대분류(예: 식비) 안에서 방문빈도 가중으로 category2(예: 한식) 선택. */
    public String pickCategory2(String category1, Random r) {
        List<String> cats = cat2ByCat1.get(category1);
        if (cats == null || cats.isEmpty()) return null;
        double[] cum = cat2Cumul.get(category1);
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
    public ResolvedMerchant resolveMerchant(String category2, RegionEntry anchor, Random r) {
        CatalogContext ctx = ctxByCat2.get(category2);
        String source = ctx == null ? "INDEPENDENT" : ctx.merchantSource();
        String channel = ctx == null ? "OFFLINE" : ctx.channel();
        boolean useBrand = switch (source) {
            case "BRAND", "ONLINE", "OPERATOR" -> true;
            case "MIXED" -> r.nextBoolean();
            default -> false; // INDEPENDENT
        };

        String base;         // 정규 신원의 이름 부분(정식 브랜드명 또는 독립상호)
        String display;      // 결제 명세서 표시상호(forms 노이즈·동점 포함 가능)
        boolean branchable = false;
        if (useBrand && hasBrands(category2)) {
            BrandEntry b = pick(brands.get(category2), r);
            base = b.name();
            branchable = b.branchable();
            display = displayName(b, branchable, anchor, r);
        } else if (hasIndependents(category2)) {
            base = pick(independents.get(category2), r);
            display = base;
        } else if (hasBrands(category2)) {
            BrandEntry b = pick(brands.get(category2), r);
            base = b.name();
            branchable = b.branchable();
            display = displayName(b, branchable, anchor, r);
        } else {
            base = category2;   // 최후 폴백
            display = category2;
        }

        boolean online = "ONLINE".equals(channel);
        if (online || anchor == null) {
            Merchant m = registry.resolveOnline(base, base);   // 온라인 정규명 = base(전국 HQ)
            return new ResolvedMerchant(display, channel, m.lat(), m.lon(), m.address(), m.businessNumber());
        }
        String canonicalName = branchable ? base + " " + anchor.dong() + "점" : base;
        Merchant m = registry.resolveOffline(base, canonicalName, anchor);
        return new ResolvedMerchant(display, channel, m.lat(), m.lon(), m.address(), m.businessNumber());
    }

    /** category2 → 상품(품목·단가·재량성). 단가는 [저,고] 균등. */
    public ResolvedProduct resolveProduct(String category2, Random r) {
        List<ProductEntry> list = products.get(category2);
        if (list == null || list.isEmpty()) return new ResolvedProduct(category2, 10000, 0.5);
        ProductEntry p = list.get(r.nextInt(list.size()));
        int price = GenSeed.uniformInt(r, p.priceLow(), p.priceHigh());
        return new ResolvedProduct(p.name(), price, p.discretionary());
    }

    // ── 내부 ──
    private boolean hasBrands(String c) { List<BrandEntry> b = brands.get(c); return b != null && !b.isEmpty(); }
    private boolean hasIndependents(String c) { List<String> i = independents.get(c); return i != null && !i.isEmpty(); }

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
