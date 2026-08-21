package com.finntech.service;

import com.finntech.engine.IndustryCategoryMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>PG 이름이 붙어 있다고 버리면 안 된다.</b>
 *
 * <p>실 명세서의 간편결제 상호는 {@code CGV_카카오페이}·{@code KICC(일반)-주식회사 설빙} 처럼
 * <b>PG 이름 + 진짜 가맹점</b> 꼴이다. 옛 코드는 {@code contains} 하나로 판정해 이런 것을
 * 통째로 뺐다 — {@code worthAsking} 이 false 를 주니 <b>모델에게 묻지도 않았다.</b>
 *
 * <p>운영 실측(2026-08-21): 실사용자 미분류 <b>15종 중 14종</b>이 여기서 빠지고 있었다.
 * 그런데 모델은 이름만 보고 맞힌다 — {@code CGV_카카오페이} 를 실제로 물어보니
 * <b>영화관 운영업</b>이라고 답했다. 못 하는 것이 아니라 <b>안 묻고 있던 것</b>이다.
 *
 * <p>여기서 잠그는 것은 하나다 — <b>PG 상호를 걷어낸 뒤에 무엇이 남는가.</b> 남으면 묻는다.
 */
class AgencyNameTest {

    private final MerchantClassifierService svc = new MerchantClassifierService(
            new IndustryCategoryMapper(new ObjectMapper()), "", "", "http://localhost");

    private boolean asks(String merchantName) {
        return svc.worthAsking(merchantName, "1234567890");
    }

    @Test
    @DisplayName("PG 이름 뒤에 진짜 가맹점이 붙어 있으면 묻는다 — 이것이 이 수정의 이유다")
    void PG_뒤에_가맹점이_있으면_묻는다() {
        assertThat(asks("CGV_카카오페이")).as("CGV 가 남는다").isTrue();
        assertThat(asks("KICC(일반)-주식회사 설빙")).as("설빙이 남는다").isTrue();
        assertThat(asks("롯데지알에스(웹)_KICC-(주)롯데리아")).as("롯데리아가 남는다").isTrue();
        assertThat(asks("파이브가이즈_KICC-㈜에프지코리아")).isTrue();
        assertThat(asks("카카오페이-(주)엘지유플러스")).isTrue();
        assertThat(asks("토스페이먼츠 - 구글클라우드")).isTrue();
        assertThat(asks("카카오(상품권)_다날")).isTrue();
        assertThat(asks("웰컴페이먼츠_상품권-(주)티웨이브 역삼지점")).isTrue();
    }

    /** 상호가 PG <b>그것뿐</b>이면 무엇을 샀는지 원리적으로 알 수 없다 — 물어도 소용없다. */
    @Test
    @DisplayName("상호가 PG 하나뿐이면 안 묻는다")
    void PG_하나뿐이면_안_묻는다() {
        assertThat(asks("(주)카카오페이")).isFalse();
        assertThat(asks("네이버파이낸셜(주)")).isFalse();
        assertThat(asks("토스페이먼츠")).isFalse();
        assertThat(asks("KICC")).isFalse();
    }

    /**
     * 법인격은 <b>낱말째로</b> 뺀다. 글자 {@code 주} 하나를 아무 데서나 빼면
     * {@code 네이버파이낸셜 주식회사} 가 {@code 네이버파이낸셜식회사} 가 되어 안 걸린다
     * (옛 코드가 그랬다).
     */
    @Test
    @DisplayName("주식회사를 붙여 써도 PG 는 PG 다")
    void 법인격_표기가_달라도_같다() {
        assertThat(asks("네이버파이낸셜 주식회사")).isFalse();
        assertThat(asks("주식회사 카카오페이")).isFalse();
        assertThat(asks("㈜토스페이먼츠")).isFalse();
    }

    @Test
    @DisplayName("PG 와 무관한 상호는 그대로 묻는다")
    void 보통_가맹점은_묻는다() {
        assertThat(asks("스타벅스 포항공대점")).isTrue();
        assertThat(asks("삼성물산리조트(주)에버랜드")).isTrue();
        assertThat(asks("행복한밥상")).isTrue();
    }

    @Test
    @DisplayName("이름이 없거나 한 글자면 안 묻는다")
    void 이름이_모자라면_안_묻는다() {
        assertThat(asks(null)).isFalse();
        assertThat(asks("")).isFalse();
        assertThat(asks("  ")).isFalse();
        assertThat(asks("A")).isFalse();
    }

    /**
     * 화면 표시도 같은 판정을 쓴다 — <i>"앱이 원리적으로 못 하는 것"</i>과 <i>"내가 알려주면
     * 되는 것"</i>을 가르는 자리다. {@code CGV_카카오페이} 는 전자가 아니다.
     */
    @Test
    @DisplayName("표시용 판정도 같이 좁아진다")
    void 표시용도_같다() {
        assertThat(svc.isPaymentAgencyMerchant("(주)카카오페이")).isTrue();
        assertThat(svc.isPaymentAgencyMerchant("CGV_카카오페이")).isFalse();
    }
}
