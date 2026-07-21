package com.finntech.web;

import com.finntech.service.SavingsCompareService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 통장 비교 (정보성) 조회 — 마스터 §5-5. 실 예·적금 금리를 자격 제한 상품 제외 후 금리순으로 제공한다.
 * <b>정보성일 뿐 판매·중개가 아니다</b>(가입 버튼·제휴 없음, 가입은 각 금융사에서).
 */
@RestController
@RequestMapping("/api/savings")
public class SavingsCompareController {

    private final SavingsCompareService service;

    public SavingsCompareController(SavingsCompareService service) {
        this.service = service;
    }

    @GetMapping("/compare")
    public SavingsCompareService.CompareResult compare(@RequestParam(required = false) Integer limit) {
        return service.compare(limit);
    }
}
