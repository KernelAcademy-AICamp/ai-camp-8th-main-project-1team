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
}
