package com.finntech.mydata.generation;

import com.finntech.mydata.generation.CatalogModels.RegionEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 가맹점 신원 → 고정 사업자번호·지번주소·좌표 해석기.
 *
 * <p><b>캐시 없음.</b> 값이 신원의 순수 함수({@link MerchantMinter})라 매번 재계산해도 항상 같은 결과(§3) —
 * 중복/불일치 배정이 없고 메모리도 축적하지 않는다. 온라인 가맹점은 신원별로 고정 본사(HQ) 행정동을
 * 결정론적으로 뽑아 전국 어디서 결제해도 본사 소재지로 기록한다.
 *
 * <p>고유 가맹점 목록(mydata_merchant 테이블·정리 CSV)은 생성 후 결제에서 {@code business_number}
 * DISTINCT로 집계한다(레지스트리가 목록을 들고 있지 않음).
 */
@Component
public class MerchantRegistry {

    private final long masterSeed;
    private final List<RegionEntry> hqPool;   // 온라인 본사 소재 후보(전국 행정동)
    private final double bubunProb;

    @Autowired
    public MerchantRegistry(GenerationProperties props, CatalogLoader catalog) {
        this(props.getSeed(), catalog.regions(), props.getAddress().getBubunProb());
    }

    /** 테스트용 — 리소스 로딩 없이 직접 주입. */
    MerchantRegistry(long masterSeed, List<RegionEntry> hqPool, double bubunProb) {
        this.masterSeed = masterSeed;
        this.hqPool = hqPool;
        this.bubunProb = bubunProb;
    }

    /**
     * 오프라인 가맹점(정규신원 = base + 전체 행정구역) → 고정 번호·그 동의 지번주소·좌표.
     * <b>신원 키는 시도+시군구+동 전체</b>를 쓴다 — 동 이름만 쓰면 '중앙동'처럼 전국 중복 동명 때문에
     * 서로 다른 도시의 같은 상호가 같은 사업자번호를 받으면서 주소는 달라지는 중복배정이 생긴다.
     */
    public Merchant resolveOffline(String canonicalBase, String canonicalName, RegionEntry region) {
        String key = canonicalBase + "|" + region.sido() + " " + region.sigungu() + " " + region.dong();
        String biz = MerchantMinter.businessNumber(masterSeed, key);
        MerchantMinter.Jibun jibun = MerchantMinter.jibun(masterSeed, key, bubunProb);
        String addr = MerchantMinter.address(region, jibun);
        double[] c = MerchantMinter.coords(masterSeed, key, region);
        return new Merchant(canonicalName, biz, addr, c[0], c[1], false);
    }

    /** 온라인 가맹점(정규신원 = ONLINE|base) → 고정 번호·본사 지번주소·본사 좌표. */
    public Merchant resolveOnline(String canonicalBase, String canonicalName) {
        String key = "ONLINE|" + canonicalBase;
        RegionEntry hq = hqPool.get(Math.floorMod(MerchantMinter.fold(key), hqPool.size()));
        String biz = MerchantMinter.businessNumber(masterSeed, key);
        MerchantMinter.Jibun jibun = MerchantMinter.jibun(masterSeed, key, bubunProb);
        String addr = MerchantMinter.address(hq, jibun);
        double[] c = MerchantMinter.coords(masterSeed, key, hq);
        return new Merchant(canonicalName, biz, addr, c[0], c[1], true);
    }

    /**
     * <b>실제 본사·시설 주소를 가진 사업자</b> — 카탈로그에 적힌 값을 그대로 쓴다.
     *
     * <p>여기가 이 클래스의 존재 이유가 바뀐 지점이다. 예전에는 온라인 가맹점의 주소를
     * <b>브랜드명 해시로 전국 행정동 3,495개 중 하나</b>를 뽑아 만들었다. 브랜드당 하나로 고정되긴
     * 했지만 그 하나가 임의의 동이라, 명세서에 "카카오페이 · 경상북도 성주군 금수면"이 찍혔다.
     * 이제 실주소를 카탈로그가 들고 있으므로 해시를 쓰지 않는다.
     *
     * <p><b>해외 본사는 사업자등록번호가 없다.</b> 그러면 결제행의 번호가 {@code null}이 되고,
     * 앱은 번호가 있을 때만 주소 조회 버튼을 그리므로(Transactions.tsx) 자연스럽게 감춰진다 —
     * 실제로도 해외 결제 명세서에 국내 사업자번호는 없다.
     */
    public Merchant resolveFixed(String canonicalName, CatalogModels.HqEntry hq) {
        String biz = hq.businessNumber() == null || hq.businessNumber().isBlank() ? null : hq.businessNumber();
        return new Merchant(canonicalName, biz, hq.address(), hq.lat(), hq.lon(), true);
    }

    /**
     * <b>시군구마다 다른 사업자</b> — ㅇㅇ시설공단처럼 지자체 단위로 존재하는 곳.
     *
     * <p>전국에 하나가 아니라 시군구마다 별개 법인이고, 각자 그 시군구 안에 있다. 그래서 신원키에
     * 시도·시군구를 넣어 번호를 가르고, 주소는 <b>그 시군구의 동 중에서</b> 결정론으로 뽑는다.
     * 앵커 동을 그대로 쓰면(=오프라인 경로) 같은 공단이 관내 동마다 다른 주소를 갖게 되므로 쓰지 않는다.
     */
    public Merchant resolveDistrict(String canonicalBase, String canonicalName, RegionEntry anchor,
                                    List<RegionEntry> sameSigungu) {
        String key = canonicalBase + "@" + anchor.sido() + " " + anchor.sigungu();
        List<RegionEntry> pool = sameSigungu == null || sameSigungu.isEmpty() ? List.of(anchor) : sameSigungu;
        RegionEntry seat = pool.get(Math.floorMod(MerchantMinter.fold(key), pool.size()));
        String biz = MerchantMinter.businessNumber(masterSeed, key);
        MerchantMinter.Jibun jibun = MerchantMinter.jibun(masterSeed, key, bubunProb);
        String addr = MerchantMinter.address(seat, jibun);
        double[] c = MerchantMinter.coords(masterSeed, key, seat);
        return new Merchant(canonicalName, biz, addr, c[0], c[1], false);
    }
}
