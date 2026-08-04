package com.finntech.web;

import com.finntech.domain.AppUser;
import com.finntech.repository.AppUserRepository;
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
    private final AppUserRepository userRepository;

    public SavingsCompareController(SavingsCompareService service, AppUserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    /**
     * {@code userId}를 주면 그 사용자의 출생연도로 나이 자격까지 맞춰 거르고, <b>자금흐름 5축으로
     * FP-01 매칭(M1~M9)까지 붙여</b> {@code match}에 그룹별 추천을 내려보낸다.
     * 없거나 마이데이터 미연동이면 나이 조건은 따지지 않고 특수 신분 조건만 걸러 목록만 보여준다.
     */
    @GetMapping("/compare")
    public SavingsCompareService.CompareResult compare(@RequestParam(required = false) Integer limit,
                                                       @RequestParam(required = false) Long userId) {
        Integer birthYear = userId == null ? null
                : userRepository.findById(userId).map(AppUser::getBirthYear).orElse(null);
        return service.compare(limit, birthYear, userId);
    }
}
