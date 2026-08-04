package com.finntech.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 업종코드 대조표 — <b>국세청 6자리</b>로 갈아탄 뒤의 계약을 못박는다(2026-08-04).
 *
 * <p>여기 있는 것은 전부 "조용히 틀어지는" 종류다. 매핑이 어긋나도 예외가 안 나고
 * 카테고리만 바뀌므로, 단정으로 잡지 않으면 리포트가 이상해진 뒤에야 알게 된다.
 */
class IndustryCategoryMapperTest {

    private final IndustryCategoryMapper mapper = new IndustryCategoryMapper(new ObjectMapper());

    @Test
    @DisplayName("화장품과 의약품이 갈린다 — 이 체계로 갈아탄 이유 그 자체")
    void separatesCosmeticsFromPharmacy() {
        // KSIC 4781 은 "의약품, 의료용 기구, **화장품** 및 방향제 소매업"이 한 코드라
        // 올리브영이 '의료'가 됐다. 생성기에서 우회 코드를 붙여 덮고 있었을 뿐이다.
        assertEquals("미용", mapper.midOf("523131"), "화장품, 비누 및 방향제 소매업");
        assertEquals("의료", mapper.midOf("523111"), "의약품 및 의료용품 소매업");
        assertEquals("의료", mapper.midOf("523120"), "의료용 기구 소매업");
    }

    @Test
    @DisplayName("모르는 코드·빈 값은 '카테고리없음' — null 을 흘리지 않는다")
    void unknownCodesFallBack() {
        // 집계가 카테고리로 묶는데 null 이 섞이면 그 소비가 통째로 사라진다.
        assertEquals(IndustryCategoryMapper.UNCLASSIFIED, mapper.midOf(null));
        assertEquals(IndustryCategoryMapper.UNCLASSIFIED, mapper.midOf(""));
        assertEquals(IndustryCategoryMapper.UNCLASSIFIED, mapper.midOf("999999"));
        // 간편결제(642004)는 **일부러** 대조표에 없다 — 무엇을 샀는지 모르는 결제다.
        assertEquals(IndustryCategoryMapper.UNCLASSIFIED, mapper.midOf("642004"));
    }

    @Test
    @DisplayName("KSIC 4자리는 더는 안 먹는다 — 두 체계가 섞이면 조용히 오분류된다")
    void oldKsicCodesNoLongerResolve() {
        // 국세청은 구 분류 세대라 번호가 겹치지 않는다(KSIC 소매 47xx / 국세청 52xxxx).
        // 옛 코드가 우연히 다른 업종으로 매핑되면 그게 제일 나쁘다 — 여기서 막는다.
        for (String old : new String[]{"4781", "5611", "6031", "4712", "6312"}) {
            assertEquals(IndustryCategoryMapper.UNCLASSIFIED, mapper.midOf(old),
                    "옛 KSIC " + old + " 가 아직 매핑된다");
        }
    }

    @Test
    @DisplayName("PG 사업자번호는 업종코드가 뭐든 분류하지 않는다")
    void paymentAgencyBlocksClassification() {
        // 724000 은 OTT(넷플릭스)와 '데이터베이스 및 온라인 정보 제공업'(NHN KCP 등 일부 PG)이
        // **한 코드에 섞여** 있다. 업종코드로는 못 가르므로 사업자번호로 막는다.
        assertEquals("취미/여가", mapper.midOf("724000"), "PG 가 아니면 그대로 분류한다");
        assertEquals(IndustryCategoryMapper.UNCLASSIFIED,
                mapper.midOf("724000", "5278800686"), "카카오페이 — PG 라 분류하지 않는다");

        assertTrue(mapper.isPaymentAgency("4118601799"), "토스페이먼츠");
        assertTrue(mapper.isPaymentAgency("411-86-01799"), "하이픈이 있어도 같은 번호다");
        assertFalse(mapper.isPaymentAgency("1658700119"), "넷플릭스는 PG 가 아니다");
        assertFalse(mapper.isPaymentAgency(null));
    }

    @Test
    @DisplayName("사업자번호가 없으면 업종코드만으로 정한다 — 해외 가맹점이 여기다")
    void nullBusinessNumberFallsBackToCode() {
        assertEquals("취미/여가", mapper.midOf("724000", null));
        assertEquals("미용", mapper.midOf("523131", ""));
    }

    @Test
    @DisplayName("재량성은 대조표가 준다 — 필수 판정이 목록 복사로 갈라지지 않게")
    void discretionaryComesFromTable() {
        // ESSENTIAL 목록이 네 곳에 손으로 복사돼 있던 시절이 있었다. 지금은 카탈로그의
        // discretionaryBase 에서 유도하므로 한 곳만 고쳐 갈라질 수 없다.
        assertTrue(mapper.discretionaryOf("의료") < 0.30, "의료는 필수여야 한다");
        assertTrue(mapper.discretionaryOf("취미/여가") > 0.30, "취미는 재량이어야 한다");
        assertEquals(0.5, mapper.discretionaryOf("없는중분류"), 1e-9, "모르면 중간값");
    }

    @Test
    @DisplayName("표가 비어 있지 않고 중분류가 15개 축을 유지한다")
    void tableIsPopulated() {
        assertTrue(mapper.size() > 400, "소비 업종이 400개 넘게 있어야 한다: " + mapper.size());
        assertTrue(mapper.midCategories().size() >= 15,
                "중분류 축이 줄었다: " + mapper.midCategories());
    }
}
