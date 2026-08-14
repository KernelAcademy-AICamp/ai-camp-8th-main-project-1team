package com.finntech.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 일반 예적금 비교의 실제 HTTP 계약을 검증한다. 외부 API 키가 없어 예시 데이터 폴백을 사용한다.
 *
 * <p><b>인증을 이 시험에서만 끈다.</b> develop 의 인증 도입(V32)을 병합하면서 모든 {@code /api/}
 * 가 토큰을 요구하게 됐고, 여기서 검증하려는 것은 <b>응답의 내용</b>이지 인증이 아니다. 토큰을
 * 만들어 붙이면 시험이 인증 설정에 묶여, 인증이 바뀔 때마다 예적금 시험이 같이 깨진다.
 *
 * <p><b>전역(`application-test.yml`)에서 끄면 안 된다</b> — 2026-08-14 에 그렇게 했다가
 * {@code AuthFilterTest} 가 전체 실행에서만 깨졌다(혼자 돌리면 통과). 인증 검증은 그 시험이
 * 소유하므로 범위를 이 클래스로 좁힌다.
 *
 * <p>대신 <b>이 두 경로가 인증 밖이라는 뜻은 아니다.</b> 공개 여부는
 * {@code AuthFilter.PUBLIC_PREFIXES} 가 정하고, 여기에는 들어 있지 않다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:savings-compare-endpoint;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "finntech.savings-compare.fss.auth=",
        "finntech.auth.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SavingsCompareEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 비교_API는_사용자_추천_없이_기본금리순_목록만_반환한다() throws Exception {
        mockMvc.perform(get("/api/savings/compare").param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts.length()").value(3))
                .andExpect(jsonPath("$.accounts[0].baseRate").value(4.5))
                .andExpect(jsonPath("$.accounts[1].baseRate").value(3.5))
                .andExpect(jsonPath("$.accounts[2].baseRate").value(3.5))
                .andExpect(jsonPath("$.live").value(false))
                .andExpect(jsonPath("$.match").doesNotExist());
    }

    @Test
    void 목표별_금융상품_추천_API는_더_이상_노출하지_않는다() throws Exception {
        mockMvc.perform(get("/api/points/recommendations").param("userId", "1"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/products/recommend").param("userId", "1"))
                .andExpect(status().isNotFound());
    }
}
