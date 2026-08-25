package com.finntech.service;

import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.MerchantBrandRepository;
import com.finntech.repository.MerchantCategoryRepository;
import com.finntech.repository.UserPaymentRepository;
import com.finntech.freechannel.FreeChannelQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * <b>소분류가 탈 브랜드를 상호에서 고르는 규칙.</b>
 *
 * <p>운영 사전 845행을 실제로 대조해 나온 규칙이다(2026-08-25). 두 가지가 드러났다.
 *
 * <ol>
 *   <li><b>저장된 브랜드는 못 믿는다.</b> 845행 중 <b>269행</b>의 브랜드가 표기표에 없는
 *       이름이었다 — 무료 통로가 지어낸 것이다. {@code (주)카카오} 는 <b>멜론</b>으로,
 *       {@code 주식회사 데이원컴퍼니}(온라인 교육)는 <b>배달의민족</b>으로 적혀 있었다.
 *       그 값으로 소분류를 정하면 브랜드 하나가 통째로 틀린 카테고리로 간다.</li>
 *   <li><b>긴 표기 우선은 소분류에서 진다.</b> 한국 상호는 브랜드가 앞, 지점명이 뒤다.</li>
 * </ol>
 */
class SubBrandResolutionTest {

    private final IndustryCategoryMapper industries = new IndustryCategoryMapper(new ObjectMapper());

    private final MerchantBrandService brands = new MerchantBrandService(
            mock(MerchantBrandRepository.class), mock(MerchantCategoryRepository.class),
            mock(TempClassifierService.class), mock(UserPaymentRepository.class),
            new ObjectMapper(), null, mock(FreeChannelQueue.class));

    /** 그 상호가 어느 소분류로 가는가 — 실제 경로와 같은 순서로 묻는다. */
    private String subOf(String merchantName) {
        return industries.subOfBrand(brands.subBrandOf(merchantName, industries::hasSub).orElse(""));
    }

    /**
     * <b>지점명이 브랜드를 삼키던 자리.</b> 둘 다 여섯 글자라 길이로는 못 가른다 —
     * 등장 순서가 가른다.
     */
    @Test
    @DisplayName("지점명에 든 이름이 브랜드를 이기지 않는다")
    void 지점명이_브랜드를_안_이긴다() {
        assertThat(subOf("이마트24 서울어린이대공원정문점"))
                .as("편의점이 공원이 됐다").isEqualTo("편의점");
        assertThat(subOf("노티드 잠실롯데월드몰"))
                .as("도넛집이 테마파크가 됐다").isEqualTo("베이커리");
    }

    /**
     * <b>결제대행사를 걷어내는 일을 겸한다.</b> 결제수단·회사명은 소분류를 안 받으므로
     * 자동으로 밀려나고, 그 뒤의 진짜 가맹점이 답한다.
     */
    @Test
    @DisplayName("앞에 붙은 결제수단을 건너뛰고 진짜 가맹점을 본다")
    void 결제수단을_건너뛴다() {
        assertThat(subOf("넥슨_카카오페이")).isEqualTo("게임");
        assertThat(subOf("CGV_카카오페이")).isEqualTo("영화관");
        assertThat(subOf("토스페이_알라딘-(주)비바리퍼블리카")).isEqualTo("도서");
        assertThat(subOf("토스페이_마켓컬리-(주)비바리퍼블리카")).isEqualTo("새벽배송");
        assertThat(subOf("네이버페이-트립닷컴")).isEqualTo("여행사");
        assertThat(subOf("네이버페이-메가박스-메가박스중앙 (주)")).isEqualTo("영화관");
    }

    /**
     * 회사명밖에 없으면 <b>답하지 않는다.</b> 대표 업태를 찍으면 그 브랜드 전체가 한꺼번에
     * 틀린다 — {@code 카카오} 가 멜론의 표기였을 때 카카오택시 72곳이 그랬다.
     */
    @Test
    @DisplayName("회사명과 결제수단만 있으면 소분류를 안 준다")
    void 회사명만_있으면_안_준다() {
        assertThat(subOf("문화비_카카오_카카오페이-주식회사 카카오")).isEmpty();
        assertThat(subOf("애플서비스_카카오페이")).isEmpty();
        assertThat(subOf("토스페이먼츠 - 구글클라우드")).isEmpty();
    }

    /** 짧은 표기가 더 긴 상호 안에 들어 있던 자리 — {@code 웨이브}(OTT) 대 {@code 티웨이브}(상품권). */
    @Test
    @DisplayName("긴 표기를 세워 짧은 이름이 삼켜지지 않게 한다")
    void 짧은_표기가_안_삼킨다() {
        assertThat(subOf("웰컴페이먼츠_상품권-(주)티웨이브 역삼지점"))
                .as("상품권 판매점이 OTT 구독이 됐다").isEmpty();
        assertThat(subOf("웨이브")).isEqualTo("구독");
    }

    /**
     * <b>운영 상호 1,016개를 전수 대조해 잡은 자리</b>(2026-08-25). 한 지붕에서 갈라져야
     * 하는데 안 갈라져 있던 것들이라, 표기를 세워 가른 뒤 여기 못박는다.
     */
    @Test
    @DisplayName("한 지붕의 다른 업태를 표기로 가른다")
    void 한_지붕을_가른다() {
        // 코레일유통은 역 안의 편의점이지 철도 운송이 아니다 — 실사용자 13건이 철도로 갔었다.
        assertThat(subOf("코레일유통주식회사(의왕역)")).isEqualTo("편의점");
        assertThat(subOf("고속철도(KTX)서울-포항")).isEqualTo("철도");
        // 롯데월드몰은 복합쇼핑몰이지 놀이공원이 아니다.
        assertThat(subOf("롯데물산 (주) 롯데월드몰점")).isEqualTo("백화점");
        assertThat(subOf("(주)호텔롯데 롯데월드")).isEqualTo("테마파크");
        // 카카오T 바이크는 택시가 아니라 공유 자전거다.
        assertThat(subOf("카카오 T_바이크-주식회사 카카오모빌리티")).isEqualTo("공유이동");
        assertThat(subOf("카카오T일반택시_0")).isEqualTo("택시");
        // 파리크라상은 회사명이라 업태가 안 정해진다 — 그래서 뒤의 파스쿠찌가 답한다.
        assertThat(subOf("(주)파리크라상 파스쿠찌 센트로 인천공항 랜드마크점")).isEqualTo("커피");
        assertThat(subOf("파리바게뜨사당점")).isEqualTo("베이커리");
        // 코레일관광개발은 관광열차와 역사 내 카페를 함께 한다 — 하나로 안 정해진다.
        assertThat(subOf("한국신용카드결제(주) - 코레일관광개발㈜")).isEmpty();
    }

    /**
     * <b>운영에서 한 브랜드가 여러 중분류로 갈려 있던 것들</b> — 표에 없어서 소분류가
     * 못 잡던 프랜차이즈다. 갈린 브랜드 22개 중 14개가 이 표로 하나가 된다.
     */
    @Test
    @DisplayName("갈려 있던 프랜차이즈를 하나로 묶는다")
    void 갈린_프랜차이즈를_묶는다() {
        // 배스킨라빈스는 등록 업종이 세 갈래였다 — 제과점업·간이 음식점업·포장 판매.
        assertThat(subOf("배스킨라빈스 써티원")).isEqualTo("디저트");
        assertThat(subOf("배스킨라빈스 보문역점")).isEqualTo("디저트");
        // 깐부치킨은 한쪽이 전자상거래로 등록돼 쇼핑이 됐었다.
        assertThat(subOf("깐부치킨 장위레디언트점")).isEqualTo("치킨");
        assertThat(subOf("깐부치킨양재역점")).isEqualTo("치킨");
        // 육회바른연어는 서양식과 한식 해산물로 갈려 있었다.
        assertThat(subOf("육회바른연어 송파점")).isEqualTo("한식");
        assertThat(subOf("육회바른연어 경복궁점")).isEqualTo("한식");
        // 같은 브랜드의 다른 표기가 빠져 있던 것들.
        assertThat(subOf("(주)코리아세븐 남산소월점")).isEqualTo("편의점");
        assertThat(subOf("메가MGC커피 의왕도깨비시장점")).isEqualTo("커피");
        assertThat(subOf("씨제이올리브네트웍스(주) 수원정자점")).isEqualTo("화장품");
        assertThat(subOf("ChatGPT_NICE(구)")).isEqualTo("구독");
    }

    /**
     * <b>원장의 브랜드 칸도 표기표를 먼저 본다.</b> 사전에 저장된 브랜드는 모델이 지어낸
     * 것일 수 있고 <b>한 번 붙으면 스스로 안 고쳐진다</b> — 운영에서 코레일유통이
     * '한국철도공사' 로, 코리아세븐이 'CU' 로 적혀 있었다(50행 208건). 분류만 고치고 이 칸을
     * 두면 같은 줄에서 브랜드와 카테고리가 어긋나 보인다.
     */
    @Test
    @DisplayName("화면에 적을 브랜드도 표기표가 정본이다")
    void 화면_브랜드도_표기표가_정본() {
        assertThat(brands.displayBrandOf("코레일유통주식회사(의왕역)")).contains("코레일유통");
        assertThat(brands.displayBrandOf("코리아세븐 삼성대웅점")).contains("세븐일레븐");
        assertThat(brands.displayBrandOf("돈치킨")).contains("돈치킨");
        assertThat(brands.displayBrandOf("(주)카카오")).as("회사명은 회사 자신으로 적는다").contains("카카오");
        assertThat(brands.displayBrandOf("동네개인식당")).as("표기표가 모르면 비운다").isEmpty();
    }

    /**
     * <b>두 글자 한글 표기는 뒤에 한글이 오면 거부된다</b>({@code matchesKorean}) — {@code 쿠팡}
     * 이 {@code 쿠팡이츠} 를 삼키지 않게 하는 방어인데, <b>대신할 긴 표기가 없으면 그 상호는
     * 아무 브랜드도 못 얻는다.</b> 운영 배포 뒤 실측(2026-08-25)에서 6곳 8건이 그랬다.
     */
    @Test
    @DisplayName("두 글자 표기가 막히는 자리에 긴 표기를 세운다")
    void 두_글자가_막히면_긴_표기로() {
        assertThat(subOf("컬리페이")).as("'컬리' 가 '컬리페이' 안에서 막힌다").isEqualTo("새벽배송");
        assertThat(subOf("주식회사 컬리페이")).isEqualTo("새벽배송");
        assertThat(subOf("(주)러쉬코리아 압구정점")).as("'러쉬' 가 '러쉬코리아' 안에서 막힌다")
                .isEqualTo("화장품");
        assertThat(subOf("주식회사러쉬코리아스파")).isEqualTo("화장품");
        assertThat(subOf("공차경주월드점")).as("'공차' 가 '공차경주' 안에서 막힌다").isEqualTo("디저트");
        // 방어 자체는 그대로여야 한다 — 긴 형제가 있는 자리는 여전히 긴 쪽이 이긴다.
        assertThat(subOf("쿠팡이츠_KCP")).isEqualTo("배달");
        assertThat(subOf("스타벅스코리아")).as("'벅스'(OTT 구독)가 아니다").isEqualTo("커피");
        assertThat(subOf("투썸플레이스 송파나루역점")).isEqualTo("커피");
    }

    /** 같은 자리에서 시작하면 그때는 긴 쪽이 이긴다. */
    @Test
    @DisplayName("같은 자리면 긴 표기가 이긴다")
    void 같은_자리면_긴_쪽() {
        assertThat(subOf("노브랜드버거 고속터미널점")).isEqualTo("패스트푸드");
        assertThat(subOf("주)신세계푸드 노브랜드")).isEqualTo("종합소매");
    }
}
