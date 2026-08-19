package com.finntech.web;

import com.finntech.service.MerchantDictionaryRecomputeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>대조표를 고친 뒤 사전을 다시 계산하는 문</b>(V29).
 *
 * <p><b>기본은 꺼져 있다.</b> {@link ObservabilityController} 와 같은 이유다 — nginx 는
 * {@code /api/} 아래를 경로별 구분 없이 백엔드로 넘기므로 <b>여기 만든 매핑은 배포되는 순간
 * 공개된다</b>. 게다가 이쪽은 읽기가 아니라 <b>원장을 고치는</b> 문이라 더 그렇다.
 *
 * <p><b>기본이 dry-run 이다.</b> {@code apply} 를 명시하지 않으면 무엇이 달라질지만 돌려주고
 * 아무것도 쓰지 않는다. 표를 고치면 수백 행의 분류와 그에 딸린 결제·소비가 한꺼번에 움직이는데,
 * 그것을 확인 없이 실행하는 것은 조용한 원장 재작성이다.
 *
 * <p>스케줄러에 걸지 않는다. 부르는 것은 <b>사람의 손</b>이고, 부를 때는
 * {@code scripts/industry/build_industry.py} 를 다시 돌려 {@code industry-mid.json} 이
 * 새 대조표를 반영한 뒤여야 한다 — 안 그러면 옛 표로 재계산한다, 오류 없이.
 */
@RestController
/*
 * **`/api/ops` 에서 옮겨 왔다(2026-08-19).** 그 자리는 운영에서 기본으로 켜져 있고
 * (`FINNTECH_OPS_ENABLED:true`) `AuthFilter` 가 <b>사용자 토큰</b>만 요구한다 — 경로에
 * 사용자 번호가 없어 소유 확인도 안 걸리므로 <b>로그인한 아무나</b> 부를 수 있었다.
 *
 * 여기서 하는 일은 <b>실사용자 전원의 분류를 다시 계산하는 쓰기</b>다. 관측용 문과 같은
 * 자리에 둘 것이 아니다. 소비 원장의 ops 문을 admin 뒤로 옮긴 것과 같은 이유·같은 처리다
 * (PR #202). `/api/admin/` 접두라야 admin 쿠키를 요구한다.
 */
@RequestMapping("/api/admin")
@ConditionalOnProperty(name = "finntech.ops.enabled", havingValue = "true")
public class MerchantDictionaryOpsController {

    private final MerchantDictionaryRecomputeService recompute;

    public MerchantDictionaryOpsController(MerchantDictionaryRecomputeService recompute) {
        this.recompute = recompute;
    }

    @PostMapping("/merchant-category/recompute")
    public MerchantDictionaryRecomputeService.Result recompute(
            @RequestParam(defaultValue = "false") boolean apply) {
        return recompute.recompute(apply);
    }
}
