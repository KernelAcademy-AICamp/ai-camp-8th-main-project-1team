package com.finntech.web;

import com.finntech.service.TasteAnalysisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 취향·성향 분석 조회 (③ 취향·추천 에이전트). 마이데이터 결제내역에서 취미 성향을 읽어 준다.
 * ①·②의 상태를 바꾸지 않는 <b>사용자 노출물</b>이다(07 R1).
 */
@RestController
public class TasteController {

    private final TasteAnalysisService service;

    public TasteController(TasteAnalysisService service) {
        this.service = service;
    }

    /** {@code months}는 분석 창(개월). 생략하면 기본 6개월. */
    @GetMapping("/api/taste")
    public TasteAnalysisService.TasteProfile taste(@RequestParam Long userId,
                                                   @RequestParam(required = false) Integer months) {
        return service.analyze(userId, months);
    }
}
