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
@RequestMapping("/api/ops")
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
