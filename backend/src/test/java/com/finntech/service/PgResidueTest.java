package com.finntech.service;

import com.finntech.engine.IndustryCategoryMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>PG 상호를 걷어낸 나머지가 물어볼 이름이다.</b>
 *
 * <p>실 명세서의 간편결제 상호는 세 꼴이 섞여 있다. 셋을 갈라야 분류가 맞는다.
 *
 * <pre>
 *   CGV_카카오페이        → CGV     남는다      물어본다
 *   (주)카카오페이         → (없음)   안 남는다   못 묻는다
 *   NICE_통신판매         → 통신판매  업태명이다   못 묻는다
 * </pre>
 *
 * <p>가운데를 놓치면 <b>이름에 답이 있는 결제</b>를 버리고(2026-08-21 이전), 셋째를 놓치면
 * <b>근거 없는 축</b>이 붙는다 — 홍상호의 {@code NICE_통신판매} 21건이 전부 '쇼핑'이었고
 * {@code 비인증_스마트로} 50,000원이 '주거/통신'이었다.
 */
class PgResidueTest {

    private final MerchantClassifierService svc = new MerchantClassifierService(
            new IndustryCategoryMapper(new ObjectMapper()), "", "", "http://localhost");

    @Test
    @DisplayName("PG 뒤에 진짜 가맹점이 남으면 그 이름으로 묻는다")
    void 남은_이름으로_묻는다() {
        assertThat(svc.residueOf("CGV_카카오페이")).isEqualTo("CGV");
        assertThat(svc.residueOf("KICC(일반)-주식회사 설빙")).contains("설빙");
        assertThat(svc.residueOf("구글_네이버페이")).isEqualTo("구글");
        assertThat(svc.residueOf("넥슨_카카오페이")).isEqualTo("넥슨");
    }

    /**
     * 이것이 이 수정의 핵심이다 — 원문을 그대로 물으면 모델이 "결제대행사는 모름" 규칙에
     * 걸려 답을 안 한다. {@code 구글_네이버페이} 를 두 번(브랜드 있이·없이) 물어 확인했다.
     */
    @Test
    @DisplayName("PG 를 뗀 이름은 원문과 다르다 — 프롬프트에 원문이 가면 안 된다")
    void 원문과_다르다() {
        assertThat(svc.residueOf("구글_네이버페이")).isNotEqualTo("구글_네이버페이");
    }

    @Test
    @DisplayName("상호가 PG 하나뿐이면 아무것도 안 남는다")
    void PG_단독은_빈다() {
        assertThat(svc.residueOf("(주)카카오페이")).isEmpty();
        assertThat(svc.residueOf("네이버파이낸셜(주)")).isEmpty();
        assertThat(svc.residueOf("네이버파이낸셜 주식회사")).isEmpty();
        assertThat(svc.residueOf("토스페이먼츠")).isEmpty();
    }

    /**
     * <b>업태명은 가맹점이 아니다.</b> {@code 통신판매} 만 남으면 무엇을 샀는지 여전히 모른다.
     * 그런데 모델은 거기서 '전자상거래 소매업'을 답했고 그 값이 확정 축으로 굳었다.
     */
    @Test
    @DisplayName("PG 를 떼고 업태명만 남으면 못 묻는다")
    void 업태명만_남으면_빈다() {
        assertThat(svc.residueOf("NICE_통신판매")).isEmpty();
        assertThat(svc.residueOf("비인증_스마트로")).isEmpty();
        assertThat(svc.residueOf("KCP-결제")).isEmpty();
        assertThat(svc.residueOf("KIOSK_나이스")).isEmpty();
        assertThat(svc.residueOf("토스페이_일반")).isEmpty();
    }

    /** 업태명이 <b>붙어만</b> 있고 진짜 이름이 함께 있으면 그 이름으로 묻는다. */
    @Test
    @DisplayName("업태명과 가맹점이 같이 있으면 가맹점이 남는다")
    void 업태명과_가맹점이_같이_있으면() {
        assertThat(svc.residueOf("웰컴페이먼츠_상품권-(주)티웨이브 역삼지점")).contains("티웨이브");
        assertThat(svc.residueOf("빅픽처인터렉티브_정기결제(4)_이니시스")).contains("빅픽처인터렉티브");
    }

    @Test
    @DisplayName("PG 와 무관한 상호는 원문 그대로 묻는다")
    void 보통_가맹점은_그대로() {
        assertThat(svc.residueOf("스타벅스 포항공대점")).contains("스타벅스");
        assertThat(svc.residueOf("삼성물산리조트(주)에버랜드")).contains("에버랜드");
        // PG 가 안 섞였으면 업태명 낱말이 들어 있어도 깎지 않는다 — 그것은 진짜 상호다.
        assertThat(svc.residueOf("일반통신판매사")).isNotEmpty();
    }

    @Test
    @DisplayName("worthAsking 은 나머지가 비었는지로 정한다")
    void worthAsking_이_나머지를_본다() {
        assertThat(svc.worthAsking("CGV_카카오페이", "1234567890")).isTrue();
        assertThat(svc.worthAsking("(주)카카오페이", "1234567890")).isFalse();
        assertThat(svc.worthAsking("NICE_통신판매", "1234567890")).isFalse();
    }
}
