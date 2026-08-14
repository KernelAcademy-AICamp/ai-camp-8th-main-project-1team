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

/** 일반 예적금 비교의 실제 HTTP 계약을 검증한다. 외부 API 키가 없어 예시 데이터 폴백을 사용한다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:savings-compare-endpoint;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "finntech.savings-compare.fss.auth="
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
