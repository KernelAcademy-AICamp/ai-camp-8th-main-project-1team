package com.finntech.mydata.generation;

import com.finntech.mydata.generation.CatalogModels.RegionEntry;
import com.finntech.mydata.generation.MerchantMinter.Jibun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 결정론 신원 파생기 검증 — 사용자 우려("같은 가맹점이 다른 번호·주소로 중복 배정되나?")를 테스트로 증명한다.
 */
class MerchantMinterTest {

    private static final long SEED = 20260721L;
    private static final RegionEntry YEOKSAM =
            new RegionEntry("서울특별시", "강남구", "역삼동", 37.5006, 127.0366, 0.01);
    private static final RegionEntry SEOCHO =
            new RegionEntry("서울특별시", "서초구", "서초동", 37.4837, 127.0324, 0.01);

    @Test
    @DisplayName("같은 신원 → 항상 같은 사업자번호·주소·좌표 (중복 배정 없음)")
    void sameIdentitySameEverything() {
        String key = "스타벅스|역삼동";
        String biz1 = MerchantMinter.businessNumber(SEED, key);
        String biz2 = MerchantMinter.businessNumber(SEED, key);
        assertEquals(biz1, biz2, "같은 신원은 같은 번호");

        Jibun j1 = MerchantMinter.jibun(SEED, key, 0.35);
        Jibun j2 = MerchantMinter.jibun(SEED, key, 0.35);
        assertEquals(j1, j2, "같은 신원은 같은 지번");

        double[] c1 = MerchantMinter.coords(SEED, key, YEOKSAM);
        double[] c2 = MerchantMinter.coords(SEED, key, YEOKSAM);
        assertArrayEquals(c1, c2, "같은 신원은 같은 좌표");
    }

    @Test
    @DisplayName("이동범위가 겹쳐 두 사용자가 같은 점포 결제 → 동일 번호·주소 (핵심 시나리오)")
    void overlappingUsersSameStore() {
        // 사용자 A와 B가 모두 역삼동 스타벅스에서 결제 → 신원이 같으므로 같은 가맹점으로 귀결
        String keyForUserA = "스타벅스|역삼동";
        String keyForUserB = "스타벅스|역삼동";
        assertEquals(MerchantMinter.businessNumber(SEED, keyForUserA),
                MerchantMinter.businessNumber(SEED, keyForUserB));
        assertEquals(MerchantMinter.jibun(SEED, keyForUserA, 0.35),
                MerchantMinter.jibun(SEED, keyForUserB, 0.35));
    }

    @Test
    @DisplayName("같은 브랜드라도 동이 다르면 다른 지점 → 다른 번호·주소")
    void sameBrandDifferentDongDiffers() {
        String yeoksam = "스타벅스|역삼동";
        String seocho = "스타벅스|서초동";
        assertNotEquals(MerchantMinter.businessNumber(SEED, yeoksam),
                MerchantMinter.businessNumber(SEED, seocho), "다른 동은 다른 사업자번호");
        String addrY = MerchantMinter.address(YEOKSAM, MerchantMinter.jibun(SEED, yeoksam, 0.35));
        String addrS = MerchantMinter.address(SEOCHO, MerchantMinter.jibun(SEED, seocho, 0.35));
        assertNotEquals(addrY, addrS);
        assertTrue(addrY.startsWith("서울특별시 강남구 역삼동 "));
        assertTrue(addrS.startsWith("서울특별시 서초구 서초동 "));
    }

    @Test
    @DisplayName("파생된 사업자번호는 전부 유효")
    void mintedNumbersValid() {
        String[] dongs = {"역삼동", "서초동", "삼성동", "청담동", "논현동"};
        String[] brands = {"스타벅스", "이디야", "김밥천국", "올리브영", "다이소"};
        for (String b : brands) {
            for (String d : dongs) {
                String key = b + "|" + d;
                assertTrue(BusinessNumberGenerator.isValid(MerchantMinter.businessNumber(SEED, key)),
                        "무효 번호: " + key);
            }
        }
    }

    @Test
    @DisplayName("지번 형식: 본번 1~999, 부번 있으면 1~9, '번지' 접미")
    void jibunFormat() {
        boolean sawBubun = false, sawNoBubun = false;
        for (int i = 0; i < 500; i++) {
            String key = "가게" + i + "|역삼동";
            Jibun j = MerchantMinter.jibun(SEED, key, 0.35);
            assertTrue(j.bonbun() >= 1 && j.bonbun() <= 999, "본번 범위: " + j.bonbun());
            if (j.bubun() != null) { assertTrue(j.bubun() >= 1 && j.bubun() <= 9); sawBubun = true; }
            else sawNoBubun = true;
            String addr = MerchantMinter.address(YEOKSAM, j);
            assertTrue(addr.endsWith("번지"), addr);
            if (j.bubun() != null) assertTrue(addr.contains(j.bonbun() + "-" + j.bubun()));
        }
        assertTrue(sawBubun && sawNoBubun, "부번 존재/부재가 모두 나와야 확률적임");
    }

    @Test
    @DisplayName("대량 신원에서 번호 충돌이 과도하지 않다(결정론 + 낮은 충돌)")
    void lowCollision() {
        Map<String, String> biznoToKey = new HashMap<>();
        int collisions = 0, n = 0;
        String[] dongs = {"역삼동", "서초동", "삼성동", "청담동", "논현동", "방배동", "잠원동", "대치동"};
        for (int i = 0; i < 2000; i++) {
            for (String d : dongs) {
                String key = "상점" + i + "|" + d;
                String biz = MerchantMinter.businessNumber(SEED, key);
                n++;
                String prev = biznoToKey.putIfAbsent(biz, key);
                if (prev != null && !prev.equals(key)) collisions++;
            }
        }
        // 사업자번호 공간이 ~약 700*79*9999 ≈ 5.5억이라 1.6만 신원에서 충돌은 매우 드물어야 한다.
        assertTrue(collisions < n * 0.001, "충돌률 과다: " + collisions + "/" + n);
    }
}
