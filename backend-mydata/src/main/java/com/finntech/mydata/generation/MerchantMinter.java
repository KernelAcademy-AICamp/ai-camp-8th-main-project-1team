package com.finntech.mydata.generation;

import com.finntech.mydata.generation.CatalogModels.RegionEntry;

import java.util.Random;

/**
 * 가맹점 신원 → 사업자등록번호·지번주소·좌표를 <b>결정론적으로</b> 파생하는 순수 유틸(테스트 용이).
 *
 * <p>핵심 성질(사용자 우려 대응): 값이 신원 문자열의 순수 함수라 <b>같은 신원은 항상 같은 번호·주소·좌표</b>.
 * 여러 사용자·여러 거래·재생성에 걸쳐 중복/불일치 배정이 원천적으로 없다. 표기 노이즈(forms)는 표시명만
 * 흔들고 신원(정식명+동)엔 넣지 않으므로, 같은 점포의 명세서 표기차가 같은 번호·주소를 갖는다.
 */
public final class MerchantMinter {

    private MerchantMinter() {}

    /** 지번: 본번(1~999) + 선택적 부번(1~9). */
    public record Jibun(int bonbun, Integer bubun) {
        /** "123" 또는 "123-4". */
        public String suffix() { return bubun == null ? String.valueOf(bonbun) : bonbun + "-" + bubun; }
    }

    /** 신원 → 사업자등록번호(10자리, 하이픈 없음). */
    public static String businessNumber(long masterSeed, String identityKey) {
        return BusinessNumberGenerator.generate(GenSeed.mix(masterSeed, fold(identityKey), 1));
    }

    /** 신원 → 지번(본번·부번). bubunProb 확률로 부번 존재. */
    public static Jibun jibun(long masterSeed, String identityKey, double bubunProb) {
        Random r = new Random(GenSeed.mix(masterSeed, fold(identityKey), 2));
        int bonbun = 1 + r.nextInt(999);                 // 1 ~ 999
        Integer bubun = r.nextDouble() < bubunProb ? 1 + r.nextInt(9) : null;  // 1 ~ 9
        return new Jibun(bonbun, bubun);
    }

    /** "시도 시군구 동 본번[-부번]번지". */
    public static String address(RegionEntry region, Jibun jibun) {
        return region.sido() + " " + region.sigungu() + " " + region.dong() + " " + jibun.suffix() + "번지";
    }

    /** 신원 → 고정 좌표(동 중심 + 결정론 오프셋 ~1km). 같은 점포는 항상 같은 좌표. */
    public static double[] coords(long masterSeed, String identityKey, RegionEntry region) {
        Random r = new Random(GenSeed.mix(masterSeed, fold(identityKey), 3));
        double lat = round5(region.lat() + r.nextGaussian() * 0.012);
        double lon = round5(region.lon() + r.nextGaussian() * 0.012);
        return new double[]{lat, lon};
    }

    /** 신원 문자열을 결정론 long으로 접는다(FNV-1a 64bit) — String.hashCode(32bit)보다 충돌 적음. */
    static long fold(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static double round5(double v) { return Math.round(v * 1e5) / 1e5; }
}
